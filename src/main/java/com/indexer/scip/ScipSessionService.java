package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.sourcegraph.scip.Scip;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates multi-part SCIP uploads. Parts are parsed with the existing ScipParser and
 * written to scip_symbols/scip_relationships under the session's synthetic staging upload_sha;
 * complete() atomically promotes those rows to the real target SHA (all-or-nothing).
 */
public class ScipSessionService {

    private static final Logger log = LoggerFactory.getLogger(ScipSessionService.class);

    private final RepositoryDao repositoryDao;
    private final FileDao fileDao;
    private final ScipSessionDao sessionDao;
    private final Jdbi jdbi;

    public ScipSessionService(RepositoryDao repositoryDao, FileDao fileDao,
                              ScipSessionDao sessionDao, Jdbi jdbi) {
        this.repositoryDao = repositoryDao;
        this.fileDao = fileDao;
        this.sessionDao = sessionDao;
        this.jdbi = jdbi;
    }

    public ScipUploadSession init(Repository repo, String targetSha, Integer expectedParts) {
        ScipUploadSession session = sessionDao.create(repo.id(), targetSha);
        log.info("SCIP upload session {} opened for {} (sha {})", session.id(), repo.name(), targetSha);
        return session;
    }

    /** Parse and stage one part. Idempotent: a re-posted part returns its recorded counts. */
    public ScipUploadResult part(String uploadId, int partNumber, byte[] partBytes) {
        ScipUploadSession session = sessionDao.find(uploadId)
                .orElseThrow(() -> new ScipUploadException("Invalid SCIP upload session: " + uploadId));
        if (!"open".equals(session.status())) {
            throw new ScipUploadException("Invalid SCIP session state: session " + uploadId + " is " + session.status());
        }

        // Idempotent replay: already recorded → return stored counts, do not re-insert.
        var existing = sessionDao.partCounts(uploadId, partNumber);
        if (existing.isPresent()) {
            return new ScipUploadResult(String.valueOf(session.repoId()), session.targetSha(),
                    existing.get()[0], existing.get()[1], 0);
        }

        Scip.Index index;
        try {
            index = Scip.Index.parseFrom(partBytes);
        } catch (Exception e) {
            throw new ScipUploadException("Invalid SCIP protobuf in part " + partNumber + ": " + e.getMessage());
        }
        ScipParseResult parsed = ScipParser.parse(index);

        // Insert staging rows + record the part in ONE transaction so a failure leaves no
        // half-applied part (and no ledger row → safe retry).
        jdbi.useTransaction(handle -> {
            ScipWriter.insert(handle, session.repoId(), session.stagingSha(), parsed);
            handle.createUpdate("""
                    INSERT INTO scip_upload_parts (session_id, part_number, byte_size, symbol_count, rel_count)
                    VALUES (:s, :p, :bytes, :syms, :rels)
                    """)
                    .bind("s", uploadId).bind("p", partNumber).bind("bytes", (long) partBytes.length)
                    .bind("syms", parsed.symbols().size()).bind("rels", parsed.relationships().size())
                    .execute();
            handle.createUpdate("UPDATE scip_upload_sessions SET updated_at = NOW() WHERE id = :id")
                    .bind("id", uploadId).execute();
        });

        return new ScipUploadResult(String.valueOf(session.repoId()), session.targetSha(),
                parsed.symbols().size(), parsed.relationships().size(), index.getDocumentsCount());
    }

    /** Atomically promote staged rows to the real SHA. Idempotent if already completed. */
    public ScipUploadResult complete(Repository repo, String uploadId) {
        ScipUploadSession session = sessionDao.find(uploadId)
                .orElseThrow(() -> new ScipUploadException("Invalid SCIP upload session: " + uploadId));

        if ("completed".equals(session.status())) {
            int syms = liveCount(repo.id(), session.targetSha(), "scip_symbols");
            int rels = liveCount(repo.id(), session.targetSha(), "scip_relationships");
            return new ScipUploadResult(repo.name(), session.targetSha(), syms, rels, 0);
        }

        return jdbi.inTransaction(handle -> {
            // Serialize concurrent completes for this session.
            handle.createQuery("SELECT id FROM scip_upload_sessions WHERE id = :id FOR UPDATE")
                    .bind("id", uploadId).mapTo(String.class).one();

            // File-overlap guard (mirrors single-shot 422): at least one staged document
            // path must match an indexed file.
            boolean overlap = handle.createQuery("""
                    SELECT EXISTS (
                        SELECT 1 FROM scip_symbols ss
                        JOIN files f ON f.repo_id = ss.repo_id AND f.path = ss.file_path
                        WHERE ss.repo_id = :repoId AND ss.upload_sha = :stagingSha
                    )
                    """)
                    .bind("repoId", repo.id()).bind("stagingSha", session.stagingSha())
                    .mapTo(Boolean.class).one();
            if (!overlap) {
                throw new ScipUploadException(
                        "No SCIP document paths match indexed files for repo '" + repo.name()
                        + "'. Ensure the SCIP data was produced from the correct repository.");
            }

            // Delete old live rows for the target SHA, then promote staging → target.
            ScipWriter.deleteForSha(handle, repo.id(), session.targetSha());
            handle.createUpdate("UPDATE scip_symbols SET upload_sha = :target WHERE repo_id = :r AND upload_sha = :staging")
                    .bind("target", session.targetSha()).bind("r", repo.id()).bind("staging", session.stagingSha())
                    .execute();
            handle.createUpdate("UPDATE scip_relationships SET upload_sha = :target WHERE repo_id = :r AND upload_sha = :staging")
                    .bind("target", session.targetSha()).bind("r", repo.id()).bind("staging", session.stagingSha())
                    .execute();

            handle.createUpdate("UPDATE repositories SET scip_sha = :sha, scip_uploaded_at = NOW() WHERE id = :id")
                    .bind("sha", session.targetSha()).bind("id", repo.id()).execute();
            handle.createUpdate("UPDATE scip_upload_sessions SET status = 'completed', updated_at = NOW() WHERE id = :id")
                    .bind("id", uploadId).execute();

            int syms = handle.createQuery("SELECT count(*) FROM scip_symbols WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", repo.id()).bind("sha", session.targetSha()).mapTo(Integer.class).one();
            int rels = handle.createQuery("SELECT count(*) FROM scip_relationships WHERE repo_id = :r AND upload_sha = :sha")
                    .bind("r", repo.id()).bind("sha", session.targetSha()).mapTo(Integer.class).one();

            log.info("SCIP upload session {} completed for {} (sha {}): {} symbols, {} relationships",
                    uploadId, repo.name(), session.targetSha(), syms, rels);
            return new ScipUploadResult(repo.name(), session.targetSha(), syms, rels, 0);
        });
    }

    /** Abort an in-flight session, discarding staged rows. */
    public void abort(String uploadId) {
        sessionDao.find(uploadId).ifPresent(sessionDao::deleteSessionAndStaging);
    }

    private int liveCount(int repoId, String sha, String table) {
        return jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM " + table + " WHERE repo_id = :r AND upload_sha = :sha")
                .bind("r", repoId).bind("sha", sha).mapTo(Integer.class).one());
    }
}

package com.indexer.scip;

import com.indexer.db.FileDao;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import com.sourcegraph.scip.Scip;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ScipService {

    private static final Logger log = LoggerFactory.getLogger(ScipService.class);
    private static final long MAX_UPLOAD_BYTES = 50 * 1024 * 1024; // 50 MB

    private final RepositoryDao repositoryDao;
    private final FileDao fileDao;
    private final Jdbi jdbi;

    public ScipService(RepositoryDao repositoryDao, FileDao fileDao, Jdbi jdbi) {
        this.repositoryDao = repositoryDao;
        this.fileDao = fileDao;
        this.jdbi = jdbi;
    }

    public Optional<Repository> findRepo(String repoName) {
        return repositoryDao.findByName(repoName);
    }

    public ScipUploadResult processUpload(Repository repo, String gitSha, byte[] scipBytes) {
        // Size check
        if (scipBytes.length > MAX_UPLOAD_BYTES) {
            throw new ScipUploadException("Upload exceeds maximum size of " + MAX_UPLOAD_BYTES + " bytes");
        }

        // Parse protobuf
        Scip.Index index;
        try {
            index = Scip.Index.parseFrom(scipBytes);
        } catch (Exception e) {
            throw new ScipUploadException("Invalid SCIP protobuf: " + e.getMessage());
        }

        // Cross-ref check: at least one document path must match an indexed file
        Set<String> indexedPaths = fileDao.findByRepo(repo.id()).stream()
                .map(f -> f.path())
                .collect(Collectors.toSet());

        boolean hasOverlap = index.getDocumentsList().stream()
                .anyMatch(doc -> indexedPaths.contains(doc.getRelativePath()));
        if (!hasOverlap) {
            throw new ScipUploadException(
                    "No SCIP document paths match indexed files for repo '" + repo.name()
                    + "'. Ensure the SCIP data was produced from the correct repository.");
        }

        // Parse
        ScipParseResult parseResult = ScipParser.parse(index);

        // Store in transaction: delete old, insert new, update repo
        jdbi.useTransaction(handle -> {
            // Delete only this SHA's prior rows — other SHAs are retained
            ScipWriter.deleteForSha(handle, repo.id(), gitSha);
            ScipWriter.insert(handle, repo.id(), gitSha, parseResult);

            // Update repo SCIP tracking. scip_sha/scip_uploaded_at are informational only
            // ("most recent upload"); SCIP freshness is computed existence-based, not from this column.
            handle.createUpdate(
                    "UPDATE repositories SET scip_sha = :sha, scip_uploaded_at = NOW() WHERE id = :id")
                    .bind("sha", gitSha)
                    .bind("id", repo.id())
                    .execute();
        });

        log.info("SCIP upload for {}: {} symbols, {} relationships from {} documents (sha: {})",
                repo.name(), parseResult.symbols().size(), parseResult.relationships().size(),
                parseResult.documentsProcessed(), gitSha);

        return new ScipUploadResult(repo.name(), gitSha,
                parseResult.symbols().size(), parseResult.relationships().size(),
                parseResult.documentsProcessed());
    }
}

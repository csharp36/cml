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
            // Delete existing SCIP data
            handle.createUpdate("DELETE FROM scip_relationships WHERE repo_id = :repoId")
                    .bind("repoId", repo.id())
                    .execute();
            handle.createUpdate("DELETE FROM scip_symbols WHERE repo_id = :repoId")
                    .bind("repoId", repo.id())
                    .execute();

            // Bulk insert symbols
            var symbolBatch = handle.prepareBatch("""
                    INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, documentation,
                                              file_path, start_line, end_line, upload_sha, uploaded_at)
                    VALUES (:repoId, :scipSymbol, :displayName, :kind, :documentation,
                            :filePath, :startLine, :endLine, :uploadSha, NOW())
                    ON CONFLICT (repo_id, scip_symbol) DO UPDATE SET
                        display_name = EXCLUDED.display_name, kind = EXCLUDED.kind,
                        documentation = EXCLUDED.documentation, file_path = EXCLUDED.file_path,
                        start_line = EXCLUDED.start_line, end_line = EXCLUDED.end_line,
                        upload_sha = EXCLUDED.upload_sha, uploaded_at = NOW()
                    """);
            for (var sym : parseResult.symbols()) {
                symbolBatch
                        .bind("repoId", repo.id())
                        .bind("scipSymbol", sym.scipSymbol())
                        .bind("displayName", sym.displayName())
                        .bind("kind", sym.kind())
                        .bind("documentation", sym.documentation())
                        .bind("filePath", sym.filePath())
                        .bind("startLine", sym.startLine())
                        .bind("endLine", sym.endLine())
                        .bind("uploadSha", gitSha)
                        .add();
            }
            if (!parseResult.symbols().isEmpty()) {
                symbolBatch.execute();
            }

            // Bulk insert relationships
            var relBatch = handle.prepareBatch("""
                    INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line)
                    VALUES (:repoId, :fromSymbol, :toSymbol, :kind, :filePath, :line)
                    """);
            for (var rel : parseResult.relationships()) {
                relBatch
                        .bind("repoId", repo.id())
                        .bind("fromSymbol", rel.fromSymbol())
                        .bind("toSymbol", rel.toSymbol())
                        .bind("kind", rel.kind())
                        .bind("filePath", rel.filePath())
                        .bind("line", rel.line())
                        .add();
            }
            if (!parseResult.relationships().isEmpty()) {
                relBatch.execute();
            }

            // Update repo SCIP tracking
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

package com.indexer.scip;

import org.jdbi.v3.core.Handle;

/**
 * Shared SCIP row writer used by both the single-shot upload (ScipService) and the
 * multi-part session upload (ScipSessionService). All methods operate within a caller-supplied
 * transaction (Handle). Rows are keyed by the supplied uploadSha — a real git SHA for single-shot,
 * a synthetic staging key ('__staging__:<uploadId>') for in-flight session parts.
 */
public final class ScipWriter {

    private ScipWriter() {}

    /** Delete all SCIP rows for one (repo, uploadSha) from both tables. */
    public static void deleteForSha(Handle handle, int repoId, String uploadSha) {
        handle.createUpdate("DELETE FROM scip_relationships WHERE repo_id = :repoId AND upload_sha = :sha")
                .bind("repoId", repoId).bind("sha", uploadSha).execute();
        handle.createUpdate("DELETE FROM scip_symbols WHERE repo_id = :repoId AND upload_sha = :sha")
                .bind("repoId", repoId).bind("sha", uploadSha).execute();
    }

    /** Batch-insert parsed symbols and relationships under the given uploadSha. */
    public static void insert(Handle handle, int repoId, String uploadSha, ScipParseResult parseResult) {
        var symbolBatch = handle.prepareBatch("""
                INSERT INTO scip_symbols (repo_id, scip_symbol, display_name, kind, documentation,
                                          file_path, start_line, end_line, upload_sha, uploaded_at)
                VALUES (:repoId, :scipSymbol, :displayName, :kind, :documentation,
                        :filePath, :startLine, :endLine, :uploadSha, NOW())
                ON CONFLICT (repo_id, upload_sha, scip_symbol) DO UPDATE SET
                    display_name = EXCLUDED.display_name, kind = EXCLUDED.kind,
                    documentation = EXCLUDED.documentation, file_path = EXCLUDED.file_path,
                    start_line = EXCLUDED.start_line, end_line = EXCLUDED.end_line,
                    uploaded_at = NOW()
                """);
        for (var sym : parseResult.symbols()) {
            symbolBatch
                    .bind("repoId", repoId)
                    .bind("scipSymbol", sym.scipSymbol())
                    .bind("displayName", sym.displayName())
                    .bind("kind", sym.kind())
                    .bind("documentation", sym.documentation())
                    .bind("filePath", sym.filePath())
                    .bind("startLine", sym.startLine())
                    .bind("endLine", sym.endLine())
                    .bind("uploadSha", uploadSha)
                    .add();
        }
        if (!parseResult.symbols().isEmpty()) {
            symbolBatch.execute();
        }

        var relBatch = handle.prepareBatch("""
                INSERT INTO scip_relationships (repo_id, from_symbol, to_symbol, kind, file_path, line, upload_sha)
                VALUES (:repoId, :fromSymbol, :toSymbol, :kind, :filePath, :line, :uploadSha)
                """);
        for (var rel : parseResult.relationships()) {
            relBatch
                    .bind("repoId", repoId)
                    .bind("fromSymbol", rel.fromSymbol())
                    .bind("toSymbol", rel.toSymbol())
                    .bind("kind", rel.kind())
                    .bind("filePath", rel.filePath())
                    .bind("line", rel.line())
                    .bind("uploadSha", uploadSha)
                    .add();
        }
        if (!parseResult.relationships().isEmpty()) {
            relBatch.execute();
        }
    }
}

package com.indexer.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditException;
import com.indexer.audit.AuditSink;
import com.indexer.auth.CallerIdentity;
import com.indexer.auth.PermissionCache;
import com.indexer.db.BranchIndexDao;
import com.indexer.db.RepositoryDao;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RefKind;
import io.modelcontextprotocol.spec.McpSchema;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Core query engine for the MCP server. All MCP tools delegate to this class.
 */
public class QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Jdbi jdbi;
    private final BranchIndexDao branchIndexDao;
    private final IndexingPipeline indexingPipeline;
    private final RepositoryDao repositoryDao;
    private final GitOperations gitOps;
    private final PermissionCache permissionCache;
    private final AuditSink auditSink;

    // Backward-compatible constructor (used in tests)
    public QueryExecutor(Jdbi jdbi) {
        this(jdbi, null, null, null, null, null, null);
    }

    public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                         RepositoryDao repositoryDao, GitOperations gitOps) {
        this(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, null, null);
    }

    public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                         RepositoryDao repositoryDao, GitOperations gitOps, PermissionCache permissionCache) {
        this(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, permissionCache, null);
    }

    public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                         RepositoryDao repositoryDao, GitOperations gitOps, PermissionCache permissionCache,
                         AuditSink auditSink) {
        this.jdbi = jdbi;
        this.branchIndexDao = branchIndexDao;
        this.indexingPipeline = indexingPipeline;
        this.repositoryDao = repositoryDao;
        this.gitOps = gitOps;
        this.permissionCache = permissionCache;
        this.auditSink = auditSink;
    }

    /**
     * Pipeline wrapper that logs the caller, executes the query, and returns
     * a CallToolResult with JSON-serialized output or an error.
     *
     * Enforces "no audit, no access": if auditSink is configured and fails to
     * record, the query result is discarded.
     */
    public McpSchema.CallToolResult executeQuery(
            CallerIdentity caller, String repo, String action,
            Map<String, Object> params, Supplier<Object> query) {
        log.info("Tool call: {} by {} ({})", action, caller.displayName(), caller.authMethod());

        // Authorization check — only for OAuth users with configured permissions
        if (permissionCache != null && "oauth".equals(caller.authMethod())) {
            if (repo == null) {
                log.warn("Access denied: {} called {} without repo parameter", caller.displayName(), action);
                auditBestEffort(caller, action, null, false, "denied", "Repository parameter required");
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Repository parameter is required for authenticated queries")
                        .isError(true)
                        .build();
            }
            try {
                Set<String> allowed = permissionCache.getAllowedRepos(caller);
                if (!allowed.contains(repo)) {
                    log.warn("Access denied: {} attempted to query repo {}", caller.displayName(), repo);
                    auditBestEffort(caller, action, repo, false, "denied", "Access denied to repository: " + repo);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Access denied to repository: " + repo)
                            .isError(true)
                            .build();
                }
            } catch (Exception e) {
                log.error("Permission resolution failed for {}: {}", caller.displayName(), e.getMessage());
                auditBestEffort(caller, action, repo, false, "denied", "Permission resolution failed");
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Authorization failed: unable to verify permissions")
                        .isError(true)
                        .build();
            }
        }

        // Execute query
        Object result;
        String resultStatus;
        String errorMessage = null;
        try {
            result = query.get();
            resultStatus = "success";
        } catch (Exception e) {
            log.error("Tool execution error in {}: {}", action, e.getMessage(), e);
            result = null;
            resultStatus = "error";
            errorMessage = e.getMessage();
        }

        // Audit — "no audit, no access"
        if (auditSink != null) {
            try {
                auditSink.record(AuditEvent.from(caller, action, repo, true, resultStatus, errorMessage));
            } catch (AuditException e) {
                log.error("Audit write failed for {}, discarding query result: {}", action, e.getMessage());
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Audit recording failed — query result withheld")
                        .isError(true)
                        .build();
            }
        }

        // Return result
        if (result == null) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error: " + errorMessage)
                    .isError(true)
                    .build();
        }

        try {
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            log.error("Result serialization error in {}: {}", action, e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /** Best-effort audit for denied queries — log warning if audit itself fails. */
    private void auditBestEffort(CallerIdentity caller, String action, String repo,
                                 boolean authorized, String resultStatus, String errorMessage) {
        if (auditSink == null) return;
        try {
            auditSink.record(AuditEvent.from(caller, action, repo, authorized, resultStatus, errorMessage));
        } catch (Exception e) {
            log.warn("Audit write failed for denied query {}: {}", action, e.getMessage());
        }
    }

    /**
     * Search symbols by name (regex), kind, language, and repo.
     */
    public Object searchSymbols(String query, String kind, String language, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        if (repo != null && !repo.isBlank()) {
            ensureBranchIndexed(repo, effectiveBranch);
        }
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT s.name, s.kind, s.signature, s.start_line, s.end_line, s.visibility,
                           ef.path AS file_path, r.name AS repo_name
                    FROM symbols s
                    JOIN effective_files ef ON s.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE 1=1
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            if (query != null && !query.isBlank()) {
                sb.append(" AND s.name ~* :query");
                params.put("query", query);
            }
            if (kind != null && !kind.isBlank()) {
                sb.append(" AND s.kind = :kind");
                params.put("kind", kind);
            }
            if (language != null && !language.isBlank()) {
                sb.append(" AND ef.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" ORDER BY s.name LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var results = q.mapToMap().list();

            // Wrap in a map with scip_status if repo is specified
            if (repo != null && !repo.isBlank()) {
                var wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("results", results);
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) wrapper.put("scip_status", scipStatus);
                return wrapper;
            }
            return results;
        });
    }

    /**
     * Get detailed information about a specific symbol, including source code, children, and type relationships.
     */
    public Map<String, Object> getSymbolDetail(String repo, String filePath, String symbolName, Integer line, String branch) {
        String effectiveBranch = resolveBranch(branch);
        ensureBranchIndexed(repo, effectiveBranch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT s.id, s.name, s.kind, s.signature, s.start_line, s.end_line,
                           s.parent_id, s.visibility, s.is_static,
                           ef.id AS file_id,
                           ef.path AS file_path, r.name AS repo_name
                    FROM symbols s
                    JOIN effective_files ef ON s.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE r.name = :repo AND ef.path = :filePath AND s.name = :symbolName
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            params.put("repo", repo);
            params.put("filePath", filePath);
            params.put("symbolName", symbolName);

            if (line != null) {
                sb.append(" AND s.start_line = :line");
                params.put("line", line);
            }

            sb.append(" LIMIT 1");

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var optSymbol = q.mapToMap().findOne();

            if (optSymbol.isEmpty()) {
                return Collections.<String, Object>emptyMap();
            }

            var symbol = new LinkedHashMap<>(optSymbol.get());
            int symbolId = ((Number) symbol.get("id")).intValue();
            int startLine = ((Number) symbol.get("start_line")).intValue();
            int endLine = ((Number) symbol.get("end_line")).intValue();
            long fileId = ((Number) symbol.get("file_id")).longValue();

            // Read source from the overlay-resolved file_contents row (ref-aware),
            // not the on-disk working tree (which is always checked out to main).
            String content = handle.createQuery(
                            "SELECT content FROM file_contents WHERE file_id = :fileId")
                    .bind("fileId", fileId)
                    .mapTo(String.class)
                    .findOne()
                    .orElse(null);
            symbol.put("source_code", sliceLines(content, startLine, endLine));
            symbol.remove("file_id"); // internal id — not part of the response contract

            // Fetch children (e.g., methods if this is a class)
            var children = handle.createQuery("""
                    SELECT name, kind, signature, start_line, end_line, visibility
                    FROM symbols
                    WHERE parent_id = :parentId
                    ORDER BY start_line
                    """)
                    .bind("parentId", symbolId)
                    .mapToMap()
                    .list();
            symbol.put("children", children);

            // Fetch type relationships
            var relationships = handle.createQuery("""
                    SELECT related_name, kind
                    FROM type_relationships
                    WHERE symbol_id = :symbolId
                    """)
                    .bind("symbolId", symbolId)
                    .mapToMap()
                    .list();
            symbol.put("relationships", relationships);

            // Add SCIP precision indicator
            String scipStatus = getScipStatus(repo);
            if (scipStatus != null) symbol.put("scip_status", scipStatus);

            return (Map<String, Object>) symbol;
        });
    }

    /**
     * Find implementations of a type by looking up 'implements' relationships.
     */
    public Object findImplementations(String typeName, String repo, String branch) {
        String effectiveBranch = resolveBranch(branch);
        if (repo != null && !repo.isBlank()) {
            ensureBranchIndexed(repo, effectiveBranch);
        }
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT s.name AS class_name, s.signature, ef.path AS file_path, r.name AS repo_name
                    FROM type_relationships tr
                    JOIN symbols s ON tr.symbol_id = s.id
                    JOIN effective_files ef ON s.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE tr.related_name = :typeName AND tr.kind = 'implements'
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            params.put("typeName", typeName);

            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var results = q.mapToMap().list();

            if (repo != null && !repo.isBlank()) {
                var wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("results", results);
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) wrapper.put("scip_status", scipStatus);
                return wrapper;
            }
            return results;
        });
    }

    /**
     * Find files that import a given symbol name.
     */
    public Object findReferences(String symbolName, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        if (repo != null && !repo.isBlank()) {
            ensureBranchIndexed(repo, effectiveBranch);
        }
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path AS file_path, r.name AS repo_name, i.import_path
                    FROM imports i
                    JOIN effective_files ef ON i.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE i.import_path LIKE :pattern
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            params.put("pattern", "%" + symbolName + "%");

            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var results = q.mapToMap().list();

            if (repo != null && !repo.isBlank()) {
                var wrapper = new LinkedHashMap<String, Object>();
                wrapper.put("results", results);
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) wrapper.put("scip_status", scipStatus);
                return wrapper;
            }
            return results;
        });
    }

    /**
     * Full-text search across file contents using PostgreSQL tsvector/tsquery.
     */
    public List<Map<String, Object>> searchCode(String query, String language, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        if (repo != null && !repo.isBlank()) {
            ensureBranchIndexed(repo, effectiveBranch);
        }
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path AS file_path, r.name AS repo_name,
                           ts_headline('english', fc.content, plainto_tsquery('english', :query),
                                       'StartSel=<<, StopSel=>>, MaxWords=30, MinWords=10') AS matching_lines
                    FROM file_contents fc
                    JOIN effective_files ef ON fc.file_id = ef.id
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE fc.search_vector @@ plainto_tsquery('english', :query)
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            params.put("query", query);

            if (language != null && !language.isBlank()) {
                sb.append(" AND ef.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }

    /**
     * Search files by glob-style pattern (e.g., "*.java", "src/**").
     */
    public List<Map<String, Object>> searchFiles(String pattern, String language, String repo, String branch, int limit) {
        String effectiveBranch = resolveBranch(branch);
        if (repo != null && !repo.isBlank()) {
            ensureBranchIndexed(repo, effectiveBranch);
        }
        return jdbi.withHandle(handle -> {
            // Convert glob * to SQL %
            String sqlPattern = pattern != null ? pattern.replace("*", "%") : "%";

            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path, r.name AS repo_name, ef.language, ef.size_bytes, ef.last_modified_at
                    FROM effective_files ef
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE ef.path LIKE :pattern
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            params.put("pattern", sqlPattern);

            if (language != null && !language.isBlank()) {
                sb.append(" AND ef.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" ORDER BY ef.path LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }

    /**
     * Get a high-level summary of a repository.
     */
    public Map<String, Object> getRepoSummary(String repoName, String branch) {
        String effectiveBranch = resolveBranch(branch);
        ensureBranchIndexed(repoName, effectiveBranch);
        return jdbi.withHandle(handle -> {
            // Get repo record
            var optRepo = handle.createQuery("""
                    SELECT id, name, url, branch, clone_path, auth_type, last_indexed_sha, last_indexed_at
                    FROM repositories WHERE name = :name
                    """)
                    .bind("name", repoName)
                    .mapToMap()
                    .findOne();

            if (optRepo.isEmpty()) {
                return Collections.<String, Object>emptyMap();
            }

            var result = new LinkedHashMap<>(optRepo.get());
            int repoId = ((Number) result.get("id")).intValue();

            if ("main".equals(effectiveBranch)) {
                // Count files — main branch only
                long fileCount = handle.createQuery(
                        "SELECT COUNT(*) FROM files WHERE repo_id = :repoId AND branch = 'main'")
                        .bind("repoId", repoId)
                        .mapTo(Long.class)
                        .one();
                result.put("fileCount", fileCount);

                // Language breakdown — main branch only
                var languageBreakdown = handle.createQuery("""
                        SELECT language, COUNT(*) AS count
                        FROM files WHERE repo_id = :repoId AND branch = 'main' AND language IS NOT NULL
                        GROUP BY language ORDER BY count DESC
                        """)
                        .bind("repoId", repoId)
                        .mapToMap()
                        .list();
                result.put("languageBreakdown", languageBreakdown);

                // Top-level directories — main branch only
                var topLevelDirs = handle.createQuery("""
                        SELECT DISTINCT split_part(path, '/', 1) AS dir
                        FROM files WHERE repo_id = :repoId AND branch = 'main' AND path LIKE '%/%'
                        ORDER BY dir
                        """)
                        .bind("repoId", repoId)
                        .mapTo(String.class)
                        .list();
                result.put("topLevelDirectories", topLevelDirs);
            } else {
                // Count effective files for branch (DISTINCT ON overlay)
                long fileCount = handle.createQuery("""
                        SELECT COUNT(*) FROM (
                            SELECT DISTINCT ON (path) path
                            FROM files
                            WHERE repo_id = :repoId AND branch IN (:branch, 'main')
                            ORDER BY path, CASE WHEN branch = :branch THEN 0 ELSE 1 END
                        ) effective
                        """)
                        .bind("repoId", repoId)
                        .bind("branch", effectiveBranch)
                        .mapTo(Long.class)
                        .one();
                result.put("fileCount", fileCount);

                // Language breakdown for effective files
                var languageBreakdown = handle.createQuery("""
                        SELECT language, COUNT(*) AS count FROM (
                            SELECT DISTINCT ON (path) path, language
                            FROM files
                            WHERE repo_id = :repoId AND branch IN (:branch, 'main') AND language IS NOT NULL
                            ORDER BY path, CASE WHEN branch = :branch THEN 0 ELSE 1 END
                        ) effective
                        GROUP BY language ORDER BY count DESC
                        """)
                        .bind("repoId", repoId)
                        .bind("branch", effectiveBranch)
                        .mapToMap()
                        .list();
                result.put("languageBreakdown", languageBreakdown);

                // Top-level directories for effective files
                var topLevelDirs = handle.createQuery("""
                        SELECT DISTINCT split_part(path, '/', 1) AS dir FROM (
                            SELECT DISTINCT ON (path) path
                            FROM files
                            WHERE repo_id = :repoId AND branch IN (:branch, 'main') AND path LIKE '%/%'
                            ORDER BY path, CASE WHEN branch = :branch THEN 0 ELSE 1 END
                        ) effective
                        ORDER BY dir
                        """)
                        .bind("repoId", repoId)
                        .bind("branch", effectiveBranch)
                        .mapTo(String.class)
                        .list();
                result.put("topLevelDirectories", topLevelDirs);
            }

            // Add SCIP precision indicator
            String scipStatus = getScipStatus(repoName);
            if (scipStatus != null) result.put("scip_status", scipStatus);

            return (Map<String, Object>) result;
        });
    }

    /**
     * Get a summary of a specific file including its symbols and imports.
     */
    public Map<String, Object> getFileSummary(String repoName, String filePath, String branch) {
        String effectiveBranch = resolveBranch(branch);
        ensureBranchIndexed(repoName, effectiveBranch);
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.id, ef.path, ef.language, ef.size_bytes, ef.last_commit_sha, ef.last_modified_at,
                           r.name AS repo_name
                    FROM effective_files ef
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE r.name = :repoName AND ef.path = :filePath
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            params.put("repoName", repoName);
            params.put("filePath", filePath);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            var optFile = q.mapToMap().findOne();

            if (optFile.isEmpty()) {
                return Collections.<String, Object>emptyMap();
            }

            var result = new LinkedHashMap<>(optFile.get());
            int fileId = ((Number) result.get("id")).intValue();

            // List symbols
            var symbols = handle.createQuery("""
                    SELECT name, kind, signature, start_line
                    FROM symbols WHERE file_id = :fileId
                    ORDER BY start_line
                    """)
                    .bind("fileId", fileId)
                    .mapToMap()
                    .list();
            result.put("symbols", symbols);

            // List imports
            var imports = handle.createQuery("""
                    SELECT import_path, alias
                    FROM imports WHERE file_id = :fileId
                    ORDER BY import_path
                    """)
                    .bind("fileId", fileId)
                    .mapToMap()
                    .list();
            result.put("imports", imports);

            return (Map<String, Object>) result;
        });
    }

    /**
     * Get a flat directory tree for a repository path prefix.
     */
    public List<Map<String, Object>> getDirectoryTree(String repoName, String path, int depth, String branch) {
        String effectiveBranch = resolveBranch(branch);
        ensureBranchIndexed(repoName, effectiveBranch);
        return jdbi.withHandle(handle -> {
            String prefix = (path != null && !path.isBlank()) ? path : "";
            String pattern = prefix.isEmpty() ? "%" : (prefix.endsWith("/") ? prefix + "%" : prefix + "/%");

            var sb = new StringBuilder(effectiveFilesCte(effectiveBranch));
            sb.append("""
                    SELECT ef.path, ef.language
                    FROM effective_files ef
                    JOIN repositories r ON ef.repo_id = r.id
                    WHERE r.name = :repoName AND ef.path LIKE :pattern
                    ORDER BY ef.path
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (!"main".equals(effectiveBranch)) {
                params.put("branch", effectiveBranch);
            }

            params.put("repoName", repoName);
            params.put("pattern", pattern);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }

    /**
     * Get the health status of the indexer, including per-repo stats and recent failures.
     */
    public Map<String, Object> getIndexHealth() {
        return jdbi.withHandle(handle -> {
            var result = new LinkedHashMap<String, Object>();

            // Per-repo stats
            var repos = handle.createQuery("""
                    SELECT r.name AS repo_name, r.last_indexed_sha, r.scip_uploaded_at,
                           CASE
                               WHEN NOT EXISTS (SELECT 1 FROM scip_symbols s WHERE s.repo_id = r.id) THEN 'unavailable'
                               WHEN EXISTS (SELECT 1 FROM scip_symbols s WHERE s.repo_id = r.id AND s.upload_sha = r.last_indexed_sha) THEN 'fresh'
                               ELSE 'stale'
                           END AS scip_status,
                           COUNT(CASE WHEN ie.status = 'pending' THEN 1 END) AS pending_events,
                           COUNT(CASE WHEN ie.status = 'failed' THEN 1 END) AS failed_events
                    FROM repositories r
                    LEFT JOIN indexing_events ie ON ie.repo_name = r.name
                    GROUP BY r.id, r.name, r.last_indexed_sha, r.scip_uploaded_at
                    ORDER BY r.name
                    """)
                    .mapToMap()
                    .list();
            result.put("repositories", repos);

            // System totals
            long totalPending = handle.createQuery(
                    "SELECT COUNT(*) FROM indexing_events WHERE status = 'pending'")
                    .mapTo(Long.class).one();
            result.put("totalPendingEvents", totalPending);

            long totalFailed = handle.createQuery(
                    "SELECT COUNT(*) FROM indexing_events WHERE status = 'failed'")
                    .mapTo(Long.class).one();
            result.put("totalFailedEvents", totalFailed);

            // Recent failures
            var recentFailures = handle.createQuery("""
                    SELECT repo_name, error_message, created_at
                    FROM indexing_events
                    WHERE status = 'failed'
                    ORDER BY created_at DESC
                    LIMIT 10
                    """)
                    .mapToMap()
                    .list();
            result.put("recentFailures", recentFailures);

            return result;
        });
    }

    /**
     * Get the SCIP data status for a given repository.
     * Returns "fresh", "stale", or "unavailable".
     */
    public String getScipStatus(String repoName) {
        if (repoName == null || repoName.isBlank()) return null;
        return jdbi.withHandle(handle -> {
            var repo = handle.createQuery("SELECT id, last_indexed_sha FROM repositories WHERE name = :name")
                    .bind("name", repoName).mapToMap().findOne();
            if (repo.isEmpty()) return null;
            int repoId = ((Number) repo.get().get("id")).intValue();
            String indexedSha = (String) repo.get().get("last_indexed_sha");

            boolean anyScip = handle.createQuery("SELECT EXISTS(SELECT 1 FROM scip_symbols WHERE repo_id = :id)")
                    .bind("id", repoId).mapTo(Boolean.class).one();
            if (!anyScip) return "unavailable";

            boolean freshScip = indexedSha != null && handle.createQuery(
                            "SELECT EXISTS(SELECT 1 FROM scip_symbols WHERE repo_id = :id AND upload_sha = :sha)")
                    .bind("id", repoId).bind("sha", indexedSha).mapTo(Boolean.class).one();
            return freshScip ? "fresh" : "stale";
        });
    }

    /**
     * Resolve a display name to SCIP symbol row(s). Returns the first match, optionally
     * narrowed by file_path, kind, and upload_sha. When uploadSha is non-null, only rows
     * for that SHA are considered — prevents cross-SHA non-determinism with multi-SHA data.
     * @param uploadSha the resolved ref SHA to scope to; callers pass a non-null SHA (null/blank means unfiltered, used only as internal defensiveness).
     */
    private Map<String, Object> resolveScipSymbol(org.jdbi.v3.core.Handle handle,
            int repoId, String symbolName, String filePath, String kind, String uploadSha) {
        var sb = new StringBuilder("""
                SELECT scip_symbol, display_name, kind, documentation, file_path, start_line, end_line
                FROM scip_symbols
                WHERE repo_id = :repoId AND display_name = :symbolName
                """);
        var params = new LinkedHashMap<String, Object>();
        params.put("repoId", repoId);
        params.put("symbolName", symbolName);

        if (uploadSha != null && !uploadSha.isBlank()) {
            sb.append(" AND upload_sha = :uploadSha");
            params.put("uploadSha", uploadSha);
        }
        if (filePath != null && !filePath.isBlank()) {
            sb.append(" AND file_path = :filePath");
            params.put("filePath", filePath);
        }
        if (kind != null && !kind.isBlank()) {
            sb.append(" AND kind = :kind");
            params.put("kind", kind);
        }
        sb.append(" LIMIT 1");

        var q = handle.createQuery(sb.toString());
        params.forEach(q::bind);
        return q.mapToMap().findOne().orElse(null);
    }

    /**
     * Look up the repo ID by name. Returns -1 if not found.
     */
    private int resolveRepoId(org.jdbi.v3.core.Handle handle, String repoName) {
        return handle.createQuery("SELECT id FROM repositories WHERE name = :name")
                .bind("name", repoName)
                .mapTo(Integer.class)
                .findOne()
                .orElse(-1);
    }

    /**
     * Recursively build a type hierarchy tree in one direction.
     * @param direction "up" follows from_symbol->to_symbol, "down" follows to_symbol->from_symbol
     * @param uploadSha the resolved ref SHA; MUST be non-null — a null would make every "upload_sha = :uploadSha" predicate false and silently return empty.
     */
    private List<Map<String, Object>> traverseHierarchy(org.jdbi.v3.core.Handle handle,
            int repoId, String scipSymbol, String direction, int maxDepth, int currentDepth,
            String uploadSha) {
        if (currentDepth >= maxDepth) return List.of();

        List<Map<String, Object>> edges;
        if ("up".equals(direction)) {
            edges = handle.createQuery("""
                    SELECT to_symbol AS related_symbol, kind AS relationship
                    FROM scip_relationships
                    WHERE repo_id = :repoId AND from_symbol = :symbol AND kind IN ('implements', 'extends')
                    AND upload_sha = :uploadSha
                    """)
                    .bind("repoId", repoId)
                    .bind("symbol", scipSymbol)
                    .bind("uploadSha", uploadSha)
                    .mapToMap()
                    .list();
        } else {
            edges = handle.createQuery("""
                    SELECT from_symbol AS related_symbol, kind AS relationship
                    FROM scip_relationships
                    WHERE repo_id = :repoId AND to_symbol = :symbol AND kind IN ('implements', 'extends')
                    AND upload_sha = :uploadSha
                    """)
                    .bind("repoId", repoId)
                    .bind("symbol", scipSymbol)
                    .bind("uploadSha", uploadSha)
                    .mapToMap()
                    .list();
        }

        var results = new ArrayList<Map<String, Object>>();
        for (var edge : edges) {
            String relatedSymbol = (String) edge.get("related_symbol");
            String relationship = (String) edge.get("relationship");

            var node = new LinkedHashMap<String, Object>();
            // Enrich with symbol metadata — scoped to uploadSha to avoid findOne() throwing
            var symRow = handle.createQuery("""
                    SELECT display_name, kind, file_path, start_line, documentation
                    FROM scip_symbols
                    WHERE repo_id = :repoId AND scip_symbol = :symbol AND upload_sha = :uploadSha
                    """)
                    .bind("repoId", repoId)
                    .bind("symbol", relatedSymbol)
                    .bind("uploadSha", uploadSha)
                    .mapToMap()
                    .findOne();

            if (symRow.isPresent()) {
                node.put("symbol", symRow.get().get("display_name"));
                node.put("scip_symbol", relatedSymbol);
                node.put("kind", symRow.get().get("kind"));
                node.put("file_path", symRow.get().get("file_path"));
                node.put("line", symRow.get().get("start_line"));
            } else {
                node.put("scip_symbol", relatedSymbol);
            }
            node.put("relationship", relationship);

            // Recurse — pass uploadSha through
            String childKey = "up".equals(direction) ? "supertypes" : "subtypes";
            var children = traverseHierarchy(handle, repoId, relatedSymbol, direction, maxDepth,
                    currentDepth + 1, uploadSha);
            if (!children.isEmpty()) {
                node.put(childKey, children);
            }

            results.add(node);
        }
        return results;
    }

    /**
     * Get the type hierarchy for a symbol — supertypes, subtypes, or both.
     * @param branch ref to resolve SCIP at: branch name, tag, or commit SHA (null → main)
     */
    public Map<String, Object> getTypeHierarchy(String repo, String symbolName, String filePath,
            String kind, String direction, int depth, String branch) {
        if (direction == null || direction.isBlank()) direction = "both";
        if (depth <= 0) depth = 3;
        final String dir = direction;
        final int maxDepth = depth;

        ensureBranchIndexed(repo, resolveBranch(branch));

        return jdbi.withHandle(handle -> {
            int repoId = resolveRepoId(handle, repo);
            if (repoId < 0) {
                return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
            }

            String uploadSha = resolveRefSha(handle, repoId, branch);
            if (uploadSha == null) {
                var result = new LinkedHashMap<String, Object>();
                result.put("symbol", symbolName);
                result.put("message", "No SCIP data indexed for ref '" + resolveBranch(branch) + "'");
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) result.put("scip_status", scipStatus);
                return result;
            }

            var resolved = resolveScipSymbol(handle, repoId, symbolName, filePath, kind, uploadSha);
            if (resolved == null) {
                var result = new LinkedHashMap<String, Object>();
                result.put("symbol", symbolName);
                result.put("message", "Symbol not found in SCIP data");
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) result.put("scip_status", scipStatus);
                return result;
            }

            String scipSymbol = (String) resolved.get("scip_symbol");

            var result = new LinkedHashMap<String, Object>();
            result.put("symbol", resolved.get("display_name"));
            result.put("scip_symbol", scipSymbol);
            result.put("kind", resolved.get("kind"));
            result.put("file_path", resolved.get("file_path"));
            result.put("line", resolved.get("start_line"));
            if (resolved.get("documentation") != null) {
                result.put("documentation", resolved.get("documentation"));
            }

            if ("up".equals(dir) || "both".equals(dir)) {
                result.put("supertypes", traverseHierarchy(handle, repoId, scipSymbol, "up", maxDepth, 0, uploadSha));
            }
            if ("down".equals(dir) || "both".equals(dir)) {
                result.put("subtypes", traverseHierarchy(handle, repoId, scipSymbol, "down", maxDepth, 0, uploadSha));
            }

            String scipStatus = getScipStatus(repo);
            if (scipStatus != null) result.put("scip_status", scipStatus);

            return result;
        });
    }

    /**
     * Find symbols related to a given symbol through SCIP relationships.
     * Flat list of direct edges (not recursive).
     * @param branch ref to resolve SCIP at: branch name, tag, or commit SHA (null → main)
     */
    public Map<String, Object> getSymbolReferences(String repo, String symbolName, String filePath,
            String relationshipKind, String direction, int limit, String branch) {
        if (direction == null || direction.isBlank()) direction = "inbound";
        if (limit <= 0) limit = 50;
        final String dir = direction;
        final int maxResults = limit;

        ensureBranchIndexed(repo, resolveBranch(branch));

        return jdbi.withHandle(handle -> {
            int repoId = resolveRepoId(handle, repo);
            if (repoId < 0) {
                return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
            }

            String uploadSha = resolveRefSha(handle, repoId, branch);
            if (uploadSha == null) {
                var result = new LinkedHashMap<String, Object>();
                result.put("symbol", symbolName);
                result.put("message", "No SCIP data indexed for ref '" + resolveBranch(branch) + "'");
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) result.put("scip_status", scipStatus);
                return result;
            }

            var resolved = resolveScipSymbol(handle, repoId, symbolName, filePath, null, uploadSha);
            if (resolved == null) {
                var result = new LinkedHashMap<String, Object>();
                result.put("symbol", symbolName);
                result.put("message", "Symbol not found in SCIP data");
                String scipStatus = getScipStatus(repo);
                if (scipStatus != null) result.put("scip_status", scipStatus);
                return result;
            }

            String scipSymbol = (String) resolved.get("scip_symbol");

            var references = new ArrayList<Map<String, Object>>();

            // Inbound: who references this symbol?
            if ("inbound".equals(dir) || "both".equals(dir)) {
                var sb = new StringBuilder("""
                        SELECT sr.from_symbol, sr.kind AS relationship, sr.file_path, sr.line
                        FROM scip_relationships sr
                        WHERE sr.repo_id = :repoId AND sr.to_symbol = :symbol AND sr.upload_sha = :uploadSha
                        """);
                var params = new LinkedHashMap<String, Object>();
                params.put("repoId", repoId);
                params.put("symbol", scipSymbol);
                params.put("uploadSha", uploadSha);
                if (relationshipKind != null && !relationshipKind.isBlank()) {
                    sb.append(" AND sr.kind = :kind");
                    params.put("kind", relationshipKind);
                }
                sb.append(" LIMIT :limit");
                params.put("limit", maxResults);

                var q = handle.createQuery(sb.toString());
                params.forEach(q::bind);
                for (var row : q.mapToMap().list()) {
                    var ref = new LinkedHashMap<String, Object>();
                    String fromSymbol = (String) row.get("from_symbol");
                    // Enrich with symbol metadata — scoped to uploadSha to avoid findOne() throwing
                    var symRow = handle.createQuery("""
                            SELECT display_name, kind FROM scip_symbols
                            WHERE repo_id = :repoId AND scip_symbol = :symbol AND upload_sha = :uploadSha
                            """)
                            .bind("repoId", repoId)
                            .bind("symbol", fromSymbol)
                            .bind("uploadSha", uploadSha)
                            .mapToMap()
                            .findOne();
                    if (symRow.isPresent()) {
                        ref.put("symbol", symRow.get().get("display_name"));
                        ref.put("kind", symRow.get().get("kind"));
                    }
                    ref.put("scip_symbol", fromSymbol);
                    ref.put("relationship", row.get("relationship"));
                    ref.put("file_path", row.get("file_path"));
                    ref.put("line", row.get("line"));
                    ref.put("direction", "inbound");
                    references.add(ref);
                }
            }

            // Outbound: what does this symbol reference?
            if ("outbound".equals(dir) || "both".equals(dir)) {
                var sb = new StringBuilder("""
                        SELECT sr.to_symbol, sr.kind AS relationship, sr.file_path, sr.line
                        FROM scip_relationships sr
                        WHERE sr.repo_id = :repoId AND sr.from_symbol = :symbol AND sr.upload_sha = :uploadSha
                        """);
                var params = new LinkedHashMap<String, Object>();
                params.put("repoId", repoId);
                params.put("symbol", scipSymbol);
                params.put("uploadSha", uploadSha);
                if (relationshipKind != null && !relationshipKind.isBlank()) {
                    sb.append(" AND sr.kind = :kind");
                    params.put("kind", relationshipKind);
                }
                sb.append(" LIMIT :limit");
                params.put("limit", maxResults);

                var q = handle.createQuery(sb.toString());
                params.forEach(q::bind);
                for (var row : q.mapToMap().list()) {
                    var ref = new LinkedHashMap<String, Object>();
                    String toSymbol = (String) row.get("to_symbol");
                    // Enrich — scoped to uploadSha to avoid findOne() throwing
                    var symRow = handle.createQuery("""
                            SELECT display_name, kind FROM scip_symbols
                            WHERE repo_id = :repoId AND scip_symbol = :symbol AND upload_sha = :uploadSha
                            """)
                            .bind("repoId", repoId)
                            .bind("symbol", toSymbol)
                            .bind("uploadSha", uploadSha)
                            .mapToMap()
                            .findOne();
                    if (symRow.isPresent()) {
                        ref.put("symbol", symRow.get().get("display_name"));
                        ref.put("kind", symRow.get().get("kind"));
                    }
                    ref.put("scip_symbol", toSymbol);
                    ref.put("relationship", row.get("relationship"));
                    ref.put("file_path", row.get("file_path"));
                    ref.put("line", row.get("line"));
                    ref.put("direction", "outbound");
                    references.add(ref);
                }
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("symbol", resolved.get("display_name"));
            result.put("scip_symbol", scipSymbol);
            result.put("kind", resolved.get("kind"));
            result.put("file_path", resolved.get("file_path"));
            result.put("references", references);
            result.put("total", references.size());
            String scipStatus = getScipStatus(repo);
            if (scipStatus != null) result.put("scip_status", scipStatus);

            return result;
        });
    }

    /**
     * Check whether a local repository HEAD SHA matches the indexed SHA.
     * Supports branch-aware comparison.
     */
    public Map<String, Object> checkSync(String repoName, String localSha, String branch) {
        String effectiveBranch = resolveBranch(branch);

        return jdbi.withHandle(handle -> {
            var optRepo = handle.createQuery(
                            "SELECT id, name, last_indexed_sha, last_indexed_at FROM repositories WHERE name = :name")
                    .bind("name", repoName)
                    .mapToMap()
                    .findOne();

            if (optRepo.isEmpty()) {
                return Map.<String, Object>of("error",
                        "Repository '" + repoName + "' not found in index");
            }

            var repo = optRepo.get();
            int repoId = ((Number) repo.get("id")).intValue();

            String indexedSha;
            Object indexedAt;

            if ("main".equals(effectiveBranch)) {
                indexedSha = (String) repo.get("last_indexed_sha");
                indexedAt = repo.get("last_indexed_at");
            } else {
                // Look up branch_index for this branch
                var optBranch = handle.createQuery(
                                "SELECT indexed_sha, indexed_at FROM branch_index WHERE repo_id = :repoId AND branch = :branch")
                        .bind("repoId", repoId)
                        .bind("branch", effectiveBranch)
                        .mapToMap()
                        .findOne();

                if (optBranch.isEmpty()) {
                    // Branch not indexed — trigger fault-in if possible
                    ensureBranchIndexed(repoName, effectiveBranch);

                    // Re-check after fault-in
                    optBranch = handle.createQuery(
                                    "SELECT indexed_sha, indexed_at FROM branch_index WHERE repo_id = :repoId AND branch = :branch")
                            .bind("repoId", repoId)
                            .bind("branch", effectiveBranch)
                            .mapToMap()
                            .findOne();
                }

                if (optBranch.isEmpty()) {
                    var result = new LinkedHashMap<String, Object>();
                    result.put("repo_name", repoName);
                    result.put("branch", effectiveBranch);
                    result.put("status", "not_indexed");
                    result.put("local_sha", localSha);
                    result.put("indexed_sha", null);
                    result.put("indexed_at", null);
                    result.put("message", "Branch '" + effectiveBranch + "' could not be indexed. It may not exist on the remote.");
                    return (Map<String, Object>) result;
                }

                indexedSha = (String) optBranch.get().get("indexed_sha");
                indexedAt = optBranch.get().get("indexed_at");

                // Touch last_accessed_at
                if (branchIndexDao != null) {
                    branchIndexDao.touchLastAccessed(repoId, effectiveBranch);
                }
            }

            if (indexedSha == null) {
                var result = new LinkedHashMap<String, Object>();
                result.put("repo_name", repoName);
                result.put("branch", effectiveBranch);
                result.put("status", "not_indexed");
                result.put("local_sha", localSha);
                result.put("indexed_sha", null);
                result.put("indexed_at", null);
                result.put("message", "Repository exists but has not been indexed yet.");
                return (Map<String, Object>) result;
            }

            boolean inSync = shaMatches(localSha, indexedSha);

            var result = new LinkedHashMap<String, Object>();
            result.put("repo_name", repoName);
            result.put("branch", effectiveBranch);
            result.put("status", inSync ? "in_sync" : "out_of_sync");
            result.put("local_sha", localSha);
            result.put("indexed_sha", indexedSha);
            result.put("indexed_at", indexedAt != null ? indexedAt.toString() : null);
            result.put("message", inSync
                    ? "Your local repo matches the index."
                    : "Your local repo does not match the index.");
            if (!inSync) {
                result.put("action", "Run 'git pull' to sync, or push your changes to trigger re-indexing.");
            }
            return (Map<String, Object>) result;
        });
    }

    public List<Map<String, Object>> queryAuditLog(String callerHash, String action, String repo,
                                                    String resultStatus, Instant since, Instant until, int limit) {
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder("SELECT ae.*, aim.user_id, aim.display_name ");
            sb.append("FROM audit_events ae ");
            sb.append("LEFT JOIN audit_identity_map aim ON ae.caller_hash = aim.caller_hash ");
            sb.append("WHERE 1=1 ");

            var params = new java.util.LinkedHashMap<String, Object>();

            if (callerHash != null && !callerHash.isBlank()) {
                sb.append("AND ae.caller_hash = :callerHash ");
                params.put("callerHash", callerHash);
            }
            if (action != null && !action.isBlank()) {
                sb.append("AND ae.action = :action ");
                params.put("action", action);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append("AND ae.repo = :repo ");
                params.put("repo", repo);
            }
            if (resultStatus != null && !resultStatus.isBlank()) {
                sb.append("AND ae.result_status = :resultStatus ");
                params.put("resultStatus", resultStatus);
            }
            if (since != null) {
                sb.append("AND ae.timestamp >= :since ");
                params.put("since", since);
            }
            if (until != null) {
                sb.append("AND ae.timestamp <= :until ");
                params.put("until", until);
            }

            sb.append("ORDER BY ae.timestamp DESC LIMIT :limit");
            params.put("limit", Math.min(limit, 500));

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }

    public Map<String, Object> verifyAuditChain(int count) {
        int effectiveCount = Math.min(Math.max(count, 1), 1000);
        return jdbi.withHandle(handle -> {
            var events = handle.createQuery("""
                    SELECT id, caller_hash, action, repo, result_status,
                           EXTRACT(EPOCH FROM timestamp) * 1000 AS timestamp_millis,
                           chain_hash
                    FROM audit_events ORDER BY id DESC LIMIT :count
                    """)
                    .bind("count", effectiveCount)
                    .mapToMap()
                    .list();

            if (events.isEmpty()) {
                return Map.<String, Object>of(
                        "checked", 0, "intact", true, "message", "No audit events found");
            }

            // Reverse to process oldest first
            var sorted = new java.util.ArrayList<>(events);
            java.util.Collections.reverse(sorted);

            // Get the hash before the first event in our window
            long firstId = ((Number) sorted.get(0).get("id")).longValue();
            String prevHash;
            if (firstId == 1) {
                prevHash = "aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e"; // genesis
            } else {
                prevHash = handle.createQuery(
                        "SELECT chain_hash FROM audit_events WHERE id = :id")
                        .bind("id", firstId - 1)
                        .mapTo(String.class)
                        .findOne()
                        .orElse("aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e");
            }

            for (int i = 0; i < sorted.size(); i++) {
                var event = sorted.get(i);
                String callerHash = (String) event.get("caller_hash");
                String action = (String) event.get("action");
                String repo = event.get("repo") != null ? (String) event.get("repo") : "null";
                String resultStatus = (String) event.get("result_status");
                long timestampMillis = ((Number) event.get("timestamp_millis")).longValue();
                String storedHash = (String) event.get("chain_hash");

                String chainInput = prevHash + "|" + callerHash + "|" + action
                        + "|" + repo + "|" + resultStatus + "|" + timestampMillis;
                String computedHash = com.indexer.audit.AuditEvent.sha256(chainInput);

                if (!computedHash.equals(storedHash)) {
                    long eventId = ((Number) event.get("id")).longValue();
                    return Map.<String, Object>of(
                            "checked", i + 1,
                            "intact", false,
                            "break_at_event_id", eventId,
                            "break_at_position", i + 1,
                            "message", "Chain break detected at event " + eventId);
                }
                prevHash = storedHash;
            }

            return Map.<String, Object>of(
                    "checked", sorted.size(), "intact", true,
                    "message", "Chain intact for " + sorted.size() + " events");
        });
    }

    /**
     * Compare two branches and return differences at file or symbol granularity.
     * Uses DISTINCT ON overlay for each branch to get effective file sets, then FULL OUTER JOIN to find diffs.
     */
    public Map<String, Object> diffBranches(String repo, String branchA, String branchB, String detail, int limit) {
        String effectiveDetail = (detail != null && "files".equalsIgnoreCase(detail)) ? "files" : "symbols";
        ensureBranchIndexed(repo, branchA);
        ensureBranchIndexed(repo, branchB);

        return jdbi.withHandle(handle -> {
            var optRepo = handle.createQuery("SELECT id FROM repositories WHERE name = :name")
                    .bind("name", repo)
                    .mapTo(Integer.class)
                    .findOne();
            if (optRepo.isEmpty()) {
                return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
            }
            int repoId = optRepo.get();

            if ("files".equals(effectiveDetail)) {
                return diffFiles(handle, repoId, branchA, branchB, limit);
            } else {
                return diffSymbols(handle, repoId, branchA, branchB, limit);
            }
        });
    }

    private Map<String, Object> diffFiles(org.jdbi.v3.core.Handle handle, int repoId,
                                           String branchA, String branchB, int limit) {
        var rows = handle.createQuery("""
                WITH effective_a AS (
                    SELECT DISTINCT ON (path) path, language, last_commit_sha
                    FROM files WHERE repo_id = :repoId AND branch IN (:branchA, 'main')
                    ORDER BY path, CASE WHEN branch = :branchA THEN 0 ELSE 1 END
                ),
                effective_b AS (
                    SELECT DISTINCT ON (path) path, language, last_commit_sha
                    FROM files WHERE repo_id = :repoId AND branch IN (:branchB, 'main')
                    ORDER BY path, CASE WHEN branch = :branchB THEN 0 ELSE 1 END
                )
                SELECT a.path AS a_path, a.language AS a_lang, a.last_commit_sha AS a_sha,
                       b.path AS b_path, b.language AS b_lang, b.last_commit_sha AS b_sha
                FROM effective_a a
                FULL OUTER JOIN effective_b b ON a.path = b.path
                WHERE a.path IS NULL OR b.path IS NULL OR a.last_commit_sha != b.last_commit_sha
                LIMIT :limit
                """)
                .bind("repoId", repoId)
                .bind("branchA", branchA)
                .bind("branchB", branchB)
                .bind("limit", limit)
                .mapToMap()
                .list();

        var added = new java.util.ArrayList<Map<String, Object>>();
        var removed = new java.util.ArrayList<Map<String, Object>>();
        var modified = new java.util.ArrayList<Map<String, Object>>();

        for (var row : rows) {
            if (row.get("b_path") == null) {
                added.add(Map.of("path", row.get("a_path"), "language", nullSafeObj(row.get("a_lang"))));
            } else if (row.get("a_path") == null) {
                removed.add(Map.of("path", row.get("b_path"), "language", nullSafeObj(row.get("b_lang"))));
            } else {
                modified.add(Map.of("path", row.get("a_path"), "language", nullSafeObj(row.get("a_lang"))));
            }
        }

        return Map.of("detail", "files", "branch_a", branchA, "branch_b", branchB,
                "added", added, "removed", removed, "modified", modified);
    }

    private Map<String, Object> diffSymbols(org.jdbi.v3.core.Handle handle, int repoId,
                                             String branchA, String branchB, int limit) {
        // Fetch the effective symbol set for each branch (branch files overlay main).
        // We diff in Java keyed by a full fingerprint (file+name+kind+signature) so that
        // overloaded / same-named symbols don't cartesian-explode the way a name-only
        // SQL self-join does. A symbol whose signature is unchanged contributes the same
        // fingerprint to both branches and simply cancels out.
        var aRows = fetchEffectiveSymbols(handle, repoId, branchA);
        var bRows = fetchEffectiveSymbols(handle, repoId, branchB);

        var aByFp = new LinkedHashMap<String, Map<String, Object>>();
        for (var r : aRows) aByFp.putIfAbsent(symbolFingerprint(r), r);
        var bByFp = new LinkedHashMap<String, Map<String, Object>>();
        for (var r : bRows) bByFp.putIfAbsent(symbolFingerprint(r), r);

        // Fingerprints present in exactly one branch.
        var onlyA = new java.util.ArrayList<Map<String, Object>>();
        for (var e : aByFp.entrySet()) if (!bByFp.containsKey(e.getKey())) onlyA.add(e.getValue());
        var onlyB = new java.util.ArrayList<Map<String, Object>>();
        for (var e : bByFp.entrySet()) if (!aByFp.containsKey(e.getKey())) onlyB.add(e.getValue());

        // Index the B-only rows by (file, name, kind) so a B-only symbol whose name also
        // appears among the A-only rows can be paired up as a *modification* rather than
        // double-counted as an add + a remove.
        var onlyBByNameKey = new LinkedHashMap<String, java.util.List<Map<String, Object>>>();
        for (var r : onlyB) onlyBByNameKey.computeIfAbsent(symbolNameKey(r), k -> new java.util.ArrayList<>()).add(r);

        var added = new java.util.ArrayList<Map<String, Object>>();
        var removed = new java.util.ArrayList<Map<String, Object>>();
        var modified = new java.util.ArrayList<Map<String, Object>>();
        var consumedB = new java.util.HashSet<Map<String, Object>>();

        for (var a : onlyA) {
            var candidates = onlyBByNameKey.get(symbolNameKey(a));
            Map<String, Object> partner = null;
            if (candidates != null) {
                for (var b : candidates) {
                    if (!consumedB.contains(b)) { partner = b; break; }
                }
            }
            if (partner != null) {
                consumedB.add(partner);
                var entry = new LinkedHashMap<String, Object>();
                entry.put("name", a.get("name"));
                entry.put("kind", a.get("kind"));
                entry.put("file_path", a.get("file_path"));
                entry.put("branch_a_signature", nullSafeObj(a.get("signature")));
                entry.put("branch_b_signature", nullSafeObj(partner.get("signature")));
                modified.add(entry);
            } else {
                added.add(Map.of(
                        "name", a.get("name"), "kind", a.get("kind"),
                        "file_path", a.get("file_path"), "signature", nullSafeObj(a.get("signature"))));
            }
        }
        for (var b : onlyB) {
            if (consumedB.contains(b)) continue;
            removed.add(Map.of(
                    "name", b.get("name"), "kind", b.get("kind"),
                    "file_path", b.get("file_path"), "signature", nullSafeObj(b.get("signature"))));
        }

        return Map.of("detail", "symbols", "branch_a", branchA, "branch_b", branchB,
                "added", capList(added, limit), "removed", capList(removed, limit),
                "modified", capList(modified, limit));
    }

    private java.util.List<Map<String, Object>> fetchEffectiveSymbols(org.jdbi.v3.core.Handle handle,
                                                                       int repoId, String branch) {
        return handle.createQuery("""
                WITH effective AS (
                    SELECT DISTINCT ON (f.path) f.id AS file_id, f.path
                    FROM files f WHERE f.repo_id = :repoId AND f.branch IN (:branch, 'main')
                    ORDER BY f.path, CASE WHEN f.branch = :branch THEN 0 ELSE 1 END
                )
                SELECT e.path AS file_path, s.name, s.kind, s.signature, s.start_line, s.end_line
                FROM symbols s JOIN effective e ON s.file_id = e.file_id
                """)
                .bind("repoId", repoId)
                .bind("branch", branch)
                .mapToMap()
                .list();
    }

    private static String symbolFingerprint(Map<String, Object> r) {
        return r.get("file_path") + " " + r.get("name") + " "
                + r.get("kind") + " " + nullSafeObj(r.get("signature"));
    }

    private static String symbolNameKey(Map<String, Object> r) {
        return r.get("file_path") + " " + r.get("name") + " " + r.get("kind");
    }

    private static <T> java.util.List<T> capList(java.util.List<T> list, int limit) {
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private static Object nullSafeObj(Object val) {
        return val != null ? val : "";
    }

    /**
     * Search for symbols matching a pattern across multiple indexed branches.
     * Returns results grouped by branch. Searches branch delta files only (not full overlay).
     */
    public Map<String, Object> searchBranches(String repo, String query, String kind,
                                               String branchPattern, int maxBranches, int limit) {
        String effectivePattern = (branchPattern != null && !branchPattern.isBlank()) ? branchPattern : ".*";
        int effectiveMaxBranches = Math.min(Math.max(maxBranches, 1), 200);
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);

        return jdbi.withHandle(handle -> {
            var optRepo = handle.createQuery("SELECT id FROM repositories WHERE name = :name")
                    .bind("name", repo)
                    .mapTo(Integer.class)
                    .findOne();
            if (optRepo.isEmpty()) {
                return Map.<String, Object>of("error", "Repository '" + repo + "' not found");
            }
            int repoId = optRepo.get();

            var matchingBranches = handle.createQuery("""
                    SELECT branch FROM branch_index
                    WHERE repo_id = :repoId AND branch ~ :pattern
                    ORDER BY branch
                    LIMIT :maxBranches
                    """)
                    .bind("repoId", repoId)
                    .bind("pattern", effectivePattern)
                    .bind("maxBranches", effectiveMaxBranches)
                    .mapTo(String.class)
                    .list();

            int branchesSearched = matchingBranches.size() + 1; // +1 for main

            var allBranches = new java.util.ArrayList<String>();
            allBranches.add("main");
            allBranches.addAll(matchingBranches);

            // Build IN clause from server-controlled branch names
            String branchList = allBranches.stream()
                    .map(b -> "'" + b.replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(", "));

            var sb = new StringBuilder(
                    "SELECT f.branch, s.name, s.kind, s.signature, f.path AS file_path " +
                    "FROM symbols s JOIN files f ON s.file_id = f.id " +
                    "WHERE f.repo_id = :repoId AND s.name ~* :query " +
                    "AND f.branch IN (" + branchList + ")");

            if (kind != null && !kind.isBlank()) {
                sb.append(" AND s.kind = :kind");
            }
            sb.append(" ORDER BY f.branch, s.name");

            var q = handle.createQuery(sb.toString())
                    .bind("repoId", repoId)
                    .bind("query", query);
            if (kind != null && !kind.isBlank()) {
                q.bind("kind", kind);
            }

            var allResults = q.mapToMap().list();

            // Group by branch, cap per-branch results
            var grouped = new LinkedHashMap<String, java.util.List<Map<String, Object>>>();
            for (var row : allResults) {
                String branch = (String) row.get("branch");
                grouped.computeIfAbsent(branch, k -> new java.util.ArrayList<>());
                var branchResults = grouped.get(branch);
                if (branchResults.size() < effectiveLimit) {
                    branchResults.add(Map.of(
                            "name", row.get("name"),
                            "kind", row.get("kind"),
                            "file_path", row.get("file_path"),
                            "signature", nullSafeObj(row.get("signature"))
                    ));
                }
            }

            var results = new java.util.ArrayList<Map<String, Object>>();
            for (var entry : grouped.entrySet()) {
                results.add(Map.of("branch", entry.getKey(), "symbols", entry.getValue()));
            }

            return Map.<String, Object>of(
                    "branches_searched", branchesSearched,
                    "branches_matched", grouped.size(),
                    "results", results);
        });
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Compare two git SHAs, handling abbreviated SHAs and case differences.
     */
    private boolean shaMatches(String sha1, String sha2) {
        if (sha1 == null || sha2 == null) return false;
        String lower1 = sha1.toLowerCase();
        String lower2 = sha2.toLowerCase();
        return lower1.startsWith(lower2) || lower2.startsWith(lower1);
    }

    /**
     * Build a CTE that returns the effective files for a repo+branch combination.
     * Branch-specific files take priority over main files for the same path.
     * When branch is null or "main", this returns only main files.
     */
    private String effectiveFilesCte(String branch) {
        String effectiveBranch = (branch == null || branch.isBlank()) ? "main" : branch;
        if ("main".equals(effectiveBranch)) {
            return """
                    WITH effective_files AS (
                        SELECT f.id, f.repo_id, f.path, f.language, f.size_bytes,
                               f.last_commit_sha, f.last_modified_at, f.branch
                        FROM files f
                        WHERE f.branch = 'main'
                    )
                    """;
        }
        return """
                WITH effective_files AS (
                    SELECT DISTINCT ON (f.repo_id, f.path)
                           f.id, f.repo_id, f.path, f.language, f.size_bytes,
                           f.last_commit_sha, f.last_modified_at, f.branch
                    FROM files f
                    WHERE f.branch IN (:branch, 'main')
                    ORDER BY f.repo_id, f.path,
                             CASE WHEN f.branch = :branch THEN 0 ELSE 1 END
                )
                """;
    }

    /**
     * Resolve branch to a non-null value. Null or blank defaults to "main".
     */
    private String resolveBranch(String branch) {
        return (branch == null || branch.isBlank()) ? "main" : branch;
    }

    /**
     * Resolve a ref (branch/tag/sha) to the indexed commit SHA whose SCIP we should read.
     * main -> repositories.last_indexed_sha; any other ref -> branch_index.indexed_sha.
     */
    private String resolveRefSha(org.jdbi.v3.core.Handle handle, int repoId, String branch) {
        String effective = resolveBranch(branch);
        if ("main".equals(effective)) {
            return handle.createQuery("SELECT last_indexed_sha FROM repositories WHERE id = :id")
                    .bind("id", repoId).mapTo(String.class).findOne().orElse(null);
        }
        return handle.createQuery("SELECT indexed_sha FROM branch_index WHERE repo_id = :id AND branch = :b")
                .bind("id", repoId).bind("b", effective).mapTo(String.class).findOne().orElse(null);
    }

    /**
     * Ensure an index exists for the given ref (branch, tag, or commit SHA). If no
     * branch_index record exists, attempts a synchronous fault-in. Falls back to
     * main-only results if the ref is unresolvable or fault-in fails.
     * This is a no-op for the main branch or when fault-in dependencies are not configured.
     */
    private void ensureBranchIndexed(String repo, String effectiveBranch) {
        if ("main".equals(effectiveBranch)) return;
        if (branchIndexDao == null || indexingPipeline == null || repositoryDao == null || gitOps == null) return;

        // Check if we already have an index for this branch
        var repoRecord = repositoryDao.findByName(repo);
        if (repoRecord.isEmpty()) return;

        var repoObj = repoRecord.get();
        var existing = branchIndexDao.find(repoObj.id(), effectiveBranch);

        if (existing.isPresent()) {
            // Branch is indexed -- touch last_accessed_at to reset TTL
            branchIndexDao.touchLastAccessed(repoObj.id(), effectiveBranch);
            return;
        }

        // No index exists -- attempt fault-in
        log.info("Ref '{}' not indexed for repo '{}', triggering synchronous fault-in", effectiveBranch, repo);
        try {
            Path repoDir = Path.of(repoObj.clonePath());
            gitOps.fetch(repoDir, null); // also fetches tags

            var resolved = gitOps.resolveAnyRef(repoDir, effectiveBranch);
            if (resolved.isEmpty()) {
                log.debug("Ref '{}' not resolvable for repo '{}', falling back to main", effectiveBranch, repo);
                return;
            }
            var ref = resolved.get();
            indexingPipeline.branchIndex(repoObj.id(), effectiveBranch, repoDir, ref.sha(), ref.kind());
            log.info("Fault-in complete for ref '{}' ({}) repo '{}'", effectiveBranch, ref.kind(), repo);
        } catch (Exception e) {
            log.warn("Fault-in failed for ref '{}' repo '{}': {}", effectiveBranch, repo, e.getMessage());
            // Fall through -- query will return main-only results
        }
    }

    /**
     * Slice lines [startLine, endLine] (1-based, inclusive) out of stored file content.
     * Mirrors the old disk-based readSourceLines semantics but reads from file_contents
     * (ref-aware via the effective_files overlay) instead of the working tree.
     * Returns null when content is null (binary/oversized/metadata-only files).
     */
    private static String sliceLines(String content, int startLine, int endLine) {
        if (content == null) {
            return null;
        }
        // String.lines() splits on \n, \r, and \r\n and drops a trailing terminator,
        // matching Files.readAllLines — so symbol line numbers line up.
        List<String> lines = content.lines().toList();
        int from = Math.max(0, startLine - 1);
        int to = Math.min(lines.size(), endLine);
        if (from >= to) {
            return "";
        }
        return String.join("\n", lines.subList(from, to));
    }
}

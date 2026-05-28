package com.indexer.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.auth.CallerIdentity;
import com.indexer.auth.PermissionCache;
import com.indexer.db.BranchIndexDao;
import com.indexer.db.RepositoryDao;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.repository.GitOperations;
import io.modelcontextprotocol.spec.McpSchema;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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

    // Backward-compatible constructor (used in tests)
    public QueryExecutor(Jdbi jdbi) {
        this(jdbi, null, null, null, null, null);
    }

    public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                         RepositoryDao repositoryDao, GitOperations gitOps) {
        this(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, null);
    }

    public QueryExecutor(Jdbi jdbi, BranchIndexDao branchIndexDao, IndexingPipeline indexingPipeline,
                         RepositoryDao repositoryDao, GitOperations gitOps, PermissionCache permissionCache) {
        this.jdbi = jdbi;
        this.branchIndexDao = branchIndexDao;
        this.indexingPipeline = indexingPipeline;
        this.repositoryDao = repositoryDao;
        this.gitOps = gitOps;
        this.permissionCache = permissionCache;
    }

    /**
     * Pipeline wrapper that logs the caller, executes the query, and returns
     * a CallToolResult with JSON-serialized output or an error.
     */
    public McpSchema.CallToolResult executeQuery(
            CallerIdentity caller, String repo, String action,
            Map<String, Object> params, Supplier<Object> query) {
        log.info("Tool call: {} by {} ({})", action, caller.displayName(), caller.authMethod());

        // Authorization check — only for OAuth users with configured permissions
        if (permissionCache != null && repo != null && "oauth".equals(caller.authMethod())) {
            try {
                Set<String> allowed = permissionCache.getAllowedRepos(caller);
                if (!allowed.contains(repo)) {
                    log.warn("Access denied: {} attempted to query repo {}", caller.displayName(), repo);
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("Access denied to repository: " + repo)
                            .isError(true)
                            .build();
                }
            } catch (Exception e) {
                log.error("Permission resolution failed for {}: {}", caller.displayName(), e.getMessage());
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Authorization failed: unable to verify permissions")
                        .isError(true)
                        .build();
            }
        }

        try {
            Object result = query.get();
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            log.error("Tool execution error in {}: {}", action, e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /**
     * Search symbols by name (regex), kind, language, and repo.
     */
    public List<Map<String, Object>> searchSymbols(String query, String kind, String language, String repo, String branch, int limit) {
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
            return q.mapToMap().list();
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
                           ef.path AS file_path, r.name AS repo_name, r.clone_path
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
            String clonePath = (String) symbol.get("clone_path");

            // Read source lines from disk
            symbol.put("source_code", readSourceLines(clonePath, filePath, startLine, endLine));

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

            return (Map<String, Object>) symbol;
        });
    }

    /**
     * Find implementations of a type by looking up 'implements' relationships.
     */
    public List<Map<String, Object>> findImplementations(String typeName, String repo, String branch) {
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
            return q.mapToMap().list();
        });
    }

    /**
     * Find files that import a given symbol name.
     */
    public List<Map<String, Object>> findReferences(String symbolName, String repo, String branch, int limit) {
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
            return q.mapToMap().list();
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
                    SELECT r.name AS repo_name, r.last_indexed_sha,
                           COUNT(CASE WHEN ie.status = 'pending' THEN 1 END) AS pending_events,
                           COUNT(CASE WHEN ie.status = 'failed' THEN 1 END) AS failed_events
                    FROM repositories r
                    LEFT JOIN indexing_events ie ON ie.repo_name = r.name
                    GROUP BY r.name, r.last_indexed_sha
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
     * Ensure branch index data exists. If querying a non-main branch and no branch_index
     * record exists, attempt synchronous fault-in by indexing the branch delta from main.
     * This is a no-op for main branch or when fault-in dependencies are not configured.
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
        log.info("Branch '{}' not indexed for repo '{}', triggering synchronous fault-in", effectiveBranch, repo);
        try {
            Path repoDir = Path.of(repoObj.clonePath());
            gitOps.fetch(repoDir, null);

            if (!gitOps.remoteBranchExists(repoDir, effectiveBranch)) {
                log.debug("Remote branch '{}' does not exist for repo '{}', falling back to main", effectiveBranch, repo);
                return;
            }

            String branchSha = gitOps.getShaForRef(repoDir, "origin/" + effectiveBranch);
            indexingPipeline.branchIndex(repoObj.id(), effectiveBranch, repoDir, branchSha);
            log.info("Fault-in complete for branch '{}' repo '{}'", effectiveBranch, repo);
        } catch (Exception e) {
            log.warn("Fault-in failed for branch '{}' repo '{}': {}", effectiveBranch, repo, e.getMessage());
            // Fall through -- query will return main-only results
        }
    }

    private String readSourceLines(String clonePath, String filePath, int startLine, int endLine) {
        if (clonePath == null || clonePath.isBlank()) {
            return null;
        }
        try {
            Path fullPath = Path.of(clonePath).resolve(filePath);
            if (!Files.exists(fullPath)) {
                return null;
            }
            List<String> lines = Files.readAllLines(fullPath);
            int from = Math.max(0, startLine - 1);
            int to = Math.min(lines.size(), endLine);
            return lines.subList(from, to).stream().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            log.warn("Could not read source lines from {}/{}: {}", clonePath, filePath, e.getMessage());
            return null;
        }
    }
}

package com.indexer.mcp;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core query engine for the MCP server. All MCP tools delegate to this class.
 */
public class QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);

    private final Jdbi jdbi;

    public QueryExecutor(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Search symbols by name (regex), kind, language, and repo.
     */
    public List<Map<String, Object>> searchSymbols(String query, String kind, String language, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder("""
                    SELECT s.name, s.kind, s.signature, s.start_line, s.end_line, s.visibility,
                           f.path AS file_path, r.name AS repo_name
                    FROM symbols s
                    JOIN files f ON s.file_id = f.id
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE 1=1
                    """);

            var params = new LinkedHashMap<String, Object>();

            if (query != null && !query.isBlank()) {
                sb.append(" AND s.name ~* :query");
                params.put("query", query);
            }
            if (kind != null && !kind.isBlank()) {
                sb.append(" AND s.kind = :kind");
                params.put("kind", kind);
            }
            if (language != null && !language.isBlank()) {
                sb.append(" AND f.language = :language");
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
    public Map<String, Object> getSymbolDetail(String repo, String filePath, String symbolName, Integer line) {
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder("""
                    SELECT s.id, s.name, s.kind, s.signature, s.start_line, s.end_line,
                           s.parent_id, s.visibility, s.is_static,
                           f.path AS file_path, r.name AS repo_name, r.clone_path
                    FROM symbols s
                    JOIN files f ON s.file_id = f.id
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE r.name = :repo AND f.path = :filePath AND s.name = :symbolName
                    """);

            var params = new LinkedHashMap<String, Object>();
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
    public List<Map<String, Object>> findImplementations(String typeName, String repo) {
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder("""
                    SELECT s.name AS class_name, s.signature, f.path AS file_path, r.name AS repo_name
                    FROM type_relationships tr
                    JOIN symbols s ON tr.symbol_id = s.id
                    JOIN files f ON s.file_id = f.id
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE tr.related_name = :typeName AND tr.kind = 'implements'
                    """);

            var params = new LinkedHashMap<String, Object>();
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
    public List<Map<String, Object>> findReferences(String symbolName, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder("""
                    SELECT f.path AS file_path, r.name AS repo_name, i.import_path
                    FROM imports i
                    JOIN files f ON i.file_id = f.id
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE i.import_path LIKE :pattern
                    """);

            var params = new LinkedHashMap<String, Object>();
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
    public List<Map<String, Object>> searchCode(String query, String language, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            var sb = new StringBuilder("""
                    SELECT f.path AS file_path, r.name AS repo_name,
                           ts_headline('english', fc.content, plainto_tsquery('english', :query),
                                       'StartSel=<<, StopSel=>>, MaxWords=30, MinWords=10') AS matching_lines
                    FROM file_contents fc
                    JOIN files f ON fc.file_id = f.id
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE fc.search_vector @@ plainto_tsquery('english', :query)
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("query", query);

            if (language != null && !language.isBlank()) {
                sb.append(" AND f.language = :language");
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
    public List<Map<String, Object>> searchFiles(String pattern, String language, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            // Convert glob * to SQL %
            String sqlPattern = pattern != null ? pattern.replace("*", "%") : "%";

            var sb = new StringBuilder("""
                    SELECT f.path, r.name AS repo_name, f.language, f.size_bytes, f.last_modified_at
                    FROM files f
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE f.path LIKE :pattern
                    """);

            var params = new LinkedHashMap<String, Object>();
            params.put("pattern", sqlPattern);

            if (language != null && !language.isBlank()) {
                sb.append(" AND f.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sb.append(" AND r.name = :repo");
                params.put("repo", repo);
            }

            sb.append(" ORDER BY f.path LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sb.toString());
            params.forEach(q::bind);
            return q.mapToMap().list();
        });
    }

    /**
     * Get a high-level summary of a repository.
     */
    public Map<String, Object> getRepoSummary(String repoName) {
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

            // Count files
            long fileCount = handle.createQuery("SELECT COUNT(*) FROM files WHERE repo_id = :repoId")
                    .bind("repoId", repoId)
                    .mapTo(Long.class)
                    .one();
            result.put("fileCount", fileCount);

            // Language breakdown
            var languageBreakdown = handle.createQuery("""
                    SELECT language, COUNT(*) AS count
                    FROM files WHERE repo_id = :repoId AND language IS NOT NULL
                    GROUP BY language ORDER BY count DESC
                    """)
                    .bind("repoId", repoId)
                    .mapToMap()
                    .list();
            result.put("languageBreakdown", languageBreakdown);

            // Top-level directories (first path segment)
            var topLevelDirs = handle.createQuery("""
                    SELECT DISTINCT split_part(path, '/', 1) AS dir
                    FROM files WHERE repo_id = :repoId AND path LIKE '%/%'
                    ORDER BY dir
                    """)
                    .bind("repoId", repoId)
                    .mapTo(String.class)
                    .list();
            result.put("topLevelDirectories", topLevelDirs);

            return (Map<String, Object>) result;
        });
    }

    /**
     * Get a summary of a specific file including its symbols and imports.
     */
    public Map<String, Object> getFileSummary(String repoName, String filePath) {
        return jdbi.withHandle(handle -> {
            var optFile = handle.createQuery("""
                    SELECT f.id, f.path, f.language, f.size_bytes, f.last_commit_sha, f.last_modified_at,
                           r.name AS repo_name
                    FROM files f
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE r.name = :repoName AND f.path = :filePath
                    """)
                    .bind("repoName", repoName)
                    .bind("filePath", filePath)
                    .mapToMap()
                    .findOne();

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
    public List<Map<String, Object>> getDirectoryTree(String repoName, String path, int depth) {
        return jdbi.withHandle(handle -> {
            String prefix = (path != null && !path.isBlank()) ? path : "";
            String pattern = prefix.isEmpty() ? "%" : (prefix.endsWith("/") ? prefix + "%" : prefix + "/%");

            return handle.createQuery("""
                    SELECT f.path, f.language
                    FROM files f
                    JOIN repositories r ON f.repo_id = r.id
                    WHERE r.name = :repoName AND f.path LIKE :pattern
                    ORDER BY f.path
                    """)
                    .bind("repoName", repoName)
                    .bind("pattern", pattern)
                    .mapToMap()
                    .list();
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

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

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

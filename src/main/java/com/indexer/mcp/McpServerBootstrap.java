package com.indexer.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Registers all 10 MCP tools with the MCP Java SDK and starts the server over stdio.
 */
public class McpServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(McpServerBootstrap.class);

    private final QueryExecutor queryExecutor;
    private McpSyncServer server;

    public McpServerBootstrap(Jdbi jdbi) {
        this.queryExecutor = new QueryExecutor(jdbi);
    }

    public void start() {
        var transport = new StdioServerTransportProvider(new ObjectMapper());

        server = McpServer.sync(transport)
                .serverInfo("source-code-indexer", "1.0.0")
                .tool(searchSymbolsTool(), this::handleSearchSymbols)
                .tool(getSymbolDetailTool(), this::handleGetSymbolDetail)
                .tool(findImplementationsTool(), this::handleFindImplementations)
                .tool(findReferencesTool(), this::handleFindReferences)
                .tool(searchCodeTool(), this::handleSearchCode)
                .tool(searchFilesTool(), this::handleSearchFiles)
                .tool(getRepoSummaryTool(), this::handleGetRepoSummary)
                .tool(getFileSummaryTool(), this::handleGetFileSummary)
                .tool(getDirectoryTreeTool(), this::handleGetDirectoryTree)
                .tool(getIndexHealthTool(), this::handleGetIndexHealth)
                .build();

        log.info("MCP server started over stdio with 10 tools registered");
    }

    public void stop() {
        if (server != null) {
            server.closeGracefully();
        }
    }

    // -----------------------------------------------------------------------
    // Tool definitions
    // -----------------------------------------------------------------------

    private McpSchema.Tool searchSymbolsTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "query",    Map.of("type", "string", "description", "Regex pattern to match symbol names"),
                        "kind",     Map.of("type", "string", "description", "Symbol kind (class, method, function, ...)"),
                        "language", Map.of("type", "string", "description", "Programming language filter"),
                        "repo",     Map.of("type", "string", "description", "Repository name filter"),
                        "limit",    Map.of("type", "integer", "description", "Max results to return", "default", 20)
                ),
                List.of(),
                false, null, null);
        return new McpSchema.Tool("search_symbols",
                "Search for symbols (classes, methods, functions) by name pattern, kind, language, or repo.",
                schema);
    }

    private McpSchema.Tool getSymbolDetailTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo",        Map.of("type", "string", "description", "Repository name"),
                        "file_path",   Map.of("type", "string", "description", "Path to the file within the repo"),
                        "symbol_name", Map.of("type", "string", "description", "Name of the symbol"),
                        "line",        Map.of("type", "integer", "description", "Optional start line to disambiguate overloads")
                ),
                List.of("repo", "file_path", "symbol_name"),
                false, null, null);
        return new McpSchema.Tool("get_symbol_detail",
                "Get detailed information about a specific symbol including source code, children, and type relationships.",
                schema);
    }

    private McpSchema.Tool findImplementationsTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "type_name", Map.of("type", "string", "description", "Name of the interface or base class"),
                        "repo",      Map.of("type", "string", "description", "Optional repository name filter")
                ),
                List.of("type_name"),
                false, null, null);
        return new McpSchema.Tool("find_implementations",
                "Find all classes that implement a given interface or extend a given class.",
                schema);
    }

    private McpSchema.Tool findReferencesTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "symbol_name", Map.of("type", "string", "description", "Symbol name to search for in imports"),
                        "repo",        Map.of("type", "string", "description", "Optional repository name filter"),
                        "limit",       Map.of("type", "integer", "description", "Max results", "default", 20)
                ),
                List.of("symbol_name"),
                false, null, null);
        return new McpSchema.Tool("find_references",
                "Find files that import or reference a given symbol name.",
                schema);
    }

    private McpSchema.Tool searchCodeTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "query",    Map.of("type", "string", "description", "Full-text search query"),
                        "language", Map.of("type", "string", "description", "Optional language filter"),
                        "repo",     Map.of("type", "string", "description", "Optional repository filter"),
                        "limit",    Map.of("type", "integer", "description", "Max results", "default", 20)
                ),
                List.of("query"),
                false, null, null);
        return new McpSchema.Tool("search_code",
                "Full-text search across indexed file contents using PostgreSQL full-text search.",
                schema);
    }

    private McpSchema.Tool searchFilesTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "pattern",  Map.of("type", "string", "description", "Glob-style path pattern (use * as wildcard)"),
                        "language", Map.of("type", "string", "description", "Optional language filter"),
                        "repo",     Map.of("type", "string", "description", "Optional repository filter"),
                        "limit",    Map.of("type", "integer", "description", "Max results", "default", 50)
                ),
                List.of("pattern"),
                false, null, null);
        return new McpSchema.Tool("search_files",
                "Search for files by path pattern (glob-style * wildcard).",
                schema);
    }

    private McpSchema.Tool getRepoSummaryTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo_name", Map.of("type", "string", "description", "Repository name")
                ),
                List.of("repo_name"),
                false, null, null);
        return new McpSchema.Tool("get_repo_summary",
                "Get a high-level summary of a repository including file count, language breakdown, and top directories.",
                schema);
    }

    private McpSchema.Tool getFileSummaryTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo_name", Map.of("type", "string", "description", "Repository name"),
                        "file_path", Map.of("type", "string", "description", "Path to the file within the repo")
                ),
                List.of("repo_name", "file_path"),
                false, null, null);
        return new McpSchema.Tool("get_file_summary",
                "Get a summary of a specific file including its symbols and imports.",
                schema);
    }

    private McpSchema.Tool getDirectoryTreeTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo_name", Map.of("type", "string", "description", "Repository name"),
                        "path",      Map.of("type", "string", "description", "Directory path prefix (empty for root)"),
                        "depth",     Map.of("type", "integer", "description", "Tree depth (informational, filtering done client-side)", "default", 3)
                ),
                List.of("repo_name"),
                false, null, null);
        return new McpSchema.Tool("get_directory_tree",
                "Get a flat list of files under a directory path in a repository.",
                schema);
    }

    private McpSchema.Tool getIndexHealthTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(),
                List.of(),
                false, null, null);
        return new McpSchema.Tool("get_index_health",
                "Get the health status of the indexer including per-repo stats, pending/failed event counts, and recent failures.",
                schema);
    }

    // -----------------------------------------------------------------------
    // Tool handlers
    // -----------------------------------------------------------------------

    private McpSchema.CallToolResult handleSearchSymbols(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String query    = stringArg(args, "query");
            String kind     = stringArg(args, "kind");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            int limit       = intArg(args, "limit", 20);

            var results = queryExecutor.searchSymbols(query, kind, language, repo, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetSymbolDetail(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repo       = stringArg(args, "repo");
            String filePath   = stringArg(args, "file_path");
            String symbolName = stringArg(args, "symbol_name");
            Integer line      = args.containsKey("line") ? intArg(args, "line", 0) : null;

            var result = queryExecutor.getSymbolDetail(repo, filePath, symbolName, line);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleFindImplementations(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String typeName = stringArg(args, "type_name");
            String repo     = stringArg(args, "repo");

            var results = queryExecutor.findImplementations(typeName, repo);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleFindReferences(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String symbolName = stringArg(args, "symbol_name");
            String repo       = stringArg(args, "repo");
            int limit         = intArg(args, "limit", 20);

            var results = queryExecutor.findReferences(symbolName, repo, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleSearchCode(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String query    = stringArg(args, "query");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            int limit       = intArg(args, "limit", 20);

            var results = queryExecutor.searchCode(query, language, repo, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleSearchFiles(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String pattern  = stringArg(args, "pattern");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            int limit       = intArg(args, "limit", 50);

            var results = queryExecutor.searchFiles(pattern, language, repo, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetRepoSummary(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repoName = stringArg(args, "repo_name");
            var result = queryExecutor.getRepoSummary(repoName);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetFileSummary(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repoName = stringArg(args, "repo_name");
            String filePath = stringArg(args, "file_path");
            var result = queryExecutor.getFileSummary(repoName, filePath);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetDirectoryTree(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            String repoName = stringArg(args, "repo_name");
            String path     = stringArg(args, "path");
            int depth       = intArg(args, "depth", 3);

            var results = queryExecutor.getDirectoryTree(repoName, path, depth);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetIndexHealth(
            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            Map<String, Object> args) {
        try {
            var result = queryExecutor.getIndexHealth();
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    // -----------------------------------------------------------------------
    // Helper utilities
    // -----------------------------------------------------------------------

    private String stringArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private McpSchema.CallToolResult jsonResult(Object data) {
        try {
            String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(json)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult errorResult(Exception e) {
        log.error("Tool execution error: {}", e.getMessage(), e);
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + e.getMessage())
                .isError(true)
                .build();
    }
}

package com.indexer.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registers all 11 MCP tools with the MCP Java SDK and starts the server over stdio or Streamable HTTP.
 */
public class McpServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(McpServerBootstrap.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final QueryExecutor queryExecutor;
    private McpSyncServer stdioServer;
    private McpSyncServer httpServer;

    // Backward-compatible constructor (used in tests)
    public McpServerBootstrap(Jdbi jdbi) {
        this(new QueryExecutor(jdbi));
    }

    public McpServerBootstrap(QueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    public void startStdio() {
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        stdioServer = buildServer(transport);
        log.info("MCP server started over stdio with 11 tools registered");
    }

    public void startHttp(McpStreamableServerTransportProvider httpTransport) {
        httpServer = McpServer.sync(httpTransport)
                .serverInfo("source-code-indexer", "1.0.0")
                .toolCall(searchSymbolsTool(), this::handleSearchSymbols)
                .toolCall(getSymbolDetailTool(), this::handleGetSymbolDetail)
                .toolCall(findImplementationsTool(), this::handleFindImplementations)
                .toolCall(findReferencesTool(), this::handleFindReferences)
                .toolCall(searchCodeTool(), this::handleSearchCode)
                .toolCall(searchFilesTool(), this::handleSearchFiles)
                .toolCall(getRepoSummaryTool(), this::handleGetRepoSummary)
                .toolCall(getFileSummaryTool(), this::handleGetFileSummary)
                .toolCall(getDirectoryTreeTool(), this::handleGetDirectoryTree)
                .toolCall(getIndexHealthTool(), this::handleGetIndexHealth)
                .toolCall(checkSyncTool(), this::handleCheckSync)
                .build();
        log.info("MCP server started over Streamable HTTP with 11 tools registered");
    }

    private McpSyncServer buildServer(McpServerTransportProvider transport) {
        return McpServer.sync(transport)
                .serverInfo("source-code-indexer", "1.0.0")
                .toolCall(searchSymbolsTool(), this::handleSearchSymbols)
                .toolCall(getSymbolDetailTool(), this::handleGetSymbolDetail)
                .toolCall(findImplementationsTool(), this::handleFindImplementations)
                .toolCall(findReferencesTool(), this::handleFindReferences)
                .toolCall(searchCodeTool(), this::handleSearchCode)
                .toolCall(searchFilesTool(), this::handleSearchFiles)
                .toolCall(getRepoSummaryTool(), this::handleGetRepoSummary)
                .toolCall(getFileSummaryTool(), this::handleGetFileSummary)
                .toolCall(getDirectoryTreeTool(), this::handleGetDirectoryTree)
                .toolCall(getIndexHealthTool(), this::handleGetIndexHealth)
                .toolCall(checkSyncTool(), this::handleCheckSync)
                .build();
    }

    public void stop() {
        if (httpServer != null) httpServer.closeGracefully();
        if (stdioServer != null) stdioServer.closeGracefully();
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
                        "branch",   Map.of("type", "string", "description", "Branch name (defaults to main)"),
                        "limit",    Map.of("type", "integer", "description", "Max results to return", "default", 20)
                ),
                List.of(),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("search_symbols")
                .description("Search for symbols (classes, methods, functions) by name pattern, kind, language, or repo.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool getSymbolDetailTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo",        Map.of("type", "string", "description", "Repository name"),
                        "file_path",   Map.of("type", "string", "description", "Path to the file within the repo"),
                        "symbol_name", Map.of("type", "string", "description", "Name of the symbol"),
                        "line",        Map.of("type", "integer", "description", "Optional start line to disambiguate overloads"),
                        "branch",      Map.of("type", "string", "description", "Branch name (defaults to main)")
                ),
                List.of("repo", "file_path", "symbol_name"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("get_symbol_detail")
                .description("Get detailed information about a specific symbol including source code, children, and type relationships.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool findImplementationsTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "type_name", Map.of("type", "string", "description", "Name of the interface or base class"),
                        "repo",      Map.of("type", "string", "description", "Optional repository name filter"),
                        "branch",    Map.of("type", "string", "description", "Branch name (defaults to main)")
                ),
                List.of("type_name"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("find_implementations")
                .description("Find all classes that implement a given interface or extend a given class.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool findReferencesTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "symbol_name", Map.of("type", "string", "description", "Symbol name to search for in imports"),
                        "repo",        Map.of("type", "string", "description", "Optional repository name filter"),
                        "branch",      Map.of("type", "string", "description", "Branch name (defaults to main)"),
                        "limit",       Map.of("type", "integer", "description", "Max results", "default", 20)
                ),
                List.of("symbol_name"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("find_references")
                .description("Find files that import or reference a given symbol name.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool searchCodeTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "query",    Map.of("type", "string", "description", "Full-text search query"),
                        "language", Map.of("type", "string", "description", "Optional language filter"),
                        "repo",     Map.of("type", "string", "description", "Optional repository filter"),
                        "branch",   Map.of("type", "string", "description", "Branch name (defaults to main)"),
                        "limit",    Map.of("type", "integer", "description", "Max results", "default", 20)
                ),
                List.of("query"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("search_code")
                .description("Full-text search across indexed file contents using PostgreSQL full-text search.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool searchFilesTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "pattern",  Map.of("type", "string", "description", "Glob-style path pattern (use * as wildcard)"),
                        "language", Map.of("type", "string", "description", "Optional language filter"),
                        "repo",     Map.of("type", "string", "description", "Optional repository filter"),
                        "branch",   Map.of("type", "string", "description", "Branch name (defaults to main)"),
                        "limit",    Map.of("type", "integer", "description", "Max results", "default", 50)
                ),
                List.of("pattern"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("search_files")
                .description("Search for files by path pattern (glob-style * wildcard).")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool getRepoSummaryTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo_name", Map.of("type", "string", "description", "Repository name"),
                        "branch",    Map.of("type", "string", "description", "Branch name (defaults to main)")
                ),
                List.of("repo_name"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("get_repo_summary")
                .description("Get a high-level summary of a repository including file count, language breakdown, and top directories.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool getFileSummaryTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo_name", Map.of("type", "string", "description", "Repository name"),
                        "file_path", Map.of("type", "string", "description", "Path to the file within the repo"),
                        "branch",    Map.of("type", "string", "description", "Branch name (defaults to main)")
                ),
                List.of("repo_name", "file_path"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("get_file_summary")
                .description("Get a summary of a specific file including its symbols and imports.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool getDirectoryTreeTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(
                        "repo_name", Map.of("type", "string", "description", "Repository name"),
                        "path",      Map.of("type", "string", "description", "Directory path prefix (empty for root)"),
                        "depth",     Map.of("type", "integer", "description", "Tree depth (informational, filtering done client-side)", "default", 3),
                        "branch",    Map.of("type", "string", "description", "Branch name (defaults to main)")
                ),
                List.of("repo_name"),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("get_directory_tree")
                .description("Get a flat list of files under a directory path in a repository.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool getIndexHealthTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of(),
                List.of(),
                false, null, null);
        return McpSchema.Tool.builder()
                .name("get_index_health")
                .description("Get the health status of the indexer including per-repo stats, pending/failed event counts, and recent failures.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool checkSyncTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("repo_name",  Map.of("type", "string", "description", "Name of the repository to check"));
        props.put("local_sha",  Map.of("type", "string", "description", "Your local HEAD SHA from 'git rev-parse HEAD'"));
        props.put("branch",     Map.of("type", "string", "description", "Branch name (defaults to repo's configured branch, usually 'main')"));
        var schema = new McpSchema.JsonSchema("object", props, List.of("repo_name", "local_sha"), false, null, null);
        return McpSchema.Tool.builder()
                .name("check_sync")
                .description("Check whether a local repository is in sync with the indexed version. Pass the repo name and your local HEAD SHA (from 'git rev-parse HEAD'). Returns sync status and recommended action if out of sync.")
                .inputSchema(schema)
                .build();
    }

    // -----------------------------------------------------------------------
    // Tool handlers
    // -----------------------------------------------------------------------

    private McpSchema.CallToolResult handleSearchSymbols(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String query    = stringArg(args, "query");
            String kind     = stringArg(args, "kind");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");
            int limit       = intArg(args, "limit", 20);

            var results = queryExecutor.searchSymbols(query, kind, language, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetSymbolDetail(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String repo       = stringArg(args, "repo");
            String filePath   = stringArg(args, "file_path");
            String symbolName = stringArg(args, "symbol_name");
            Integer line      = args.containsKey("line") ? intArg(args, "line", 0) : null;
            String branch     = stringArg(args, "branch");

            var result = queryExecutor.getSymbolDetail(repo, filePath, symbolName, line, branch);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleFindImplementations(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String typeName = stringArg(args, "type_name");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");

            var results = queryExecutor.findImplementations(typeName, repo, branch);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleFindReferences(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String symbolName = stringArg(args, "symbol_name");
            String repo       = stringArg(args, "repo");
            String branch     = stringArg(args, "branch");
            int limit         = intArg(args, "limit", 20);

            var results = queryExecutor.findReferences(symbolName, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleSearchCode(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String query    = stringArg(args, "query");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");
            int limit       = intArg(args, "limit", 20);

            var results = queryExecutor.searchCode(query, language, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleSearchFiles(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String pattern  = stringArg(args, "pattern");
            String language = stringArg(args, "language");
            String repo     = stringArg(args, "repo");
            String branch   = stringArg(args, "branch");
            int limit       = intArg(args, "limit", 50);

            var results = queryExecutor.searchFiles(pattern, language, repo, branch, limit);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetRepoSummary(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String repoName = stringArg(args, "repo_name");
            String branch   = stringArg(args, "branch");
            var result = queryExecutor.getRepoSummary(repoName, branch);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetFileSummary(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String repoName = stringArg(args, "repo_name");
            String filePath = stringArg(args, "file_path");
            String branch   = stringArg(args, "branch");
            var result = queryExecutor.getFileSummary(repoName, filePath, branch);
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetDirectoryTree(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String repoName = stringArg(args, "repo_name");
            String path     = stringArg(args, "path");
            int depth       = intArg(args, "depth", 3);
            String branch   = stringArg(args, "branch");

            var results = queryExecutor.getDirectoryTree(repoName, path, depth, branch);
            return jsonResult(results);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleGetIndexHealth(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var result = queryExecutor.getIndexHealth();
            return jsonResult(result);
        } catch (Exception e) {
            return errorResult(e);
        }
    }

    private McpSchema.CallToolResult handleCheckSync(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        try {
            var args = request.arguments();
            String repoName = stringArg(args, "repo_name");
            String localSha = stringArg(args, "local_sha");
            String branch   = stringArg(args, "branch");

            var result = queryExecutor.checkSync(repoName, localSha, branch);
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
            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
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

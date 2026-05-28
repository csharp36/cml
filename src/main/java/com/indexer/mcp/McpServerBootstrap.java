package com.indexer.mcp;

import com.indexer.auth.CallerIdentity;
import io.modelcontextprotocol.common.McpTransportContext;
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
 * Registers all 17 MCP tools with the MCP Java SDK and starts the server over stdio or Streamable HTTP.
 */
public class McpServerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(McpServerBootstrap.class);

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
        log.info("MCP server started over stdio with 17 tools registered");
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
                .toolCall(queryAuditLogTool(), this::handleQueryAuditLog)
                .toolCall(verifyAuditChainTool(), this::handleVerifyAuditChain)
                .toolCall(diffBranchesTool(), this::handleDiffBranches)
                .toolCall(searchBranchesTool(), this::handleSearchBranches)
                .toolCall(getTypeHierarchyTool(), this::handleGetTypeHierarchy)
                .toolCall(getSymbolReferencesTool(), this::handleGetSymbolReferences)
                .build();
        log.info("MCP server started over Streamable HTTP with 17 tools registered");
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
                .toolCall(queryAuditLogTool(), this::handleQueryAuditLog)
                .toolCall(verifyAuditChainTool(), this::handleVerifyAuditChain)
                .toolCall(diffBranchesTool(), this::handleDiffBranches)
                .toolCall(searchBranchesTool(), this::handleSearchBranches)
                .toolCall(getTypeHierarchyTool(), this::handleGetTypeHierarchy)
                .toolCall(getSymbolReferencesTool(), this::handleGetSymbolReferences)
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

    private McpSchema.Tool queryAuditLogTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("caller_hash",   Map.of("type", "string", "description", "Filter by caller hash"));
        props.put("action",        Map.of("type", "string", "description", "Filter by tool name or admin action"));
        props.put("repo",          Map.of("type", "string", "description", "Filter by repository name"));
        props.put("result_status", Map.of("type", "string", "description", "Filter: success, error, denied"));
        props.put("since",         Map.of("type", "string", "description", "ISO 8601 timestamp lower bound"));
        props.put("until",         Map.of("type", "string", "description", "ISO 8601 timestamp upper bound"));
        props.put("limit",         Map.of("type", "integer", "description", "Max results (default 50, max 500)", "default", 50));
        var schema = new McpSchema.JsonSchema("object", props, List.of(), false, null, null);
        return McpSchema.Tool.builder()
                .name("query_audit_log")
                .description("Query the audit log. Requires audit reader access (stdio or API key with auditReader: true).")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool verifyAuditChainTool() {
        var schema = new McpSchema.JsonSchema("object",
                Map.of("count", Map.of("type", "integer", "description", "Number of recent events to verify (default 100, max 1000)", "default", 100)),
                List.of(), false, null, null);
        return McpSchema.Tool.builder()
                .name("verify_audit_chain")
                .description("Verify audit log hash chain integrity. Checks the last N events for tamper evidence.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool diffBranchesTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("repo",     Map.of("type", "string", "description", "Repository name"));
        props.put("branch_a", Map.of("type", "string", "description", "First branch (treated as 'new')"));
        props.put("branch_b", Map.of("type", "string", "description", "Second branch (treated as 'old')"));
        props.put("detail",   Map.of("type", "string", "description", "Granularity: 'files' or 'symbols' (default: symbols)"));
        props.put("limit",    Map.of("type", "integer", "description", "Max results (default 100)", "default", 100));
        var schema = new McpSchema.JsonSchema("object", props,
                List.of("repo", "branch_a", "branch_b"), false, null, null);
        return McpSchema.Tool.builder()
                .name("diff_branches")
                .description("Compare two branches and show what's different. Returns added, removed, and modified files or symbols.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool searchBranchesTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("repo",           Map.of("type", "string", "description", "Repository name"));
        props.put("query",          Map.of("type", "string", "description", "Regex pattern for symbol name"));
        props.put("kind",           Map.of("type", "string", "description", "Optional symbol kind filter (class, method, function, ...)"));
        props.put("branch_pattern", Map.of("type", "string", "description", "Regex to filter branches (default: all indexed branches)"));
        props.put("max_branches",   Map.of("type", "integer", "description", "Max branches to search (default 50)", "default", 50));
        props.put("limit",          Map.of("type", "integer", "description", "Max results per branch (default 20)", "default", 20));
        var schema = new McpSchema.JsonSchema("object", props,
                List.of("repo", "query"), false, null, null);
        return McpSchema.Tool.builder()
                .name("search_branches")
                .description("Search for a symbol across multiple indexed branches. Returns which branches have changes involving the matched symbol.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool getTypeHierarchyTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("repo",        Map.of("type", "string", "description", "Repository name"));
        props.put("symbol_name", Map.of("type", "string", "description", "Display name of the type (e.g., PaymentProcessor)"));
        props.put("file_path",   Map.of("type", "string", "description", "File path to disambiguate when multiple symbols share a name"));
        props.put("kind",        Map.of("type", "string", "description", "Filter by symbol kind (Class, Interface, etc.)"));
        props.put("direction",   Map.of("type", "string", "description", "Traversal direction: up (supertypes), down (subtypes), or both (default: both)"));
        props.put("depth",       Map.of("type", "integer", "description", "Max traversal depth (default: 3)", "default", 3));
        var schema = new McpSchema.JsonSchema("object", props,
                List.of("repo", "symbol_name"), false, null, null);
        return McpSchema.Tool.builder()
                .name("get_type_hierarchy")
                .description("Get the type hierarchy for a symbol using SCIP semantic data. Shows supertypes (interfaces/base classes) and subtypes (implementations/subclasses). Requires SCIP data to be uploaded for the repo.")
                .inputSchema(schema)
                .build();
    }

    private McpSchema.Tool getSymbolReferencesTool() {
        var props = new LinkedHashMap<String, Object>();
        props.put("repo",              Map.of("type", "string", "description", "Repository name"));
        props.put("symbol_name",       Map.of("type", "string", "description", "Display name of the symbol to look up"));
        props.put("file_path",         Map.of("type", "string", "description", "File path to disambiguate when multiple symbols share a name"));
        props.put("relationship_kind", Map.of("type", "string", "description", "Filter by relationship: implements, extends, references, or all (default: all)"));
        props.put("direction",         Map.of("type", "string", "description", "inbound (who references this), outbound (what this references), or both (default: inbound)"));
        props.put("limit",             Map.of("type", "integer", "description", "Max results (default: 50)", "default", 50));
        var schema = new McpSchema.JsonSchema("object", props,
                List.of("repo", "symbol_name"), false, null, null);
        return McpSchema.Tool.builder()
                .name("get_symbol_references")
                .description("Find symbols related to a given symbol through SCIP semantic relationships (implements, extends, references). Returns a flat list of direct edges. Requires SCIP data to be uploaded for the repo.")
                .inputSchema(schema)
                .build();
    }

    // -----------------------------------------------------------------------
    // Identity extraction
    // -----------------------------------------------------------------------

    private CallerIdentity extractIdentity(McpSyncServerExchange exchange) {
        McpTransportContext ctx = exchange.transportContext();
        if (ctx == null) {
            return CallerIdentity.fromStdio();
        }
        Object identity = ctx.get(CallerIdentity.CONTEXT_KEY);
        if (identity instanceof CallerIdentity ci) {
            return ci;
        }
        return CallerIdentity.fromStdio();
    }

    // -----------------------------------------------------------------------
    // Tool handlers
    // -----------------------------------------------------------------------

    private McpSchema.CallToolResult handleSearchSymbols(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "search_symbols", args,
                () -> queryExecutor.searchSymbols(
                        stringArg(args, "query"), stringArg(args, "kind"),
                        stringArg(args, "language"), repo,
                        stringArg(args, "branch"), intArg(args, "limit", 20)));
    }

    private McpSchema.CallToolResult handleGetSymbolDetail(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "get_symbol_detail", args,
                () -> queryExecutor.getSymbolDetail(
                        repo, stringArg(args, "file_path"),
                        stringArg(args, "symbol_name"),
                        args.containsKey("line") ? intArg(args, "line", 0) : null,
                        stringArg(args, "branch")));
    }

    private McpSchema.CallToolResult handleFindImplementations(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "find_implementations", args,
                () -> queryExecutor.findImplementations(
                        stringArg(args, "type_name"), repo,
                        stringArg(args, "branch")));
    }

    private McpSchema.CallToolResult handleFindReferences(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "find_references", args,
                () -> queryExecutor.findReferences(
                        stringArg(args, "symbol_name"), repo,
                        stringArg(args, "branch"), intArg(args, "limit", 20)));
    }

    private McpSchema.CallToolResult handleSearchCode(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "search_code", args,
                () -> queryExecutor.searchCode(
                        stringArg(args, "query"), stringArg(args, "language"),
                        repo, stringArg(args, "branch"),
                        intArg(args, "limit", 20)));
    }

    private McpSchema.CallToolResult handleSearchFiles(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "search_files", args,
                () -> queryExecutor.searchFiles(
                        stringArg(args, "pattern"), stringArg(args, "language"),
                        repo, stringArg(args, "branch"),
                        intArg(args, "limit", 50)));
    }

    private McpSchema.CallToolResult handleGetRepoSummary(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo_name");
        return queryExecutor.executeQuery(caller, repo, "get_repo_summary", args,
                () -> queryExecutor.getRepoSummary(repo, stringArg(args, "branch")));
    }

    private McpSchema.CallToolResult handleGetFileSummary(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo_name");
        return queryExecutor.executeQuery(caller, repo, "get_file_summary", args,
                () -> queryExecutor.getFileSummary(
                        repo, stringArg(args, "file_path"),
                        stringArg(args, "branch")));
    }

    private McpSchema.CallToolResult handleGetDirectoryTree(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo_name");
        return queryExecutor.executeQuery(caller, repo, "get_directory_tree", args,
                () -> queryExecutor.getDirectoryTree(
                        repo, stringArg(args, "path"),
                        intArg(args, "depth", 3), stringArg(args, "branch")));
    }

    private McpSchema.CallToolResult handleGetIndexHealth(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var caller = extractIdentity(exchange);
        return queryExecutor.executeQuery(caller, null, "get_index_health", Map.of(),
                () -> queryExecutor.getIndexHealth());
    }

    private McpSchema.CallToolResult handleCheckSync(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo_name");
        return queryExecutor.executeQuery(caller, repo, "check_sync", args,
                () -> queryExecutor.checkSync(
                        repo, stringArg(args, "local_sha"),
                        stringArg(args, "branch")));
    }

    private McpSchema.CallToolResult handleQueryAuditLog(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);

        if (!caller.auditReader()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Access denied: audit reader permission required")
                    .isError(true)
                    .build();
        }

        java.time.Instant since = parseInstant(stringArg(args, "since"));
        java.time.Instant until = parseInstant(stringArg(args, "until"));

        return queryExecutor.executeQuery(caller, null, "query_audit_log", args,
                () -> queryExecutor.queryAuditLog(
                        stringArg(args, "caller_hash"), stringArg(args, "action"),
                        stringArg(args, "repo"), stringArg(args, "result_status"),
                        since, until, intArg(args, "limit", 50)));
    }

    private McpSchema.CallToolResult handleVerifyAuditChain(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);

        if (!caller.auditReader()) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Access denied: audit reader permission required")
                    .isError(true)
                    .build();
        }

        return queryExecutor.executeQuery(caller, null, "verify_audit_chain", args,
                () -> queryExecutor.verifyAuditChain(intArg(args, "count", 100)));
    }

    private McpSchema.CallToolResult handleDiffBranches(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "diff_branches", args,
                () -> queryExecutor.diffBranches(
                        repo, stringArg(args, "branch_a"), stringArg(args, "branch_b"),
                        stringArg(args, "detail"), intArg(args, "limit", 100)));
    }

    private McpSchema.CallToolResult handleSearchBranches(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "search_branches", args,
                () -> queryExecutor.searchBranches(
                        repo, stringArg(args, "query"), stringArg(args, "kind"),
                        stringArg(args, "branch_pattern"),
                        intArg(args, "max_branches", 50),
                        intArg(args, "limit", 20)));
    }

    private McpSchema.CallToolResult handleGetTypeHierarchy(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "get_type_hierarchy", args,
                () -> queryExecutor.getTypeHierarchy(
                        repo, stringArg(args, "symbol_name"),
                        stringArg(args, "file_path"), stringArg(args, "kind"),
                        stringArg(args, "direction"), intArg(args, "depth", 3)));
    }

    private McpSchema.CallToolResult handleGetSymbolReferences(
            McpSyncServerExchange exchange,
            McpSchema.CallToolRequest request) {
        var args = request.arguments();
        var caller = extractIdentity(exchange);
        String repo = stringArg(args, "repo");
        return queryExecutor.executeQuery(caller, repo, "get_symbol_references", args,
                () -> queryExecutor.getSymbolReferences(
                        repo, stringArg(args, "symbol_name"),
                        stringArg(args, "file_path"), stringArg(args, "relationship_kind"),
                        stringArg(args, "direction"), intArg(args, "limit", 50)));
    }

    private java.time.Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.Instant.parse(s);
        } catch (Exception e) {
            return null;
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

}

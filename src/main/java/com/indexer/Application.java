package com.indexer;

import com.indexer.admin.AdminApi;
import com.indexer.admin.AdminService;
import com.indexer.auth.AuthProviderRegistry;
import com.indexer.config.ConfigLoader;
import com.indexer.config.IndexerConfig;
import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.indexing.BranchCleanupTask;
import com.indexer.indexing.FileIndexer;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.indexing.SymbolExtractor;
import com.indexer.indexing.treesitter.TSParserPool;
import com.indexer.indexing.treesitter.TreeSitterEngine;
import com.indexer.auth.*;
import java.time.Duration;
import java.util.Set;
import java.util.HashSet;
import com.indexer.mcp.McpServerBootstrap;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import com.indexer.queue.EventQueuePoller;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RepositoryManager;
import com.indexer.server.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private DatabaseManager dbManager;
    private HttpServer httpServer;
    private McpServerBootstrap mcpServer;
    private EventQueuePoller poller;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private AdminService adminService;
    private TSParserPool parserPool;

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : System.getProperty("user.home") + "/.source-code-indexer/config.yaml";
        new Application().start(Path.of(configPath));
    }

    public void start(Path configPath) {
        log.info("Source Code Indexer MCP Server starting...");
        log.info("Loading config from: {}", configPath);

        try {
            // 1. Load config
            IndexerConfig config = new ConfigLoader().load(configPath);

            // 2. Initialize database
            dbManager = new DatabaseManager(
                    config.database().jdbcUrl(),
                    config.database().username(),
                    config.database().password()
            );
            dbManager.initialize();

            var jdbi = dbManager.getJdbi();
            var repositoryDao = new RepositoryDao(jdbi);
            var fileDao = new FileDao(jdbi);
            var symbolDao = new SymbolDao(jdbi);
            var eventDao = new EventDao(jdbi);

            // 3. Set up components
            var authRegistry = new AuthProviderRegistry();
            var gitOps = new GitOperations();
            var languageRegistry = new LanguageRegistry(config.languages().customExtensions());
            parserPool = new TSParserPool(config.server().indexWorkers());
            var treeSitterEngine = new TreeSitterEngine(parserPool);
            var symbolExtractor = new SymbolExtractor(treeSitterEngine);
            var fileIndexer = new FileIndexer(fileDao, symbolDao, jdbi, languageRegistry,
                    symbolExtractor, config.server().maxFileSizeBytes());
            var branchIndexDao = new BranchIndexDao(jdbi);
            var indexingPipeline = new IndexingPipeline(repositoryDao, fileIndexer, gitOps, branchIndexDao);
            var repoManager = new RepositoryManager(config, authRegistry, repositoryDao, gitOps);

            // 4. Clone/fetch repos and run initial index
            var repos = repoManager.initializeRepositories();
            log.info("Initialized {} repositories", repos.size());

            for (var repo : repos) {
                if (repo.lastIndexedSha() == null) {
                    log.info("Running full index for {}", repo.name());
                    indexingPipeline.fullIndex(repo.id(), repo.branch(), Path.of(repo.clonePath()));
                } else {
                    String currentSha = gitOps.getCurrentSha(Path.of(repo.clonePath()));
                    if (!currentSha.equals(repo.lastIndexedSha())) {
                        log.info("Catching up index for {}: {} -> {}", repo.name(), repo.lastIndexedSha(), currentSha);
                        indexingPipeline.incrementalIndex(repo.id(), repo.branch(), Path.of(repo.clonePath()),
                                repo.lastIndexedSha(), currentSha);
                    }
                }
            }

            // 5. Report any failed events from previous runs
            int failedCount = eventDao.countByStatus("failed");
            if (failedCount > 0) {
                log.warn("{} events failed in previous runs. Use get_index_health for details.", failedCount);
            }

            // 5b. Set up API key authenticator
            var apiKeyConfigs = config.mcpAuth().apiKeys().stream()
                    .map(e -> new ApiKeyAuthenticator.ApiKeyConfig(e.key(), e.id(), e.name(), e.auditReader(), e.scipUpload()))
                    .toList();
            var authenticator = new ApiKeyAuthenticator(apiKeyConfigs);

            // 5c. Set up JWT validator (if OAuth configured)
            JwtValidator jwtValidator = null;
            if (config.mcpAuth().oauth() != null && config.mcpAuth().oauth().jwksUrl() != null) {
                var oauth = config.mcpAuth().oauth();
                jwtValidator = new JwtValidator(oauth.jwksUrl(), oauth.issuer(), oauth.audience(), oauth.groupsClaim());
            }

            // 5d. Set up permission resolver and cache
            PermissionCache permissionCache = null;
            if (config.mcpAuth().permissions() != null && !"none".equals(config.mcpAuth().permissions().type())) {
                var permConfig = config.mcpAuth().permissions();
                PermissionResolver resolver;
                if ("github".equals(permConfig.type())) {
                    if (permConfig.serviceAccountToken() == null || permConfig.serviceAccountToken().isBlank()) {
                        throw new com.indexer.config.ConfigValidationException(
                                "permissions.serviceAccountToken is required when type=github");
                    }
                    if (permConfig.org() == null || permConfig.org().isBlank()) {
                        throw new com.indexer.config.ConfigValidationException(
                                "permissions.org is required when type=github");
                    }
                    resolver = new GitHubPermissionResolver(permConfig.org(), permConfig.serviceAccountToken(), repositoryDao);
                } else {
                    resolver = new NoOpPermissionResolver(repositoryDao);
                }
                Set<String> openRepos = new HashSet<>(permConfig.openRepos());
                permissionCache = new PermissionCache(resolver, openRepos, Duration.ofMinutes(30));
            }

            // 5e. Set up audit sink
            var auditSink = new com.indexer.audit.PostgresAuditSink(jdbi);

            // 5f. Set up SCIP upload endpoint
            var scipService = new com.indexer.scip.ScipService(repositoryDao, fileDao, jdbi);
            var scipApi = new com.indexer.scip.ScipApi(authenticator, scipService, auditSink);

            // 6. Build Streamable HTTP transport
            final JwtValidator finalJwtValidator = jwtValidator;

            var streamableTransport = HttpServletStreamableServerTransportProvider.builder()
                    .mcpEndpoint("/mcp")
                    .contextExtractor(request -> {
                        CallerIdentity identity;
                        if (!authenticator.isEnabled() && finalJwtValidator == null) {
                            identity = CallerIdentity.anonymous("streamable-http");
                        } else {
                            String authHeader = request.getHeader("Authorization");
                            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                                throw new RuntimeException("Missing or invalid Authorization header");
                            }
                            String token = authHeader.substring("Bearer ".length());
                            String sourceIp = request.getRemoteAddr();

                            if (finalJwtValidator != null && looksLikeJwt(token)) {
                                try {
                                    identity = finalJwtValidator.validate(token, sourceIp);
                                } catch (JwtValidationException e) {
                                    log.warn("JWT validation failed from {}: {}", sourceIp, e.getMessage());
                                    throw new RuntimeException("Unauthorized");
                                }
                            } else if (authenticator.isEnabled()) {
                                identity = authenticator.authenticate(token, sourceIp)
                                        .orElseThrow(() -> new RuntimeException("Invalid API key"));
                            } else {
                                throw new RuntimeException("No authentication method available for token");
                            }
                        }
                        return McpTransportContext.create(Map.of(CallerIdentity.CONTEXT_KEY, identity));
                    })
                    .build();

            httpServer = new HttpServer(eventDao, repositoryDao, streamableTransport);

            // Create admin API
            var queryExecutor = new com.indexer.mcp.QueryExecutor(jdbi, branchIndexDao, indexingPipeline, repositoryDao, gitOps, permissionCache, auditSink);
            adminService = new AdminService(
                    repoManager, repositoryDao, fileDao, symbolDao,
                    eventDao, indexingPipeline, gitOps, queryExecutor, jdbi);
            String adminToken = config.admin() != null ? config.admin().token() : null;
            var adminApi = new AdminApi(adminService, adminToken, auditSink);
            httpServer.addRoutes(adminApi::registerRoutes);
            httpServer.addRoutes(scipApi::registerRoutes);

            // 7. Initialize MCP servers before starting HTTP (transport must be ready before accepting connections)
            mcpServer = new McpServerBootstrap(queryExecutor);
            mcpServer.startStdio();
            mcpServer.startHttp(streamableTransport);

            httpServer.start(config.server().httpPort());

            // 8. Start event queue poller
            executor = Executors.newFixedThreadPool(config.server().indexWorkers());
            poller = new EventQueuePoller(eventDao, event -> {
                var repo = repositoryDao.findByName(event.repoName()).orElse(null);
                if (repo == null) {
                    throw new RuntimeException("Unknown repo: " + event.repoName());
                }
                try {
                    String branch = event.branch() != null ? event.branch() : "main";
                    if ("main".equals(branch) || branch.equals(repo.branch())) {
                        // Main branch: fetch, fast-forward, incremental index
                        var repoDir = Path.of(repo.clonePath());
                        gitOps.fetch(repoDir, null);
                        try {
                            gitOps.fastForward(repoDir, repo.branch());
                        } catch (IOException e) {
                            log.warn("Fast-forward failed for {} (likely force-push), resetting to origin/{}: {}",
                                    repo.name(), repo.branch(), e.getMessage());
                            gitOps.resetToRemote(repoDir, repo.branch());
                        }
                        indexingPipeline.incrementalIndex(repo.id(), branch, repoDir,
                                event.previousSha(), event.currentSha());
                    } else {
                        // Feature branch: fetch, then delta-index from main
                        gitOps.fetch(Path.of(repo.clonePath()), null);
                        indexingPipeline.branchIndex(repo.id(), branch, Path.of(repo.clonePath()), event.currentSha());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Indexing failed: " + e.getMessage(), e);
                }
            }, 1000);
            executor.submit(poller);

            // 8b. Schedule branch cleanup task
            scheduler = Executors.newSingleThreadScheduledExecutor();
            var cleanupTask = new BranchCleanupTask(branchIndexDao, fileDao, config.branches().ttlDays());
            scheduler.scheduleAtFixedRate(cleanupTask,
                    config.branches().cleanupIntervalHours(),
                    config.branches().cleanupIntervalHours(),
                    TimeUnit.HOURS);
            log.info("Branch cleanup task scheduled every {}h (TTL={}d)",
                    config.branches().cleanupIntervalHours(), config.branches().ttlDays());

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
            log.info("Source Code Indexer MCP Server ready");

        } catch (Exception e) {
            log.error("Failed to start: {}", e.getMessage(), e);
            shutdown();
            System.exit(1);
        }
    }

    private static boolean looksLikeJwt(String token) {
        int first = token.indexOf('.');
        if (first < 0) return false;
        int second = token.indexOf('.', first + 1);
        if (second < 0) return false;
        return token.indexOf('.', second + 1) < 0; // exactly two dots
    }

    public void shutdown() {
        log.info("Shutting down...");
        if (poller != null) poller.stop();
        if (executor != null) executor.shutdownNow();
        if (scheduler != null) scheduler.shutdownNow();
        if (adminService != null) adminService.shutdown();
        if (mcpServer != null) mcpServer.stop();
        if (httpServer != null) httpServer.stop();
        if (parserPool != null) parserPool.close();
        if (dbManager != null) dbManager.close();
        log.info("Shutdown complete");
    }
}

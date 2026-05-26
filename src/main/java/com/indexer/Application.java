package com.indexer;

import com.indexer.admin.AdminApi;
import com.indexer.admin.AdminService;
import com.indexer.auth.AuthProviderRegistry;
import com.indexer.config.ConfigLoader;
import com.indexer.config.IndexerConfig;
import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.indexing.FileIndexer;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.indexing.SymbolExtractor;
import com.indexer.indexing.treesitter.TSParserPool;
import com.indexer.indexing.treesitter.TreeSitterEngine;
import com.indexer.mcp.McpServerBootstrap;
import com.indexer.mcp.transport.JavalinSseServerTransportProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.queue.EventQueuePoller;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RepositoryManager;
import com.indexer.server.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private DatabaseManager dbManager;
    private HttpServer httpServer;
    private McpServerBootstrap mcpServer;
    private EventQueuePoller poller;
    private ExecutorService executor;
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
            var indexingPipeline = new IndexingPipeline(repositoryDao, fileIndexer, gitOps);
            var repoManager = new RepositoryManager(config, authRegistry, repositoryDao, gitOps);

            // 4. Clone/fetch repos and run initial index
            var repos = repoManager.initializeRepositories();
            log.info("Initialized {} repositories", repos.size());

            for (var repo : repos) {
                if (repo.lastIndexedSha() == null) {
                    log.info("Running full index for {}", repo.name());
                    indexingPipeline.fullIndex(repo.id(), Path.of(repo.clonePath()));
                } else {
                    String currentSha = gitOps.getCurrentSha(Path.of(repo.clonePath()));
                    if (!currentSha.equals(repo.lastIndexedSha())) {
                        log.info("Catching up index for {}: {} -> {}", repo.name(), repo.lastIndexedSha(), currentSha);
                        indexingPipeline.incrementalIndex(repo.id(), Path.of(repo.clonePath()),
                                repo.lastIndexedSha(), currentSha);
                    }
                }
            }

            // 5. Report any failed events from previous runs
            int failedCount = eventDao.countByStatus("failed");
            if (failedCount > 0) {
                log.warn("{} events failed in previous runs. Use get_index_health for details.", failedCount);
            }

            // 6. Create HTTP server with SSE transport
            httpServer = new HttpServer(eventDao);
            var sseTransport = new JavalinSseServerTransportProvider(new ObjectMapper());
            sseTransport.registerRoutes(httpServer.getApp());

            // Create admin API
            var queryExecutor = new com.indexer.mcp.QueryExecutor(jdbi);
            adminService = new AdminService(
                    repoManager, repositoryDao, fileDao, symbolDao,
                    eventDao, indexingPipeline, gitOps, queryExecutor, jdbi);
            String adminToken = config.admin() != null ? config.admin().token() : null;
            var adminApi = new AdminApi(adminService, adminToken);
            adminApi.registerRoutes(httpServer.getApp());

            httpServer.start(config.server().httpPort());

            // 7. Start event queue poller
            executor = Executors.newFixedThreadPool(config.server().indexWorkers());
            poller = new EventQueuePoller(eventDao, event -> {
                var repo = repositoryDao.findByName(event.repoName()).orElse(null);
                if (repo == null) {
                    throw new RuntimeException("Unknown repo: " + event.repoName());
                }
                try {
                    indexingPipeline.incrementalIndex(repo.id(), Path.of(repo.clonePath()),
                            event.previousSha(), event.currentSha());
                } catch (Exception e) {
                    throw new RuntimeException("Indexing failed: " + e.getMessage(), e);
                }
            }, 1000);
            executor.submit(poller);

            // 8. Start MCP servers (both transports)
            mcpServer = new McpServerBootstrap(jdbi);
            mcpServer.startStdio();
            mcpServer.startSse(sseTransport);

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
            log.info("Source Code Indexer MCP Server ready");

        } catch (Exception e) {
            log.error("Failed to start: {}", e.getMessage(), e);
            shutdown();
            System.exit(1);
        }
    }

    public void shutdown() {
        log.info("Shutting down...");
        if (poller != null) poller.stop();
        if (executor != null) executor.shutdownNow();
        if (adminService != null) adminService.shutdown();
        if (mcpServer != null) mcpServer.stop();
        if (httpServer != null) httpServer.stop();
        if (parserPool != null) parserPool.close();
        if (dbManager != null) dbManager.close();
        log.info("Shutdown complete");
    }
}

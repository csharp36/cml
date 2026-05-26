package com.indexer.admin;

import com.indexer.config.IndexerConfig;
import com.indexer.db.*;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RepositoryManager;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final RepositoryManager repoManager;
    private final RepositoryDao repositoryDao;
    private final FileDao fileDao;
    private final SymbolDao symbolDao;
    private final EventDao eventDao;
    private final IndexingPipeline indexingPipeline;
    private final GitOperations gitOps;
    private final QueryExecutor queryExecutor;
    private final Jdbi jdbi;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, OperationStatus> operations = new ConcurrentHashMap<>();

    public record OperationStatus(String status, String message) {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String msg) { super(msg); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String msg) { super(msg); }
    }

    public AdminService(
            RepositoryManager repoManager,
            RepositoryDao repositoryDao,
            FileDao fileDao,
            SymbolDao symbolDao,
            EventDao eventDao,
            IndexingPipeline indexingPipeline,
            GitOperations gitOps,
            QueryExecutor queryExecutor,
            Jdbi jdbi) {
        this.repoManager = repoManager;
        this.repositoryDao = repositoryDao;
        this.fileDao = fileDao;
        this.symbolDao = symbolDao;
        this.eventDao = eventDao;
        this.indexingPipeline = indexingPipeline;
        this.gitOps = gitOps;
        this.queryExecutor = queryExecutor;
        this.jdbi = jdbi;
    }

    public Map<String, Object> getHealth() {
        return queryExecutor.getIndexHealth();
    }

    public List<Map<String, Object>> listRepositories() {
        var repos = repositoryDao.findAll();
        var result = new ArrayList<Map<String, Object>>();
        for (var repo : repos) {
            var map = new LinkedHashMap<String, Object>();
            map.put("name", repo.name());
            map.put("url", repo.url());
            map.put("branch", repo.branch());
            map.put("fileCount", fileDao.countByRepo(repo.id()));
            map.put("lastIndexedSha", repo.lastIndexedSha());
            map.put("lastIndexedAt", repo.lastIndexedAt());
            map.put("status", getRepoStatus(repo));
            result.add(map);
        }
        return result;
    }

    public Map<String, Object> addRepository(String url, String branch, IndexerConfig.AuthConfig authConfig) {
        String repoName = repoManager.extractRepoName(url);
        if (repositoryDao.findByName(repoName).isPresent()) {
            throw new ConflictException("Repository already exists: " + repoName);
        }

        operations.put(repoName, new OperationStatus("cloning", null));

        backgroundExecutor.submit(() -> {
            try {
                Path baseDir = Path.of(repoManager.getCloneBaseDir());
                var repo = repoManager.addRepository(url, branch, authConfig, baseDir);
                operations.put(repoName, new OperationStatus("indexing", null));
                indexingPipeline.fullIndex(repo.id(), Path.of(repo.clonePath()));
                operations.put(repoName, new OperationStatus("ready", null));
                log.info("Repository {} added and indexed successfully", repoName);
            } catch (Exception e) {
                log.error("Failed to add repository {}: {}", repoName, e.getMessage(), e);
                operations.put(repoName, new OperationStatus("error", e.getMessage()));
            }
        });

        return Map.of("name", repoName, "status", "cloning");
    }

    public void deleteRepository(String name) {
        var repo = repositoryDao.findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));

        // Cascade delete in dependency order
        // Note: type_relationships FK -> symbols and file_contents FK -> files
        // may have ON DELETE CASCADE. If not, add explicit deletes here.
        var files = fileDao.findByRepo(repo.id());
        for (var file : files) {
            symbolDao.deleteSymbolsByFileId(file.id());
            symbolDao.deleteImportsByFileId(file.id());
        }
        fileDao.deleteByRepoId(repo.id());
        eventDao.deleteByRepoName(name);
        repositoryDao.delete(name);

        // Delete clone from disk
        try {
            Path clonePath = Path.of(repo.clonePath());
            if (Files.exists(clonePath)) {
                Files.walkFileTree(clonePath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Failed to delete clone directory for {}: {}", name, e.getMessage());
        }

        operations.remove(name);
        log.info("Repository {} deleted", name);
    }

    public Map<String, Object> triggerReindex(String name) {
        var repo = repositoryDao.findByName(name)
                .orElseThrow(() -> new NotFoundException("Repository not found: " + name));

        operations.put(name, new OperationStatus("indexing", null));

        backgroundExecutor.submit(() -> {
            try {
                indexingPipeline.fullIndex(repo.id(), Path.of(repo.clonePath()));
                operations.put(name, new OperationStatus("ready", null));
                log.info("Reindex complete for {}", name);
            } catch (Exception e) {
                log.error("Reindex failed for {}: {}", name, e.getMessage(), e);
                operations.put(name, new OperationStatus("error", e.getMessage()));
            }
        });

        return Map.of("name", name, "status", "indexing");
    }

    public List<com.indexer.model.IndexingEvent> listEvents(String repo, String status, Instant since, int limit) {
        return eventDao.findFiltered(repo, status, since, limit);
    }

    public Map<String, Object> retryEvent(long eventId) {
        var event = eventDao.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found: " + eventId));

        if (!"failed".equals(event.status())) {
            throw new BadRequestException("Can only retry failed events. Current status: " + event.status());
        }

        eventDao.resetToPending(eventId);
        return Map.of("id", eventId, "status", "pending");
    }

    private String getRepoStatus(Repository repo) {
        var op = operations.get(repo.name());
        if (op != null) return op.status();
        return repo.lastIndexedSha() != null ? "ready" : "pending";
    }

    public void shutdown() {
        backgroundExecutor.shutdownNow();
    }
}

package com.indexer.repository;

import com.indexer.auth.AuthProviderRegistry;
import com.indexer.auth.GitCredentials;
import com.indexer.config.IndexerConfig;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(RepositoryManager.class);

    private final IndexerConfig config;
    private final AuthProviderRegistry authRegistry;
    private final RepositoryDao repositoryDao;
    private final GitOperations gitOperations;
    private final HookInstaller hookInstaller;

    public RepositoryManager(
            IndexerConfig config,
            AuthProviderRegistry authRegistry,
            RepositoryDao repositoryDao,
            GitOperations gitOperations
    ) {
        this.config = config;
        this.authRegistry = authRegistry;
        this.repositoryDao = repositoryDao;
        this.gitOperations = gitOperations;
        String webhookUrl = "http://localhost:" + config.server().httpPort();
        this.hookInstaller = new HookInstaller(webhookUrl);
    }

    public List<Repository> initializeRepositories() {
        List<Repository> initialized = new ArrayList<>();
        Path baseDir = Path.of(config.server().cloneBaseDir());

        for (IndexerConfig.RepositoryConfig repoConfig : config.repositories()) {
            try {
                Repository repo = initializeRepository(repoConfig, baseDir);
                initialized.add(repo);
            } catch (Exception e) {
                log.error("Failed to initialize repository {}: {}", repoConfig.url(), e.getMessage(), e);
            }
        }

        return initialized;
    }

    private Repository initializeRepository(IndexerConfig.RepositoryConfig repoConfig, Path baseDir) throws IOException {
        String repoName = extractRepoName(repoConfig.url());
        Path repoDir = baseDir.resolve(repoName);

        GitCredentials creds = authRegistry.resolve(repoConfig.auth());

        if (Files.exists(repoDir.resolve(".git"))) {
            log.info("Repository {} already exists, fetching updates", repoName);
            gitOperations.fetch(repoDir, creds);
            gitOperations.fastForward(repoDir, repoConfig.branch());
        } else {
            log.info("Cloning repository {} into {}", repoConfig.url(), repoDir);
            Files.createDirectories(repoDir);
            gitOperations.clone(repoConfig.url(), repoConfig.branch(), repoDir, creds);
        }

        try {
            hookInstaller.installHooks(repoDir);
        } catch (Exception e) {
            log.warn("Failed to install hooks for repository {}: {}", repoName, e.getMessage());
        }

        String currentSha;
        try {
            currentSha = gitOperations.getCurrentSha(repoDir);
        } catch (IOException e) {
            log.warn("Could not determine current SHA for {}: {}", repoName, e.getMessage());
            currentSha = null;
        }

        return upsertRepository(repoName, repoConfig, repoDir, creds, currentSha);
    }

    private Repository upsertRepository(
            String repoName,
            IndexerConfig.RepositoryConfig repoConfig,
            Path repoDir,
            GitCredentials creds,
            String currentSha
    ) {
        Optional<Repository> existing = repositoryDao.findByName(repoName);
        if (existing.isPresent()) {
            return existing.get();
        }

        Repository newRepo = new Repository(
                0,
                repoName,
                repoConfig.url(),
                repoConfig.branch(),
                repoDir.toAbsolutePath().toString(),
                creds.type().name(),
                currentSha,
                null
        );
        int id = repositoryDao.insert(newRepo);
        return new Repository(
                id,
                newRepo.name(),
                newRepo.url(),
                newRepo.branch(),
                newRepo.clonePath(),
                newRepo.authType(),
                newRepo.lastIndexedSha(),
                newRepo.lastIndexedAt()
        );
    }

    public String extractRepoName(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int lastSlash = trimmed.lastIndexOf('/');
        String name = (lastSlash >= 0) ? trimmed.substring(lastSlash + 1) : trimmed;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isEmpty() ? "unknown" : name;
    }

    public Repository addRepository(String url, String branch, IndexerConfig.AuthConfig authConfig, Path baseDir) throws IOException {
        var repoConfig = new IndexerConfig.RepositoryConfig(url, branch, authConfig);
        return initializeRepository(repoConfig, baseDir);
    }

    public String getCloneBaseDir() {
        return config.server().cloneBaseDir();
    }
}

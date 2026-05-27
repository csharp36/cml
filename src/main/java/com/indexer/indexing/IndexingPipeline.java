package com.indexer.indexing;

import com.indexer.db.RepositoryDao;
import com.indexer.repository.GitOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public class IndexingPipeline {

    private static final Logger log = LoggerFactory.getLogger(IndexingPipeline.class);

    private final RepositoryDao repositoryDao;
    private final FileIndexer fileIndexer;
    private final GitOperations gitOps;

    public IndexingPipeline(RepositoryDao repositoryDao, FileIndexer fileIndexer, GitOperations gitOps) {
        this.repositoryDao = repositoryDao;
        this.fileIndexer = fileIndexer;
        this.gitOps = gitOps;
    }

    public void fullIndex(int repoId, Path repoDir) throws IOException {
        fullIndex(repoId, "main", repoDir);
    }

    public void fullIndex(int repoId, String branch, Path repoDir) throws IOException {
        String currentSha = gitOps.getCurrentSha(repoDir);
        List<String> files = gitOps.listAllFiles(repoDir);

        log.info("Full indexing repo {} branch {} at SHA {} — {} files", repoId, branch, currentSha, files.size());

        for (String relativePath : files) {
            try {
                fileIndexer.indexFile(repoId, branch, repoDir, relativePath, currentSha);
            } catch (Exception e) {
                log.error("Failed to index file {} in repo {}: {}", relativePath, repoId, e.getMessage(), e);
            }
        }

        repositoryDao.updateLastIndexed(repoId, currentSha, Instant.now());
        log.info("Full index complete for repo {}", repoId);
    }

    public void incrementalIndex(int repoId, Path repoDir, String fromSha, String toSha) throws IOException {
        incrementalIndex(repoId, "main", repoDir, fromSha, toSha);
    }

    public void incrementalIndex(int repoId, String branch, Path repoDir, String fromSha, String toSha) throws IOException {
        if (fromSha == null || fromSha.isBlank()) {
            log.info("No fromSha provided for repo {}, falling back to full index", repoId);
            fullIndex(repoId, branch, repoDir);
            return;
        }

        log.info("Incremental indexing repo {} branch {} from {} to {}", repoId, branch, fromSha, toSha);

        List<GitOperations.DiffEntry> diffEntries = gitOps.diff(repoDir, fromSha, toSha);

        for (GitOperations.DiffEntry entry : diffEntries) {
            try {
                switch (entry.type()) {
                    case DELETED -> fileIndexer.removeFile(repoId, entry.path());
                    case ADDED, MODIFIED -> fileIndexer.indexFile(repoId, branch, repoDir, entry.path(), toSha);
                }
            } catch (Exception e) {
                log.error("Failed to process diff entry {} ({}) in repo {}: {}", entry.path(), entry.type(), repoId, e.getMessage(), e);
            }
        }

        repositoryDao.updateLastIndexed(repoId, toSha, Instant.now());
        log.info("Incremental index complete for repo {}", repoId);
    }
}

package com.indexer.admin;

import com.indexer.db.*;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.IndexingEvent;
import com.indexer.model.Repository;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RepositoryManager;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminServiceTest {

    private AdminService adminService;
    private RepositoryManager repoManager;
    private RepositoryDao repositoryDao;
    private FileDao fileDao;
    private SymbolDao symbolDao;
    private EventDao eventDao;
    private IndexingPipeline indexingPipeline;
    private GitOperations gitOps;
    private QueryExecutor queryExecutor;
    private Jdbi jdbi;

    @BeforeEach
    void setUp() {
        repoManager = mock(RepositoryManager.class);
        repositoryDao = mock(RepositoryDao.class);
        fileDao = mock(FileDao.class);
        symbolDao = mock(SymbolDao.class);
        eventDao = mock(EventDao.class);
        indexingPipeline = mock(IndexingPipeline.class);
        gitOps = mock(GitOperations.class);
        queryExecutor = mock(QueryExecutor.class);
        jdbi = mock(Jdbi.class);

        when(repoManager.getCloneBaseDir()).thenReturn("/tmp/repos");

        adminService = new AdminService(
                repoManager, repositoryDao, fileDao, symbolDao,
                eventDao, indexingPipeline, gitOps, queryExecutor, jdbi);
    }

    @Test
    void listRepositoriesReturnsAllRepos() {
        var repo = new Repository(1, "my-repo", "git@github.com:org/repo.git", "main",
                "/tmp/repos/my-repo", "ssh-key", "abc123", Instant.now());
        when(repositoryDao.findAll()).thenReturn(List.of(repo));
        when(fileDao.countByRepo(1)).thenReturn(42);

        var result = adminService.listRepositories();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("name", "my-repo");
        assertThat(result.get(0)).containsEntry("fileCount", 42);
        assertThat(result.get(0)).containsEntry("status", "ready");
    }

    @Test
    void getHealthDelegatesToQueryExecutor() {
        var health = Map.<String, Object>of("totalPendingEvents", 0L);
        when(queryExecutor.getIndexHealth()).thenReturn(health);

        var result = adminService.getHealth();

        assertThat(result).containsEntry("totalPendingEvents", 0L);
    }

    @Test
    void retryEventResetsPendingStatus() {
        var event = new IndexingEvent(42L, "repo", "/path", "post-commit",
                "aaa", "bbb", "main", "branch", "failed", "error msg",
                Instant.now(), null, null, null);
        when(eventDao.findById(42L)).thenReturn(Optional.of(event));
        when(eventDao.resetToPending(42L)).thenReturn(1);

        var result = adminService.retryEvent(42L);

        assertThat(result).containsEntry("id", 42L);
        assertThat(result).containsEntry("status", "pending");
        verify(eventDao).resetToPending(42L);
    }

    @Test
    void retryEventThrowsForNonFailedEvent() {
        var event = new IndexingEvent(42L, "repo", "/path", "post-commit",
                "aaa", "bbb", "main", "branch", "completed", null,
                Instant.now(), null, null, null);
        when(eventDao.findById(42L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> adminService.retryEvent(42L))
                .isInstanceOf(AdminService.BadRequestException.class);
    }

    @Test
    void retryEventThrowsForUnknownEvent() {
        when(eventDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.retryEvent(99L))
                .isInstanceOf(AdminService.NotFoundException.class);
    }

    @Test
    void addRepositoryRejectsDuplicateUrl() {
        var existing = new Repository(1, "repo", "git@github.com:org/repo.git", "main",
                "/tmp/repos/repo", "ssh-key", null, null);
        when(repositoryDao.findByName("repo")).thenReturn(Optional.of(existing));
        when(repoManager.extractRepoName("git@github.com:org/repo.git")).thenReturn("repo");

        assertThatThrownBy(() -> adminService.addRepository(
                "git@github.com:org/repo.git", "main", null))
                .isInstanceOf(AdminService.ConflictException.class);
    }

    @Test
    void listEventsPassesFilterToDao() {
        when(eventDao.findFiltered(eq("my-repo"), eq("failed"), any(), eq(50)))
                .thenReturn(List.of());

        var result = adminService.listEvents("my-repo", "failed", null, 50);

        assertThat(result).isEmpty();
        verify(eventDao).findFiltered("my-repo", "failed", null, 50);
    }
}

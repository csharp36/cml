package com.indexer.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GitHubPermissionResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void resolvesReposForSingleGroup() throws Exception {
        var repositoryDao = mock(RepositoryDao.class);
        when(repositoryDao.findAll()).thenReturn(List.of(
                repo("payments-api"), repo("platform-lib"), repo("other-repo")));

        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(MAPPER.writeValueAsString(List.of(
                java.util.Map.of("name", "payments-api"),
                java.util.Map.of("name", "platform-lib"),
                java.util.Map.of("name", "unindexed-repo")
        )));
        when(httpResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var resolver = new GitHubPermissionResolver("my-org", "ghp_token", repositoryDao, httpClient);
        Set<String> repos = resolver.resolveAllowedRepos(List.of("team-payments"));

        assertThat(repos).containsExactlyInAnyOrder("payments-api", "platform-lib");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergesReposFromMultipleGroups() throws Exception {
        var repositoryDao = mock(RepositoryDao.class);
        when(repositoryDao.findAll()).thenReturn(List.of(
                repo("payments-api"), repo("platform-lib"), repo("infra-tools")));

        var httpClient = mock(HttpClient.class);
        var httpResponse1 = mock(HttpResponse.class);
        when(httpResponse1.statusCode()).thenReturn(200);
        when(httpResponse1.body()).thenReturn(MAPPER.writeValueAsString(
                List.of(java.util.Map.of("name", "payments-api"))));
        when(httpResponse1.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));

        var httpResponse2 = mock(HttpResponse.class);
        when(httpResponse2.statusCode()).thenReturn(200);
        when(httpResponse2.body()).thenReturn(MAPPER.writeValueAsString(
                List.of(java.util.Map.of("name", "infra-tools"))));
        when(httpResponse2.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse1, httpResponse2);

        var resolver = new GitHubPermissionResolver("my-org", "ghp_token", repositoryDao, httpClient);
        Set<String> repos = resolver.resolveAllowedRepos(List.of("team-payments", "team-infra"));

        assertThat(repos).containsExactlyInAnyOrder("payments-api", "infra-tools");
    }

    @Test
    @SuppressWarnings("unchecked")
    void apiErrorThrowsPermissionResolutionException() throws Exception {
        var repositoryDao = mock(RepositoryDao.class);
        when(repositoryDao.findAll()).thenReturn(List.of(repo("payments-api")));

        var httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("Connection refused"));

        var resolver = new GitHubPermissionResolver("my-org", "ghp_token", repositoryDao, httpClient);

        assertThatThrownBy(() -> resolver.resolveAllowedRepos(List.of("team-payments")))
                .isInstanceOf(PermissionResolutionException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonOkStatusThrowsPermissionResolutionException() throws Exception {
        var repositoryDao = mock(RepositoryDao.class);
        when(repositoryDao.findAll()).thenReturn(List.of(repo("payments-api")));

        var httpClient = mock(HttpClient.class);
        var httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(403);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        var resolver = new GitHubPermissionResolver("my-org", "ghp_token", repositoryDao, httpClient);

        assertThatThrownBy(() -> resolver.resolveAllowedRepos(List.of("team-payments")))
                .isInstanceOf(PermissionResolutionException.class)
                .hasMessageContaining("403");
    }

    private Repository repo(String name) {
        return new Repository(0, name, "https://github.com/org/" + name, "main", "/repos/" + name, "ssh-key", null, null);
    }
}

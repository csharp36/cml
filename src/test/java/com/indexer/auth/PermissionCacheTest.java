package com.indexer.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PermissionCacheTest {

    @Test
    void cacheMissTriggersResolution() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a"))).thenReturn(Set.of("repo-1", "repo-2"));

        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactlyInAnyOrder("repo-1", "repo-2");
        verify(resolver, times(1)).resolveAllowedRepos(List.of("team-a"));
    }

    @Test
    void cacheHitDoesNotReResolve() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a"))).thenReturn(Set.of("repo-1"));

        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        cache.getAllowedRepos(caller);
        cache.getAllowedRepos(caller);

        verify(resolver, times(1)).resolveAllowedRepos(any());
    }

    @Test
    void openReposAlwaysIncluded() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a"))).thenReturn(Set.of("repo-1"));

        var cache = new PermissionCache(resolver, Set.of("shared-lib"), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactlyInAnyOrder("repo-1", "shared-lib");
    }

    @Test
    void emptyGroupsReturnsOpenReposOnly() {
        var resolver = mock(PermissionResolver.class);

        var cache = new PermissionCache(resolver, Set.of("shared-lib"), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of(), "10.0.0.1");

        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactly("shared-lib");
        verify(resolver, never()).resolveAllowedRepos(any());
    }

    @Test
    void resolverExceptionPropagatesFailClosed() {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(any())).thenThrow(
                new PermissionResolutionException("GitHub API unreachable", null));

        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMinutes(30));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        assertThatThrownBy(() -> cache.getAllowedRepos(caller))
                .isInstanceOf(PermissionResolutionException.class);
    }

    @Test
    void expiredCacheEntryReResolves() throws InterruptedException {
        var resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-a")))
                .thenReturn(Set.of("repo-1"))
                .thenReturn(Set.of("repo-1", "repo-2"));

        // 100ms TTL for testing
        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMillis(100));
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-a"), "10.0.0.1");

        cache.getAllowedRepos(caller);
        Thread.sleep(150);
        Set<String> result = cache.getAllowedRepos(caller);

        assertThat(result).containsExactlyInAnyOrder("repo-1", "repo-2");
        verify(resolver, times(2)).resolveAllowedRepos(any());
    }
}

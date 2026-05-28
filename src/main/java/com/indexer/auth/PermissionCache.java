package com.indexer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PermissionCache {

    private static final Logger log = LoggerFactory.getLogger(PermissionCache.class);

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final PermissionResolver resolver;
    private final Set<String> openRepos;
    private final Duration ttl;

    private record CacheEntry(Set<String> allowedRepos, Instant resolvedAt) {}

    public PermissionCache(PermissionResolver resolver, Set<String> openRepos, Duration ttl) {
        this.resolver = resolver;
        this.openRepos = openRepos != null ? Set.copyOf(openRepos) : Set.of();
        this.ttl = ttl;
    }

    public Set<String> getAllowedRepos(CallerIdentity caller) {
        if (caller.groups().isEmpty()) {
            return Set.copyOf(openRepos);
        }

        String cacheKey = caller.userId();
        var entry = cache.get(cacheKey);
        if (entry != null && entry.resolvedAt().plus(ttl).isAfter(Instant.now())) {
            return entry.allowedRepos();
        }

        // Cache miss or expired — resolve via platform API (fail-closed: exception propagates)
        log.info("Resolving permissions for user {} ({} groups)", caller.userId(), caller.groups().size());
        Set<String> resolved = resolver.resolveAllowedRepos(caller.groups());
        Set<String> combined = new HashSet<>(resolved);
        combined.addAll(openRepos);
        Set<String> result = Set.copyOf(combined);
        cache.put(cacheKey, new CacheEntry(result, Instant.now()));
        return result;
    }
}

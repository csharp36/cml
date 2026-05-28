package com.indexer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
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
        if (cacheKey == null) {
            throw new IllegalArgumentException("Cannot cache permissions for caller with null userId");
        }

        return cache.compute(cacheKey, (key, existing) -> {
            if (existing != null && existing.resolvedAt().plus(ttl).isAfter(Instant.now())) {
                return existing;
            }
            log.debug("Resolving permissions for user {} ({} groups)", caller.userId(), caller.groups().size());
            Set<String> resolved = resolver.resolveAllowedRepos(caller.groups());
            Set<String> combined = new HashSet<>(resolved);
            combined.addAll(openRepos);
            return new CacheEntry(Set.copyOf(combined), Instant.now());
        }).allowedRepos();
    }
}

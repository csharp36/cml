package com.indexer.auth;

import java.util.List;
import java.util.Set;

public interface PermissionResolver {
    /**
     * Resolve which indexed repos the given groups can access.
     * O(groups) API calls — one "repos for team" call per group.
     *
     * @throws PermissionResolutionException if the platform API is unreachable (fail-closed)
     */
    Set<String> resolveAllowedRepos(List<String> groups);
}

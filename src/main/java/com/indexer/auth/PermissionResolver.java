package com.indexer.auth;

import java.util.List;
import java.util.Set;

public interface PermissionResolver {
    Set<String> resolveAllowedRepos(List<String> groups);
}

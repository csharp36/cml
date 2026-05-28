package com.indexer.auth;

import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class NoOpPermissionResolver implements PermissionResolver {
    private final RepositoryDao repositoryDao;

    public NoOpPermissionResolver(RepositoryDao repositoryDao) {
        this.repositoryDao = repositoryDao;
    }

    @Override
    public Set<String> resolveAllowedRepos(List<String> groups) {
        return repositoryDao.findAll().stream()
                .map(Repository::name)
                .collect(Collectors.toUnmodifiableSet());
    }
}

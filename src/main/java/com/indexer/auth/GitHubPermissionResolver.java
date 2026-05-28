package com.indexer.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

public class GitHubPermissionResolver implements PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(GitHubPermissionResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String org;
    private final String serviceAccountToken;
    private final RepositoryDao repositoryDao;
    private final HttpClient httpClient;

    public GitHubPermissionResolver(String org, String serviceAccountToken,
                                    RepositoryDao repositoryDao, HttpClient httpClient) {
        this.org = org;
        this.serviceAccountToken = serviceAccountToken;
        this.repositoryDao = repositoryDao;
        this.httpClient = httpClient;
    }

    public GitHubPermissionResolver(String org, String serviceAccountToken,
                                    RepositoryDao repositoryDao) {
        this(org, serviceAccountToken, repositoryDao, HttpClient.newHttpClient());
    }

    @Override
    public Set<String> resolveAllowedRepos(List<String> groups) {
        Set<String> indexedRepos = repositoryDao.findAll().stream()
                .map(Repository::name)
                .collect(Collectors.toSet());

        Set<String> allowed = new HashSet<>();
        for (String group : groups) {
            try {
                Set<String> teamRepos = fetchTeamRepos(group);
                for (String repo : teamRepos) {
                    if (indexedRepos.contains(repo)) {
                        allowed.add(repo);
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw new PermissionResolutionException(
                        "Failed to resolve repos for group '" + group + "': " + msg, e);
            }
        }
        log.info("Resolved {} allowed repos for {} groups", allowed.size(), groups.size());
        return Set.copyOf(allowed);
    }

    private Set<String> fetchTeamRepos(String teamSlug) throws IOException, InterruptedException {
        Set<String> repos = new HashSet<>();
        String url = "https://api.github.com/orgs/" + org + "/teams/" + teamSlug + "/repos?per_page=100";

        while (url != null) {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + serviceAccountToken)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new PermissionResolutionException(
                        "GitHub API returned " + response.statusCode() + " for team '" + teamSlug + "'");
            }

            List<Map<String, Object>> repoList = MAPPER.readValue(
                    response.body(), new TypeReference<>() {});
            for (var repo : repoList) {
                repos.add((String) repo.get("name"));
            }

            url = parseLinkNext(response.headers().firstValue("Link").orElse(null));
        }
        return repos;
    }

    private String parseLinkNext(String linkHeader) {
        if (linkHeader == null) return null;
        for (String part : linkHeader.split(",")) {
            part = part.trim();
            if (part.endsWith("rel=\"next\"")) {
                int start = part.indexOf('<');
                int end = part.indexOf('>');
                if (start >= 0 && end > start) {
                    return part.substring(start + 1, end);
                }
            }
        }
        return null;
    }
}

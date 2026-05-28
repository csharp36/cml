package com.indexer.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexerConfig(
        ServerConfig server,
        DatabaseConfig database,
        List<RepositoryConfig> repositories,
        LanguagesConfig languages,
        AdminConfig admin,
        BranchConfig branches,
        @JsonProperty("auth") McpAuthConfig mcpAuth
) {
    public record ServerConfig(
            String cloneBaseDir,
            @JsonProperty("maxFileSizeBytes") long maxFileSizeBytes,
            int indexWorkers,
            int httpPort
    ) {
        public ServerConfig {
            if (cloneBaseDir == null) throw new ConfigValidationException("server.cloneBaseDir is required");
            if (maxFileSizeBytes <= 0) maxFileSizeBytes = 1_048_576;
            if (indexWorkers <= 0) indexWorkers = 4;
            if (httpPort <= 0) httpPort = 8080;
        }
    }

    public record DatabaseConfig(String host, int port, String name, String username, String password) {
        public DatabaseConfig {
            if (host == null) throw new ConfigValidationException("database.host is required");
            if (name == null) throw new ConfigValidationException("database.name is required");
            if (port <= 0) port = 5432;
        }

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name;
        }
    }

    public record RepositoryConfig(String url, String branch, AuthConfig auth) {
        public RepositoryConfig {
            if (url == null) throw new ConfigValidationException("repository.url is required");
            if (branch == null) branch = "main";
            if (auth == null) throw new ConfigValidationException("repository.auth is required");
        }
    }

    public record AuthConfig(String type, Map<String, String> properties) {
        public AuthConfig {
            if (type == null) throw new ConfigValidationException("auth.type is required");
        }

        public String get(String key) {
            return properties != null ? properties.get(key) : null;
        }
    }

    public record LanguagesConfig(Map<String, String> customExtensions) {
        public LanguagesConfig {
            if (customExtensions == null) customExtensions = Map.of();
        }
    }

    public record AdminConfig(String token) {
        // token can be null — admin API is disabled
    }

    public record BranchConfig(boolean autoIndex, int ttlDays, int cleanupIntervalHours) {
        public BranchConfig {
            if (ttlDays <= 0) ttlDays = 14;
            if (cleanupIntervalHours <= 0) cleanupIntervalHours = 24;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpAuthConfig(List<ApiKeyEntry> apiKeys, OAuthConfig oauth, PermissionsConfig permissions) {
        public McpAuthConfig {
            if (apiKeys == null) apiKeys = List.of();
        }
        public record ApiKeyEntry(String key, String id, String name, boolean auditReader, boolean scipUpload) {}
        public record OAuthConfig(String jwksUrl, String issuer, String audience, String groupsClaim) {
            public OAuthConfig {
                if (groupsClaim == null) groupsClaim = "groups";
            }
        }
        public record PermissionsConfig(String type, String serviceAccountToken, String org, List<String> openRepos) {
            public PermissionsConfig {
                if (type == null) type = "none";
                if (openRepos == null) openRepos = List.of();
            }
        }
    }

    public IndexerConfig {
        if (server == null) throw new ConfigValidationException("server section is required");
        if (database == null) throw new ConfigValidationException("database section is required");
        if (repositories == null) repositories = List.of();
        if (languages == null) languages = new LanguagesConfig(Map.of());
        // admin can be null — admin API disabled
        if (branches == null) branches = new BranchConfig(true, 14, 24);
        if (mcpAuth == null) mcpAuth = new McpAuthConfig(List.of(), null, null);
    }
}

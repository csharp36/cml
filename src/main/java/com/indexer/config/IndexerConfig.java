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
        LanguagesConfig languages
) {
    public record ServerConfig(
            String cloneBaseDir,
            @JsonProperty("maxFileSizeBytes") long maxFileSizeBytes,
            int indexWorkers,
            String transport,
            int ssePort,
            int webhookPort
    ) {
        public ServerConfig {
            if (cloneBaseDir == null) throw new ConfigValidationException("server.cloneBaseDir is required");
            if (maxFileSizeBytes <= 0) maxFileSizeBytes = 1_048_576;
            if (indexWorkers <= 0) indexWorkers = 4;
            if (transport == null) transport = "stdio";
            if (ssePort <= 0) ssePort = 8080;
            if (webhookPort <= 0) webhookPort = 8081;
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

    public IndexerConfig {
        if (server == null) throw new ConfigValidationException("server section is required");
        if (database == null) throw new ConfigValidationException("database section is required");
        if (repositories == null) repositories = List.of();
        if (languages == null) languages = new LanguagesConfig(Map.of());
    }
}

package com.indexer.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private static final String FULL_YAML = """
            server:
              cloneBaseDir: /tmp/repos
              maxFileSizeBytes: 2097152
              indexWorkers: 8
              httpPort: 9090

            database:
              host: localhost
              port: 5432
              name: indexer_db
              username: admin
              password: secret

            repositories:
              - url: https://github.com/example/repo.git
                branch: develop
                auth:
                  type: token
                  token: ghp_abc123

            languages:
              customExtensions:
                .myext: java
                .tpl: html

            admin:
              token: my-secret-token
            """;

    private static final String ENV_VAR_YAML = """
            server:
              cloneBaseDir: /tmp/repos

            database:
              host: localhost
              name: indexer_db
              password: ${TEST_DB_PASSWORD}
            """;

    private static final String MISSING_REQUIRED_YAML = """
            database:
              host: localhost
              name: indexer_db
            """;

    @Test
    void loadsValidConfig() throws IOException {
        ConfigLoader loader = new ConfigLoader();
        IndexerConfig config = loader.load(toStream(FULL_YAML));

        // Server
        assertThat(config.server().cloneBaseDir()).isEqualTo("/tmp/repos");
        assertThat(config.server().maxFileSizeBytes()).isEqualTo(2_097_152L);
        assertThat(config.server().indexWorkers()).isEqualTo(8);
        assertThat(config.server().httpPort()).isEqualTo(9090);

        // Database
        assertThat(config.database().host()).isEqualTo("localhost");
        assertThat(config.database().port()).isEqualTo(5432);
        assertThat(config.database().name()).isEqualTo("indexer_db");
        assertThat(config.database().username()).isEqualTo("admin");
        assertThat(config.database().password()).isEqualTo("secret");
        assertThat(config.database().jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/indexer_db");

        // Repositories
        assertThat(config.repositories()).hasSize(1);
        IndexerConfig.RepositoryConfig repo = config.repositories().get(0);
        assertThat(repo.url()).isEqualTo("https://github.com/example/repo.git");
        assertThat(repo.branch()).isEqualTo("develop");
        assertThat(repo.auth().type()).isEqualTo("token");
        assertThat(repo.auth().get("token")).isEqualTo("ghp_abc123");

        // Languages
        assertThat(config.languages().customExtensions()).containsEntry(".myext", "java");
        assertThat(config.languages().customExtensions()).containsEntry(".tpl", "html");

        // Admin
        assertThat(config.admin()).isNotNull();
        assertThat(config.admin().token()).isEqualTo("my-secret-token");
    }

    @Test
    void substitutesEnvironmentVariables() throws IOException {
        ConfigLoader loader = new ConfigLoader(varName -> {
            if ("TEST_DB_PASSWORD".equals(varName)) return "super-secret-password";
            return null;
        });

        IndexerConfig config = loader.load(toStream(ENV_VAR_YAML));

        assertThat(config.database().password()).isEqualTo("super-secret-password");
    }

    @Test
    void throwsOnMissingRequiredFields() {
        ConfigLoader loader = new ConfigLoader();

        assertThatThrownBy(() -> loader.load(toStream(MISSING_REQUIRED_YAML)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("server");
    }

    @Test
    void adminConfigIsOptionalAndDefaultsToNull() throws IOException {
        ConfigLoader loader = new ConfigLoader();
        IndexerConfig config = loader.load(toStream(ENV_VAR_YAML));
        assertThat(config.admin()).isNull();
    }

    @Test
    void expandsTildeInCloneBaseDir() throws IOException {
        String yaml = """
                server:
                  cloneBaseDir: ~/.source-code-indexer/repos

                database:
                  host: localhost
                  name: indexer_db
                """;
        ConfigLoader loader = new ConfigLoader();
        IndexerConfig config = loader.load(toStream(yaml));

        String home = System.getProperty("user.home");
        assertThat(config.server().cloneBaseDir())
                .isEqualTo(home + "/.source-code-indexer/repos")
                .doesNotContain("~");
    }

    @Test
    void parsesPerRepoWebhookSecret() throws IOException {
        String yaml = """
                server:
                  cloneBaseDir: /tmp/repos

                database:
                  host: localhost
                  name: indexer_db

                repositories:
                  - url: git@github.com:org/myrepo.git
                    branch: main
                    auth:
                      type: ssh-key
                      keyPath: /k
                    webhookSecret: ${HOOK_SECRET}
                """;
        ConfigLoader loader = new ConfigLoader(v -> "HOOK_SECRET".equals(v) ? "s3cr3t" : null);
        IndexerConfig config = loader.load(toStream(yaml));

        assertThat(config.repositories().get(0).webhookSecret()).isEqualTo("s3cr3t");
    }

    @Test
    void webhookSecretIsNullWhenOmitted() throws IOException {
        String yaml = """
                server:
                  cloneBaseDir: /tmp/repos

                database:
                  host: localhost
                  name: indexer_db

                repositories:
                  - url: git@github.com:org/myrepo.git
                    branch: main
                    auth:
                      type: token
                      token: t
                """;
        ConfigLoader loader = new ConfigLoader();
        IndexerConfig config = loader.load(toStream(yaml));

        assertThat(config.repositories().get(0).webhookSecret()).isNull();
    }

    @Test
    void parsesApiKeyReposAllowList() throws IOException {
        String yaml = """
                server:
                  cloneBaseDir: /tmp/repos

                database:
                  host: localhost
                  name: indexer_db

                auth:
                  apiKeys:
                    - key: scoped-key
                      id: ci-a
                      name: CI A
                      repos: [repo-a, repo-b]
                    - key: full-key
                      id: admin
                      name: Admin
                      repos: ["*"]
                    - key: bare-key
                      id: legacy
                      name: Legacy
                """;
        ConfigLoader loader = new ConfigLoader();
        IndexerConfig config = loader.load(toStream(yaml));

        var keys = config.mcpAuth().apiKeys();
        assertThat(keys).hasSize(3);
        assertThat(keys.get(0).repos()).containsExactly("repo-a", "repo-b");
        assertThat(keys.get(1).repos()).containsExactly("*");
        assertThat(keys.get(2).repos()).isEmpty(); // absent → empty (fail-closed)
    }

    private InputStream toStream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}

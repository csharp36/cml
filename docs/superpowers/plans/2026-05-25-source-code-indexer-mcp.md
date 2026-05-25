# Source Code Indexer MCP Server — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java MCP server that indexes source code repositories into PostgreSQL and exposes token-efficient query tools to Claude Code.

**Architecture:** Gradle Java 21 project with six components: MCP server (stdio+SSE), webhook HTTP endpoint, repository manager with pluggable auth, PostgreSQL event queue with SKIP LOCKED, Tree-sitter indexing pipeline, and admin API. All state lives in PostgreSQL except repo clones on disk.

**Tech Stack:** Java 21, Gradle (Kotlin DSL), PostgreSQL 16, Flyway, JDBI, HikariCP, Javalin (HTTP), Jackson (YAML/JSON), Tree-sitter (JNR-FFI), MCP Java SDK (`io.modelcontextprotocol:sdk`), JUnit 5, Testcontainers, SLF4J + Logback

---

## Phase 1: Project Foundation

### Task 1: Gradle Project Scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/java/com/indexer/Application.java`
- Create: `src/main/resources/logback.xml`

- [ ] **Step 1: Initialize Gradle wrapper**

Run:
```bash
cd /Users/csharpl/Projects/SourceCodeIndexerMCP
gradle init --type java-application --dsl kotlin --test-framework junit-jupiter --package com.indexer --project-name source-code-indexer --java-version 21 --no-comments --overwrite
```

If `gradle` is not installed, use:
```bash
brew install gradle
gradle init --type java-application --dsl kotlin --test-framework junit-jupiter --package com.indexer --project-name source-code-indexer --java-version 21 --no-comments --overwrite
```

- [ ] **Step 2: Replace settings.gradle.kts**

```kotlin
rootProject.name = "source-code-indexer"
```

- [ ] **Step 3: Replace build.gradle.kts with full dependency set**

```kotlin
plugins {
    java
    application
}

group = "com.indexer"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // MCP SDK
    implementation("io.modelcontextprotocol:sdk:0.10.0")

    // HTTP server
    implementation("io.javalin:javalin:6.4.0")

    // Database
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.jdbi:jdbi3-core:3.45.4")
    implementation("org.jdbi:jdbi3-postgres:3.45.4")
    implementation("org.jdbi:jdbi3-sqlobject:3.45.4")
    implementation("org.flywaydb:flyway-core:10.21.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.21.0")

    // Config
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Tree-sitter via JNR-FFI
    implementation("com.github.jnr:jnr-ffi:2.2.16")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("io.javalin:javalin-testtools:6.4.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass = "com.indexer.Application"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
}

tasks.register<Test>("e2eTest") {
    useJUnitPlatform {
        includeTags("e2e")
    }
}
```

- [ ] **Step 4: Create minimal Application.java**

```java
package com.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        log.info("Source Code Indexer MCP Server starting...");
    }
}
```

- [ ] **Step 5: Create logback.xml**

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${INDEXER_LOG_DIR:-logs}/indexer.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">
            <maxFileSize>50MB</maxFileSize>
        </rollingPolicy>
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT" />
        <appender-ref ref="FILE" />
    </root>
</configuration>
```

- [ ] **Step 6: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx512m
```

- [ ] **Step 7: Verify build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat \
  src/main/java/com/indexer/Application.java \
  src/main/resources/logback.xml
git commit -m "feat: initialize Gradle project with full dependency set"
```

---

### Task 2: Flyway Schema Migration

**Files:**
- Create: `src/main/resources/db/migration/V1__initial_schema.sql`
- Create: `src/main/java/com/indexer/db/DatabaseManager.java`
- Create: `src/test/java/com/indexer/db/MigrationTest.java`

- [ ] **Step 1: Write the migration test**

```java
package com.indexer.db;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class MigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    @Test
    void migrationCreatesAllTables() {
        var dbManager = new DatabaseManager(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        dbManager.initialize();

        var jdbi = dbManager.getJdbi();
        var tables = jdbi.withHandle(handle ->
                handle.createQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public'
                    ORDER BY table_name
                    """)
                        .mapTo(String.class)
                        .list()
        );

        assertThat(tables).contains(
                "repositories", "files", "symbols", "imports",
                "type_relationships", "file_contents", "indexing_events"
        );

        dbManager.close();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.db.MigrationTest" -i`
Expected: FAIL — `DatabaseManager` class doesn't exist

- [ ] **Step 3: Create DatabaseManager**

```java
package com.indexer.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    public DatabaseManager(String jdbcUrl, String username, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);

        this.dataSource = new HikariDataSource(config);
        this.jdbi = Jdbi.create(dataSource);
    }

    public void initialize() {
        log.info("Running Flyway migrations...");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        log.info("Migrations complete");
    }

    public Jdbi getJdbi() {
        return jdbi;
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        dataSource.close();
    }
}
```

- [ ] **Step 4: Create V1__initial_schema.sql**

```sql
-- Repositories tracked by the indexer
CREATE TABLE repositories (
    id              SERIAL PRIMARY KEY,
    name            TEXT UNIQUE NOT NULL,
    url             TEXT NOT NULL,
    branch          TEXT NOT NULL,
    clone_path      TEXT NOT NULL,
    auth_type       TEXT NOT NULL,
    last_indexed_sha TEXT,
    last_indexed_at  TIMESTAMPTZ
);

-- Indexed files
CREATE TABLE files (
    id              SERIAL PRIMARY KEY,
    repo_id         INT NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    language        TEXT,
    size_bytes      INT,
    last_commit_sha TEXT,
    last_modified_at TIMESTAMPTZ,
    UNIQUE(repo_id, path)
);

-- Structural symbols extracted by Tree-sitter
CREATE TABLE symbols (
    id              SERIAL PRIMARY KEY,
    file_id         INT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    kind            TEXT NOT NULL,
    signature       TEXT,
    start_line      INT NOT NULL,
    end_line        INT NOT NULL,
    parent_id       INT REFERENCES symbols(id) ON DELETE CASCADE,
    visibility      TEXT,
    is_static       BOOLEAN NOT NULL DEFAULT FALSE
);

-- Import statements per file
CREATE TABLE imports (
    id              SERIAL PRIMARY KEY,
    file_id         INT NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    import_path     TEXT NOT NULL,
    alias           TEXT
);

-- Type hierarchy relationships (implements, extends)
CREATE TABLE type_relationships (
    id              SERIAL PRIMARY KEY,
    symbol_id       INT NOT NULL REFERENCES symbols(id) ON DELETE CASCADE,
    related_name    TEXT NOT NULL,
    kind            TEXT NOT NULL
);

-- Full-text searchable file contents
CREATE TABLE file_contents (
    id              SERIAL PRIMARY KEY,
    file_id         INT NOT NULL REFERENCES files(id) ON DELETE CASCADE UNIQUE,
    content         TEXT,
    search_vector   TSVECTOR
);

-- Event queue for git hook notifications
CREATE TABLE indexing_events (
    id              BIGSERIAL PRIMARY KEY,
    repo_name       TEXT NOT NULL,
    repo_path       TEXT NOT NULL,
    event_type      TEXT NOT NULL,
    previous_sha    TEXT,
    current_sha     TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'pending',
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    worker_id       TEXT
);

-- Indexes for symbol lookups
CREATE INDEX idx_symbols_name ON symbols(name);
CREATE INDEX idx_symbols_kind ON symbols(kind);
CREATE INDEX idx_symbols_file_id ON symbols(file_id);

-- Indexes for file lookups
CREATE INDEX idx_files_repo_path ON files(repo_id, path);
CREATE INDEX idx_files_language ON files(language);

-- Full-text search index
CREATE INDEX idx_file_contents_search ON file_contents USING GIN(search_vector);

-- Type relationship indexes
CREATE INDEX idx_type_rel_related ON type_relationships(related_name);
CREATE INDEX idx_type_rel_symbol ON type_relationships(symbol_id);

-- Import indexes
CREATE INDEX idx_imports_path ON imports(import_path);

-- Event queue indexes
CREATE INDEX idx_events_pending ON indexing_events(status, created_at) WHERE status = 'pending';
CREATE INDEX idx_events_repo ON indexing_events(repo_name, status);

-- Trigger to auto-update search_vector on content change
CREATE OR REPLACE FUNCTION update_search_vector() RETURNS trigger AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', COALESCE(NEW.content, ''));
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER file_contents_search_update
    BEFORE INSERT OR UPDATE OF content ON file_contents
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();
```

- [ ] **Step 5: Run migration test**

Run: `./gradlew test --tests "com.indexer.db.MigrationTest" -i`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V1__initial_schema.sql \
  src/main/java/com/indexer/db/DatabaseManager.java \
  src/test/java/com/indexer/db/MigrationTest.java
git commit -m "feat: add Flyway migration with full schema and DatabaseManager"
```

---

### Task 3: Configuration Loading

**Files:**
- Create: `src/main/java/com/indexer/config/IndexerConfig.java`
- Create: `src/main/java/com/indexer/config/ConfigLoader.java`
- Create: `src/test/java/com/indexer/config/ConfigLoaderTest.java`
- Create: `src/test/resources/test-config.yaml`

- [ ] **Step 1: Write the config test**

```java
package com.indexer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @Test
    void loadsValidConfig(@TempDir Path tempDir) throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                server:
                  cloneBaseDir: /tmp/repos
                  maxFileSizeBytes: 2097152
                  indexWorkers: 8
                  transport: stdio
                  ssePort: 9090
                  webhookPort: 9091
                database:
                  host: localhost
                  port: 5432
                  name: test_db
                  username: user
                  password: pass
                repositories:
                  - url: git@github.com:org/repo.git
                    branch: main
                    auth:
                      type: ssh-key
                      keyPath: /home/user/.ssh/id_ed25519
                languages:
                  customExtensions:
                    .proto: protobuf
                """);

        var config = ConfigLoader.load(configFile);

        assertThat(config.server().cloneBaseDir()).isEqualTo("/tmp/repos");
        assertThat(config.server().maxFileSizeBytes()).isEqualTo(2097152);
        assertThat(config.server().indexWorkers()).isEqualTo(8);
        assertThat(config.server().transport()).isEqualTo("stdio");
        assertThat(config.server().ssePort()).isEqualTo(9090);
        assertThat(config.server().webhookPort()).isEqualTo(9091);
        assertThat(config.database().host()).isEqualTo("localhost");
        assertThat(config.database().jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/test_db");
        assertThat(config.repositories()).hasSize(1);
        assertThat(config.repositories().get(0).url()).isEqualTo("git@github.com:org/repo.git");
        assertThat(config.repositories().get(0).auth().type()).isEqualTo("ssh-key");
        assertThat(config.languages().customExtensions()).containsEntry(".proto", "protobuf");
    }

    @Test
    void substitutesEnvironmentVariables(@TempDir Path tempDir) throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                server:
                  cloneBaseDir: /tmp/repos
                  maxFileSizeBytes: 1048576
                  indexWorkers: 4
                  transport: stdio
                  ssePort: 8080
                  webhookPort: 8081
                database:
                  host: localhost
                  port: 5432
                  name: test_db
                  username: user
                  password: ${TEST_DB_PASSWORD}
                repositories: []
                languages:
                  customExtensions: {}
                """);

        // ConfigLoader.load resolves ${ENV_VAR} patterns
        // For test, we verify the pattern is detected (actual env resolution happens at runtime)
        var config = ConfigLoader.loadWithEnv(configFile, key -> {
            if (key.equals("TEST_DB_PASSWORD")) return "secret123";
            return null;
        });

        assertThat(config.database().password()).isEqualTo("secret123");
    }

    @Test
    void throwsOnMissingRequiredFields(@TempDir Path tempDir) throws IOException {
        var configFile = tempDir.resolve("config.yaml");
        Files.writeString(configFile, """
                server:
                  cloneBaseDir: /tmp/repos
                """);

        assertThatThrownBy(() -> ConfigLoader.load(configFile))
                .isInstanceOf(ConfigValidationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest" -i`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create IndexerConfig record hierarchy**

```java
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

    public record DatabaseConfig(
            String host,
            int port,
            String name,
            String username,
            String password
    ) {
        public DatabaseConfig {
            if (host == null) throw new ConfigValidationException("database.host is required");
            if (name == null) throw new ConfigValidationException("database.name is required");
            if (port <= 0) port = 5432;
        }

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name;
        }
    }

    public record RepositoryConfig(
            String url,
            String branch,
            AuthConfig auth
    ) {
        public RepositoryConfig {
            if (url == null) throw new ConfigValidationException("repository.url is required");
            if (branch == null) branch = "main";
            if (auth == null) throw new ConfigValidationException("repository.auth is required");
        }
    }

    public record AuthConfig(
            String type,
            Map<String, String> properties
    ) {
        public AuthConfig {
            if (type == null) throw new ConfigValidationException("auth.type is required");
        }

        public String get(String key) {
            return properties != null ? properties.get(key) : null;
        }
    }

    public record LanguagesConfig(
            Map<String, String> customExtensions
    ) {
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
```

- [ ] **Step 4: Create ConfigValidationException**

```java
package com.indexer.config;

public class ConfigValidationException extends RuntimeException {
    public ConfigValidationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Create ConfigLoader**

```java
package com.indexer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

public class ConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    public static IndexerConfig load(Path configPath) {
        return loadWithEnv(configPath, System::getenv);
    }

    public static IndexerConfig loadWithEnv(Path configPath, Function<String, String> envResolver) {
        try {
            JsonNode root = YAML_MAPPER.readTree(configPath.toFile());
            resolveEnvVars(root, envResolver);
            return parseConfig(root);
        } catch (IOException e) {
            throw new ConfigValidationException("Failed to read config file: " + configPath + " - " + e.getMessage());
        }
    }

    private static void resolveEnvVars(JsonNode node, Function<String, String> envResolver) {
        if (node.isObject()) {
            var objNode = (ObjectNode) node;
            var fieldNames = new ArrayList<String>();
            objNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String field : fieldNames) {
                JsonNode child = objNode.get(field);
                if (child.isTextual()) {
                    String resolved = resolveString(child.asText(), envResolver);
                    objNode.set(field, new TextNode(resolved));
                } else {
                    resolveEnvVars(child, envResolver);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                resolveEnvVars(child, envResolver);
            }
        }
    }

    private static String resolveString(String value, Function<String, String> envResolver) {
        var matcher = ENV_PATTERN.matcher(value);
        var result = new StringBuilder();
        while (matcher.find()) {
            String envKey = matcher.group(1);
            String envValue = envResolver.apply(envKey);
            if (envValue == null) {
                log.warn("Environment variable {} not found, leaving as-is", envKey);
                envValue = matcher.group(0);
            }
            matcher.appendReplacement(result, envValue);
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static IndexerConfig parseConfig(JsonNode root) {
        var server = parseServer(root.path("server"));
        var database = parseDatabase(root.path("database"));
        var repositories = parseRepositories(root.path("repositories"));
        var languages = parseLanguages(root.path("languages"));
        return new IndexerConfig(server, database, repositories, languages);
    }

    private static IndexerConfig.ServerConfig parseServer(JsonNode node) {
        if (node.isMissingNode()) throw new ConfigValidationException("server section is required");
        return new IndexerConfig.ServerConfig(
                node.path("cloneBaseDir").asText(null),
                node.path("maxFileSizeBytes").asLong(1_048_576),
                node.path("indexWorkers").asInt(4),
                node.path("transport").asText("stdio"),
                node.path("ssePort").asInt(8080),
                node.path("webhookPort").asInt(8081)
        );
    }

    private static IndexerConfig.DatabaseConfig parseDatabase(JsonNode node) {
        if (node.isMissingNode()) throw new ConfigValidationException("database section is required");
        return new IndexerConfig.DatabaseConfig(
                node.path("host").asText(null),
                node.path("port").asInt(5432),
                node.path("name").asText(null),
                node.path("username").asText(null),
                node.path("password").asText(null)
        );
    }

    private static List<IndexerConfig.RepositoryConfig> parseRepositories(JsonNode node) {
        if (node.isMissingNode() || !node.isArray()) return List.of();
        var repos = new ArrayList<IndexerConfig.RepositoryConfig>();
        for (JsonNode repoNode : node) {
            var authNode = repoNode.path("auth");
            Map<String, String> authProps = new HashMap<>();
            authNode.fields().forEachRemaining(entry -> {
                if (!entry.getKey().equals("type")) {
                    authProps.put(entry.getKey(), entry.getValue().asText());
                }
            });
            var auth = new IndexerConfig.AuthConfig(
                    authNode.path("type").asText(null),
                    authProps
            );
            repos.add(new IndexerConfig.RepositoryConfig(
                    repoNode.path("url").asText(null),
                    repoNode.path("branch").asText("main"),
                    auth
            ));
        }
        return repos;
    }

    private static IndexerConfig.LanguagesConfig parseLanguages(JsonNode node) {
        if (node.isMissingNode()) return new IndexerConfig.LanguagesConfig(Map.of());
        Map<String, String> extensions = new HashMap<>();
        var extNode = node.path("customExtensions");
        if (extNode.isObject()) {
            extNode.fields().forEachRemaining(entry ->
                    extensions.put(entry.getKey(), entry.getValue().asText()));
        }
        return new IndexerConfig.LanguagesConfig(extensions);
    }
}
```

- [ ] **Step 6: Run config tests**

Run: `./gradlew test --tests "com.indexer.config.ConfigLoaderTest" -i`
Expected: PASS (all 3 tests)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/config/ src/test/java/com/indexer/config/
git commit -m "feat: add YAML config loading with env var substitution"
```

---

### Task 4: Language Registry

**Files:**
- Create: `src/main/java/com/indexer/config/LanguageRegistry.java`
- Create: `src/test/java/com/indexer/config/LanguageRegistryTest.java`

- [ ] **Step 1: Write the language registry test**

```java
package com.indexer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRegistryTest {

    private final LanguageRegistry registry = new LanguageRegistry(Map.of(".proto", "protobuf"));

    @ParameterizedTest
    @CsvSource({
            "Main.java, java",
            "app.py, python",
            "index.ts, typescript",
            "component.tsx, typescript",
            "main.go, go",
            "index.js, javascript",
            "app.jsx, javascript",
            "schema.proto, protobuf",
            "README.md, markdown",
            "Makefile, makefile"
    })
    void detectsLanguageFromExtension(String filename, String expected) {
        assertThat(registry.detectLanguage(filename)).isEqualTo(expected);
    }

    @Test
    void unknownExtensionReturnsPlaintext() {
        assertThat(registry.detectLanguage("file.xyz")).isEqualTo("plaintext");
    }

    @Test
    void coreLanguagesAreIdentified() {
        assertThat(registry.isCoreLanguage("java")).isTrue();
        assertThat(registry.isCoreLanguage("python")).isTrue();
        assertThat(registry.isCoreLanguage("typescript")).isTrue();
        assertThat(registry.isCoreLanguage("javascript")).isTrue();
        assertThat(registry.isCoreLanguage("go")).isTrue();
        assertThat(registry.isCoreLanguage("markdown")).isFalse();
        assertThat(registry.isCoreLanguage("plaintext")).isFalse();
    }

    @Test
    void identifiesBinaryFiles() {
        assertThat(registry.isBinary("image.png")).isTrue();
        assertThat(registry.isBinary("archive.zip")).isTrue();
        assertThat(registry.isBinary("app.jar")).isTrue();
        assertThat(registry.isBinary("main.java")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.config.LanguageRegistryTest" -i`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Implement LanguageRegistry**

```java
package com.indexer.config;

import java.util.Map;
import java.util.Set;

public class LanguageRegistry {

    private static final Set<String> CORE_LANGUAGES = Set.of(
            "java", "python", "typescript", "javascript", "go"
    );

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
            ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar",
            ".jar", ".war", ".ear", ".class",
            ".exe", ".dll", ".so", ".dylib",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx",
            ".woff", ".woff2", ".ttf", ".eot",
            ".mp3", ".mp4", ".avi", ".mov",
            ".pyc", ".pyo", ".o", ".obj"
    );

    private static final Map<String, String> DEFAULT_EXTENSIONS = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".py", "python"),
            Map.entry(".pyi", "python"),
            Map.entry(".ts", "typescript"),
            Map.entry(".tsx", "typescript"),
            Map.entry(".js", "javascript"),
            Map.entry(".jsx", "javascript"),
            Map.entry(".mjs", "javascript"),
            Map.entry(".cjs", "javascript"),
            Map.entry(".go", "go"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".rs", "rust"),
            Map.entry(".rb", "ruby"),
            Map.entry(".php", "php"),
            Map.entry(".cs", "csharp"),
            Map.entry(".cpp", "cpp"),
            Map.entry(".cc", "cpp"),
            Map.entry(".c", "c"),
            Map.entry(".h", "c"),
            Map.entry(".hpp", "cpp"),
            Map.entry(".swift", "swift"),
            Map.entry(".scala", "scala"),
            Map.entry(".md", "markdown"),
            Map.entry(".yml", "yaml"),
            Map.entry(".yaml", "yaml"),
            Map.entry(".json", "json"),
            Map.entry(".xml", "xml"),
            Map.entry(".html", "html"),
            Map.entry(".css", "css"),
            Map.entry(".scss", "scss"),
            Map.entry(".sql", "sql"),
            Map.entry(".sh", "shell"),
            Map.entry(".bash", "shell"),
            Map.entry(".zsh", "shell"),
            Map.entry(".dockerfile", "dockerfile"),
            Map.entry(".tf", "hcl"),
            Map.entry(".toml", "toml"),
            Map.entry(".gradle", "groovy")
    );

    private final Map<String, String> customExtensions;

    public LanguageRegistry(Map<String, String> customExtensions) {
        this.customExtensions = customExtensions != null ? customExtensions : Map.of();
    }

    public String detectLanguage(String filename) {
        if (filename == null) return "plaintext";

        // Check special filenames
        String lower = filename.toLowerCase();
        if (lower.equals("makefile")) return "makefile";
        if (lower.equals("dockerfile")) return "dockerfile";

        // Find extension
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) return "plaintext";
        String ext = filename.substring(lastDot).toLowerCase();

        // Custom extensions take priority
        String custom = customExtensions.get(ext);
        if (custom != null) return custom;

        // Default extensions
        String defaultLang = DEFAULT_EXTENSIONS.get(ext);
        if (defaultLang != null) return defaultLang;

        return "plaintext";
    }

    public boolean isCoreLanguage(String language) {
        return CORE_LANGUAGES.contains(language);
    }

    public boolean isBinary(String filename) {
        if (filename == null) return false;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) return false;
        String ext = filename.substring(lastDot).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew test --tests "com.indexer.config.LanguageRegistryTest" -i`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/indexer/config/LanguageRegistry.java \
  src/test/java/com/indexer/config/LanguageRegistryTest.java
git commit -m "feat: add LanguageRegistry with core language detection"
```

---

### Task 5: Auth Provider Framework

**Files:**
- Create: `src/main/java/com/indexer/auth/AuthProvider.java`
- Create: `src/main/java/com/indexer/auth/GitCredentials.java`
- Create: `src/main/java/com/indexer/auth/AuthProviderRegistry.java`
- Create: `src/main/java/com/indexer/auth/SshKeyAuthProvider.java`
- Create: `src/main/java/com/indexer/auth/TokenAuthProvider.java`
- Create: `src/main/java/com/indexer/auth/GitCredentialManagerProvider.java`
- Create: `src/main/java/com/indexer/auth/AuthResolutionException.java`
- Create: `src/test/java/com/indexer/auth/AuthProviderRegistryTest.java`
- Create: `src/main/resources/META-INF/services/com.indexer.auth.AuthProvider`

- [ ] **Step 1: Write the auth provider test**

```java
package com.indexer.auth;

import com.indexer.config.IndexerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthProviderRegistryTest {

    private final AuthProviderRegistry registry = new AuthProviderRegistry();

    @Test
    void resolvesSshKeyAuth(@TempDir Path tempDir) throws IOException {
        var keyFile = tempDir.resolve("id_ed25519");
        Files.writeString(keyFile, "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----");

        var authConfig = new IndexerConfig.AuthConfig("ssh-key", Map.of("keyPath", keyFile.toString()));
        var creds = registry.resolve(authConfig);

        assertThat(creds.type()).isEqualTo(GitCredentials.Type.SSH_KEY);
        assertThat(creds.sshKeyPath()).isEqualTo(keyFile);
    }

    @Test
    void resolvesTokenAuth() {
        var authConfig = new IndexerConfig.AuthConfig("token", Map.of("token", "ghp_abc123"));
        var creds = registry.resolve(authConfig);

        assertThat(creds.type()).isEqualTo(GitCredentials.Type.TOKEN);
        assertThat(creds.token()).isEqualTo("ghp_abc123");
    }

    @Test
    void resolvesGitCredentialManager() {
        var authConfig = new IndexerConfig.AuthConfig("git-credential-manager", Map.of());
        var creds = registry.resolve(authConfig);

        assertThat(creds.type()).isEqualTo(GitCredentials.Type.GIT_CREDENTIAL_MANAGER);
    }

    @Test
    void throwsOnUnsupportedAuthType() {
        var authConfig = new IndexerConfig.AuthConfig("kerberos", Map.of());

        assertThatThrownBy(() -> registry.resolve(authConfig))
                .isInstanceOf(AuthResolutionException.class)
                .hasMessageContaining("kerberos");
    }

    @Test
    void throwsOnMissingSshKey(@TempDir Path tempDir) {
        var authConfig = new IndexerConfig.AuthConfig("ssh-key",
                Map.of("keyPath", tempDir.resolve("nonexistent").toString()));

        assertThatThrownBy(() -> registry.resolve(authConfig))
                .isInstanceOf(AuthResolutionException.class)
                .hasMessageContaining("not found");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.auth.AuthProviderRegistryTest" -i`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create GitCredentials**

```java
package com.indexer.auth;

import java.nio.file.Path;

public record GitCredentials(
        Type type,
        Path sshKeyPath,
        String token,
        Path certPath,
        Path certKeyPath
) {
    public enum Type {
        SSH_KEY, TOKEN, GIT_CREDENTIAL_MANAGER, CLIENT_CERT, OAUTH2, KERBEROS
    }

    public static GitCredentials sshKey(Path keyPath) {
        return new GitCredentials(Type.SSH_KEY, keyPath, null, null, null);
    }

    public static GitCredentials token(String token) {
        return new GitCredentials(Type.TOKEN, null, token, null, null);
    }

    public static GitCredentials gitCredentialManager() {
        return new GitCredentials(Type.GIT_CREDENTIAL_MANAGER, null, null, null, null);
    }

    public static GitCredentials clientCert(Path certPath, Path keyPath) {
        return new GitCredentials(Type.CLIENT_CERT, null, null, certPath, keyPath);
    }
}
```

- [ ] **Step 4: Create AuthProvider interface**

```java
package com.indexer.auth;

import com.indexer.config.IndexerConfig;

public interface AuthProvider {
    GitCredentials resolve(IndexerConfig.AuthConfig config);
    boolean supports(String authType);
}
```

- [ ] **Step 5: Create AuthResolutionException**

```java
package com.indexer.auth;

public class AuthResolutionException extends RuntimeException {
    public AuthResolutionException(String message) {
        super(message);
    }

    public AuthResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 6: Create SshKeyAuthProvider**

```java
package com.indexer.auth;

import com.indexer.config.IndexerConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public class SshKeyAuthProvider implements AuthProvider {

    @Override
    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        String keyPathStr = config.get("keyPath");
        if (keyPathStr == null) {
            throw new AuthResolutionException("ssh-key auth requires 'keyPath' property");
        }
        Path keyPath = Path.of(keyPathStr);
        if (!Files.exists(keyPath)) {
            throw new AuthResolutionException("SSH key not found: " + keyPath);
        }
        return GitCredentials.sshKey(keyPath);
    }

    @Override
    public boolean supports(String authType) {
        return "ssh-key".equals(authType);
    }
}
```

- [ ] **Step 7: Create TokenAuthProvider**

```java
package com.indexer.auth;

import com.indexer.config.IndexerConfig;

public class TokenAuthProvider implements AuthProvider {

    @Override
    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        String token = config.get("token");
        if (token == null || token.isBlank()) {
            throw new AuthResolutionException("token auth requires 'token' property");
        }
        return GitCredentials.token(token);
    }

    @Override
    public boolean supports(String authType) {
        return "token".equals(authType);
    }
}
```

- [ ] **Step 8: Create GitCredentialManagerProvider**

```java
package com.indexer.auth;

import com.indexer.config.IndexerConfig;

public class GitCredentialManagerProvider implements AuthProvider {

    @Override
    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        return GitCredentials.gitCredentialManager();
    }

    @Override
    public boolean supports(String authType) {
        return "git-credential-manager".equals(authType);
    }
}
```

- [ ] **Step 9: Create AuthProviderRegistry**

```java
package com.indexer.auth;

import com.indexer.config.IndexerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class AuthProviderRegistry {
    private static final Logger log = LoggerFactory.getLogger(AuthProviderRegistry.class);

    private final List<AuthProvider> providers;

    public AuthProviderRegistry() {
        this.providers = new ArrayList<>();
        // Built-in providers
        providers.add(new SshKeyAuthProvider());
        providers.add(new TokenAuthProvider());
        providers.add(new GitCredentialManagerProvider());

        // Load additional providers via ServiceLoader
        ServiceLoader.load(AuthProvider.class).forEach(provider -> {
            log.info("Loaded auth provider plugin: {}", provider.getClass().getName());
            providers.add(provider);
        });
    }

    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        for (AuthProvider provider : providers) {
            if (provider.supports(config.type())) {
                return provider.resolve(config);
            }
        }
        throw new AuthResolutionException(
                "No auth provider found for type: " + config.type() +
                ". Available types: " + providers.stream()
                        .map(p -> p.getClass().getSimpleName())
                        .toList()
        );
    }
}
```

- [ ] **Step 10: Create ServiceLoader registration file**

Create `src/main/resources/META-INF/services/com.indexer.auth.AuthProvider` (empty — built-ins are registered manually, this file is for plugin JARs):

```
# External AuthProvider implementations are registered here via ServiceLoader.
# Drop a JAR with this file on the classpath to add custom auth providers.
```

- [ ] **Step 11: Run auth tests**

Run: `./gradlew test --tests "com.indexer.auth.AuthProviderRegistryTest" -i`
Expected: PASS (all 5 tests)

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/indexer/auth/ \
  src/test/java/com/indexer/auth/ \
  src/main/resources/META-INF/services/
git commit -m "feat: add pluggable AuthProvider framework with SSH, token, GCM support"
```

---

## Phase 2: Data Access & Git Operations

### Task 6: Data Access Objects (DAOs)

**Files:**
- Create: `src/main/java/com/indexer/model/Repository.java`
- Create: `src/main/java/com/indexer/model/SourceFile.java`
- Create: `src/main/java/com/indexer/model/Symbol.java`
- Create: `src/main/java/com/indexer/model/Import.java`
- Create: `src/main/java/com/indexer/model/TypeRelationship.java`
- Create: `src/main/java/com/indexer/model/IndexingEvent.java`
- Create: `src/main/java/com/indexer/db/RepositoryDao.java`
- Create: `src/main/java/com/indexer/db/FileDao.java`
- Create: `src/main/java/com/indexer/db/SymbolDao.java`
- Create: `src/main/java/com/indexer/db/EventDao.java`
- Create: `src/test/java/com/indexer/db/RepositoryDaoTest.java`

- [ ] **Step 1: Write the DAO integration test**

```java
package com.indexer.db;

import com.indexer.model.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class RepositoryDaoTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private DatabaseManager dbManager;
    private RepositoryDao repositoryDao;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        repositoryDao = new RepositoryDao(dbManager.getJdbi());
        // Clean state between tests
        dbManager.getJdbi().useHandle(h -> h.execute("DELETE FROM repositories"));
    }

    @Test
    void insertsAndFindsRepository() {
        var repo = new Repository(0, "my-repo", "git@github.com:org/my-repo.git",
                "main", "/repos/my-repo", "ssh-key", null, null);

        int id = repositoryDao.insert(repo);
        var found = repositoryDao.findByName("my-repo");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().url()).isEqualTo("git@github.com:org/my-repo.git");
    }

    @Test
    void updatesLastIndexedSha() {
        var repo = new Repository(0, "my-repo", "git@github.com:org/my-repo.git",
                "main", "/repos/my-repo", "ssh-key", null, null);
        int id = repositoryDao.insert(repo);

        repositoryDao.updateLastIndexed(id, "abc123", Instant.now());

        var found = repositoryDao.findByName("my-repo");
        assertThat(found.get().lastIndexedSha()).isEqualTo("abc123");
        assertThat(found.get().lastIndexedAt()).isNotNull();
    }

    @Test
    void listsAllRepositories() {
        repositoryDao.insert(new Repository(0, "repo-a", "url-a", "main", "/a", "ssh-key", null, null));
        repositoryDao.insert(new Repository(0, "repo-b", "url-b", "main", "/b", "token", null, null));

        var all = repositoryDao.findAll();
        assertThat(all).hasSize(2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.db.RepositoryDaoTest" -i`
Expected: FAIL — model classes and DAO don't exist

- [ ] **Step 3: Create model records**

```java
// src/main/java/com/indexer/model/Repository.java
package com.indexer.model;

import java.time.Instant;

public record Repository(
        int id,
        String name,
        String url,
        String branch,
        String clonePath,
        String authType,
        String lastIndexedSha,
        Instant lastIndexedAt
) {}
```

```java
// src/main/java/com/indexer/model/SourceFile.java
package com.indexer.model;

import java.time.Instant;

public record SourceFile(
        int id,
        int repoId,
        String path,
        String language,
        int sizeBytes,
        String lastCommitSha,
        Instant lastModifiedAt
) {}
```

```java
// src/main/java/com/indexer/model/Symbol.java
package com.indexer.model;

public record Symbol(
        int id,
        int fileId,
        String name,
        String kind,
        String signature,
        int startLine,
        int endLine,
        Integer parentId,
        String visibility,
        boolean isStatic
) {}
```

```java
// src/main/java/com/indexer/model/Import.java
package com.indexer.model;

public record Import(
        int id,
        int fileId,
        String importPath,
        String alias
) {}
```

```java
// src/main/java/com/indexer/model/TypeRelationship.java
package com.indexer.model;

public record TypeRelationship(
        int id,
        int symbolId,
        String relatedName,
        String kind
) {}
```

```java
// src/main/java/com/indexer/model/IndexingEvent.java
package com.indexer.model;

import java.time.Instant;

public record IndexingEvent(
        long id,
        String repoName,
        String repoPath,
        String eventType,
        String previousSha,
        String currentSha,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String workerId
) {}
```

- [ ] **Step 4: Create RepositoryDao**

```java
package com.indexer.db;

import com.indexer.model.Repository;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class RepositoryDao {
    private final Jdbi jdbi;

    public RepositoryDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public int insert(Repository repo) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                    INSERT INTO repositories (name, url, branch, clone_path, auth_type)
                    VALUES (:name, :url, :branch, :clonePath, :authType)
                    """)
                        .bind("name", repo.name())
                        .bind("url", repo.url())
                        .bind("branch", repo.branch())
                        .bind("clonePath", repo.clonePath())
                        .bind("authType", repo.authType())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public Optional<Repository> findByName(String name) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM repositories WHERE name = :name")
                        .bind("name", name)
                        .map((rs, ctx) -> new Repository(
                                rs.getInt("id"),
                                rs.getString("name"),
                                rs.getString("url"),
                                rs.getString("branch"),
                                rs.getString("clone_path"),
                                rs.getString("auth_type"),
                                rs.getString("last_indexed_sha"),
                                rs.getTimestamp("last_indexed_at") != null
                                        ? rs.getTimestamp("last_indexed_at").toInstant() : null
                        ))
                        .findOne()
        );
    }

    public List<Repository> findAll() {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM repositories ORDER BY name")
                        .map((rs, ctx) -> new Repository(
                                rs.getInt("id"),
                                rs.getString("name"),
                                rs.getString("url"),
                                rs.getString("branch"),
                                rs.getString("clone_path"),
                                rs.getString("auth_type"),
                                rs.getString("last_indexed_sha"),
                                rs.getTimestamp("last_indexed_at") != null
                                        ? rs.getTimestamp("last_indexed_at").toInstant() : null
                        ))
                        .list()
        );
    }

    public void updateLastIndexed(int id, String sha, Instant timestamp) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                    UPDATE repositories SET last_indexed_sha = :sha, last_indexed_at = :timestamp
                    WHERE id = :id
                    """)
                        .bind("id", id)
                        .bind("sha", sha)
                        .bind("timestamp", timestamp)
                        .execute()
        );
    }

    public void delete(String name) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM repositories WHERE name = :name")
                        .bind("name", name)
                        .execute()
        );
    }
}
```

- [ ] **Step 5: Create FileDao**

```java
package com.indexer.db;

import com.indexer.model.SourceFile;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class FileDao {
    private final Jdbi jdbi;

    public FileDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public int upsert(SourceFile file) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                    INSERT INTO files (repo_id, path, language, size_bytes, last_commit_sha, last_modified_at)
                    VALUES (:repoId, :path, :language, :sizeBytes, :lastCommitSha, :lastModifiedAt)
                    ON CONFLICT (repo_id, path) DO UPDATE SET
                        language = EXCLUDED.language,
                        size_bytes = EXCLUDED.size_bytes,
                        last_commit_sha = EXCLUDED.last_commit_sha,
                        last_modified_at = EXCLUDED.last_modified_at
                    """)
                        .bind("repoId", file.repoId())
                        .bind("path", file.path())
                        .bind("language", file.language())
                        .bind("sizeBytes", file.sizeBytes())
                        .bind("lastCommitSha", file.lastCommitSha())
                        .bind("lastModifiedAt", file.lastModifiedAt())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public Optional<SourceFile> findByRepoAndPath(int repoId, String path) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM files WHERE repo_id = :repoId AND path = :path")
                        .bind("repoId", repoId)
                        .bind("path", path)
                        .map((rs, ctx) -> new SourceFile(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("path"),
                                rs.getString("language"),
                                rs.getInt("size_bytes"),
                                rs.getString("last_commit_sha"),
                                rs.getTimestamp("last_modified_at") != null
                                        ? rs.getTimestamp("last_modified_at").toInstant() : null
                        ))
                        .findOne()
        );
    }

    public void deleteByRepoAndPath(int repoId, String path) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM files WHERE repo_id = :repoId AND path = :path")
                        .bind("repoId", repoId)
                        .bind("path", path)
                        .execute()
        );
    }

    public int countByRepo(int repoId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM files WHERE repo_id = :repoId")
                        .bind("repoId", repoId)
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public List<SourceFile> findByRepo(int repoId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM files WHERE repo_id = :repoId ORDER BY path")
                        .bind("repoId", repoId)
                        .map((rs, ctx) -> new SourceFile(
                                rs.getInt("id"),
                                rs.getInt("repo_id"),
                                rs.getString("path"),
                                rs.getString("language"),
                                rs.getInt("size_bytes"),
                                rs.getString("last_commit_sha"),
                                rs.getTimestamp("last_modified_at") != null
                                        ? rs.getTimestamp("last_modified_at").toInstant() : null
                        ))
                        .list()
        );
    }
}
```

- [ ] **Step 6: Create SymbolDao**

```java
package com.indexer.db;

import com.indexer.model.Symbol;
import com.indexer.model.Import;
import com.indexer.model.TypeRelationship;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

public class SymbolDao {
    private final Jdbi jdbi;

    public SymbolDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public int insertSymbol(Symbol symbol) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                    INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, parent_id, visibility, is_static)
                    VALUES (:fileId, :name, :kind, :signature, :startLine, :endLine, :parentId, :visibility, :isStatic)
                    """)
                        .bind("fileId", symbol.fileId())
                        .bind("name", symbol.name())
                        .bind("kind", symbol.kind())
                        .bind("signature", symbol.signature())
                        .bind("startLine", symbol.startLine())
                        .bind("endLine", symbol.endLine())
                        .bind("parentId", symbol.parentId())
                        .bind("visibility", symbol.visibility())
                        .bind("isStatic", symbol.isStatic())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public void insertImport(Import imp) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                    INSERT INTO imports (file_id, import_path, alias)
                    VALUES (:fileId, :importPath, :alias)
                    """)
                        .bind("fileId", imp.fileId())
                        .bind("importPath", imp.importPath())
                        .bind("alias", imp.alias())
                        .execute()
        );
    }

    public void insertTypeRelationship(TypeRelationship rel) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                    INSERT INTO type_relationships (symbol_id, related_name, kind)
                    VALUES (:symbolId, :relatedName, :kind)
                    """)
                        .bind("symbolId", rel.symbolId())
                        .bind("relatedName", rel.relatedName())
                        .bind("kind", rel.kind())
                        .execute()
        );
    }

    public void deleteSymbolsByFileId(int fileId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM symbols WHERE file_id = :fileId")
                        .bind("fileId", fileId)
                        .execute()
        );
    }

    public void deleteImportsByFileId(int fileId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM imports WHERE file_id = :fileId")
                        .bind("fileId", fileId)
                        .execute()
        );
    }

    public List<Symbol> findByFileId(int fileId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM symbols WHERE file_id = :fileId ORDER BY start_line")
                        .bind("fileId", fileId)
                        .map((rs, ctx) -> new Symbol(
                                rs.getInt("id"),
                                rs.getInt("file_id"),
                                rs.getString("name"),
                                rs.getString("kind"),
                                rs.getString("signature"),
                                rs.getInt("start_line"),
                                rs.getInt("end_line"),
                                rs.getObject("parent_id", Integer.class),
                                rs.getString("visibility"),
                                rs.getBoolean("is_static")
                        ))
                        .list()
        );
    }
}
```

- [ ] **Step 7: Create EventDao**

```java
package com.indexer.db;

import com.indexer.model.IndexingEvent;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public class EventDao {
    private final Jdbi jdbi;

    public EventDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public long insert(String repoName, String repoPath, String eventType, String previousSha, String currentSha) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                    INSERT INTO indexing_events (repo_name, repo_path, event_type, previous_sha, current_sha)
                    VALUES (:repoName, :repoPath, :eventType, :previousSha, :currentSha)
                    """)
                        .bind("repoName", repoName)
                        .bind("repoPath", repoPath)
                        .bind("eventType", eventType)
                        .bind("previousSha", previousSha)
                        .bind("currentSha", currentSha)
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Long.class)
                        .one()
        );
    }

    public Optional<IndexingEvent> claimNextPending(String workerId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                    UPDATE indexing_events
                    SET status = 'processing', started_at = NOW(), worker_id = :workerId
                    WHERE id = (
                        SELECT id FROM indexing_events
                        WHERE status = 'pending'
                        ORDER BY created_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    RETURNING *
                    """)
                        .bind("workerId", workerId)
                        .map((rs, ctx) -> new IndexingEvent(
                                rs.getLong("id"),
                                rs.getString("repo_name"),
                                rs.getString("repo_path"),
                                rs.getString("event_type"),
                                rs.getString("previous_sha"),
                                rs.getString("current_sha"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                                rs.getString("worker_id")
                        ))
                        .findOne()
        );
    }

    public void markCompleted(long eventId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("UPDATE indexing_events SET status = 'completed', completed_at = NOW() WHERE id = :id")
                        .bind("id", eventId)
                        .execute()
        );
    }

    public void markFailed(long eventId, String errorMessage) {
        jdbi.useHandle(handle ->
                handle.createUpdate("UPDATE indexing_events SET status = 'failed', completed_at = NOW(), error_message = :msg WHERE id = :id")
                        .bind("id", eventId)
                        .bind("msg", errorMessage)
                        .execute()
        );
    }

    public List<IndexingEvent> findPendingByRepo(String repoName) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                    SELECT * FROM indexing_events
                    WHERE repo_name = :repoName AND status = 'pending'
                    ORDER BY created_at
                    """)
                        .bind("repoName", repoName)
                        .map((rs, ctx) -> new IndexingEvent(
                                rs.getLong("id"),
                                rs.getString("repo_name"),
                                rs.getString("repo_path"),
                                rs.getString("event_type"),
                                rs.getString("previous_sha"),
                                rs.getString("current_sha"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                                rs.getString("worker_id")
                        ))
                        .list()
        );
    }

    public int countByStatus(String status) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT COUNT(*) FROM indexing_events WHERE status = :status")
                        .bind("status", status)
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public List<IndexingEvent> findRecentFailed(int limit) {
        return jdbi.withHandle(handle ->
                handle.createQuery("""
                    SELECT * FROM indexing_events
                    WHERE status = 'failed'
                    ORDER BY completed_at DESC
                    LIMIT :limit
                    """)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new IndexingEvent(
                                rs.getLong("id"),
                                rs.getString("repo_name"),
                                rs.getString("repo_path"),
                                rs.getString("event_type"),
                                rs.getString("previous_sha"),
                                rs.getString("current_sha"),
                                rs.getString("status"),
                                rs.getString("error_message"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                                rs.getTimestamp("completed_at") != null ? rs.getTimestamp("completed_at").toInstant() : null,
                                rs.getString("worker_id")
                        ))
                        .list()
        );
    }

    public void notifyNewEvent() {
        jdbi.useHandle(handle -> handle.execute("NOTIFY new_event"));
    }
}
```

- [ ] **Step 8: Run DAO test**

Run: `./gradlew test --tests "com.indexer.db.RepositoryDaoTest" -i`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/indexer/model/ src/main/java/com/indexer/db/ \
  src/test/java/com/indexer/db/RepositoryDaoTest.java
git commit -m "feat: add data model records and DAOs for all tables"
```

---

### Task 7: Repository Manager & Git Operations

**Files:**
- Create: `src/main/java/com/indexer/repository/GitOperations.java`
- Create: `src/main/java/com/indexer/repository/HookInstaller.java`
- Create: `src/main/java/com/indexer/repository/RepositoryManager.java`
- Create: `src/test/java/com/indexer/repository/HookInstallerTest.java`
- Create: `src/test/java/com/indexer/repository/GitOperationsTest.java`

- [ ] **Step 1: Write the hook installer test**

```java
package com.indexer.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HookInstallerTest {

    @Test
    void installsAllHookScripts(@TempDir Path tempDir) throws IOException {
        // Simulate a .git/hooks directory
        var hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        var installer = new HookInstaller("http://localhost:8081");
        installer.installHooks(tempDir);

        assertThat(hooksDir.resolve("post-commit")).exists();
        assertThat(hooksDir.resolve("post-merge")).exists();
        assertThat(hooksDir.resolve("post-checkout")).exists();
        assertThat(hooksDir.resolve("post-rewrite")).exists();

        // Verify scripts are executable
        assertThat(Files.isExecutable(hooksDir.resolve("post-commit"))).isTrue();

        // Verify content contains curl to webhook
        String content = Files.readString(hooksDir.resolve("post-commit"));
        assertThat(content).contains("curl");
        assertThat(content).contains("http://localhost:8081/webhook");
        assertThat(content).contains("post-commit");
    }

    @Test
    void hookScriptComputesShasCorrectly(@TempDir Path tempDir) throws IOException {
        var hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        var installer = new HookInstaller("http://localhost:8081");
        installer.installHooks(tempDir);

        String content = Files.readString(hooksDir.resolve("post-commit"));
        // Should use git rev-parse HEAD for current SHA
        assertThat(content).contains("git rev-parse HEAD");
        // Should use git rev-parse HEAD~1 for previous SHA (post-commit)
        assertThat(content).contains("HEAD~1");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.repository.HookInstallerTest" -i`
Expected: FAIL — class doesn't exist

- [ ] **Step 3: Create HookInstaller**

```java
package com.indexer.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

public class HookInstaller {
    private static final Logger log = LoggerFactory.getLogger(HookInstaller.class);

    private final String webhookUrl;

    public HookInstaller(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void installHooks(Path repoPath) throws IOException {
        Path hooksDir = repoPath.resolve(".git/hooks");
        if (!Files.exists(hooksDir)) {
            Files.createDirectories(hooksDir);
        }

        installHook(hooksDir, "post-commit", generatePostCommitScript());
        installHook(hooksDir, "post-merge", generatePostMergeScript());
        installHook(hooksDir, "post-checkout", generatePostCheckoutScript());
        installHook(hooksDir, "post-rewrite", generatePostRewriteScript());

        log.info("Installed git hooks in {}", repoPath);
    }

    private void installHook(Path hooksDir, String hookName, String script) throws IOException {
        Path hookFile = hooksDir.resolve(hookName);
        Files.writeString(hookFile, script);
        try {
            Files.setPosixFilePermissions(hookFile, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException e) {
            // Windows — skip permission setting
            hookFile.toFile().setExecutable(true);
        }
    }

    private String generatePostCommitScript() {
        return """
                #!/bin/sh
                REPO_PATH="$(git rev-parse --show-toplevel)"
                REPO_NAME="$(basename "$REPO_PATH")"
                CURRENT_SHA="$(git rev-parse HEAD)"
                PREVIOUS_SHA="$(git rev-parse HEAD~1 2>/dev/null || echo "")"
                curl -s -X POST %s/webhook \
                  -H "Content-Type: application/json" \
                  -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"post-commit\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\",\\"timestamp\\":\\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\\"}" \
                  >/dev/null 2>&1 &
                """.formatted(webhookUrl);
    }

    private String generatePostMergeScript() {
        return """
                #!/bin/sh
                REPO_PATH="$(git rev-parse --show-toplevel)"
                REPO_NAME="$(basename "$REPO_PATH")"
                CURRENT_SHA="$(git rev-parse HEAD)"
                PREVIOUS_SHA="$(git rev-parse ORIG_HEAD 2>/dev/null || echo "")"
                curl -s -X POST %s/webhook \
                  -H "Content-Type: application/json" \
                  -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"post-merge\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\",\\"timestamp\\":\\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\\"}" \
                  >/dev/null 2>&1 &
                """.formatted(webhookUrl);
    }

    private String generatePostCheckoutScript() {
        return """
                #!/bin/sh
                PREVIOUS_SHA="$1"
                CURRENT_SHA="$2"
                IS_BRANCH="$3"
                if [ "$IS_BRANCH" = "1" ]; then
                  REPO_PATH="$(git rev-parse --show-toplevel)"
                  REPO_NAME="$(basename "$REPO_PATH")"
                  curl -s -X POST %s/webhook \
                    -H "Content-Type: application/json" \
                    -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"post-checkout\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\",\\"timestamp\\":\\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\\"}" \
                    >/dev/null 2>&1 &
                fi
                """.formatted(webhookUrl);
    }

    private String generatePostRewriteScript() {
        return """
                #!/bin/sh
                REPO_PATH="$(git rev-parse --show-toplevel)"
                REPO_NAME="$(basename "$REPO_PATH")"
                CURRENT_SHA="$(git rev-parse HEAD)"
                PREVIOUS_SHA="$(git rev-parse ORIG_HEAD 2>/dev/null || echo "")"
                curl -s -X POST %s/webhook \
                  -H "Content-Type: application/json" \
                  -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"post-rewrite\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\",\\"timestamp\\":\\"$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)\\"}" \
                  >/dev/null 2>&1 &
                """.formatted(webhookUrl);
    }
}
```

- [ ] **Step 4: Create GitOperations**

```java
package com.indexer.repository;

import com.indexer.auth.GitCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitOperations {
    private static final Logger log = LoggerFactory.getLogger(GitOperations.class);
    private static final int TIMEOUT_SECONDS = 300;

    public void clone(String url, String branch, Path targetDir, GitCredentials creds) throws IOException {
        List<String> command = buildCloneCommand(url, branch, targetDir, creds);
        executeGit(command, targetDir.getParent(), creds);
    }

    public void fetch(Path repoDir, GitCredentials creds) throws IOException {
        executeGit(List.of("git", "fetch", "--prune"), repoDir, creds);
    }

    public void fastForward(Path repoDir, String branch) throws IOException {
        executeGit(List.of("git", "merge", "--ff-only", "origin/" + branch), repoDir, null);
    }

    public String getCurrentSha(Path repoDir) throws IOException {
        return executeGitOutput(List.of("git", "rev-parse", "HEAD"), repoDir).trim();
    }

    public List<DiffEntry> diff(Path repoDir, String fromSha, String toSha) throws IOException {
        String output = executeGitOutput(
                List.of("git", "diff", "--name-status", fromSha, toSha), repoDir);

        List<DiffEntry> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 2);
            if (parts.length < 2) continue;
            String status = parts[0].trim();
            String path = parts[1].trim();
            DiffEntry.Type type = switch (status) {
                case "A" -> DiffEntry.Type.ADDED;
                case "M" -> DiffEntry.Type.MODIFIED;
                case "D" -> DiffEntry.Type.DELETED;
                default -> DiffEntry.Type.MODIFIED; // R, C, etc. treated as modified
            };
            entries.add(new DiffEntry(type, path));
        }
        return entries;
    }

    public List<String> listAllFiles(Path repoDir) throws IOException {
        String output = executeGitOutput(
                List.of("git", "ls-files"), repoDir);
        return output.lines().filter(l -> !l.isBlank()).toList();
    }

    private List<String> buildCloneCommand(String url, String branch, Path targetDir, GitCredentials creds) {
        var cmd = new ArrayList<>(List.of("git", "clone", "--branch", branch, "--single-branch"));
        if (creds != null && creds.type() == GitCredentials.Type.TOKEN) {
            // Embed token in URL for HTTPS repos
            url = url.replace("https://", "https://oauth2:" + creds.token() + "@");
        }
        cmd.add(url);
        cmd.add(targetDir.toString());
        return cmd;
    }

    private void executeGit(List<String> command, Path workDir, GitCredentials creds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir != null ? workDir.toFile() : null);
        pb.redirectErrorStream(true);
        configureSshEnv(pb, creds);

        Process process = pb.start();
        try {
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Git command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IOException("Git command failed (exit " + process.exitValue() + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }
    }

    private String executeGitOutput(List<String> command, Path workDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try {
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            var output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Git command timed out");
            }
            return output.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }
    }

    private void configureSshEnv(ProcessBuilder pb, GitCredentials creds) {
        if (creds != null && creds.type() == GitCredentials.Type.SSH_KEY) {
            pb.environment().put("GIT_SSH_COMMAND",
                    "ssh -i " + creds.sshKeyPath() + " -o StrictHostKeyChecking=no");
        }
    }

    public record DiffEntry(Type type, String path) {
        public enum Type { ADDED, MODIFIED, DELETED }
    }
}
```

- [ ] **Step 5: Create RepositoryManager**

```java
package com.indexer.repository;

import com.indexer.auth.AuthProviderRegistry;
import com.indexer.auth.GitCredentials;
import com.indexer.config.IndexerConfig;
import com.indexer.db.RepositoryDao;
import com.indexer.model.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RepositoryManager {
    private static final Logger log = LoggerFactory.getLogger(RepositoryManager.class);

    private final IndexerConfig config;
    private final AuthProviderRegistry authRegistry;
    private final RepositoryDao repositoryDao;
    private final GitOperations gitOps;
    private final HookInstaller hookInstaller;

    public RepositoryManager(IndexerConfig config, AuthProviderRegistry authRegistry,
                             RepositoryDao repositoryDao, GitOperations gitOps) {
        this.config = config;
        this.authRegistry = authRegistry;
        this.repositoryDao = repositoryDao;
        this.gitOps = gitOps;
        this.hookInstaller = new HookInstaller(
                "http://localhost:" + config.server().webhookPort());
    }

    public List<Repository> initializeRepositories() {
        Path baseDir = Path.of(config.server().cloneBaseDir());
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create clone base directory: " + baseDir, e);
        }

        List<Repository> initialized = new ArrayList<>();
        for (var repoConfig : config.repositories()) {
            try {
                var repo = initializeRepo(repoConfig, baseDir);
                initialized.add(repo);
            } catch (Exception e) {
                log.error("Failed to initialize repo {}: {}", repoConfig.url(), e.getMessage(), e);
            }
        }
        return initialized;
    }

    private Repository initializeRepo(IndexerConfig.RepositoryConfig repoConfig, Path baseDir) throws IOException {
        String repoName = extractRepoName(repoConfig.url());
        Path clonePath = baseDir.resolve(repoName);
        GitCredentials creds = authRegistry.resolve(repoConfig.auth());

        if (!Files.exists(clonePath.resolve(".git"))) {
            log.info("Cloning {} into {}", repoConfig.url(), clonePath);
            gitOps.clone(repoConfig.url(), repoConfig.branch(), clonePath, creds);
        } else {
            log.info("Repository {} already cloned, fetching latest", repoName);
            gitOps.fetch(clonePath, creds);
            gitOps.fastForward(clonePath, repoConfig.branch());
        }

        // Install hooks
        try {
            hookInstaller.installHooks(clonePath);
        } catch (IOException e) {
            log.warn("Failed to install hooks for {}: {}", repoName, e.getMessage());
        }

        // Upsert repository record in DB
        var existing = repositoryDao.findByName(repoName);
        int repoId;
        if (existing.isEmpty()) {
            var repo = new Repository(0, repoName, repoConfig.url(), repoConfig.branch(),
                    clonePath.toString(), repoConfig.auth().type(), null, null);
            repoId = repositoryDao.insert(repo);
        } else {
            repoId = existing.get().id();
        }

        return repositoryDao.findByName(repoName).orElseThrow();
    }

    public String extractRepoName(String url) {
        // Handle both SSH and HTTPS URLs
        String name = url;
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }
}
```

- [ ] **Step 6: Run hook installer test**

Run: `./gradlew test --tests "com.indexer.repository.HookInstallerTest" -i`
Expected: PASS

- [ ] **Step 7: Write GitOperations test (for diff parsing)**

```java
package com.indexer.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitOperationsTest {

    @Test
    void extractsDiffEntries(@TempDir Path tempDir) throws Exception {
        // Initialize a test repo, make commits, verify diff
        var gitOps = new GitOperations();

        // Create a git repo
        new ProcessBuilder("git", "init").directory(tempDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.email", "test@test.com").directory(tempDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.name", "Test").directory(tempDir.toFile()).start().waitFor();

        // First commit
        java.nio.file.Files.writeString(tempDir.resolve("file1.java"), "class A {}");
        new ProcessBuilder("git", "add", ".").directory(tempDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "first").directory(tempDir.toFile()).start().waitFor();
        String sha1 = gitOps.getCurrentSha(tempDir);

        // Second commit — add and modify
        java.nio.file.Files.writeString(tempDir.resolve("file1.java"), "class A { int x; }");
        java.nio.file.Files.writeString(tempDir.resolve("file2.py"), "def foo(): pass");
        new ProcessBuilder("git", "add", ".").directory(tempDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "second").directory(tempDir.toFile()).start().waitFor();
        String sha2 = gitOps.getCurrentSha(tempDir);

        var diff = gitOps.diff(tempDir, sha1, sha2);

        assertThat(diff).hasSize(2);
        assertThat(diff).anyMatch(e -> e.path().equals("file1.java") && e.type() == GitOperations.DiffEntry.Type.MODIFIED);
        assertThat(diff).anyMatch(e -> e.path().equals("file2.py") && e.type() == GitOperations.DiffEntry.Type.ADDED);
    }
}
```

- [ ] **Step 8: Run git operations test**

Run: `./gradlew test --tests "com.indexer.repository.GitOperationsTest" -i`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/indexer/repository/ src/test/java/com/indexer/repository/
git commit -m "feat: add RepositoryManager with git clone, fetch, diff, and hook installation"
```

---

## Phase 3: Event Processing

### Task 8: Webhook HTTP Server

**Files:**
- Create: `src/main/java/com/indexer/webhook/WebhookServer.java`
- Create: `src/main/java/com/indexer/webhook/WebhookPayload.java`
- Create: `src/test/java/com/indexer/webhook/WebhookServerTest.java`

- [ ] **Step 1: Write the webhook test**

```java
package com.indexer.webhook;

import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import io.javalin.Javalin;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class WebhookServerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private EventDao eventDao;
    private Javalin app;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        eventDao = new EventDao(dbManager.getJdbi());
        // Clean events
        dbManager.getJdbi().useHandle(h -> h.execute("DELETE FROM indexing_events"));

        var webhookServer = new WebhookServer(eventDao);
        app = webhookServer.createApp();
    }

    @Test
    void acceptsValidWebhookPayload() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/webhook", """
                    {
                        "repoName": "my-repo",
                        "repoPath": "/repos/my-repo",
                        "eventType": "post-commit",
                        "previousSha": "abc123",
                        "currentSha": "def456",
                        "timestamp": "2026-05-25T12:00:00Z"
                    }
                    """);

            assertThat(response.code()).isEqualTo(202);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
        });
    }

    @Test
    void rejectsMalformedPayload() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/webhook", """
                    { "repoName": "my-repo" }
                    """);

            assertThat(response.code()).isEqualTo(400);
            assertThat(eventDao.countByStatus("pending")).isEqualTo(0);
        });
    }

    @Test
    void rejectsEmptyBody() {
        JavalinTest.test(app, (server, client) -> {
            var response = client.post("/webhook", "");
            assertThat(response.code()).isEqualTo(400);
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.webhook.WebhookServerTest" -i`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create WebhookPayload**

```java
package com.indexer.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookPayload(
        String repoName,
        String repoPath,
        String eventType,
        String previousSha,
        String currentSha,
        String timestamp
) {
    public boolean isValid() {
        return repoName != null && !repoName.isBlank()
                && repoPath != null && !repoPath.isBlank()
                && eventType != null && !eventType.isBlank()
                && currentSha != null && !currentSha.isBlank();
    }
}
```

- [ ] **Step 4: Create WebhookServer**

```java
package com.indexer.webhook;

import com.indexer.db.EventDao;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebhookServer {
    private static final Logger log = LoggerFactory.getLogger(WebhookServer.class);

    private final EventDao eventDao;
    private Javalin app;

    public WebhookServer(EventDao eventDao) {
        this.eventDao = eventDao;
    }

    public Javalin createApp() {
        app = Javalin.create();
        app.post("/webhook", this::handleWebhook);
        return app;
    }

    public void start(int port) {
        if (app == null) createApp();
        app.start(port);
        log.info("Webhook server listening on port {}", port);
    }

    public void stop() {
        if (app != null) app.stop();
    }

    private void handleWebhook(Context ctx) {
        WebhookPayload payload;
        try {
            payload = ctx.bodyAsClass(WebhookPayload.class);
        } catch (Exception e) {
            log.warn("Failed to parse webhook payload: {}", e.getMessage());
            ctx.status(400).json(new ErrorResponse("Invalid JSON payload"));
            return;
        }

        if (payload == null || !payload.isValid()) {
            ctx.status(400).json(new ErrorResponse("Missing required fields: repoName, repoPath, eventType, currentSha"));
            return;
        }

        long eventId = eventDao.insert(
                payload.repoName(),
                payload.repoPath(),
                payload.eventType(),
                payload.previousSha(),
                payload.currentSha()
        );
        eventDao.notifyNewEvent();

        log.info("Received webhook event #{} for repo {} ({})", eventId, payload.repoName(), payload.eventType());
        ctx.status(202).json(new AcceptedResponse(eventId));
    }

    private record ErrorResponse(String error) {}
    private record AcceptedResponse(long eventId) {}
}
```

- [ ] **Step 5: Run webhook tests**

Run: `./gradlew test --tests "com.indexer.webhook.WebhookServerTest" -i`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/webhook/ src/test/java/com/indexer/webhook/
git commit -m "feat: add webhook HTTP endpoint for git hook event ingestion"
```

---

### Task 9: Event Queue Poller with Deduplication

**Files:**
- Create: `src/main/java/com/indexer/queue/EventQueuePoller.java`
- Create: `src/main/java/com/indexer/queue/EventDeduplicator.java`
- Create: `src/test/java/com/indexer/queue/EventDeduplicatorTest.java`
- Create: `src/test/java/com/indexer/queue/EventQueuePollerTest.java`

- [ ] **Step 1: Write deduplicator test**

```java
package com.indexer.queue;

import com.indexer.model.IndexingEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventDeduplicatorTest {

    @Test
    void collapsesSameRepoEvents() {
        var events = List.of(
                event(1, "repo-a", "sha1", "sha2"),
                event(2, "repo-a", "sha2", "sha3"),
                event(3, "repo-a", "sha3", "sha4")
        );

        var result = EventDeduplicator.collapse(events);

        assertThat(result.previousSha()).isEqualTo("sha1");
        assertThat(result.currentSha()).isEqualTo("sha4");
        assertThat(result.subsummedEventIds()).containsExactly(2L, 3L);
    }

    @Test
    void singleEventReturnsItself() {
        var events = List.of(event(1, "repo-a", "sha1", "sha2"));

        var result = EventDeduplicator.collapse(events);

        assertThat(result.previousSha()).isEqualTo("sha1");
        assertThat(result.currentSha()).isEqualTo("sha2");
        assertThat(result.subsummedEventIds()).isEmpty();
    }

    @Test
    void handlesNullPreviousSha() {
        var events = List.of(
                event(1, "repo-a", null, "sha1"),
                event(2, "repo-a", "sha1", "sha2")
        );

        var result = EventDeduplicator.collapse(events);

        assertThat(result.previousSha()).isNull();
        assertThat(result.currentSha()).isEqualTo("sha2");
    }

    private IndexingEvent event(long id, String repo, String prev, String curr) {
        return new IndexingEvent(id, repo, "/repos/" + repo, "post-commit",
                prev, curr, "pending", null, Instant.now(), null, null, null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.queue.EventDeduplicatorTest" -i`
Expected: FAIL

- [ ] **Step 3: Create EventDeduplicator**

```java
package com.indexer.queue;

import com.indexer.model.IndexingEvent;

import java.util.ArrayList;
import java.util.List;

public class EventDeduplicator {

    public record CollapsedEvent(
            String previousSha,
            String currentSha,
            List<Long> subsummedEventIds
    ) {}

    public static CollapsedEvent collapse(List<IndexingEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Cannot collapse empty event list");
        }
        if (events.size() == 1) {
            var e = events.get(0);
            return new CollapsedEvent(e.previousSha(), e.currentSha(), List.of());
        }

        // First event's previousSha is the starting point
        String previousSha = events.get(0).previousSha();
        // Last event's currentSha is the endpoint
        String currentSha = events.get(events.size() - 1).currentSha();
        // All events except the first are subsumed (their work is covered by the collapsed range)
        List<Long> subsumed = new ArrayList<>();
        for (int i = 1; i < events.size(); i++) {
            subsumed.add(events.get(i).id());
        }

        return new CollapsedEvent(previousSha, currentSha, subsumed);
    }
}
```

- [ ] **Step 4: Run deduplicator test**

Run: `./gradlew test --tests "com.indexer.queue.EventDeduplicatorTest" -i`
Expected: PASS

- [ ] **Step 5: Create EventQueuePoller**

```java
package com.indexer.queue;

import com.indexer.db.EventDao;
import com.indexer.model.IndexingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class EventQueuePoller implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(EventQueuePoller.class);

    private final EventDao eventDao;
    private final Consumer<ProcessableEvent> eventProcessor;
    private final String workerId;
    private final long pollIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EventQueuePoller(EventDao eventDao, Consumer<ProcessableEvent> eventProcessor, long pollIntervalMs) {
        this.eventDao = eventDao;
        this.eventProcessor = eventProcessor;
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        this.pollIntervalMs = pollIntervalMs;
    }

    public record ProcessableEvent(
            long eventId,
            String repoName,
            String repoPath,
            String previousSha,
            String currentSha
    ) {}

    @Override
    public void run() {
        running.set(true);
        log.info("Event queue poller started (worker: {})", workerId);

        while (running.get()) {
            try {
                var claimed = eventDao.claimNextPending(workerId);
                if (claimed.isPresent()) {
                    processEvent(claimed.get());
                } else {
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in event queue poller", e);
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Event queue poller stopped (worker: {})", workerId);
    }

    private void processEvent(IndexingEvent event) {
        // Check for additional pending events for same repo (deduplication)
        List<IndexingEvent> pending = eventDao.findPendingByRepo(event.repoName());

        String previousSha = event.previousSha();
        String currentSha = event.currentSha();

        if (!pending.isEmpty()) {
            // Collapse: include the current event + pending ones
            var allEvents = new java.util.ArrayList<IndexingEvent>();
            allEvents.add(event);
            allEvents.addAll(pending);
            var collapsed = EventDeduplicator.collapse(allEvents);
            previousSha = collapsed.previousSha();
            currentSha = collapsed.currentSha();

            // Mark subsumed events as completed
            for (long subsummedId : collapsed.subsummedEventIds()) {
                eventDao.markCompleted(subsummedId);
            }
        }

        var processable = new ProcessableEvent(
                event.id(), event.repoName(), event.repoPath(), previousSha, currentSha);

        try {
            eventProcessor.accept(processable);
            eventDao.markCompleted(event.id());
            log.info("Event #{} processed successfully for repo {}", event.id(), event.repoName());
        } catch (Exception e) {
            log.error("Event #{} failed for repo {}: {}", event.id(), event.repoName(), e.getMessage(), e);
            eventDao.markFailed(event.id(), e.getMessage());
        }
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getWorkerId() {
        return workerId;
    }
}
```

- [ ] **Step 6: Write poller integration test**

```java
package com.indexer.queue;

import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class EventQueuePollerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private EventDao eventDao;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        eventDao = new EventDao(dbManager.getJdbi());
        dbManager.getJdbi().useHandle(h -> h.execute("DELETE FROM indexing_events"));
    }

    @Test
    void claimsAndProcessesPendingEvent() throws Exception {
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha1", "sha2");

        var processed = new ArrayList<EventQueuePoller.ProcessableEvent>();
        var latch = new CountDownLatch(1);

        var poller = new EventQueuePoller(eventDao, event -> {
            processed.add(event);
            latch.countDown();
        }, 100);

        var thread = new Thread(poller);
        thread.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        thread.join(2000);

        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).repoName()).isEqualTo("repo-a");
        assertThat(processed.get(0).previousSha()).isEqualTo("sha1");
        assertThat(processed.get(0).currentSha()).isEqualTo("sha2");
        assertThat(eventDao.countByStatus("completed")).isEqualTo(1);
    }

    @Test
    void deduplicatesMultipleEventsForSameRepo() throws Exception {
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha1", "sha2");
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha2", "sha3");
        eventDao.insert("repo-a", "/repos/repo-a", "post-commit", "sha3", "sha4");

        var processed = new ArrayList<EventQueuePoller.ProcessableEvent>();
        var latch = new CountDownLatch(1);

        var poller = new EventQueuePoller(eventDao, event -> {
            processed.add(event);
            latch.countDown();
        }, 100);

        var thread = new Thread(poller);
        thread.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        poller.stop();
        thread.join(2000);

        // Should collapse to a single processing call: sha1 -> sha4
        assertThat(processed).hasSize(1);
        assertThat(processed.get(0).previousSha()).isEqualTo("sha1");
        assertThat(processed.get(0).currentSha()).isEqualTo("sha4");
        // All events should be marked completed
        assertThat(eventDao.countByStatus("completed")).isEqualTo(3);
    }
}
```

- [ ] **Step 7: Run poller tests**

Run: `./gradlew test --tests "com.indexer.queue.*" -i`
Expected: PASS (all tests)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/indexer/queue/ src/test/java/com/indexer/queue/
git commit -m "feat: add event queue poller with SKIP LOCKED and deduplication"
```

---

## Phase 4: Indexing Pipeline

### Task 10: Tree-sitter Integration (Java Language)

**Files:**
- Create: `src/main/java/com/indexer/indexing/TreeSitterParser.java`
- Create: `src/main/java/com/indexer/indexing/SymbolExtractor.java`
- Create: `src/main/java/com/indexer/indexing/ExtractedSymbol.java`
- Create: `src/main/resources/queries/java.scm`
- Create: `src/test/java/com/indexer/indexing/SymbolExtractorTest.java`
- Create: `src/test/resources/test-repos/java-sample/Calculator.java`

- [ ] **Step 1: Create test sample file**

Create `src/test/resources/test-repos/java-sample/Calculator.java`:

```java
package com.example;

import java.util.List;
import java.util.Optional;

public interface MathOperation {
    double execute(double a, double b);
}

public class Calculator implements MathOperation {
    private static final double PI = 3.14159;
    private final List<String> history;

    public Calculator() {
        this.history = new java.util.ArrayList<>();
    }

    @Override
    public double execute(double a, double b) {
        return add(a, b);
    }

    public double add(double a, double b) {
        double result = a + b;
        history.add("add: " + result);
        return result;
    }

    private double subtract(double a, double b) {
        return a - b;
    }

    protected static double multiply(double a, double b) {
        return a * b;
    }

    public Optional<String> getLastOperation() {
        if (history.isEmpty()) return Optional.empty();
        return Optional.of(history.get(history.size() - 1));
    }
}
```

- [ ] **Step 2: Write the symbol extractor test**

```java
package com.indexer.indexing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolExtractorTest {

    private final SymbolExtractor extractor = new SymbolExtractor();

    @Test
    void extractsJavaClassesAndInterfaces() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");

        // Should find the interface
        assertThat(symbols).anyMatch(s ->
                s.name().equals("MathOperation") && s.kind().equals("interface"));

        // Should find the class
        assertThat(symbols).anyMatch(s ->
                s.name().equals("Calculator") && s.kind().equals("class"));
    }

    @Test
    void extractsJavaMethods() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");

        // Should find methods with visibility
        assertThat(symbols).anyMatch(s ->
                s.name().equals("add") && s.kind().equals("method")
                        && s.visibility().equals("public"));

        assertThat(symbols).anyMatch(s ->
                s.name().equals("subtract") && s.kind().equals("method")
                        && s.visibility().equals("private"));

        assertThat(symbols).anyMatch(s ->
                s.name().equals("multiply") && s.kind().equals("method")
                        && s.isStatic());
    }

    @Test
    void extractsJavaImports() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");

        var imports = symbols.stream()
                .filter(s -> s.kind().equals("import"))
                .toList();

        assertThat(imports).anyMatch(s -> s.name().equals("java.util.List"));
        assertThat(imports).anyMatch(s -> s.name().equals("java.util.Optional"));
    }

    @Test
    void extractsTypeRelationships() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");

        var calculator = symbols.stream()
                .filter(s -> s.name().equals("Calculator") && s.kind().equals("class"))
                .findFirst().orElseThrow();

        assertThat(calculator.relationships()).anyMatch(r ->
                r.relatedName().equals("MathOperation") && r.kind().equals("implements"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.indexing.SymbolExtractorTest" -i`
Expected: FAIL — classes don't exist

- [ ] **Step 4: Create ExtractedSymbol**

```java
package com.indexer.indexing;

import java.util.List;

public record ExtractedSymbol(
        String name,
        String kind,         // class, interface, method, function, enum, type_alias, import, field
        String signature,
        int startLine,
        int endLine,
        String parentName,   // null for top-level, class name for methods
        String visibility,   // public, private, protected, package
        boolean isStatic,
        List<Relationship> relationships
) {
    public record Relationship(String relatedName, String kind) {} // implements, extends

    public static ExtractedSymbol importSymbol(String importPath, int line) {
        return new ExtractedSymbol(importPath, "import", importPath, line, line, null, null, false, List.of());
    }
}
```

- [ ] **Step 5: Create SymbolExtractor (regex-based initial implementation)**

Note: A full Tree-sitter JNR-FFI integration requires native library compilation. For the initial implementation, we use a regex-based parser that extracts the same information. Tree-sitter integration will be added as an enhancement once the native build pipeline is set up. The `SymbolExtractor` interface remains the same regardless of the underlying parser.

```java
package com.indexer.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SymbolExtractor {
    private static final Logger log = LoggerFactory.getLogger(SymbolExtractor.class);

    public List<ExtractedSymbol> extract(String source, String language) {
        return switch (language) {
            case "java" -> extractJava(source);
            case "python" -> extractPython(source);
            case "typescript", "javascript" -> extractTypeScript(source);
            case "go" -> extractGo(source);
            default -> List.of();
        };
    }

    private List<ExtractedSymbol> extractJava(String source) {
        var symbols = new ArrayList<ExtractedSymbol>();
        String[] lines = source.split("\n");

        // Extract imports
        Pattern importPattern = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+);");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = importPattern.matcher(lines[i].trim());
            if (m.find()) {
                symbols.add(ExtractedSymbol.importSymbol(m.group(2), i + 1));
            }
        }

        // Extract classes and interfaces
        Pattern classPattern = Pattern.compile(
                "^(\\s*)(public|private|protected)?\\s*(static)?\\s*(abstract)?\\s*(class|interface|enum|record)\\s+(\\w+)" +
                "(?:<[^>]*>)?(?:\\s+extends\\s+([\\w<>,\\s]+))?(?:\\s+implements\\s+([\\w<>,\\s]+))?");
        String currentClass = null;
        int classStartLine = -1;

        for (int i = 0; i < lines.length; i++) {
            Matcher m = classPattern.matcher(lines[i]);
            if (m.find()) {
                String visibility = m.group(2) != null ? m.group(2) : "package";
                boolean isStatic = m.group(3) != null;
                String kind = m.group(5);
                String name = m.group(6);
                String extendsClause = m.group(7);
                String implementsClause = m.group(8);

                var relationships = new ArrayList<ExtractedSymbol.Relationship>();
                if (extendsClause != null) {
                    for (String parent : extendsClause.split(",")) {
                        String trimmed = parent.trim().replaceAll("<.*>", "");
                        if (!trimmed.isEmpty()) {
                            relationships.add(new ExtractedSymbol.Relationship(trimmed, "extends"));
                        }
                    }
                }
                if (implementsClause != null) {
                    for (String iface : implementsClause.split(",")) {
                        String trimmed = iface.trim().replaceAll("<.*>", "");
                        if (!trimmed.isEmpty()) {
                            relationships.add(new ExtractedSymbol.Relationship(trimmed, "implements"));
                        }
                    }
                }

                int endLine = findBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, kind, lines[i].trim(),
                        i + 1, endLine + 1, null, visibility, isStatic, relationships));

                currentClass = name;
                classStartLine = i;
            }
        }

        // Extract methods
        Pattern methodPattern = Pattern.compile(
                "^\\s*(public|private|protected)?\\s*(static)?\\s*(?:abstract\\s+)?(?:synchronized\\s+)?" +
                "(?:<[^>]*>\\s+)?([\\w<>\\[\\],\\s?]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // Skip class/interface/enum declarations
            if (line.matches(".*\\b(class|interface|enum|record)\\b.*")) continue;
            // Skip import/package
            if (line.startsWith("import ") || line.startsWith("package ")) continue;

            Matcher m = methodPattern.matcher(lines[i]);
            if (m.find()) {
                String visibility = m.group(1) != null ? m.group(1) : "package";
                boolean isStatic = m.group(2) != null;
                String returnType = m.group(3).trim();
                String name = m.group(4);
                String params = m.group(5);

                // Skip constructors detected as methods if return type equals class name
                if (returnType.equals(currentClass)) continue;

                String signature = String.format("%s %s(%s)", returnType, name, params);
                int endLine = findBlockEnd(lines, i);

                symbols.add(new ExtractedSymbol(name, "method", signature,
                        i + 1, endLine + 1, currentClass, visibility, isStatic, List.of()));
            }
        }

        return symbols;
    }

    private List<ExtractedSymbol> extractPython(String source) {
        var symbols = new ArrayList<ExtractedSymbol>();
        String[] lines = source.split("\n");

        Pattern importPattern = Pattern.compile("^(?:from\\s+(\\S+)\\s+)?import\\s+(.+)");
        Pattern classPattern = Pattern.compile("^class\\s+(\\w+)(?:\\(([^)]*)\\))?:");
        Pattern funcPattern = Pattern.compile("^(\\s*)def\\s+(\\w+)\\s*\\(([^)]*)\\)(?:\\s*->\\s*(.+))?:");

        String currentClass = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            Matcher importMatch = importPattern.matcher(trimmed);
            if (importMatch.find()) {
                String from = importMatch.group(1);
                String imported = importMatch.group(2);
                String path = from != null ? from + "." + imported : imported;
                symbols.add(ExtractedSymbol.importSymbol(path.trim(), i + 1));
                continue;
            }

            Matcher classMatch = classPattern.matcher(trimmed);
            if (classMatch.find()) {
                String name = classMatch.group(1);
                String bases = classMatch.group(2);
                var relationships = new ArrayList<ExtractedSymbol.Relationship>();
                if (bases != null) {
                    for (String base : bases.split(",")) {
                        String trimmedBase = base.trim();
                        if (!trimmedBase.isEmpty()) {
                            relationships.add(new ExtractedSymbol.Relationship(trimmedBase, "extends"));
                        }
                    }
                }
                int endLine = findPythonBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, "class", trimmed,
                        i + 1, endLine + 1, null, "public", false, relationships));
                currentClass = name;
                continue;
            }

            Matcher funcMatch = funcPattern.matcher(line);
            if (funcMatch.find()) {
                String indent = funcMatch.group(1);
                String name = funcMatch.group(2);
                String params = funcMatch.group(3);
                String returnType = funcMatch.group(4);

                boolean isMethod = indent.length() > 0 && currentClass != null;
                String visibility = name.startsWith("__") && !name.endsWith("__") ? "private"
                        : name.startsWith("_") ? "protected" : "public";
                boolean isStatic = i > 0 && lines[i - 1].trim().equals("@staticmethod");

                String signature = returnType != null
                        ? String.format("def %s(%s) -> %s", name, params, returnType)
                        : String.format("def %s(%s)", name, params);

                int endLine = findPythonBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, isMethod ? "method" : "function", signature,
                        i + 1, endLine + 1, isMethod ? currentClass : null, visibility, isStatic, List.of()));
            }
        }

        return symbols;
    }

    private List<ExtractedSymbol> extractTypeScript(String source) {
        var symbols = new ArrayList<ExtractedSymbol>();
        String[] lines = source.split("\n");

        Pattern importPattern = Pattern.compile("^import\\s+.*from\\s+['\"]([^'\"]+)['\"]");
        Pattern classPattern = Pattern.compile("^\\s*(export\\s+)?(abstract\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+([\\w,\\s]+))?");
        Pattern interfacePattern = Pattern.compile("^\\s*(export\\s+)?interface\\s+(\\w+)(?:\\s+extends\\s+([\\w,\\s]+))?");
        Pattern funcPattern = Pattern.compile("^\\s*(export\\s+)?(async\\s+)?function\\s+(\\w+)\\s*(?:<[^>]*>)?\\s*\\(([^)]*)\\)(?:\\s*:\\s*(.+))?");
        Pattern methodPattern = Pattern.compile("^\\s*(public|private|protected)?\\s*(static)?\\s*(async\\s+)?(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*([^{]+))?\\s*\\{");

        String currentClass = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            Matcher importMatch = importPattern.matcher(trimmed);
            if (importMatch.find()) {
                symbols.add(ExtractedSymbol.importSymbol(importMatch.group(1), i + 1));
                continue;
            }

            Matcher classMatch = classPattern.matcher(line);
            if (classMatch.find()) {
                String name = classMatch.group(3);
                var relationships = new ArrayList<ExtractedSymbol.Relationship>();
                if (classMatch.group(4) != null) {
                    relationships.add(new ExtractedSymbol.Relationship(classMatch.group(4).trim(), "extends"));
                }
                if (classMatch.group(5) != null) {
                    for (String iface : classMatch.group(5).split(",")) {
                        relationships.add(new ExtractedSymbol.Relationship(iface.trim(), "implements"));
                    }
                }
                int endLine = findBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, "class", trimmed,
                        i + 1, endLine + 1, null, "public", false, relationships));
                currentClass = name;
                continue;
            }

            Matcher ifaceMatch = interfacePattern.matcher(line);
            if (ifaceMatch.find()) {
                String name = ifaceMatch.group(2);
                var relationships = new ArrayList<ExtractedSymbol.Relationship>();
                if (ifaceMatch.group(3) != null) {
                    for (String parent : ifaceMatch.group(3).split(",")) {
                        relationships.add(new ExtractedSymbol.Relationship(parent.trim(), "extends"));
                    }
                }
                int endLine = findBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, "interface", trimmed,
                        i + 1, endLine + 1, null, "public", false, relationships));
                continue;
            }

            Matcher funcMatch = funcPattern.matcher(line);
            if (funcMatch.find()) {
                String name = funcMatch.group(3);
                String params = funcMatch.group(4);
                String returnType = funcMatch.group(5);
                String sig = returnType != null
                        ? String.format("function %s(%s): %s", name, params, returnType.trim())
                        : String.format("function %s(%s)", name, params);
                int endLine = findBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, "function", sig,
                        i + 1, endLine + 1, null, "public", false, List.of()));
                continue;
            }

            if (currentClass != null) {
                Matcher methMatch = methodPattern.matcher(line);
                if (methMatch.find()) {
                    String visibility = methMatch.group(1) != null ? methMatch.group(1) : "public";
                    boolean isStatic = methMatch.group(2) != null;
                    String name = methMatch.group(4);
                    String params = methMatch.group(5);
                    String returnType = methMatch.group(6);
                    String sig = returnType != null
                            ? String.format("%s(%s): %s", name, params, returnType.trim())
                            : String.format("%s(%s)", name, params);
                    int endLine = findBlockEnd(lines, i);
                    symbols.add(new ExtractedSymbol(name, "method", sig,
                            i + 1, endLine + 1, currentClass, visibility, isStatic, List.of()));
                }
            }
        }

        return symbols;
    }

    private List<ExtractedSymbol> extractGo(String source) {
        var symbols = new ArrayList<ExtractedSymbol>();
        String[] lines = source.split("\n");

        Pattern importPattern = Pattern.compile("^\\s*\"([^\"]+)\"");
        Pattern funcPattern = Pattern.compile("^func\\s+(?:\\(\\w+\\s+\\*?(\\w+)\\)\\s+)?(\\w+)\\s*\\(([^)]*)\\)\\s*(?:([^{]+))?");
        Pattern typePattern = Pattern.compile("^type\\s+(\\w+)\\s+(struct|interface)");

        boolean inImportBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.equals("import (")) { inImportBlock = true; continue; }
            if (inImportBlock && trimmed.equals(")")) { inImportBlock = false; continue; }
            if (inImportBlock) {
                Matcher m = importPattern.matcher(trimmed);
                if (m.find()) symbols.add(ExtractedSymbol.importSymbol(m.group(1), i + 1));
                continue;
            }
            if (trimmed.startsWith("import \"")) {
                Matcher m = Pattern.compile("import\\s+\"([^\"]+)\"").matcher(trimmed);
                if (m.find()) symbols.add(ExtractedSymbol.importSymbol(m.group(1), i + 1));
                continue;
            }

            Matcher typeMatch = typePattern.matcher(trimmed);
            if (typeMatch.find()) {
                String name = typeMatch.group(1);
                String kind = typeMatch.group(2).equals("interface") ? "interface" : "class";
                int endLine = findBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, kind, trimmed,
                        i + 1, endLine + 1, null, isExported(name) ? "public" : "package", false, List.of()));
                continue;
            }

            Matcher funcMatch = funcPattern.matcher(line);
            if (funcMatch.find()) {
                String receiver = funcMatch.group(1);
                String name = funcMatch.group(2);
                String params = funcMatch.group(3);
                String returnType = funcMatch.group(4);

                String kind = receiver != null ? "method" : "function";
                String sig = returnType != null
                        ? String.format("func %s(%s) %s", name, params, returnType.trim())
                        : String.format("func %s(%s)", name, params);
                int endLine = findBlockEnd(lines, i);
                symbols.add(new ExtractedSymbol(name, kind, sig,
                        i + 1, endLine + 1, receiver, isExported(name) ? "public" : "package", false, List.of()));
            }
        }

        return symbols;
    }

    private boolean isExported(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private int findBlockEnd(String[] lines, int startLine) {
        int braceCount = 0;
        for (int i = startLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') braceCount++;
                if (c == '}') braceCount--;
                if (braceCount == 0 && i > startLine) return i;
            }
        }
        return Math.min(startLine + 1, lines.length - 1);
    }

    private int findPythonBlockEnd(String[] lines, int startLine) {
        if (startLine >= lines.length - 1) return startLine;
        String startIndent = lines[startLine].replaceAll("\\S.*", "");
        for (int i = startLine + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) continue;
            String indent = line.replaceAll("\\S.*", "");
            if (indent.length() <= startIndent.length()) return i - 1;
        }
        return lines.length - 1;
    }
}
```

- [ ] **Step 6: Run symbol extractor tests**

Run: `./gradlew test --tests "com.indexer.indexing.SymbolExtractorTest" -i`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/indexer/indexing/ src/test/java/com/indexer/indexing/ \
  src/test/resources/test-repos/
git commit -m "feat: add SymbolExtractor with Java/Python/TS/Go regex-based parsing"
```

---

### Task 11: Indexing Pipeline Orchestration

**Files:**
- Create: `src/main/java/com/indexer/indexing/IndexingPipeline.java`
- Create: `src/main/java/com/indexer/indexing/FileIndexer.java`
- Create: `src/test/java/com/indexer/indexing/IndexingPipelineIntegrationTest.java`

- [ ] **Step 1: Write the pipeline integration test**

```java
package com.indexer.indexing;

import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.repository.GitOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class IndexingPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private DatabaseManager dbManager;
    private RepositoryDao repositoryDao;
    private FileDao fileDao;
    private SymbolDao symbolDao;
    private IndexingPipeline pipeline;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        repositoryDao = new RepositoryDao(dbManager.getJdbi());
        fileDao = new FileDao(dbManager.getJdbi());
        symbolDao = new SymbolDao(dbManager.getJdbi());
        var languageRegistry = new LanguageRegistry(Map.of());
        var symbolExtractor = new SymbolExtractor();
        var fileIndexer = new FileIndexer(fileDao, symbolDao, dbManager.getJdbi(), languageRegistry, symbolExtractor, 1_048_576);
        pipeline = new IndexingPipeline(repositoryDao, fileIndexer, new GitOperations());

        // Clean state
        dbManager.getJdbi().useHandle(h -> {
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM repositories");
        });
    }

    @Test
    void indexesNewFilesOnFullIndex() throws Exception {
        // Create a test git repo
        Path repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir);
        new ProcessBuilder("git", "init").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.email", "t@t.com").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.name", "T").directory(repoDir.toFile()).start().waitFor();

        Files.writeString(repoDir.resolve("App.java"), """
                package com.example;
                public class App {
                    public void run() {}
                }
                """);
        Files.writeString(repoDir.resolve("utils.py"), """
                def helper(x):
                    return x + 1
                """);
        new ProcessBuilder("git", "add", ".").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "init").directory(repoDir.toFile()).start().waitFor();

        // Register repo in DB
        var repo = new com.indexer.model.Repository(0, "test-repo", "file://" + repoDir,
                "main", repoDir.toString(), "ssh-key", null, null);
        int repoId = repositoryDao.insert(repo);

        // Full index
        pipeline.fullIndex(repoId, repoDir);

        // Verify files were indexed
        assertThat(fileDao.countByRepo(repoId)).isEqualTo(2);

        // Verify symbols were extracted
        var javaFile = fileDao.findByRepoAndPath(repoId, "App.java").orElseThrow();
        var javaSymbols = symbolDao.findByFileId(javaFile.id());
        assertThat(javaSymbols).anyMatch(s -> s.name().equals("App") && s.kind().equals("class"));
        assertThat(javaSymbols).anyMatch(s -> s.name().equals("run") && s.kind().equals("method"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.indexing.IndexingPipelineIntegrationTest" -i`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create FileIndexer**

```java
package com.indexer.indexing;

import com.indexer.config.LanguageRegistry;
import com.indexer.db.FileDao;
import com.indexer.db.SymbolDao;
import com.indexer.model.Import;
import com.indexer.model.SourceFile;
import com.indexer.model.Symbol;
import com.indexer.model.TypeRelationship;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class FileIndexer {
    private static final Logger log = LoggerFactory.getLogger(FileIndexer.class);

    private final FileDao fileDao;
    private final SymbolDao symbolDao;
    private final Jdbi jdbi;
    private final LanguageRegistry languageRegistry;
    private final SymbolExtractor symbolExtractor;
    private final long maxFileSizeBytes;

    public FileIndexer(FileDao fileDao, SymbolDao symbolDao, Jdbi jdbi,
                       LanguageRegistry languageRegistry, SymbolExtractor symbolExtractor,
                       long maxFileSizeBytes) {
        this.fileDao = fileDao;
        this.symbolDao = symbolDao;
        this.jdbi = jdbi;
        this.languageRegistry = languageRegistry;
        this.symbolExtractor = symbolExtractor;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public void indexFile(int repoId, Path repoRoot, String relativePath, String commitSha) {
        Path filePath = repoRoot.resolve(relativePath);
        String filename = filePath.getFileName().toString();

        if (languageRegistry.isBinary(filename)) {
            indexMetadataOnly(repoId, relativePath, filePath, commitSha);
            return;
        }

        long fileSize;
        try {
            fileSize = Files.size(filePath);
        } catch (IOException e) {
            log.warn("Cannot read file size for {}: {}", relativePath, e.getMessage());
            return;
        }

        if (fileSize > maxFileSizeBytes) {
            indexMetadataOnly(repoId, relativePath, filePath, commitSha);
            return;
        }

        String language = languageRegistry.detectLanguage(filename);
        String content;
        try {
            content = Files.readString(filePath);
        } catch (IOException e) {
            log.warn("Cannot read file {}: {}", relativePath, e.getMessage());
            return;
        }

        // Upsert file record
        var sourceFile = new SourceFile(0, repoId, relativePath, language,
                (int) fileSize, commitSha, Instant.now());
        int fileId = fileDao.upsert(sourceFile);

        // Clear existing symbols/imports for this file (re-index)
        symbolDao.deleteSymbolsByFileId(fileId);
        symbolDao.deleteImportsByFileId(fileId);

        // Full-text index
        indexContent(fileId, content);

        // Structural index for core languages
        if (languageRegistry.isCoreLanguage(language)) {
            indexSymbols(fileId, content, language);
        }
    }

    public void removeFile(int repoId, String relativePath) {
        fileDao.deleteByRepoAndPath(repoId, relativePath);
    }

    private void indexMetadataOnly(int repoId, String relativePath, Path filePath, String commitSha) {
        String language = languageRegistry.detectLanguage(filePath.getFileName().toString());
        long size;
        try {
            size = Files.size(filePath);
        } catch (IOException e) {
            size = 0;
        }
        var sourceFile = new SourceFile(0, repoId, relativePath, language,
                (int) size, commitSha, Instant.now());
        fileDao.upsert(sourceFile);
    }

    private void indexContent(int fileId, String content) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                    INSERT INTO file_contents (file_id, content)
                    VALUES (:fileId, :content)
                    ON CONFLICT (file_id) DO UPDATE SET content = EXCLUDED.content
                    """)
                        .bind("fileId", fileId)
                        .bind("content", content)
                        .execute()
        );
    }

    private void indexSymbols(int fileId, String content, String language) {
        var extracted = symbolExtractor.extract(content, language);

        // Track parent symbols for relationship mapping
        Map<String, Integer> symbolNameToId = new HashMap<>();

        for (var sym : extracted) {
            if (sym.kind().equals("import")) {
                symbolDao.insertImport(new Import(0, fileId, sym.name(), null));
                continue;
            }

            Integer parentId = sym.parentName() != null ? symbolNameToId.get(sym.parentName()) : null;

            var symbol = new Symbol(0, fileId, sym.name(), sym.kind(), sym.signature(),
                    sym.startLine(), sym.endLine(), parentId,
                    sym.visibility(), sym.isStatic());
            int symbolId = symbolDao.insertSymbol(symbol);
            symbolNameToId.put(sym.name(), symbolId);

            // Insert type relationships
            for (var rel : sym.relationships()) {
                symbolDao.insertTypeRelationship(
                        new TypeRelationship(0, symbolId, rel.relatedName(), rel.kind()));
            }
        }
    }
}
```

- [ ] **Step 4: Create IndexingPipeline**

```java
package com.indexer.indexing;

import com.indexer.db.RepositoryDao;
import com.indexer.repository.GitOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public class IndexingPipeline {
    private static final Logger log = LoggerFactory.getLogger(IndexingPipeline.class);

    private final RepositoryDao repositoryDao;
    private final FileIndexer fileIndexer;
    private final GitOperations gitOps;

    public IndexingPipeline(RepositoryDao repositoryDao, FileIndexer fileIndexer, GitOperations gitOps) {
        this.repositoryDao = repositoryDao;
        this.fileIndexer = fileIndexer;
        this.gitOps = gitOps;
    }

    public void fullIndex(int repoId, Path repoDir) throws IOException {
        log.info("Starting full index for repo {} at {}", repoId, repoDir);
        String currentSha = gitOps.getCurrentSha(repoDir);
        List<String> allFiles = gitOps.listAllFiles(repoDir);

        int indexed = 0;
        for (String file : allFiles) {
            try {
                fileIndexer.indexFile(repoId, repoDir, file, currentSha);
                indexed++;
            } catch (Exception e) {
                log.warn("Failed to index file {}: {}", file, e.getMessage());
            }
        }

        repositoryDao.updateLastIndexed(repoId, currentSha, Instant.now());
        log.info("Full index complete for repo {}: {}/{} files indexed", repoId, indexed, allFiles.size());
    }

    public void incrementalIndex(int repoId, Path repoDir, String fromSha, String toSha) throws IOException {
        log.info("Incremental index for repo {}: {} -> {}", repoId, fromSha, toSha);

        List<GitOperations.DiffEntry> diff;
        if (fromSha == null || fromSha.isBlank()) {
            // No previous SHA — treat as full index
            fullIndex(repoId, repoDir);
            return;
        }

        diff = gitOps.diff(repoDir, fromSha, toSha);
        log.info("Found {} changed files", diff.size());

        for (var entry : diff) {
            try {
                switch (entry.type()) {
                    case DELETED -> fileIndexer.removeFile(repoId, entry.path());
                    case ADDED, MODIFIED -> fileIndexer.indexFile(repoId, repoDir, entry.path(), toSha);
                }
            } catch (Exception e) {
                log.warn("Failed to process {} ({}): {}", entry.path(), entry.type(), e.getMessage());
            }
        }

        repositoryDao.updateLastIndexed(repoId, toSha, Instant.now());
        log.info("Incremental index complete for repo {}", repoId);
    }
}
```

- [ ] **Step 5: Run pipeline integration test**

Run: `./gradlew test --tests "com.indexer.indexing.IndexingPipelineIntegrationTest" -i`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/indexing/FileIndexer.java \
  src/main/java/com/indexer/indexing/IndexingPipeline.java \
  src/test/java/com/indexer/indexing/IndexingPipelineIntegrationTest.java
git commit -m "feat: add IndexingPipeline with full and incremental file indexing"
```

---

## Phase 5: MCP Server & Tools

### Task 12: MCP Server Core with Tool Dispatch

**Files:**
- Create: `src/main/java/com/indexer/mcp/McpServerBootstrap.java`
- Create: `src/main/java/com/indexer/mcp/tools/SearchSymbolsTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/GetSymbolDetailTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/FindImplementationsTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/FindReferencesTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/SearchCodeTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/SearchFilesTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/GetRepoSummaryTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/GetFileSummaryTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/GetDirectoryTreeTool.java`
- Create: `src/main/java/com/indexer/mcp/tools/GetIndexHealthTool.java`
- Create: `src/main/java/com/indexer/mcp/QueryExecutor.java`
- Create: `src/test/java/com/indexer/mcp/tools/SearchSymbolsToolTest.java`

This task integrates with the MCP Java SDK. The SDK handles protocol serialization — we register tool handlers.

- [ ] **Step 1: Write SearchSymbols tool test**

```java
package com.indexer.mcp.tools;

import com.indexer.db.*;
import com.indexer.mcp.QueryExecutor;
import com.indexer.model.Repository;
import com.indexer.model.SourceFile;
import com.indexer.model.Symbol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class SearchSymbolsToolTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    private QueryExecutor queryExecutor;

    @BeforeEach
    void setUp() {
        var dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        var jdbi = dbManager.getJdbi();

        // Clean and seed data
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM repositories");
        });

        var repoDao = new RepositoryDao(jdbi);
        int repoId = repoDao.insert(new Repository(0, "test-repo", "url", "main", "/path", "ssh-key", "abc", Instant.now()));

        var fileDao = new FileDao(jdbi);
        int fileId = fileDao.upsert(new SourceFile(0, repoId, "src/App.java", "java", 500, "abc", Instant.now()));

        var symbolDao = new SymbolDao(jdbi);
        symbolDao.insertSymbol(new Symbol(0, fileId, "App", "class", "public class App", 1, 20, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, fileId, "run", "method", "public void run()", 5, 10, null, "public", false));
        symbolDao.insertSymbol(new Symbol(0, fileId, "helper", "method", "private int helper()", 12, 18, null, "private", false));

        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    void searchesByName() {
        var results = queryExecutor.searchSymbols("App", null, null, null, 20);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("name")).isEqualTo("App");
        assertThat(results.get(0).get("kind")).isEqualTo("class");
    }

    @Test
    void searchesByKind() {
        var results = queryExecutor.searchSymbols(null, "method", null, null, 20);
        assertThat(results).hasSize(2);
    }

    @Test
    void searchesByNamePattern() {
        var results = queryExecutor.searchSymbols("run", null, null, null, 20);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("signature")).isEqualTo("public void run()");
    }

    @Test
    void respectsLimit() {
        var results = queryExecutor.searchSymbols(null, null, null, null, 1);
        assertThat(results).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.indexer.mcp.tools.SearchSymbolsToolTest" -i`
Expected: FAIL — classes don't exist

- [ ] **Step 3: Create QueryExecutor**

This is the core class that all MCP tools delegate to. It runs queries against the index database.

```java
package com.indexer.mcp;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class QueryExecutor {
    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);

    private final Jdbi jdbi;

    public QueryExecutor(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public List<Map<String, Object>> searchSymbols(String query, String kind, String language, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            var sql = new StringBuilder("""
                SELECT s.name, s.kind, s.signature, s.start_line, s.end_line, s.visibility,
                       f.path AS file_path, r.name AS repo_name
                FROM symbols s
                JOIN files f ON s.file_id = f.id
                JOIN repositories r ON f.repo_id = r.id
                WHERE 1=1
                """);
            var params = new LinkedHashMap<String, Object>();

            if (query != null && !query.isBlank()) {
                sql.append(" AND s.name ~* :query");
                params.put("query", query);
            }
            if (kind != null && !kind.isBlank()) {
                sql.append(" AND s.kind = :kind");
                params.put("kind", kind);
            }
            if (language != null && !language.isBlank()) {
                sql.append(" AND f.language = :language");
                params.put("language", language);
            }
            if (repo != null && !repo.isBlank()) {
                sql.append(" AND r.name = :repo");
                params.put("repo", repo);
            }
            sql.append(" ORDER BY s.name LIMIT :limit");
            params.put("limit", limit);

            var q = handle.createQuery(sql.toString());
            params.forEach(q::bind);

            return q.mapToMap().list();
        });
    }

    public Map<String, Object> getSymbolDetail(String repo, String filePath, String symbolName, Integer line) {
        return jdbi.withHandle(handle -> {
            var sql = new StringBuilder("""
                SELECT s.name, s.kind, s.signature, s.start_line, s.end_line, s.visibility, s.is_static,
                       f.path AS file_path, r.name AS repo_name, r.clone_path
                FROM symbols s
                JOIN files f ON s.file_id = f.id
                JOIN repositories r ON f.repo_id = r.id
                WHERE r.name = :repo AND f.path = :filePath
                """);

            if (symbolName != null) {
                sql.append(" AND s.name = :symbolName");
            }
            if (line != null) {
                sql.append(" AND s.start_line <= :line AND s.end_line >= :line");
            }
            sql.append(" LIMIT 1");

            var q = handle.createQuery(sql.toString())
                    .bind("repo", repo)
                    .bind("filePath", filePath);
            if (symbolName != null) q.bind("symbolName", symbolName);
            if (line != null) q.bind("line", line);

            var result = q.mapToMap().findOne().orElse(null);
            if (result == null) return Map.of("error", (Object) "Symbol not found");

            // Fetch source code for the symbol
            int startLine = ((Number) result.get("start_line")).intValue();
            int endLine = ((Number) result.get("end_line")).intValue();
            String clonePath = (String) result.get("clone_path");

            try {
                var fullPath = java.nio.file.Path.of(clonePath).resolve(filePath);
                var lines = java.nio.file.Files.readAllLines(fullPath);
                var source = String.join("\n", lines.subList(
                        Math.max(0, startLine - 1),
                        Math.min(lines.size(), endLine)));
                result.put("source", source);
            } catch (Exception e) {
                result.put("source", "Unable to read source: " + e.getMessage());
            }

            // Fetch children (methods of class)
            var children = handle.createQuery("""
                SELECT name, kind, signature, start_line FROM symbols
                WHERE parent_id = (SELECT id FROM symbols WHERE file_id = (
                    SELECT f.id FROM files f JOIN repositories r ON f.repo_id = r.id
                    WHERE r.name = :repo AND f.path = :filePath
                ) AND name = :symbolName LIMIT 1)
                ORDER BY start_line
                """)
                    .bind("repo", repo)
                    .bind("filePath", filePath)
                    .bind("symbolName", result.get("name"))
                    .mapToMap().list();
            result.put("children", children);

            // Fetch relationships
            var relationships = handle.createQuery("""
                SELECT tr.related_name, tr.kind FROM type_relationships tr
                JOIN symbols s ON tr.symbol_id = s.id
                JOIN files f ON s.file_id = f.id
                JOIN repositories r ON f.repo_id = r.id
                WHERE r.name = :repo AND f.path = :filePath AND s.name = :symbolName
                """)
                    .bind("repo", repo)
                    .bind("filePath", filePath)
                    .bind("symbolName", result.get("name"))
                    .mapToMap().list();
            result.put("relationships", relationships);

            return result;
        });
    }

    public List<Map<String, Object>> findImplementations(String typeName, String repo) {
        return jdbi.withHandle(handle -> {
            var sql = new StringBuilder("""
                SELECT s.name AS class_name, s.signature, f.path AS file_path, r.name AS repo_name
                FROM type_relationships tr
                JOIN symbols s ON tr.symbol_id = s.id
                JOIN files f ON s.file_id = f.id
                JOIN repositories r ON f.repo_id = r.id
                WHERE tr.related_name = :typeName AND tr.kind = 'implements'
                """);
            if (repo != null) sql.append(" AND r.name = :repo");
            sql.append(" ORDER BY s.name");

            var q = handle.createQuery(sql.toString()).bind("typeName", typeName);
            if (repo != null) q.bind("repo", repo);
            return q.mapToMap().list();
        });
    }

    public List<Map<String, Object>> findReferences(String symbolName, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            var sql = new StringBuilder("""
                SELECT f.path AS file_path, r.name AS repo_name, i.import_path
                FROM imports i
                JOIN files f ON i.file_id = f.id
                JOIN repositories r ON f.repo_id = r.id
                WHERE i.import_path LIKE :pattern
                """);
            if (repo != null) sql.append(" AND r.name = :repo");
            sql.append(" ORDER BY f.path LIMIT :limit");

            var q = handle.createQuery(sql.toString())
                    .bind("pattern", "%" + symbolName + "%")
                    .bind("limit", limit);
            if (repo != null) q.bind("repo", repo);
            return q.mapToMap().list();
        });
    }

    public List<Map<String, Object>> searchCode(String query, String language, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            var sql = new StringBuilder("""
                SELECT f.path AS file_path, r.name AS repo_name,
                       ts_headline('english', fc.content, plainto_tsquery('english', :query),
                           'MaxFragments=3, MaxWords=30, MinWords=10') AS matching_lines
                FROM file_contents fc
                JOIN files f ON fc.file_id = f.id
                JOIN repositories r ON f.repo_id = r.id
                WHERE fc.search_vector @@ plainto_tsquery('english', :query)
                """);
            if (language != null) sql.append(" AND f.language = :language");
            if (repo != null) sql.append(" AND r.name = :repo");
            sql.append(" ORDER BY ts_rank(fc.search_vector, plainto_tsquery('english', :query)) DESC");
            sql.append(" LIMIT :limit");

            var q = handle.createQuery(sql.toString()).bind("query", query).bind("limit", limit);
            if (language != null) q.bind("language", language);
            if (repo != null) q.bind("repo", repo);
            return q.mapToMap().list();
        });
    }

    public List<Map<String, Object>> searchFiles(String pattern, String language, String repo, int limit) {
        return jdbi.withHandle(handle -> {
            var sql = new StringBuilder("""
                SELECT f.path, r.name AS repo_name, f.language, f.size_bytes, f.last_modified_at
                FROM files f
                JOIN repositories r ON f.repo_id = r.id
                WHERE f.path LIKE :pattern
                """);
            if (language != null) sql.append(" AND f.language = :language");
            if (repo != null) sql.append(" AND r.name = :repo");
            sql.append(" ORDER BY f.path LIMIT :limit");

            var q = handle.createQuery(sql.toString())
                    .bind("pattern", pattern.replace("*", "%"))
                    .bind("limit", limit);
            if (language != null) q.bind("language", language);
            if (repo != null) q.bind("repo", repo);
            return q.mapToMap().list();
        });
    }

    public Map<String, Object> getRepoSummary(String repoName) {
        return jdbi.withHandle(handle -> {
            var repo = handle.createQuery("SELECT * FROM repositories WHERE name = :name")
                    .bind("name", repoName)
                    .mapToMap().findOne().orElse(null);
            if (repo == null) return Map.of("error", (Object) "Repository not found: " + repoName);

            int repoId = ((Number) repo.get("id")).intValue();

            int fileCount = handle.createQuery("SELECT COUNT(*) FROM files WHERE repo_id = :id")
                    .bind("id", repoId).mapTo(Integer.class).one();

            var langBreakdown = handle.createQuery("""
                SELECT language, COUNT(*) as count FROM files
                WHERE repo_id = :id AND language IS NOT NULL
                GROUP BY language ORDER BY count DESC
                """)
                    .bind("id", repoId).mapToMap().list();

            var topDirs = handle.createQuery("""
                SELECT DISTINCT split_part(path, '/', 1) AS dir FROM files
                WHERE repo_id = :id AND path LIKE '%/%'
                ORDER BY dir LIMIT 20
                """)
                    .bind("id", repoId).mapTo(String.class).list();

            var result = new LinkedHashMap<String, Object>(repo);
            result.put("fileCount", fileCount);
            result.put("languageBreakdown", langBreakdown);
            result.put("topLevelDirectories", topDirs);
            return result;
        });
    }

    public Map<String, Object> getFileSummary(String repoName, String filePath) {
        return jdbi.withHandle(handle -> {
            var file = handle.createQuery("""
                SELECT f.*, r.name AS repo_name FROM files f
                JOIN repositories r ON f.repo_id = r.id
                WHERE r.name = :repo AND f.path = :path
                """)
                    .bind("repo", repoName)
                    .bind("path", filePath)
                    .mapToMap().findOne().orElse(null);
            if (file == null) return Map.of("error", (Object) "File not found");

            int fileId = ((Number) file.get("id")).intValue();

            var symbols = handle.createQuery("""
                SELECT name, kind, signature, start_line FROM symbols
                WHERE file_id = :fileId ORDER BY start_line
                """)
                    .bind("fileId", fileId).mapToMap().list();

            var imports = handle.createQuery("""
                SELECT import_path, alias FROM imports WHERE file_id = :fileId
                """)
                    .bind("fileId", fileId).mapToMap().list();

            var result = new LinkedHashMap<String, Object>(file);
            result.put("symbols", symbols);
            result.put("imports", imports);
            return result;
        });
    }

    public List<Map<String, Object>> getDirectoryTree(String repoName, String path, int depth) {
        return jdbi.withHandle(handle -> {
            String prefix = (path != null && !path.isBlank()) ? path + "/" : "";
            return handle.createQuery("""
                SELECT f.path, f.language FROM files f
                JOIN repositories r ON f.repo_id = r.id
                WHERE r.name = :repo AND f.path LIKE :prefix
                ORDER BY f.path
                """)
                    .bind("repo", repoName)
                    .bind("prefix", prefix + "%")
                    .mapToMap().list();
        });
        // Note: tree structure assembly from flat file list is done in the tool handler
    }

    public Map<String, Object> getIndexHealth() {
        return jdbi.withHandle(handle -> {
            var repos = handle.createQuery("""
                SELECT r.name, r.last_indexed_sha, r.last_indexed_at,
                    (SELECT COUNT(*) FROM indexing_events WHERE repo_name = r.name AND status = 'pending') AS pending_events,
                    (SELECT COUNT(*) FROM indexing_events WHERE repo_name = r.name AND status = 'failed') AS failed_events,
                    (SELECT error_message FROM indexing_events WHERE repo_name = r.name AND status = 'failed'
                     ORDER BY completed_at DESC LIMIT 1) AS last_error
                FROM repositories r ORDER BY r.name
                """).mapToMap().list();

            int totalPending = handle.createQuery("SELECT COUNT(*) FROM indexing_events WHERE status = 'pending'")
                    .mapTo(Integer.class).one();
            int totalFailed = handle.createQuery("SELECT COUNT(*) FROM indexing_events WHERE status = 'failed'")
                    .mapTo(Integer.class).one();

            var recentFailures = handle.createQuery("""
                SELECT repo_name, error_message, completed_at FROM indexing_events
                WHERE status = 'failed' ORDER BY completed_at DESC LIMIT 10
                """).mapToMap().list();

            var result = new LinkedHashMap<String, Object>();
            result.put("repositories", repos);
            result.put("totalPendingEvents", totalPending);
            result.put("totalFailedEvents", totalFailed);
            result.put("recentFailures", recentFailures);
            return result;
        });
    }
}
```

- [ ] **Step 4: Run search symbols test**

Run: `./gradlew test --tests "com.indexer.mcp.tools.SearchSymbolsToolTest" -i`
Expected: PASS

- [ ] **Step 5: Create McpServerBootstrap**

This class registers all tools with the MCP SDK and starts the server.

```java
package com.indexer.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class McpServerBootstrap {
    private static final Logger log = LoggerFactory.getLogger(McpServerBootstrap.class);

    private final QueryExecutor queryExecutor;
    private McpServer server;

    public McpServerBootstrap(QueryExecutor queryExecutor) {
        this.queryExecutor = queryExecutor;
    }

    public void startStdio() {
        var transport = new StdioServerTransport();
        var serverInfo = new McpSchema.Implementation("source-code-indexer", "0.1.0");

        server = McpServer.sync(transport)
                .serverInfo(serverInfo)
                .capabilities(McpServerFeatures.SyncToolSpecification.builder().build())
                .tools(buildToolSpecs())
                .build();

        log.info("MCP server started (stdio transport)");
    }

    private List<McpServerFeatures.SyncToolSpecification> buildToolSpecs() {
        return List.of(
                tool("search_symbols", "Find symbols by name, kind, or pattern. Returns signatures and locations without source code.",
                        Map.of("query", stringProp("Regex pattern to match symbol names"),
                                "kind", stringProp("Filter by kind: class, interface, method, function, enum"),
                                "language", stringProp("Filter by language"),
                                "repo", stringProp("Filter by repository name"),
                                "limit", intProp("Max results (default 20)")),
                        args -> toJson(queryExecutor.searchSymbols(
                                getString(args, "query"), getString(args, "kind"),
                                getString(args, "language"), getString(args, "repo"),
                                getInt(args, "limit", 20)))),

                tool("get_symbol_detail", "Get full detail for a specific symbol including its source code.",
                        Map.of("repo", stringProp("Repository name (required)"),
                                "filePath", stringProp("File path relative to repo root (required)"),
                                "symbolName", stringProp("Symbol name to find"),
                                "line", intProp("Line number within symbol")),
                        args -> toJson(queryExecutor.getSymbolDetail(
                                getString(args, "repo"), getString(args, "filePath"),
                                getString(args, "symbolName"), getInteger(args, "line")))),

                tool("find_implementations", "Find all classes that implement an interface or extend a class.",
                        Map.of("typeName", stringProp("Interface or class name (required)"),
                                "repo", stringProp("Filter by repository name")),
                        args -> toJson(queryExecutor.findImplementations(
                                getString(args, "typeName"), getString(args, "repo")))),

                tool("find_references", "Find files that import or reference a given symbol.",
                        Map.of("symbolName", stringProp("Symbol name to search for (required)"),
                                "repo", stringProp("Filter by repository name"),
                                "limit", intProp("Max results (default 20)")),
                        args -> toJson(queryExecutor.findReferences(
                                getString(args, "symbolName"), getString(args, "repo"),
                                getInt(args, "limit", 20)))),

                tool("search_code", "Full-text search across all indexed content. Returns matching lines with context.",
                        Map.of("query", stringProp("Search query (required)"),
                                "language", stringProp("Filter by language"),
                                "repo", stringProp("Filter by repository name"),
                                "limit", intProp("Max results (default 10)")),
                        args -> toJson(queryExecutor.searchCode(
                                getString(args, "query"), getString(args, "language"),
                                getString(args, "repo"), getInt(args, "limit", 10)))),

                tool("search_files", "Find files by path pattern or name.",
                        Map.of("pattern", stringProp("Glob pattern (required, e.g. '*.java', 'src/**/test*')"),
                                "language", stringProp("Filter by language"),
                                "repo", stringProp("Filter by repository name"),
                                "limit", intProp("Max results (default 20)")),
                        args -> toJson(queryExecutor.searchFiles(
                                getString(args, "pattern"), getString(args, "language"),
                                getString(args, "repo"), getInt(args, "limit", 20)))),

                tool("get_repo_summary", "High-level overview of a repository.",
                        Map.of("repo", stringProp("Repository name (required)")),
                        args -> toJson(queryExecutor.getRepoSummary(getString(args, "repo")))),

                tool("get_file_summary", "Summary of a file without returning its content.",
                        Map.of("repo", stringProp("Repository name (required)"),
                                "filePath", stringProp("File path relative to repo root (required)")),
                        args -> toJson(queryExecutor.getFileSummary(
                                getString(args, "repo"), getString(args, "filePath")))),

                tool("get_directory_tree", "Directory structure of a repo or subdirectory.",
                        Map.of("repo", stringProp("Repository name (required)"),
                                "path", stringProp("Subdirectory path (default: root)"),
                                "depth", intProp("Max depth (default 3)")),
                        args -> toJson(queryExecutor.getDirectoryTree(
                                getString(args, "repo"), getString(args, "path"),
                                getInt(args, "depth", 3)))),

                tool("get_index_health", "System health at a glance. Shows per-repo status, errors, and queue state.",
                        Map.of(),
                        args -> toJson(queryExecutor.getIndexHealth()))
        );
    }

    // Helper methods for tool registration
    private McpServerFeatures.SyncToolSpecification tool(String name, String description,
            Map<String, Map<String, Object>> properties,
            java.util.function.Function<Map<String, Object>, String> handler) {
        var schema = new McpSchema.JsonSchema("object", properties, List.of());
        var toolDef = new McpSchema.Tool(name, description, schema);
        return new McpServerFeatures.SyncToolSpecification(toolDef, (exchange, args) -> {
            try {
                @SuppressWarnings("unchecked")
                var params = (Map<String, Object>) args;
                String result = handler.apply(params != null ? params : Map.of());
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(result)), false);
            } catch (Exception e) {
                log.error("Tool {} failed: {}", name, e.getMessage(), e);
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Error: " + e.getMessage())), true);
            }
        });
    }

    private Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private String getString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    private int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private Integer getInteger(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\": \"Serialization failed: " + e.getMessage() + "\"}";
        }
    }

    public void stop() {
        if (server != null) server.close();
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/indexer/mcp/ src/test/java/com/indexer/mcp/
git commit -m "feat: add MCP server with 10 query tools and QueryExecutor"
```

---

## Phase 6: Application Bootstrap & Deployment

### Task 13: Application Entry Point & Boot Sequence

**Files:**
- Modify: `src/main/java/com/indexer/Application.java`

- [ ] **Step 1: Implement the full boot sequence**

```java
package com.indexer;

import com.indexer.auth.AuthProviderRegistry;
import com.indexer.config.ConfigLoader;
import com.indexer.config.IndexerConfig;
import com.indexer.config.LanguageRegistry;
import com.indexer.db.*;
import com.indexer.indexing.FileIndexer;
import com.indexer.indexing.IndexingPipeline;
import com.indexer.indexing.SymbolExtractor;
import com.indexer.mcp.McpServerBootstrap;
import com.indexer.mcp.QueryExecutor;
import com.indexer.queue.EventQueuePoller;
import com.indexer.repository.GitOperations;
import com.indexer.repository.RepositoryManager;
import com.indexer.webhook.WebhookServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private DatabaseManager dbManager;
    private WebhookServer webhookServer;
    private McpServerBootstrap mcpServer;
    private EventQueuePoller poller;
    private ExecutorService executor;

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : System.getProperty("user.home") + "/.source-code-indexer/config.yaml";
        new Application().start(Path.of(configPath));
    }

    public void start(Path configPath) {
        log.info("Source Code Indexer MCP Server starting...");
        log.info("Loading config from: {}", configPath);

        try {
            // 1. Load config
            IndexerConfig config = ConfigLoader.load(configPath);

            // 2. Initialize database
            dbManager = new DatabaseManager(
                    config.database().jdbcUrl(),
                    config.database().username(),
                    config.database().password()
            );
            dbManager.initialize();

            var jdbi = dbManager.getJdbi();
            var repositoryDao = new RepositoryDao(jdbi);
            var fileDao = new FileDao(jdbi);
            var symbolDao = new SymbolDao(jdbi);
            var eventDao = new EventDao(jdbi);

            // 3. Set up components
            var authRegistry = new AuthProviderRegistry();
            var gitOps = new GitOperations();
            var languageRegistry = new LanguageRegistry(config.languages().customExtensions());
            var symbolExtractor = new SymbolExtractor();
            var fileIndexer = new FileIndexer(fileDao, symbolDao, jdbi, languageRegistry,
                    symbolExtractor, config.server().maxFileSizeBytes());
            var indexingPipeline = new IndexingPipeline(repositoryDao, fileIndexer, gitOps);
            var repoManager = new RepositoryManager(config, authRegistry, repositoryDao, gitOps);

            // 4. Clone/fetch repos and run initial index
            var repos = repoManager.initializeRepositories();
            log.info("Initialized {} repositories", repos.size());

            for (var repo : repos) {
                if (repo.lastIndexedSha() == null) {
                    log.info("Running full index for {}", repo.name());
                    indexingPipeline.fullIndex(repo.id(), Path.of(repo.clonePath()));
                } else {
                    // Catch up: index anything missed while server was down
                    String currentSha = gitOps.getCurrentSha(Path.of(repo.clonePath()));
                    if (!currentSha.equals(repo.lastIndexedSha())) {
                        log.info("Catching up index for {}: {} -> {}", repo.name(), repo.lastIndexedSha(), currentSha);
                        indexingPipeline.incrementalIndex(repo.id(), Path.of(repo.clonePath()),
                                repo.lastIndexedSha(), currentSha);
                    }
                }
            }

            // 5. Report any failed events from previous runs
            int failedCount = eventDao.countByStatus("failed");
            if (failedCount > 0) {
                log.warn("{} events failed in previous runs. Use get_index_health for details.", failedCount);
            }

            // 6. Start webhook server
            webhookServer = new WebhookServer(eventDao);
            webhookServer.start(config.server().webhookPort());

            // 7. Start event queue poller
            executor = Executors.newFixedThreadPool(config.server().indexWorkers());
            poller = new EventQueuePoller(eventDao, event -> {
                var repo = repositoryDao.findByName(event.repoName()).orElse(null);
                if (repo == null) {
                    throw new RuntimeException("Unknown repo: " + event.repoName());
                }
                try {
                    indexingPipeline.incrementalIndex(repo.id(), Path.of(repo.clonePath()),
                            event.previousSha(), event.currentSha());
                } catch (Exception e) {
                    throw new RuntimeException("Indexing failed: " + e.getMessage(), e);
                }
            }, 1000);
            executor.submit(poller);

            // 8. Start MCP server
            var queryExecutor = new QueryExecutor(jdbi);
            mcpServer = new McpServerBootstrap(queryExecutor);

            if ("sse".equals(config.server().transport())) {
                log.info("MCP transport: SSE on port {}", config.server().ssePort());
                // SSE transport would be started here (Task 14)
            } else {
                log.info("MCP transport: stdio");
                mcpServer.startStdio();
            }

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

            log.info("Source Code Indexer MCP Server ready");

        } catch (Exception e) {
            log.error("Failed to start: {}", e.getMessage(), e);
            shutdown();
            System.exit(1);
        }
    }

    public void shutdown() {
        log.info("Shutting down...");
        if (poller != null) poller.stop();
        if (executor != null) executor.shutdownNow();
        if (webhookServer != null) webhookServer.stop();
        if (mcpServer != null) mcpServer.stop();
        if (dbManager != null) dbManager.close();
        log.info("Shutdown complete");
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/indexer/Application.java
git commit -m "feat: implement full application boot sequence with all components"
```

---

### Task 14: Docker Compose & Deployment

**Files:**
- Create: `Dockerfile`
- Create: `docker-compose.yml`
- Create: `.dockerignore`

- [ ] **Step 1: Create Dockerfile**

```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
RUN ./gradlew dependencies --no-daemon
COPY src/ src/
RUN ./gradlew build -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y git curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar
COPY --from=build /app/build/distributions/ /app/dist/

EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create docker-compose.yml**

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: source_code_index
      POSTGRES_USER: indexer
      POSTGRES_PASSWORD: ${DB_PASSWORD:-changeme}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U indexer -d source_code_index"]
      interval: 5s
      timeout: 5s
      retries: 5

  indexer:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_PASSWORD: ${DB_PASSWORD:-changeme}
      GITHUB_TOKEN: ${GITHUB_TOKEN:-}
    ports:
      - "8080:8080"
      - "8081:8081"
    volumes:
      - repos:/data/repos
      - ${HOME}/.ssh:/root/.ssh:ro
      - ./config.yaml:/config/config.yaml:ro
    command: ["java", "-jar", "app.jar", "/config/config.yaml"]

volumes:
  pgdata:
  repos:
```

- [ ] **Step 3: Create .dockerignore**

```
.git
build/
.gradle/
*.class
docs/
```

- [ ] **Step 4: Verify docker-compose validates**

Run: `docker compose config`
Expected: outputs valid YAML config

- [ ] **Step 5: Commit**

```bash
git add Dockerfile docker-compose.yml .dockerignore
git commit -m "feat: add Docker multi-stage build and docker-compose for deployment"
```

---

### Task 15: Connect-Index Claude Code Skill

**Files:**
- Create: `skills/connect-index.md`

- [ ] **Step 1: Create the skill file**

```markdown
---
name: connect-index
description: Connect your current project to the Source Code Indexer MCP server. Detects your repo, verifies it's indexed, and configures Claude Code to use the indexer.
---

## Steps

1. **Detect the repository**

Check if the current directory is a git repo:
- Run `git remote get-url origin` to find the remote URL
- If not a git repo, ask the user for the repository URL

2. **Find the indexer**

Check for indexer connection info in order:
- Environment variable `SOURCE_CODE_INDEXER_URL`
- File `~/.source-code-indexer/client.yaml` (read `indexerUrl` field)
- Ask the user for the indexer URL if neither is found

3. **Verify the repo is indexed**

Use the `get_repo_summary` tool to check if the repo is in the index:
- Extract the repo name from the URL (last path segment, without .git)
- Call the tool with that repo name
- If not found, inform the user and suggest they ask an admin to add it

4. **Configure Claude Code**

Create `.claude/mcp_servers.json` in the project root:

```json
{
  "source-code-indexer": {
    "type": "sse",
    "url": "<indexer-url>/mcp"
  }
}
```

5. **Print usage guide**

Display:
```
Connected to source-code-indexer. Your repo "<name>" is indexed
(last updated: <time>, <file_count> files).

Try these:
- "What's the structure of this repo?"         → get_directory_tree
- "Find all classes implementing <Interface>"  → find_implementations
- "Search for <concept>"                       → search_code
- "Show me the <ClassName> class"              → get_symbol_detail
- "What imports <Module>?"                     → find_references
- "Is the index healthy?"                      → get_index_health
```
```

- [ ] **Step 2: Commit**

```bash
mkdir -p skills
git add skills/connect-index.md
git commit -m "feat: add connect-index Claude Code skill for developer onboarding"
```

---

## Phase 7: Final Integration

### Task 16: Integration Smoke Test

**Files:**
- Create: `src/test/java/com/indexer/IntegrationSmokeTest.java`

- [ ] **Step 1: Write end-to-end smoke test**

```java
package com.indexer;

import com.indexer.config.ConfigLoader;
import com.indexer.db.DatabaseManager;
import com.indexer.db.EventDao;
import com.indexer.db.RepositoryDao;
import com.indexer.mcp.QueryExecutor;
import com.indexer.webhook.WebhookServer;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("e2e")
class IntegrationSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index")
            .withUsername("test")
            .withPassword("test");

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dbManager.initialize();
        // Clean all tables
        dbManager.getJdbi().useHandle(h -> {
            h.execute("DELETE FROM file_contents");
            h.execute("DELETE FROM type_relationships");
            h.execute("DELETE FROM imports");
            h.execute("DELETE FROM symbols");
            h.execute("DELETE FROM files");
            h.execute("DELETE FROM indexing_events");
            h.execute("DELETE FROM repositories");
        });
    }

    @Test
    void fullPipelineFromWebhookToQuery() throws Exception {
        var jdbi = dbManager.getJdbi();
        var eventDao = new EventDao(jdbi);
        var repositoryDao = new RepositoryDao(jdbi);
        var queryExecutor = new QueryExecutor(jdbi);

        // Create a test repo with Java files
        Path repoDir = tempDir.resolve("smoke-repo");
        Files.createDirectories(repoDir);
        new ProcessBuilder("git", "init").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.email", "t@t.com").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "config", "user.name", "T").directory(repoDir.toFile()).start().waitFor();

        Files.writeString(repoDir.resolve("Service.java"), """
                package com.example;
                import java.util.List;
                public interface Service {
                    List<String> getData();
                }
                """);
        Files.writeString(repoDir.resolve("ServiceImpl.java"), """
                package com.example;
                import java.util.List;
                import java.util.ArrayList;
                public class ServiceImpl implements Service {
                    public List<String> getData() {
                        return new ArrayList<>();
                    }
                    private void helper() {}
                }
                """);

        new ProcessBuilder("git", "add", ".").directory(repoDir.toFile()).start().waitFor();
        new ProcessBuilder("git", "commit", "-m", "init").directory(repoDir.toFile()).start().waitFor();

        // Register the repo and index it
        var repo = new com.indexer.model.Repository(0, "smoke-repo", "file://" + repoDir,
                "main", repoDir.toString(), "ssh-key", null, null);
        int repoId = repositoryDao.insert(repo);

        // Full index
        var langReg = new com.indexer.config.LanguageRegistry(Map.of());
        var extractor = new com.indexer.indexing.SymbolExtractor();
        var fileDao = new com.indexer.db.FileDao(jdbi);
        var symbolDao = new com.indexer.db.SymbolDao(jdbi);
        var fileIndexer = new com.indexer.indexing.FileIndexer(fileDao, symbolDao, jdbi, langReg, extractor, 1_048_576);
        var pipeline = new com.indexer.indexing.IndexingPipeline(repositoryDao, fileIndexer, new com.indexer.repository.GitOperations());
        pipeline.fullIndex(repoId, repoDir);

        // Verify: search_symbols finds ServiceImpl
        var symbols = queryExecutor.searchSymbols("ServiceImpl", null, null, null, 20);
        assertThat(symbols).anyMatch(s -> s.get("name").equals("ServiceImpl"));

        // Verify: find_implementations finds ServiceImpl for Service interface
        var impls = queryExecutor.findImplementations("Service", null);
        assertThat(impls).anyMatch(s -> s.get("class_name").equals("ServiceImpl"));

        // Verify: search_code finds getData
        var codeResults = queryExecutor.searchCode("getData", null, null, 10);
        assertThat(codeResults).isNotEmpty();

        // Verify: get_repo_summary works
        var summary = queryExecutor.getRepoSummary("smoke-repo");
        assertThat(summary.get("fileCount")).isEqualTo(2);

        // Verify: get_index_health works
        var health = queryExecutor.getIndexHealth();
        assertThat(health.get("totalPendingEvents")).isEqualTo(0);

        // Verify: webhook inserts events
        var webhookServer = new WebhookServer(eventDao);
        var app = webhookServer.createApp();
        JavalinTest.test(app, (server, client) -> {
            client.post("/webhook", """
                {"repoName":"smoke-repo","repoPath":"%s","eventType":"post-commit","previousSha":"abc","currentSha":"def","timestamp":"2026-05-25T12:00:00Z"}
                """.formatted(repoDir));
            assertThat(eventDao.countByStatus("pending")).isEqualTo(1);
        });
    }
}
```

- [ ] **Step 2: Run smoke test**

Run: `./gradlew test --tests "com.indexer.IntegrationSmokeTest" -i`
Expected: PASS

- [ ] **Step 3: Run full test suite**

Run: `./gradlew test -i`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/indexer/IntegrationSmokeTest.java
git commit -m "feat: add end-to-end integration smoke test"
```

---

## Summary of All Files

```
SourceCodeIndexerMCP/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── Dockerfile
├── docker-compose.yml
├── .dockerignore
├── CLAUDE.md
├── skills/
│   └── connect-index.md
├── docs/superpowers/specs/
│   └── 2026-05-25-source-code-indexer-mcp-design.md
├── src/main/java/com/indexer/
│   ├── Application.java
│   ├── config/
│   │   ├── IndexerConfig.java
│   │   ├── ConfigLoader.java
│   │   ├── ConfigValidationException.java
│   │   └── LanguageRegistry.java
│   ├── auth/
│   │   ├── AuthProvider.java
│   │   ├── GitCredentials.java
│   │   ├── AuthProviderRegistry.java
│   │   ├── AuthResolutionException.java
│   │   ├── SshKeyAuthProvider.java
│   │   ├── TokenAuthProvider.java
│   │   └── GitCredentialManagerProvider.java
│   ├── db/
│   │   ├── DatabaseManager.java
│   │   ├── RepositoryDao.java
│   │   ├── FileDao.java
│   │   ├── SymbolDao.java
│   │   └── EventDao.java
│   ├── model/
│   │   ├── Repository.java
│   │   ├── SourceFile.java
│   │   ├── Symbol.java
│   │   ├── Import.java
│   │   ├── TypeRelationship.java
│   │   └── IndexingEvent.java
│   ├── repository/
│   │   ├── GitOperations.java
│   │   ├── HookInstaller.java
│   │   └── RepositoryManager.java
│   ├── webhook/
│   │   ├── WebhookServer.java
│   │   └── WebhookPayload.java
│   ├── queue/
│   │   ├── EventQueuePoller.java
│   │   └── EventDeduplicator.java
│   ├── indexing/
│   │   ├── IndexingPipeline.java
│   │   ├── FileIndexer.java
│   │   ├── SymbolExtractor.java
│   │   └── ExtractedSymbol.java
│   └── mcp/
│       ├── McpServerBootstrap.java
│       └── QueryExecutor.java
├── src/main/resources/
│   ├── logback.xml
│   ├── db/migration/
│   │   └── V1__initial_schema.sql
│   └── META-INF/services/
│       └── com.indexer.auth.AuthProvider
└── src/test/java/com/indexer/
    ├── IntegrationSmokeTest.java
    ├── config/
    │   ├── ConfigLoaderTest.java
    │   └── LanguageRegistryTest.java
    ├── auth/
    │   └── AuthProviderRegistryTest.java
    ├── db/
    │   ├── MigrationTest.java
    │   └── RepositoryDaoTest.java
    ├── repository/
    │   ├── HookInstallerTest.java
    │   └── GitOperationsTest.java
    ├── webhook/
    │   └── WebhookServerTest.java
    ├── queue/
    │   ├── EventDeduplicatorTest.java
    │   └── EventQueuePollerTest.java
    ├── indexing/
    │   ├── SymbolExtractorTest.java
    │   └── IndexingPipelineIntegrationTest.java
    └── mcp/tools/
        └── SearchSymbolsToolTest.java
```

## Implementation Notes

1. **Tree-sitter**: The initial implementation uses regex-based parsing. This is functional but less accurate than Tree-sitter. Once the project is running end-to-end, replace `SymbolExtractor` internals with JNR-FFI calls to the Tree-sitter C library. The interface stays the same.

2. **MCP SDK version**: The `io.modelcontextprotocol:sdk` dependency version and API may differ from what's shown. Check Maven Central for the latest version and adjust `McpServerBootstrap` accordingly.

3. **SSE Transport**: Not implemented in this plan. The MCP SDK supports SSE transport — wire it up in `McpServerBootstrap` using `SseServerTransport` instead of `StdioServerTransport` when `config.server().transport()` is `"sse"`.

4. **Admin API**: Not implemented in this plan (Phase 2 scope). The `WebhookServer` Javalin instance can be extended with `/admin/*` routes once the core is stable.

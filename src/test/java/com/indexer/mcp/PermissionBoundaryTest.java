package com.indexer.mcp;

import com.indexer.auth.CallerIdentity;
import com.indexer.auth.PermissionCache;
import com.indexer.auth.PermissionResolver;
import com.indexer.mcp.TestAuthSupport.CapturingAuditSink;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.indexer.mcp.TestAuthSupport.textOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security boundary: a remote caller who is not entitled to a repo must not be able to do
 * anything meaningful with it — no source, symbols, paths, or search results may leak. Proven
 * for both the OAuth path (group → allowed repos) and the API-key path (per-key repo scope),
 * across multiple tools, at the central {@link QueryExecutor#executeQuery} choke point.
 */
@Testcontainers
@Tag("integration")
class PermissionBoundaryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private Jdbi jdbi;
    private QueryExecutor qe;
    private PermissionResolver resolver;
    private CapturingAuditSink sink;

    /** Tools that read meaningful repo content; each must deny an unentitled caller. */
    private static final List<String> READ_TOOLS =
            List.of("search_symbols", "get_symbol_detail", "search_code", "get_file_summary", "get_directory_tree");

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) VALUES ('repo-pub','u','main','/tmp/pub','ssh-key','sha1')");
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) VALUES ('repo-secret','u','main','/tmp/sec','ssh-key','sha2')");
            int pub = h.createQuery("SELECT id FROM repositories WHERE name='repo-pub'").mapTo(Integer.class).one();
            int sec = h.createQuery("SELECT id FROM repositories WHERE name='repo-secret'").mapTo(Integer.class).one();

            h.execute("INSERT INTO files (repo_id, path, language) VALUES (?,'src/Pub.java','java')", pub);
            h.execute("INSERT INTO files (repo_id, path, language) VALUES (?,'src/Secret.java','java')", sec);
            int pubFile = h.createQuery("SELECT id FROM files WHERE repo_id=:r AND path='src/Pub.java'").bind("r", pub).mapTo(Integer.class).one();
            int secFile = h.createQuery("SELECT id FROM files WHERE repo_id=:r AND path='src/Secret.java'").bind("r", sec).mapTo(Integer.class).one();

            h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, visibility, is_static) VALUES (?,'PublicThing','class','class PublicThing',1,5,'public',false)", pubFile);
            h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, visibility, is_static) VALUES (?,'TopSecretKey','class','class TopSecretKey',1,5,'public',false)", secFile);

            h.createUpdate("INSERT INTO file_contents (file_id, content) VALUES (:f,:c) ON CONFLICT (file_id) DO UPDATE SET content=EXCLUDED.content")
                    .bind("f", secFile).bind("c", "class TopSecretKey { String apiSecret = \"sk-LEAK\"; }").execute();
            h.createUpdate("INSERT INTO file_contents (file_id, content) VALUES (:f,:c) ON CONFLICT (file_id) DO UPDATE SET content=EXCLUDED.content")
                    .bind("f", pubFile).bind("c", "class PublicThing {}").execute();
        });

        resolver = mock(PermissionResolver.class);
        when(resolver.resolveAllowedRepos(List.of("team-pub"))).thenReturn(Set.of("repo-pub"));
        var cache = new PermissionCache(resolver, Set.of(), Duration.ofMinutes(30));
        sink = new CapturingAuditSink();
        qe = new QueryExecutor(jdbi, null, null, null, null, cache, sink);
    }

    // ---- OAuth path ----

    @Test
    void oauthUserCannotReadRepoOutsideEntitlement_acrossTools() {
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-pub"), "ip"); // entitled to repo-pub only
        for (String tool : READ_TOOLS) {
            var result = qe.executeQuery(caller, "repo-secret", tool, Map.of(),
                    () -> { throw new AssertionError("query lambda must not run when denied: " + tool); });
            assertThat(result.isError()).as(tool).isTrue();
            assertThat(textOf(result)).as(tool).contains("Access denied to repository: repo-secret");
            assertThat(textOf(result)).as(tool).doesNotContain("TopSecretKey").doesNotContain("sk-LEAK");
        }
        assertThat(sink.events).isNotEmpty().allMatch(e -> !e.authorized());
    }

    @Test
    void oauthUserCanReadEntitledRepo() {
        var caller = CallerIdentity.fromOAuth("alice", "Alice", List.of("team-pub"), "ip");
        var result = qe.executeQuery(caller, "repo-pub", "search_symbols", Map.of(),
                () -> qe.searchSymbols(null, null, null, "repo-pub", null, 20));
        assertThat(result.isError()).isFalse();
        assertThat(textOf(result)).contains("PublicThing");
    }

    @Test
    void oauthFailClosedWhenResolverThrows() {
        when(resolver.resolveAllowedRepos(List.of("team-broken")))
                .thenThrow(new RuntimeException("github down"));
        var caller = CallerIdentity.fromOAuth("bob", "Bob", List.of("team-broken"), "ip");
        var result = qe.executeQuery(caller, "repo-pub", "search_symbols", Map.of(),
                () -> { throw new AssertionError("must not run"); });
        assertThat(result.isError()).isTrue();
    }

    // ---- API-key path (end-to-end, real tools) ----

    @Test
    void scopedApiKeyCannotReadUnscopedRepo_acrossTools() {
        var caller = CallerIdentity.fromApiKey("ci", "CI", "ip", false, false, List.of("repo-pub"));
        for (String tool : READ_TOOLS) {
            var result = qe.executeQuery(caller, "repo-secret", tool, Map.of(),
                    () -> { throw new AssertionError("query lambda must not run when denied: " + tool); });
            assertThat(result.isError()).as(tool).isTrue();
            assertThat(textOf(result)).as(tool).doesNotContain("TopSecretKey").doesNotContain("sk-LEAK");
        }
    }

    @Test
    void scopedApiKeyReadsItsRepo_andWildcardReadsAll() {
        var scoped = CallerIdentity.fromApiKey("ci", "CI", "ip", false, false, List.of("repo-pub"));
        var scopedResult = qe.executeQuery(scoped, "repo-pub", "search_symbols", Map.of(),
                () -> qe.searchSymbols(null, null, null, "repo-pub", null, 20));
        assertThat(scopedResult.isError()).isFalse();
        assertThat(textOf(scopedResult)).contains("PublicThing");

        var admin = CallerIdentity.fromApiKey("admin", "Admin", "ip", false, false, List.of("*"));
        var adminResult = qe.executeQuery(admin, "repo-secret", "search_symbols", Map.of(),
                () -> qe.searchSymbols(null, null, null, "repo-secret", null, 20));
        assertThat(adminResult.isError()).isFalse();
        assertThat(textOf(adminResult)).contains("TopSecretKey");
    }

    @Test
    void unscopedApiKeyReadsNothing() {
        var caller = CallerIdentity.fromApiKey("legacy", "Legacy", "ip"); // no repos → fail-closed
        var result = qe.executeQuery(caller, "repo-pub", "search_symbols", Map.of(),
                () -> { throw new AssertionError("must not run"); });
        assertThat(result.isError()).isTrue();
    }
}

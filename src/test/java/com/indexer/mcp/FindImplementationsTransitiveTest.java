package com.indexer.mcp;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * find_implementations recall: direct-only (default) vs transitive closure.
 *
 * Seeds an indirect inheritance chain that grep — and the shipped direct-only query —
 * cannot follow:
 *   interface Repository
 *   AbstractRepository  implements Repository        (direct)
 *   UserRepository      extends    AbstractRepository (indirect, 1 hop)
 *   AdminRepository     extends    UserRepository     (transitive, 2 hops)
 *   Unrelated           implements OtherThing         (control — must never appear)
 */
@Testcontainers
@Tag("integration")
class FindImplementationsTransitiveTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private QueryExecutor queryExecutor;
    private static final String REPO = "graph-app";

    @BeforeEach
    void setUp() {
        Jdbi jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        jdbi.useHandle(h -> {
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) VALUES (?, 'u','main','/tmp/g','ssh-key','sha1')", REPO);
            int repoId = h.createQuery("SELECT id FROM repositories WHERE name=:n").bind("n", REPO).mapTo(Integer.class).one();

            seedClass(h, repoId, "AbstractRepository", "Repository", "implements");
            seedClass(h, repoId, "UserRepository", "AbstractRepository", "extends");
            seedClass(h, repoId, "AdminRepository", "UserRepository", "extends");
            seedClass(h, repoId, "Unrelated", "OtherThing", "implements");
        });

        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    @SuppressWarnings("unchecked")
    void directOnly_returnsOnlyLiteralImplementsEdge() {
        var res = (Map<String, Object>) queryExecutor.findImplementations("Repository", REPO, null, false);
        var names = (List<Map<String, Object>>) res.get("results");
        assertThat(names).extracting(m -> m.get("class_name"))
                .containsExactly("AbstractRepository");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transitive_walksExtendsChainToClosure() {
        var res = (Map<String, Object>) queryExecutor.findImplementations("Repository", REPO, null, true);
        var names = (List<Map<String, Object>>) res.get("results");
        assertThat(names).extracting(m -> m.get("class_name"))
                .contains("AbstractRepository", "UserRepository", "AdminRepository")
                .doesNotContain("Unrelated", "Repository");
    }

    private static void seedClass(org.jdbi.v3.core.Handle h, int repoId, String name, String relatedName, String kind) {
        String path = "src/main/java/com/example/" + name + ".java";
        h.execute("INSERT INTO files (repo_id, path, language) VALUES (?, ?, 'java')", repoId, path);
        int fileId = h.createQuery("SELECT id FROM files WHERE repo_id=:r AND path=:p")
                .bind("r", repoId).bind("p", path).mapTo(Integer.class).one();
        h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, visibility, is_static) "
                + "VALUES (?,?,'class',?,1,10,'public',false)", fileId, name, "public class " + name);
        int symId = h.createQuery("SELECT id FROM symbols WHERE file_id=:f AND name=:n")
                .bind("f", fileId).bind("n", name).mapTo(Integer.class).one();
        h.execute("INSERT INTO type_relationships (symbol_id, related_name, kind) VALUES (?,?,?)", symId, relatedName, kind);
    }
}

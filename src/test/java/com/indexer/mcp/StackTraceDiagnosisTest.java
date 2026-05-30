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
 * Poster-child workflow: a developer has a stack trace and wants to diagnose it fast.
 *
 * There is no dedicated "diagnose" tool — diagnosis is a composition of existing query tools.
 * This test walks a representative Java frame end-to-end at the QueryExecutor level:
 *   at com.example.payment.PaymentProcessor.charge(PaymentProcessor.java:42)
 * frame → source (get_symbol_detail) → callers (find_references) → polymorphism
 * (find_implementations) → file context (get_file_summary) → error string (search_code).
 */
@Testcontainers
@Tag("integration")
class StackTraceDiagnosisTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("test_index").withUsername("test").withPassword("test");

    private QueryExecutor queryExecutor;

    private static final String REPO = "payment-app";
    private static final String PROCESSOR_FILE = "src/main/java/com/example/payment/PaymentProcessor.java";

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
            h.execute("INSERT INTO repositories (name, url, branch, clone_path, auth_type, last_indexed_sha) VALUES (?, 'u','main','/tmp/pay','ssh-key','sha1')", REPO);
            int repoId = h.createQuery("SELECT id FROM repositories WHERE name=:n").bind("n", REPO).mapTo(Integer.class).one();

            // --- PaymentProcessor.java: the throwing class, implements PaymentGateway ---
            h.execute("INSERT INTO files (repo_id, path, language) VALUES (?, ?, 'java')", repoId, PROCESSOR_FILE);
            int procFile = fileId(h, repoId, PROCESSOR_FILE);
            // class PaymentProcessor declared at line 2; method charge declared at line 42.
            // get_symbol_detail matches a symbol's declaration (start) line and slices source by
            // [start_line, end_line], so the seeded content must actually span those line numbers.
            h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, visibility, is_static) VALUES (?,'PaymentProcessor','class','public class PaymentProcessor',2,47,'public',false)", procFile);
            int procClass = symbolId(h, procFile, "PaymentProcessor");
            h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, parent_id, visibility, is_static) VALUES (?,'charge','method','public void charge(BigDecimal amount)',42,46,?,'public',false)", procFile, procClass);
            // PaymentProcessor implements PaymentGateway
            h.execute("INSERT INTO type_relationships (symbol_id, related_name, kind) VALUES (?, 'PaymentGateway', 'implements')", procClass);
            insertContent(h, procFile, processorSource());

            // --- PaymentService.java: imports + calls PaymentProcessor (an inbound reference) ---
            String serviceFile = "src/main/java/com/example/PaymentService.java";
            h.execute("INSERT INTO files (repo_id, path, language) VALUES (?, ?, 'java')", repoId, serviceFile);
            int svcFile = fileId(h, repoId, serviceFile);
            h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, visibility, is_static) VALUES (?,'PaymentService','class','public class PaymentService',1,30,'public',false)", svcFile);
            h.execute("INSERT INTO imports (file_id, import_path) VALUES (?, 'com.example.payment.PaymentProcessor')", svcFile);
            insertContent(h, svcFile, """
                    package com.example;
                    import com.example.payment.PaymentProcessor;
                    public class PaymentService {
                        void run() { new PaymentProcessor().charge(amount); }
                    }
                    """);

            // --- StripeGateway.java: a second implementer of PaymentGateway ---
            String stripeFile = "src/main/java/com/example/payment/StripeGateway.java";
            h.execute("INSERT INTO files (repo_id, path, language) VALUES (?, ?, 'java')", repoId, stripeFile);
            int stripeFileId = fileId(h, repoId, stripeFile);
            h.execute("INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, visibility, is_static) VALUES (?,'StripeGateway','class','public class StripeGateway',1,40,'public',false)", stripeFileId);
            int stripeClass = symbolId(h, stripeFileId, "StripeGateway");
            h.execute("INSERT INTO type_relationships (symbol_id, related_name, kind) VALUES (?, 'PaymentGateway', 'implements')", stripeClass);
            insertContent(h, stripeFileId, "package com.example.payment;\npublic class StripeGateway implements PaymentGateway {}");
        });

        queryExecutor = new QueryExecutor(jdbi);
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnoseFromStackFrame_PaymentProcessor_charge_line42() {
        // FRAME: at com.example.payment.PaymentProcessor.charge(PaymentProcessor.java:42)

        // 1) Jump to the throwing method's source. get_symbol_detail matches the symbol's
        //    declaration (start) line, so the frame's line maps to charge's declaration at 42.
        var detail = queryExecutor.getSymbolDetail(REPO, PROCESSOR_FILE, "charge", 42, null);
        assertThat(detail).isNotEmpty();
        assertThat(detail.get("name")).isEqualTo("charge");
        assertThat(detail.get("signature")).isEqualTo("public void charge(BigDecimal amount)");
        assertThat((String) detail.get("source_code")).contains("IllegalStateException");

        // 2) Who references the throwing class? (import-based inbound callers)
        var refs = (Map<String, Object>) queryExecutor.findReferences("PaymentProcessor", REPO, null, 20);
        var refList = (List<Map<String, Object>>) refs.get("results");
        assertThat(refList).extracting(m -> m.get("file_path"))
                .anyMatch(p -> p.toString().contains("PaymentService.java"));

        // 3) Polymorphism: which concrete types implement the same interface? (could be the real thrower)
        var impls = (Map<String, Object>) queryExecutor.findImplementations("PaymentGateway", REPO, null);
        var implList = (List<Map<String, Object>>) impls.get("results");
        assertThat(implList).extracting(m -> m.get("class_name"))
                .contains("PaymentProcessor", "StripeGateway");

        // 4) Widen to file context: sibling symbols + imports in the throwing file.
        var summary = queryExecutor.getFileSummary(REPO, PROCESSOR_FILE, null);
        var symbols = (List<Map<String, Object>>) summary.get("symbols");
        assertThat(symbols).extracting(m -> m.get("name")).contains("PaymentProcessor", "charge");

        // 5) Hunt the error string across the codebase.
        var hits = queryExecutor.searchCode("IllegalStateException", null, REPO, null, 10);
        assertThat(hits).extracting(m -> m.get("file_path"))
                .anyMatch(p -> p.toString().equals(PROCESSOR_FILE));
    }

    /** Build PaymentProcessor.java where charge() is declared at line 42 and throws around line 44. */
    private static String processorSource() {
        var sb = new StringBuilder();
        sb.append("package com.example.payment;\n");                                   // line 1
        sb.append("public class PaymentProcessor implements PaymentGateway {\n");      // line 2
        for (int i = 3; i <= 41; i++) sb.append("    // ... line ").append(i).append("\n"); // lines 3-41
        sb.append("    public void charge(BigDecimal amount) {\n");                    // line 42
        sb.append("        if (amount == null) {\n");                                  // line 43
        sb.append("            throw new IllegalStateException(\"charge failed: amount is null\");\n"); // line 44
        sb.append("        }\n");                                                      // line 45
        sb.append("    }\n");                                                          // line 46
        sb.append("}\n");                                                             // line 47
        return sb.toString();
    }

    private static int fileId(org.jdbi.v3.core.Handle h, int repoId, String path) {
        return h.createQuery("SELECT id FROM files WHERE repo_id=:r AND path=:p")
                .bind("r", repoId).bind("p", path).mapTo(Integer.class).one();
    }

    private static int symbolId(org.jdbi.v3.core.Handle h, int fileId, String name) {
        return h.createQuery("SELECT id FROM symbols WHERE file_id=:f AND name=:n")
                .bind("f", fileId).bind("n", name).mapTo(Integer.class).one();
    }

    private static void insertContent(org.jdbi.v3.core.Handle h, int fileId, String content) {
        h.createUpdate("INSERT INTO file_contents (file_id, content) VALUES (:f,:c) ON CONFLICT (file_id) DO UPDATE SET content=EXCLUDED.content")
                .bind("f", fileId).bind("c", content).execute();
    }
}

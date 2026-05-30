package com.indexer.mcp;

import com.indexer.auth.CallerIdentity;
import com.indexer.mcp.TestAuthSupport.CapturingAuditSink;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.indexer.mcp.TestAuthSupport.textOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the API-key repo-scope authorization branch in {@link QueryExecutor#executeQuery}.
 * The denied path never touches the DB (the query lambda is not run), so all collaborators are null.
 */
class ApiKeyScopeGateTest {

    private QueryExecutor newExecutor(CapturingAuditSink sink) {
        // Only the auth gate is exercised; DB collaborators are null and never touched on the denied path.
        return new QueryExecutor(null, null, null, null, null, null, sink);
    }

    @Test
    void scopedKeyAllowedRepoRunsQuery() {
        var qe = newExecutor(new CapturingAuditSink());
        var caller = CallerIdentity.fromApiKey("ci-a", "CI A", "ip", false, false, List.of("repo-a"));
        var ran = new AtomicBoolean(false);

        var result = qe.executeQuery(caller, "repo-a", "search_symbols", Map.of(),
                () -> { ran.set(true); return Map.of("results", List.of()); });

        assertThat(ran).isTrue();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void scopedKeyForbiddenRepoIsDeniedAndLambdaNeverRuns() {
        var sink = new CapturingAuditSink();
        var qe = newExecutor(sink);
        var caller = CallerIdentity.fromApiKey("ci-a", "CI A", "ip", false, false, List.of("repo-a"));
        var ran = new AtomicBoolean(false);

        var result = qe.executeQuery(caller, "secret-repo", "search_code", Map.of(),
                () -> { ran.set(true); return Map.of("leak", "TOP SECRET"); });

        assertThat(ran).isFalse();                          // query never executed
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("Access denied to repository: secret-repo");
        assertThat(textOf(result)).doesNotContain("TOP SECRET");
        assertThat(sink.events).anyMatch(e -> !e.authorized());  // denial audited
    }

    @Test
    void wildcardKeyReadsAnyRepo() {
        var qe = newExecutor(new CapturingAuditSink());
        var caller = CallerIdentity.fromApiKey("admin", "Admin", "ip", false, false, List.of("*"));

        var result = qe.executeQuery(caller, "any-repo", "search_symbols", Map.of(),
                () -> Map.of("results", List.of()));

        assertThat(result.isError()).isFalse();
    }

    @Test
    void unscopedKeyIsDeniedEverything() {
        var qe = newExecutor(new CapturingAuditSink());
        var caller = CallerIdentity.fromApiKey("legacy", "Legacy", "ip"); // no repos → List.of()

        var result = qe.executeQuery(caller, "repo-a", "search_symbols", Map.of(),
                () -> Map.of("results", List.of()));

        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("Access denied to repository: repo-a");
    }

    @Test
    void scopedKeyRepoLessToolIsDenied() {
        var qe = newExecutor(new CapturingAuditSink());
        var caller = CallerIdentity.fromApiKey("ci-a", "CI A", "ip", false, false, List.of("repo-a"));

        var result = qe.executeQuery(caller, null, "get_index_health", Map.of(),
                () -> Map.of("ok", true));

        assertThat(result.isError()).isTrue();  // repo-less/system tools require ["*"]
    }
}

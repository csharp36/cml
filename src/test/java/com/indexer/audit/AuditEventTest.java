package com.indexer.audit;

import com.indexer.auth.CallerIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventTest {

    @Test
    void fromCallerIdentityComputesHash() {
        var caller = CallerIdentity.fromApiKey("team-payments", "Payments Team", "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "my-repo", true, "success", null);

        assertThat(event.callerHash()).hasSize(64);
        assertThat(event.callerHash()).matches("[a-f0-9]{64}");
        assertThat(event.userId()).isEqualTo("team-payments");
        assertThat(event.displayName()).isEqualTo("Payments Team");
        assertThat(event.authMethod()).isEqualTo("api-key");
        assertThat(event.transport()).isEqualTo("streamable-http");
        assertThat(event.sourceIp()).isEqualTo("10.0.0.1");
        assertThat(event.action()).isEqualTo("search_symbols");
        assertThat(event.repo()).isEqualTo("my-repo");
        assertThat(event.authorized()).isTrue();
        assertThat(event.resultStatus()).isEqualTo("success");
        assertThat(event.errorMessage()).isNull();
    }

    @Test
    void fromAnonymousCallerUsesAnonymousHash() {
        var caller = CallerIdentity.anonymous("streamable-http");
        var event = AuditEvent.from(caller, "get_index_health", null, true, "success", null);

        assertThat(event.callerHash()).hasSize(64);
        assertThat(event.userId()).isEqualTo("anonymous");
        assertThat(event.displayName()).isEqualTo("anonymous");
    }

    @Test
    void fromStdioCallerUsesOsUser() {
        var caller = CallerIdentity.fromStdio();
        var event = AuditEvent.from(caller, "search_code", "backend", true, "success", null);

        assertThat(event.callerHash()).hasSize(64);
        assertThat(event.userId()).isEqualTo(System.getProperty("user.name"));
    }

    @Test
    void sameUserIdProducesSameHash() {
        var caller1 = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        var caller2 = CallerIdentity.fromApiKey("alice", "Alice V2", "10.0.0.2");
        var event1 = AuditEvent.from(caller1, "a", null, true, "success", null);
        var event2 = AuditEvent.from(caller2, "b", null, true, "success", null);

        assertThat(event1.callerHash()).isEqualTo(event2.callerHash());
    }

    @Test
    void deniedEventCapturesError() {
        var caller = CallerIdentity.fromOAuth("bob", "Bob", List.of("team-a"), "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "secret-repo", false, "denied", "Access denied");

        assertThat(event.authorized()).isFalse();
        assertThat(event.resultStatus()).isEqualTo("denied");
        assertThat(event.errorMessage()).isEqualTo("Access denied");
    }
}

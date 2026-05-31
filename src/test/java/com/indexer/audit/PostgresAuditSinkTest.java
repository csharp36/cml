package com.indexer.audit;

import com.indexer.auth.CallerIdentity;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class PostgresAuditSinkTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private Jdbi jdbi;
    private PostgresAuditSink sink;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());

        jdbi.useHandle(h -> {
            h.execute("DROP TABLE IF EXISTS audit_events, audit_identity_map, audit_chain_state CASCADE");
            h.execute("""
                CREATE TABLE audit_events (
                    id BIGSERIAL PRIMARY KEY, timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    caller_hash VARCHAR(64) NOT NULL, auth_method VARCHAR(32) NOT NULL,
                    transport VARCHAR(32) NOT NULL, source_ip VARCHAR(45),
                    action VARCHAR(128) NOT NULL, repo VARCHAR(256),
                    authorized BOOLEAN NOT NULL, result_status VARCHAR(16) NOT NULL,
                    error_message TEXT, chain_hash VARCHAR(64) NOT NULL
                )""");
            h.execute("""
                CREATE TABLE audit_identity_map (
                    caller_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(256) NOT NULL,
                    display_name VARCHAR(256), first_seen TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    last_seen TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )""");
            h.execute("""
                CREATE TABLE audit_chain_state (
                    id INT PRIMARY KEY DEFAULT 1, last_hash VARCHAR(64) NOT NULL,
                    last_event_id BIGINT NOT NULL DEFAULT 0
                )""");
            h.execute("INSERT INTO audit_chain_state (id, last_hash, last_event_id) VALUES (1, 'aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e', 0)");
        });

        sink = new PostgresAuditSink(jdbi);
    }

    @Test
    void recordInsertsEventAndUpdatesChain() {
        var caller = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "my-repo", true, "success", null);

        sink.record(event);

        var events = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM audit_events ORDER BY id").mapToMap().list());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("action")).isEqualTo("search_symbols");
        assertThat(events.get(0).get("result_status")).isEqualTo("success");
        assertThat(events.get(0).get("chain_hash")).isNotNull();

        var chainState = jdbi.withHandle(h ->
                h.createQuery("SELECT last_hash, last_event_id FROM audit_chain_state WHERE id = 1").mapToMap().one());
        assertThat(chainState.get("last_event_id")).isEqualTo(1L);
        assertThat(chainState.get("last_hash")).isNotEqualTo("aeebad4a796fcc2e15dc4c6061b45ed9b373f26adfc798ca7d2d8cc58182718e");
    }

    @Test
    void recordUpsertsIdentityMap() {
        var caller = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        sink.record(AuditEvent.from(caller, "search_symbols", "repo", true, "success", null));
        sink.record(AuditEvent.from(caller, "search_code", "repo", true, "success", null));

        var identities = jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM audit_identity_map").mapToMap().list());
        assertThat(identities).hasSize(1);
        assertThat(identities.get(0).get("user_id")).isEqualTo("alice");
        assertThat(identities.get(0).get("display_name")).isEqualTo("Alice");
    }

    @Test
    void hashChainIsConsecutive() {
        var caller = CallerIdentity.fromApiKey("alice", "Alice", "10.0.0.1");
        sink.record(AuditEvent.from(caller, "action1", "repo", true, "success", null));
        sink.record(AuditEvent.from(caller, "action2", "repo", true, "success", null));
        sink.record(AuditEvent.from(caller, "action3", "repo", true, "error", "something broke"));

        var events = jdbi.withHandle(h ->
                h.createQuery("SELECT chain_hash FROM audit_events ORDER BY id").mapToMap().list());
        assertThat(events).hasSize(3);

        var hashes = events.stream().map(e -> (String) e.get("chain_hash")).toList();
        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void deniedEventIsRecorded() {
        var caller = CallerIdentity.fromOAuth("bob", "Bob", List.of("team-a"), "10.0.0.1");
        var event = AuditEvent.from(caller, "search_symbols", "secret-repo", false, "denied", "Access denied");

        sink.record(event);

        var events = jdbi.withHandle(h ->
                h.createQuery("SELECT authorized, result_status, error_message FROM audit_events").mapToMap().list());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("authorized")).isEqualTo(false);
        assertThat(events.get(0).get("result_status")).isEqualTo("denied");
        assertThat(events.get(0).get("error_message")).isEqualTo("Access denied");
    }
}

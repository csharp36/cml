package com.indexer.auth;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CallerIdentityTest {

    @Test
    void fromStdioUsesOsUsername() {
        var identity = CallerIdentity.fromStdio();
        assertThat(identity.userId()).isEqualTo(System.getProperty("user.name"));
        assertThat(identity.authMethod()).isEqualTo("stdio-os-user");
        assertThat(identity.transport()).isEqualTo("stdio");
        assertThat(identity.sourceIp()).isNull();
    }

    @Test
    void anonymousHasNoAuth() {
        var identity = CallerIdentity.anonymous("streamable-http");
        assertThat(identity.userId()).isNull();
        assertThat(identity.displayName()).isEqualTo("anonymous");
        assertThat(identity.authMethod()).isEqualTo("none");
        assertThat(identity.transport()).isEqualTo("streamable-http");
    }

    @Test
    void fromApiKeyCarriesIdentity() {
        var identity = CallerIdentity.fromApiKey("team-payments", "Payments Team", "10.0.0.1");
        assertThat(identity.userId()).isEqualTo("team-payments");
        assertThat(identity.displayName()).isEqualTo("Payments Team");
        assertThat(identity.authMethod()).isEqualTo("api-key");
        assertThat(identity.transport()).isEqualTo("streamable-http");
        assertThat(identity.sourceIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void fromOAuthCarriesGroups() {
        var identity = CallerIdentity.fromOAuth("alice", "Alice Chen",
                List.of("team-payments", "team-platform"), "10.0.0.1");
        assertThat(identity.userId()).isEqualTo("alice");
        assertThat(identity.displayName()).isEqualTo("Alice Chen");
        assertThat(identity.authMethod()).isEqualTo("oauth");
        assertThat(identity.transport()).isEqualTo("streamable-http");
        assertThat(identity.groups()).containsExactly("team-payments", "team-platform");
    }

    @Test
    void fromOAuthWithNullGroupsDefaultsToEmpty() {
        var identity = CallerIdentity.fromOAuth("bob", "Bob", null, "10.0.0.1");
        assertThat(identity.groups()).isEmpty();
    }

    @Test
    void existingFactoriesHaveEmptyGroups() {
        assertThat(CallerIdentity.fromStdio().groups()).isEmpty();
        assertThat(CallerIdentity.anonymous("http").groups()).isEmpty();
        assertThat(CallerIdentity.fromApiKey("id", "name", "ip").groups()).isEmpty();
    }

    @Test
    void fromAdminTokenCreatesAdminIdentity() {
        var identity = CallerIdentity.fromAdminToken("10.0.0.1");
        assertThat(identity.userId()).isEqualTo("admin");
        assertThat(identity.displayName()).isEqualTo("Admin");
        assertThat(identity.authMethod()).isEqualTo("admin-token");
        assertThat(identity.transport()).isEqualTo("streamable-http");
        assertThat(identity.sourceIp()).isEqualTo("10.0.0.1");
        assertThat(identity.auditReader()).isFalse();
    }

    @Test
    void fromStdioHasAuditReaderTrue() {
        var identity = CallerIdentity.fromStdio();
        assertThat(identity.auditReader()).isTrue();
    }

    @Test
    void fromApiKeyWithAuditReader() {
        var identity = CallerIdentity.fromApiKey("compliance", "Compliance Team", "10.0.0.1", true);
        assertThat(identity.auditReader()).isTrue();
    }

    @Test
    void fromApiKeyDefaultsAuditReaderFalse() {
        var identity = CallerIdentity.fromApiKey("dev", "Dev Team", "10.0.0.1");
        assertThat(identity.auditReader()).isFalse();
    }
}

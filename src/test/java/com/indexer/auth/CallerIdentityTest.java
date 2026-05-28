package com.indexer.auth;

import org.junit.jupiter.api.Test;
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
}

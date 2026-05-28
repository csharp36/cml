package com.indexer.auth;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticatorTest {

    @Test
    void authenticatesValidKey() {
        var keys = List.of(new ApiKeyAuthenticator.ApiKeyConfig("secret-key-123", "team-payments", "Payments Team", false));
        var authenticator = new ApiKeyAuthenticator(keys);
        var result = authenticator.authenticate("secret-key-123", "10.0.0.1");
        assertThat(result).isPresent();
        assertThat(result.get().userId()).isEqualTo("team-payments");
        assertThat(result.get().displayName()).isEqualTo("Payments Team");
        assertThat(result.get().authMethod()).isEqualTo("api-key");
        assertThat(result.get().sourceIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void rejectsInvalidKey() {
        var keys = List.of(new ApiKeyAuthenticator.ApiKeyConfig("secret-key-123", "team-payments", "Payments Team", false));
        var authenticator = new ApiKeyAuthenticator(keys);
        assertThat(authenticator.authenticate("wrong-key", "10.0.0.1")).isEmpty();
    }

    @Test
    void rejectsNullKey() {
        var keys = List.of(new ApiKeyAuthenticator.ApiKeyConfig("secret-key-123", "team-payments", "Payments Team", false));
        var authenticator = new ApiKeyAuthenticator(keys);
        assertThat(authenticator.authenticate(null, "10.0.0.1")).isEmpty();
    }

    @Test
    void supportsMultipleKeys() {
        var keys = List.of(
                new ApiKeyAuthenticator.ApiKeyConfig("key-alpha", "alice", "Alice Chen", false),
                new ApiKeyAuthenticator.ApiKeyConfig("key-beta", "bob", "Bob Smith", false)
        );
        var authenticator = new ApiKeyAuthenticator(keys);
        assertThat(authenticator.authenticate("key-alpha", "1.1.1.1").get().userId()).isEqualTo("alice");
        assertThat(authenticator.authenticate("key-beta", "2.2.2.2").get().userId()).isEqualTo("bob");
    }

    @Test
    void isEnabledWithKeys() {
        var authenticator = new ApiKeyAuthenticator(List.of(new ApiKeyAuthenticator.ApiKeyConfig("key", "id", "name", false)));
        assertThat(authenticator.isEnabled()).isTrue();
    }

    @Test
    void isDisabledWithoutKeys() {
        assertThat(new ApiKeyAuthenticator(List.of()).isEnabled()).isFalse();
    }

    @Test
    void isDisabledWithNull() {
        assertThat(new ApiKeyAuthenticator(null).isEnabled()).isFalse();
    }
}

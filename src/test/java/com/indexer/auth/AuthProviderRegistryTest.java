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
        var authConfig = new IndexerConfig.AuthConfig("ssh-key", Map.of("keyPath", tempDir.resolve("nonexistent").toString()));
        assertThatThrownBy(() -> registry.resolve(authConfig))
                .isInstanceOf(AuthResolutionException.class)
                .hasMessageContaining("not found");
    }
}

package com.indexer.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void validSignaturePasses() throws Exception {
        assertThat(GitHubWebhookVerifier.isValid(BODY, SECRET, sign(BODY, SECRET))).isTrue();
    }

    @Test
    void tamperedBodyFails() throws Exception {
        String sig = sign(BODY, SECRET);
        byte[] tampered = "Hello, World?".getBytes(StandardCharsets.UTF_8);
        assertThat(GitHubWebhookVerifier.isValid(tampered, SECRET, sig)).isFalse();
    }

    @Test
    void wrongSecretFails() throws Exception {
        assertThat(GitHubWebhookVerifier.isValid(BODY, "wrong-secret", sign(BODY, SECRET))).isFalse();
    }

    @Test
    void missingHeaderFails() {
        assertThat(GitHubWebhookVerifier.isValid(BODY, SECRET, null)).isFalse();
    }

    @Test
    void malformedHeaderWithoutPrefixFails() {
        assertThat(GitHubWebhookVerifier.isValid(BODY, SECRET, "abc123")).isFalse();
    }

    @Test
    void blankSecretFails() throws Exception {
        assertThat(GitHubWebhookVerifier.isValid(BODY, "", sign(BODY, SECRET))).isFalse();
    }
}

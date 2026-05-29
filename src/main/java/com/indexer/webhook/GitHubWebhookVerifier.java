package com.indexer.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies GitHub webhook payloads using the per-repo HMAC-SHA256 shared secret.
 * GitHub sends the signature in the {@code X-Hub-Signature-256} header as
 * {@code sha256=<hex>} over the raw request body.
 */
public final class GitHubWebhookVerifier {

    private GitHubWebhookVerifier() {
    }

    /**
     * @param body            the raw request body bytes (must be the exact bytes received)
     * @param secret          the repo's configured webhook secret
     * @param signatureHeader the value of the X-Hub-Signature-256 header
     * @return true iff the signature header matches the HMAC of the body under the secret
     */
    public static boolean isValid(byte[] body, String secret, String signatureHeader) {
        if (body == null || secret == null || secret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}

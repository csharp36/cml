package com.indexer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    private final List<ApiKeyConfig> apiKeys;

    public record ApiKeyConfig(String key, String id, String name) {}

    public ApiKeyAuthenticator(List<ApiKeyConfig> apiKeys) {
        this.apiKeys = apiKeys != null ? apiKeys : List.of();
        if (isEnabled()) {
            log.info("API key authentication enabled with {} configured key(s)", this.apiKeys.size());
        }
    }

    public Optional<CallerIdentity> authenticate(String bearerToken, String sourceIp) {
        if (bearerToken == null) {
            return Optional.empty();
        }
        for (var keyConfig : apiKeys) {
            if (constantTimeEquals(keyConfig.key(), bearerToken)) {
                return Optional.of(CallerIdentity.fromApiKey(keyConfig.id(), keyConfig.name(), sourceIp));
            }
        }
        return Optional.empty();
    }

    public boolean isEnabled() {
        return !apiKeys.isEmpty();
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}

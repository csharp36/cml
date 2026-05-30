package com.indexer.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.indexer.config.ConfigValidationException;

import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    private final List<ApiKeyConfig> apiKeys;

    public record ApiKeyConfig(String key, String id, String name, boolean auditReader,
                               boolean scipUpload, List<String> repos) {}

    public ApiKeyAuthenticator(List<ApiKeyConfig> apiKeys) {
        this.apiKeys = apiKeys != null ? apiKeys : List.of();
        validate();
        if (isEnabled()) {
            log.info("API key authentication enabled with {} configured key(s)", this.apiKeys.size());
        }
    }

    private void validate() {
        var seenIds = new HashSet<String>();
        for (var keyConfig : apiKeys) {
            if (keyConfig.key() == null || keyConfig.key().isBlank()) {
                throw new ConfigValidationException("API key value must not be empty for id: " + keyConfig.id());
            }
            if (keyConfig.key().contains("${")) {
                log.warn("API key for id '{}' appears to contain un-substituted variable reference", keyConfig.id());
            }
            if (keyConfig.id() == null || keyConfig.id().isBlank()) {
                throw new ConfigValidationException("API key id must not be empty");
            }
            if (!seenIds.add(keyConfig.id())) {
                throw new ConfigValidationException("Duplicate API key id: " + keyConfig.id());
            }
        }
    }

    public Optional<CallerIdentity> authenticate(String bearerToken, String sourceIp) {
        if (bearerToken == null) {
            return Optional.empty();
        }
        for (var keyConfig : apiKeys) {
            if (constantTimeEquals(keyConfig.key(), bearerToken)) {
                return Optional.of(CallerIdentity.fromApiKey(keyConfig.id(), keyConfig.name(), sourceIp,
                        keyConfig.auditReader(), keyConfig.scipUpload(), keyConfig.repos()));
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

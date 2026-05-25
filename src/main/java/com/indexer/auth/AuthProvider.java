package com.indexer.auth;

import com.indexer.config.IndexerConfig;

public interface AuthProvider {
    GitCredentials resolve(IndexerConfig.AuthConfig config);
    boolean supports(String authType);
}

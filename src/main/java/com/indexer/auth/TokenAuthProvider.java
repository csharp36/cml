package com.indexer.auth;

import com.indexer.config.IndexerConfig;

public class TokenAuthProvider implements AuthProvider {

    @Override
    public boolean supports(String authType) {
        return "token".equals(authType);
    }

    @Override
    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        String token = config.get("token");
        if (token == null || token.isBlank()) {
            throw new AuthResolutionException("Token auth requires a non-blank 'token' in properties");
        }
        return GitCredentials.token(token);
    }
}

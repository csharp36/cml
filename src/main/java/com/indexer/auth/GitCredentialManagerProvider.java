package com.indexer.auth;

import com.indexer.config.IndexerConfig;

public class GitCredentialManagerProvider implements AuthProvider {

    @Override
    public boolean supports(String authType) {
        return "git-credential-manager".equals(authType);
    }

    @Override
    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        return GitCredentials.gitCredentialManager();
    }
}

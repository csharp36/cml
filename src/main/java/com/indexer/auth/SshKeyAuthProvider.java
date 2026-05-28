package com.indexer.auth;

import com.indexer.config.IndexerConfig;
import com.indexer.util.PathUtil;
import java.nio.file.Files;
import java.nio.file.Path;

public class SshKeyAuthProvider implements AuthProvider {

    @Override
    public boolean supports(String authType) {
        return "ssh-key".equals(authType);
    }

    @Override
    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        String keyPathStr = config.get("keyPath");
        if (keyPathStr == null || keyPathStr.isBlank()) {
            throw new AuthResolutionException("SSH key auth requires 'keyPath' in properties");
        }
        Path keyPath = Path.of(PathUtil.expandUserHome(keyPathStr));
        if (!Files.exists(keyPath)) {
            throw new AuthResolutionException("SSH key file not found: " + keyPath);
        }
        return GitCredentials.sshKey(keyPath);
    }
}

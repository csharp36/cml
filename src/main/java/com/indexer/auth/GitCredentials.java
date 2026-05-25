package com.indexer.auth;

import java.nio.file.Path;

public record GitCredentials(Type type, Path sshKeyPath, String token, Path certPath, Path certKeyPath) {

    public enum Type { SSH_KEY, TOKEN, GIT_CREDENTIAL_MANAGER, CLIENT_CERT, OAUTH2, KERBEROS }

    public static GitCredentials sshKey(Path keyPath) {
        return new GitCredentials(Type.SSH_KEY, keyPath, null, null, null);
    }

    public static GitCredentials token(String token) {
        return new GitCredentials(Type.TOKEN, null, token, null, null);
    }

    public static GitCredentials gitCredentialManager() {
        return new GitCredentials(Type.GIT_CREDENTIAL_MANAGER, null, null, null, null);
    }

    public static GitCredentials clientCert(Path certPath, Path keyPath) {
        return new GitCredentials(Type.CLIENT_CERT, null, null, certPath, keyPath);
    }
}

package com.indexer.auth;

import com.indexer.config.IndexerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public class AuthProviderRegistry {

    private final List<AuthProvider> providers;

    public AuthProviderRegistry() {
        providers = new ArrayList<>();
        // Register built-in providers
        providers.add(new SshKeyAuthProvider());
        providers.add(new TokenAuthProvider());
        providers.add(new GitCredentialManagerProvider());
        // Load additional providers via ServiceLoader (plugin JARs)
        ServiceLoader.load(AuthProvider.class).forEach(providers::add);
    }

    public GitCredentials resolve(IndexerConfig.AuthConfig config) {
        String authType = config.type();
        return providers.stream()
                .filter(p -> p.supports(authType))
                .findFirst()
                .map(p -> p.resolve(config))
                .orElseThrow(() -> new AuthResolutionException(
                        "No AuthProvider found for auth type: " + authType));
    }
}

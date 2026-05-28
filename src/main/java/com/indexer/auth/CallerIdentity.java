package com.indexer.auth;

import java.util.List;

public record CallerIdentity(
        String userId,
        String displayName,
        String authMethod,
        String transport,
        String sourceIp,
        String clientName,
        String clientVersion,
        List<String> groups,
        boolean auditReader,
        boolean scipUpload
) {
    public CallerIdentity {
        groups = groups != null ? List.copyOf(groups) : List.of();
    }

    public static final String CONTEXT_KEY = "callerIdentity";

    public static CallerIdentity anonymous(String transport) {
        return new CallerIdentity(null, "anonymous", "none", transport, null, null, null, List.of(), false, false);
    }

    public static CallerIdentity fromStdio() {
        String osUser = System.getProperty("user.name");
        return new CallerIdentity(osUser, osUser, "stdio-os-user", "stdio", null, null, null, List.of(), true, false);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), false, false);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader, false);
    }

    public static CallerIdentity fromApiKey(String id, String name, String sourceIp, boolean auditReader, boolean scipUpload) {
        return new CallerIdentity(id, name, "api-key", "streamable-http", sourceIp, null, null, List.of(), auditReader, scipUpload);
    }

    public static CallerIdentity fromOAuth(String sub, String name, List<String> groups, String sourceIp) {
        return new CallerIdentity(sub, name, "oauth", "streamable-http", sourceIp, null, null, groups, false, false);
    }

    public static CallerIdentity fromAdminToken(String sourceIp) {
        return new CallerIdentity("admin", "Admin", "admin-token", "streamable-http", sourceIp, null, null, List.of(), false, false);
    }
}

package com.indexer.util;

/**
 * Path helpers for config-supplied paths.
 *
 * <p>The JVM does not expand a leading {@code ~} the way a shell does, so paths read from
 * config files (e.g. {@code cloneBaseDir}, SSH {@code keyPath}) must be expanded explicitly —
 * otherwise {@code ~/foo} is treated as a literal relative directory named {@code ~}.
 */
public final class PathUtil {

    private PathUtil() {
    }

    /**
     * Expands a leading {@code ~} or {@code ~/} to the current user's home directory.
     * Paths that are absolute, relative, or use {@code ~user} syntax are returned unchanged
     * ({@code ~user} home resolution is not supported).
     *
     * @param path a path string, possibly {@code null}
     * @return the expanded path, or the input unchanged
     */
    public static String expandUserHome(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '~') {
            return path;
        }
        String home = System.getProperty("user.home");
        if (path.equals("~")) {
            return home;
        }
        if (path.startsWith("~/")) {
            return home + path.substring(1);
        }
        // "~otheruser/..." — not supported; leave as-is.
        return path;
    }
}

package com.indexer.repository;

import com.indexer.auth.GitCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitOperations {

    private static final Logger log = LoggerFactory.getLogger(GitOperations.class);
    private static final int TIMEOUT_SECONDS = 300;

    public void clone(String url, String branch, Path targetDir, GitCredentials creds) throws IOException {
        String effectiveUrl = buildUrl(url, creds);
        List<String> cmd = new ArrayList<>(List.of(
                "git", "clone", "--branch", branch, effectiveUrl, targetDir.toAbsolutePath().toString()
        ));
        runCommand(cmd, null, creds);
    }

    public void fetch(Path repoDir, GitCredentials creds) throws IOException {
        List<String> cmd = List.of("git", "fetch", "--prune");
        runCommand(cmd, repoDir, creds);
    }

    public void fastForward(Path repoDir, String branch) throws IOException {
        List<String> cmd = List.of("git", "merge", "--ff-only", "origin/" + branch);
        runCommand(cmd, repoDir, null);
    }

    public String getCurrentSha(Path repoDir) throws IOException {
        List<String> cmd = List.of("git", "rev-parse", "HEAD");
        return runCommandOutput(cmd, repoDir, null).trim();
    }

    public List<DiffEntry> diff(Path repoDir, String fromSha, String toSha) throws IOException {
        List<String> cmd = List.of("git", "diff", "--name-status", fromSha, toSha);
        String output = runCommandOutput(cmd, repoDir, null);
        List<DiffEntry> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", 2);
            if (parts.length < 2) continue;
            String statusChar = parts[0].trim();
            String filePath = parts[1].trim();
            DiffEntry.Type type = switch (statusChar.charAt(0)) {
                case 'A' -> DiffEntry.Type.ADDED;
                case 'M' -> DiffEntry.Type.MODIFIED;
                case 'D' -> DiffEntry.Type.DELETED;
                default -> DiffEntry.Type.MODIFIED;
            };
            entries.add(new DiffEntry(type, filePath));
        }
        return entries;
    }

    public List<String> listAllFiles(Path repoDir) throws IOException {
        List<String> cmd = List.of("git", "ls-files");
        String output = runCommandOutput(cmd, repoDir, null);
        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                files.add(trimmed);
            }
        }
        return files;
    }

    /**
     * Get the list of files changed between main and a branch SHA.
     */
    public List<String> diffFromMain(Path repoDir, String branchSha) throws IOException {
        List<String> cmd = List.of("git", "diff", "--name-only", "main..." + branchSha);
        String output = runCommandOutput(cmd, repoDir, null);
        List<String> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                files.add(trimmed);
            }
        }
        return files;
    }

    /**
     * Read file content from a specific git ref without checkout.
     */
    public String showFile(Path repoDir, String ref, String filePath) throws IOException {
        List<String> cmd = List.of("git", "show", ref + ":" + filePath);
        return runCommandOutput(cmd, repoDir, null);
    }

    /**
     * Check if a remote branch exists.
     */
    public boolean remoteBranchExists(Path repoDir, String branch) throws IOException {
        try {
            List<String> cmd = List.of("git", "branch", "-r", "--list", "origin/" + branch);
            String output = runCommandOutput(cmd, repoDir, null);
            return !output.trim().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get the SHA of a specific ref.
     */
    public String getShaForRef(Path repoDir, String ref) throws IOException {
        List<String> cmd = List.of("git", "rev-parse", ref);
        return runCommandOutput(cmd, repoDir, null).trim();
    }

    // --- helpers ---

    private String buildUrl(String url, GitCredentials creds) {
        if (creds == null || creds.type() != GitCredentials.Type.TOKEN) {
            return url;
        }
        String token = creds.token();
        if (token == null || token.isEmpty()) {
            return url;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                return url;
            }
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            String portPart = (port == -1) ? "" : ":" + port;
            return scheme + "://oauth2:" + token + "@" + host + portPart + path;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private ProcessBuilder buildProcess(List<String> cmd, Path workDir, GitCredentials creds) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.redirectErrorStream(true);
        if (creds != null && creds.type() == GitCredentials.Type.SSH_KEY && creds.sshKeyPath() != null) {
            pb.environment().put(
                    "GIT_SSH_COMMAND",
                    "ssh -i " + creds.sshKeyPath().toAbsolutePath() + " -o StrictHostKeyChecking=no"
            );
        }
        return pb;
    }

    private void runCommand(List<String> cmd, Path workDir, GitCredentials creds) throws IOException {
        ProcessBuilder pb = buildProcess(cmd, workDir, creds);
        log.debug("Running git command: {}", cmd);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to start git process: " + cmd, e);
        }
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted: " + cmd, e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Git command timed out after " + TIMEOUT_SECONDS + "s: " + cmd);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Git command failed with exit code " + exitCode + ": " + cmd + "\nOutput: " + output);
        }
    }

    private String runCommandOutput(List<String> cmd, Path workDir, GitCredentials creds) throws IOException {
        ProcessBuilder pb = buildProcess(cmd, workDir, creds);
        log.debug("Running git command: {}", cmd);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to start git process: " + cmd, e);
        }
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted: " + cmd, e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Git command timed out after " + TIMEOUT_SECONDS + "s: " + cmd);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("Git command failed with exit code " + exitCode + ": " + cmd + "\nOutput: " + output);
        }
        return output;
    }

    public record DiffEntry(Type type, String path) {
        public enum Type { ADDED, MODIFIED, DELETED }
    }
}

package com.indexer.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitOperationsTest {

    @TempDir
    Path tempDir;

    private GitOperations gitOps;
    private Path repoDir;
    private String firstSha;
    private String secondSha;

    @BeforeEach
    void setUp() throws Exception {
        gitOps = new GitOperations();
        repoDir = tempDir.resolve("test-repo");
        Files.createDirectories(repoDir);

        // Initialize git repo
        run(repoDir, "git", "init");
        run(repoDir, "git", "config", "user.email", "test@example.com");
        run(repoDir, "git", "config", "user.name", "Test User");
        run(repoDir, "git", "config", "commit.gpgsign", "false");

        // First commit: add a file
        Path fileA = repoDir.resolve("FileA.java");
        Files.writeString(fileA, "public class FileA {}");
        run(repoDir, "git", "add", "FileA.java");
        run(repoDir, "git", "commit", "-m", "Initial commit");
        firstSha = gitOps.getCurrentSha(repoDir);

        // Second commit: add another file + modify the first
        Path fileB = repoDir.resolve("FileB.java");
        Files.writeString(fileB, "public class FileB {}");
        Files.writeString(fileA, "public class FileA { int x; }");
        run(repoDir, "git", "add", "FileA.java", "FileB.java");
        run(repoDir, "git", "commit", "-m", "Add FileB, modify FileA");
        secondSha = gitOps.getCurrentSha(repoDir);
    }

    @Test
    void getCurrentShaReturnsValidSha() throws IOException {
        String sha = gitOps.getCurrentSha(repoDir);

        assertThat(sha).isNotNull();
        assertThat(sha).isNotBlank();
        // A git SHA is 40 hex characters
        assertThat(sha).matches("[0-9a-f]{40}");
    }

    @Test
    void diffReturnsAddedEntry() throws IOException {
        List<GitOperations.DiffEntry> entries = gitOps.diff(repoDir, firstSha, secondSha);

        assertThat(entries).isNotEmpty();
        boolean hasAdded = entries.stream()
                .anyMatch(e -> e.type() == GitOperations.DiffEntry.Type.ADDED
                        && e.path().contains("FileB.java"));
        assertThat(hasAdded)
                .as("Expected an ADDED entry for FileB.java")
                .isTrue();
    }

    @Test
    void diffReturnsModifiedEntry() throws IOException {
        List<GitOperations.DiffEntry> entries = gitOps.diff(repoDir, firstSha, secondSha);

        assertThat(entries).isNotEmpty();
        boolean hasModified = entries.stream()
                .anyMatch(e -> e.type() == GitOperations.DiffEntry.Type.MODIFIED
                        && e.path().contains("FileA.java"));
        assertThat(hasModified)
                .as("Expected a MODIFIED entry for FileA.java")
                .isTrue();
    }

    @Test
    void diffReturnsBothEntries() throws IOException {
        List<GitOperations.DiffEntry> entries = gitOps.diff(repoDir, firstSha, secondSha);

        assertThat(entries).hasSize(2);
    }

    @Test
    void listAllFilesReturnsTrackedFiles() throws IOException {
        List<String> files = gitOps.listAllFiles(repoDir);

        assertThat(files).contains("FileA.java", "FileB.java");
    }

    // --- helper ---

    private void run(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed (" + exit + "): " + List.of(cmd) + "\n" + output);
        }
    }
}

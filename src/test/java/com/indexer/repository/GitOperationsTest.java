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

    @Test
    void resolveAnyRefResolvesTagAndSha() throws Exception {
        run(repoDir, "git", "tag", "v1.0");

        var tag = gitOps.resolveAnyRef(repoDir, "v1.0");
        assertThat(tag).isPresent();
        assertThat(tag.get().kind()).isEqualTo(RefKind.TAG);
        assertThat(tag.get().sha()).isEqualTo(secondSha);

        var sha = gitOps.resolveAnyRef(repoDir, secondSha);
        assertThat(sha).isPresent();
        assertThat(sha.get().kind()).isEqualTo(RefKind.SHA);
        assertThat(sha.get().sha()).isEqualTo(secondSha);

        assertThat(gitOps.resolveAnyRef(repoDir, "does-not-exist")).isEmpty();
    }

    @Test
    void resolveAnyRefResolvesRemoteBranchAndFetchesTags() throws Exception {
        Path origin = tempDir.resolve("origin");
        Files.createDirectories(origin);
        run(origin, "git", "init");
        run(origin, "git", "config", "user.email", "test@example.com");
        run(origin, "git", "config", "user.name", "Test User");
        run(origin, "git", "config", "commit.gpgsign", "false");
        Files.writeString(origin.resolve("R.java"), "public class R {}");
        run(origin, "git", "add", "R.java");
        run(origin, "git", "commit", "-m", "init");

        Path clone = tempDir.resolve("clone");
        run(tempDir, "git", "clone", origin.toString(), clone.toString());
        String defaultBranch = runDefaultBranch(clone);

        var br = gitOps.resolveAnyRef(clone, defaultBranch);
        assertThat(br).isPresent();
        assertThat(br.get().kind()).isEqualTo(RefKind.BRANCH);

        run(origin, "git", "tag", "v2.0");
        gitOps.fetch(clone, null); // must fetch tags
        var tag = gitOps.resolveAnyRef(clone, "v2.0");
        assertThat(tag).as("fetch must bring remote tags into the clone").isPresent();
        assertThat(tag.get().kind()).isEqualTo(RefKind.TAG);
    }

    /** Returns the clone's current branch name (e.g. "main" or "master"). */
    private String runDefaultBranch(Path dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return out;
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

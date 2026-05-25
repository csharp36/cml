package com.indexer.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HookInstallerTest {

    private static final String WEBHOOK_URL = "http://localhost:8081";

    @TempDir
    Path tempDir;

    private Path createFakeRepo() throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Path hooksDir = gitDir.resolve("hooks");
        Files.createDirectories(hooksDir);
        return tempDir;
    }

    @Test
    void allFourHookFilesAreCreated() throws IOException {
        Path repoPath = createFakeRepo();
        HookInstaller installer = new HookInstaller(WEBHOOK_URL);

        installer.installHooks(repoPath);

        Path hooksDir = repoPath.resolve(".git/hooks");
        assertThat(hooksDir.resolve("post-commit")).exists();
        assertThat(hooksDir.resolve("post-merge")).exists();
        assertThat(hooksDir.resolve("post-checkout")).exists();
        assertThat(hooksDir.resolve("post-rewrite")).exists();
    }

    @Test
    void hookScriptsAreExecutable() throws IOException {
        Path repoPath = createFakeRepo();
        HookInstaller installer = new HookInstaller(WEBHOOK_URL);

        installer.installHooks(repoPath);

        Path hooksDir = repoPath.resolve(".git/hooks");
        for (String hookName : new String[]{"post-commit", "post-merge", "post-checkout", "post-rewrite"}) {
            Path hookFile = hooksDir.resolve(hookName);
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(hookFile);
                assertThat(perms).contains(PosixFilePermission.OWNER_EXECUTE);
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem — skip permission check
            }
        }
    }

    @Test
    void hookContentContainsCurlAndWebhookUrl() throws IOException {
        Path repoPath = createFakeRepo();
        HookInstaller installer = new HookInstaller(WEBHOOK_URL);

        installer.installHooks(repoPath);

        Path hooksDir = repoPath.resolve(".git/hooks");
        for (String hookName : new String[]{"post-commit", "post-merge", "post-checkout", "post-rewrite"}) {
            String content = Files.readString(hooksDir.resolve(hookName));
            assertThat(content)
                    .as("Hook %s should contain curl", hookName)
                    .contains("curl");
            assertThat(content)
                    .as("Hook %s should contain webhook URL", hookName)
                    .contains(WEBHOOK_URL);
        }
    }

    @Test
    void postCommitHookContainsPostCommitEventType() throws IOException {
        Path repoPath = createFakeRepo();
        HookInstaller installer = new HookInstaller(WEBHOOK_URL);

        installer.installHooks(repoPath);

        String content = Files.readString(repoPath.resolve(".git/hooks/post-commit"));
        assertThat(content).contains("post-commit");
    }

    @Test
    void postCommitHookUsesGitRevParseHeadAndHead1() throws IOException {
        Path repoPath = createFakeRepo();
        HookInstaller installer = new HookInstaller(WEBHOOK_URL);

        installer.installHooks(repoPath);

        String content = Files.readString(repoPath.resolve(".git/hooks/post-commit"));
        assertThat(content).contains("git rev-parse HEAD");
        assertThat(content).contains("HEAD~1");
    }

    @Test
    void hookScriptsRunCurlInBackground() throws IOException {
        Path repoPath = createFakeRepo();
        HookInstaller installer = new HookInstaller(WEBHOOK_URL);

        installer.installHooks(repoPath);

        Path hooksDir = repoPath.resolve(".git/hooks");
        for (String hookName : new String[]{"post-commit", "post-merge", "post-rewrite"}) {
            String content = Files.readString(hooksDir.resolve(hookName));
            // The curl command should end with & to run in background
            assertThat(content)
                    .as("Hook %s should run curl in background with &", hookName)
                    .contains("&");
        }
    }
}

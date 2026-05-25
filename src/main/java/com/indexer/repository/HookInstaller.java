package com.indexer.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public class HookInstaller {

    private static final Logger log = LoggerFactory.getLogger(HookInstaller.class);

    private final String webhookUrl;

    public HookInstaller(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void installHooks(Path repoPath) throws IOException {
        Path hooksDir = repoPath.resolve(".git").resolve("hooks");
        if (!Files.exists(hooksDir)) {
            throw new IOException("Hooks directory does not exist: " + hooksDir);
        }

        installPostCommit(hooksDir, repoPath);
        installPostMerge(hooksDir, repoPath);
        installPostCheckout(hooksDir, repoPath);
        installPostRewrite(hooksDir, repoPath);
    }

    private void installPostCommit(Path hooksDir, Path repoPath) throws IOException {
        String repoName = repoPath.getFileName().toString();
        String script = """
                #!/bin/sh
                REPO_NAME="%s"
                REPO_PATH="%s"
                EVENT_TYPE="post-commit"
                CURRENT_SHA=$(git rev-parse HEAD)
                PREVIOUS_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
                curl -s -X POST "%s/webhook" \\
                  -H "Content-Type: application/json" \\
                  -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"$EVENT_TYPE\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\"}" &
                """.formatted(repoName, repoPath.toAbsolutePath(), webhookUrl);

        writeHook(hooksDir.resolve("post-commit"), script);
    }

    private void installPostMerge(Path hooksDir, Path repoPath) throws IOException {
        String repoName = repoPath.getFileName().toString();
        String script = """
                #!/bin/sh
                REPO_NAME="%s"
                REPO_PATH="%s"
                EVENT_TYPE="post-merge"
                CURRENT_SHA=$(git rev-parse HEAD)
                PREVIOUS_SHA=$(git rev-parse ORIG_HEAD 2>/dev/null || echo "")
                curl -s -X POST "%s/webhook" \\
                  -H "Content-Type: application/json" \\
                  -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"$EVENT_TYPE\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\"}" &
                """.formatted(repoName, repoPath.toAbsolutePath(), webhookUrl);

        writeHook(hooksDir.resolve("post-merge"), script);
    }

    private void installPostCheckout(Path hooksDir, Path repoPath) throws IOException {
        String repoName = repoPath.getFileName().toString();
        // $1 = previous HEAD, $2 = new HEAD, $3 = branch checkout flag (1 = branch checkout, 0 = file checkout)
        String script = """
                #!/bin/sh
                REPO_NAME="%s"
                REPO_PATH="%s"
                EVENT_TYPE="post-checkout"
                PREVIOUS_SHA="$1"
                CURRENT_SHA="$2"
                BRANCH_CHECKOUT="$3"
                if [ "$BRANCH_CHECKOUT" = "1" ]; then
                  curl -s -X POST "%s/webhook" \\
                    -H "Content-Type: application/json" \\
                    -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"$EVENT_TYPE\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\"}" &
                fi
                """.formatted(repoName, repoPath.toAbsolutePath(), webhookUrl);

        writeHook(hooksDir.resolve("post-checkout"), script);
    }

    private void installPostRewrite(Path hooksDir, Path repoPath) throws IOException {
        String repoName = repoPath.getFileName().toString();
        String script = """
                #!/bin/sh
                REPO_NAME="%s"
                REPO_PATH="%s"
                EVENT_TYPE="post-rewrite"
                CURRENT_SHA=$(git rev-parse HEAD)
                PREVIOUS_SHA=$(git rev-parse ORIG_HEAD 2>/dev/null || echo "")
                curl -s -X POST "%s/webhook" \\
                  -H "Content-Type: application/json" \\
                  -d "{\\"repoName\\":\\"$REPO_NAME\\",\\"repoPath\\":\\"$REPO_PATH\\",\\"eventType\\":\\"$EVENT_TYPE\\",\\"previousSha\\":\\"$PREVIOUS_SHA\\",\\"currentSha\\":\\"$CURRENT_SHA\\"}" &
                """.formatted(repoName, repoPath.toAbsolutePath(), webhookUrl);

        writeHook(hooksDir.resolve("post-rewrite"), script);
    }

    private void writeHook(Path hookFile, String content) throws IOException {
        Files.writeString(hookFile, content);
        try {
            Files.setPosixFilePermissions(hookFile, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException e) {
            log.warn("POSIX permissions not supported on this filesystem; hook may not be executable: {}", hookFile);
        }
        log.debug("Installed hook: {}", hookFile);
    }
}

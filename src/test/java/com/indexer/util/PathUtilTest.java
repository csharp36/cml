package com.indexer.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathUtilTest {

    private final String home = System.getProperty("user.home");

    @Test
    void expandsLeadingTildeSlash() {
        assertThat(PathUtil.expandUserHome("~/.source-code-indexer/repos"))
                .isEqualTo(home + "/.source-code-indexer/repos");
    }

    @Test
    void expandsBareTilde() {
        assertThat(PathUtil.expandUserHome("~")).isEqualTo(home);
    }

    @Test
    void leavesAbsolutePathsUnchanged() {
        assertThat(PathUtil.expandUserHome("/tmp/repos")).isEqualTo("/tmp/repos");
    }

    @Test
    void leavesRelativePathsUnchanged() {
        assertThat(PathUtil.expandUserHome("repos/clones")).isEqualTo("repos/clones");
    }

    @Test
    void leavesTildeInMiddleUnchanged() {
        assertThat(PathUtil.expandUserHome("/data/~backup")).isEqualTo("/data/~backup");
    }

    @Test
    void doesNotResolveOtherUserHome() {
        assertThat(PathUtil.expandUserHome("~otheruser/repos")).isEqualTo("~otheruser/repos");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(PathUtil.expandUserHome(null)).isNull();
        assertThat(PathUtil.expandUserHome("")).isEmpty();
    }
}

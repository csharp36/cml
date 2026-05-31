package com.indexer.config;

import com.indexer.config.IndexerConfig.TagConfig;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TagConfigTest {

    @Test
    void blankPatternDefaultsToV() {
        assertThat(new TagConfig(true, null).pattern()).isEqualTo("v*");
        assertThat(new TagConfig(true, "  ").pattern()).isEqualTo("v*");
    }

    @Test
    void globMatchesSemverStyleTags() {
        var cfg = new TagConfig(true, "v*");
        assertThat(cfg.matches("v1.2.3")).isTrue();
        assertThat(cfg.matches("v2.0")).isTrue();
        assertThat(cfg.matches("nightly-2026")).isFalse();
    }

    @Test
    void questionMarkMatchesSingleChar() {
        var cfg = new TagConfig(true, "rc?");
        assertThat(cfg.matches("rc1")).isTrue();
        assertThat(cfg.matches("rc12")).isFalse();
    }

    @Test
    void dotsAreLiteralNotWildcards() {
        var cfg = new TagConfig(true, "v1.0");
        assertThat(cfg.matches("v1.0")).isTrue();
        assertThat(cfg.matches("v1x0")).isFalse();
    }
}

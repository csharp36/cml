package com.indexer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class LanguageRegistryTest {

    private final LanguageRegistry registry = new LanguageRegistry(Map.of(".proto", "protobuf"));

    @ParameterizedTest
    @CsvSource({
            "Main.java, java",
            "app.py, python",
            "index.ts, typescript",
            "component.tsx, typescript",
            "main.go, go",
            "index.js, javascript",
            "app.jsx, javascript",
            "schema.proto, protobuf",
            "README.md, markdown",
            "Makefile, makefile"
    })
    void detectsLanguageFromExtension(String filename, String expected) {
        assertThat(registry.detectLanguage(filename)).isEqualTo(expected);
    }

    @Test
    void unknownExtensionReturnsPlaintext() {
        assertThat(registry.detectLanguage("file.xyz")).isEqualTo("plaintext");
    }

    @Test
    void coreLanguagesAreIdentified() {
        assertThat(registry.isCoreLanguage("java")).isTrue();
        assertThat(registry.isCoreLanguage("python")).isTrue();
        assertThat(registry.isCoreLanguage("typescript")).isTrue();
        assertThat(registry.isCoreLanguage("javascript")).isTrue();
        assertThat(registry.isCoreLanguage("go")).isTrue();
        assertThat(registry.isCoreLanguage("markdown")).isFalse();
        assertThat(registry.isCoreLanguage("plaintext")).isFalse();
    }

    @Test
    void identifiesBinaryFiles() {
        assertThat(registry.isBinary("image.png")).isTrue();
        assertThat(registry.isBinary("archive.zip")).isTrue();
        assertThat(registry.isBinary("app.jar")).isTrue();
        assertThat(registry.isBinary("main.java")).isFalse();
    }
}

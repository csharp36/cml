package com.indexer.indexing;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class SymbolExtractorTest {
    private final SymbolExtractor extractor = new SymbolExtractor();

    @Test
    void extractsJavaClassesAndInterfaces() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");
        assertThat(symbols).anyMatch(s -> s.name().equals("MathOperation") && s.kind().equals("interface"));
        assertThat(symbols).anyMatch(s -> s.name().equals("Calculator") && s.kind().equals("class"));
    }

    @Test
    void extractsJavaMethods() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");
        assertThat(symbols).anyMatch(s -> s.name().equals("add") && s.kind().equals("method") && "public".equals(s.visibility()));
        assertThat(symbols).anyMatch(s -> s.name().equals("subtract") && s.kind().equals("method") && "private".equals(s.visibility()));
        assertThat(symbols).anyMatch(s -> s.name().equals("multiply") && s.kind().equals("method") && s.isStatic());
    }

    @Test
    void extractsJavaImports() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");
        var imports = symbols.stream().filter(s -> s.kind().equals("import")).toList();
        assertThat(imports).anyMatch(s -> s.name().equals("java.util.List"));
        assertThat(imports).anyMatch(s -> s.name().equals("java.util.Optional"));
    }

    @Test
    void extractsTypeRelationships() throws IOException {
        String source = Files.readString(Path.of("src/test/resources/test-repos/java-sample/Calculator.java"));
        var symbols = extractor.extract(source, "java");
        var calculator = symbols.stream().filter(s -> s.name().equals("Calculator") && s.kind().equals("class")).findFirst().orElseThrow();
        assertThat(calculator.relationships()).anyMatch(r -> r.relatedName().equals("MathOperation") && r.kind().equals("implements"));
    }
}

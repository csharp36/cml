package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScipParserTest {

    @Test
    void extractsDefinitionFromOccurrence() {
        var occurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .addAllRange(List.of(10, 4, 10, 10))
                .setSymbolRoles(Scip.SymbolRole.Definition_VALUE)
                .build();

        var symbolInfo = Scip.SymbolInformation.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .addDocumentation("Charges the payment method")
                .setKind(Scip.SymbolInformation.Kind.Method)
                .setDisplayName("charge")
                .build();

        var document = Scip.Document.newBuilder()
                .setRelativePath("src/main/java/com/example/Payment.java")
                .setLanguage("java")
                .addOccurrences(occurrence)
                .addSymbols(symbolInfo)
                .build();

        var index = Scip.Index.newBuilder()
                .addDocuments(document)
                .build();

        var result = ScipParser.parse(index);

        assertThat(result.documentsProcessed()).isEqualTo(1);
        assertThat(result.symbols()).hasSize(1);
        var sym = result.symbols().get(0);
        assertThat(sym.scipSymbol()).isEqualTo("java maven . com/example/Payment#charge().");
        assertThat(sym.displayName()).isEqualTo("charge");
        assertThat(sym.kind()).isEqualTo("Method");
        assertThat(sym.documentation()).isEqualTo("Charges the payment method");
        assertThat(sym.filePath()).isEqualTo("src/main/java/com/example/Payment.java");
        assertThat(sym.startLine()).isEqualTo(11); // SCIP uses 0-based lines, we store 1-based
    }

    @Test
    void extractsRelationships() {
        var relationship = Scip.Relationship.newBuilder()
                .setSymbol("java maven . com/example/PaymentProcessor#.")
                .setIsImplementation(true)
                .build();

        var symbolInfo = Scip.SymbolInformation.newBuilder()
                .setSymbol("java maven . com/example/StripeProcessor#.")
                .setKind(Scip.SymbolInformation.Kind.Class)
                .setDisplayName("StripeProcessor")
                .addRelationships(relationship)
                .build();

        var occurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/StripeProcessor#.")
                .addAllRange(List.of(5, 0, 5, 20))
                .setSymbolRoles(Scip.SymbolRole.Definition_VALUE)
                .build();

        var document = Scip.Document.newBuilder()
                .setRelativePath("src/main/java/com/example/StripeProcessor.java")
                .setLanguage("java")
                .addOccurrences(occurrence)
                .addSymbols(symbolInfo)
                .build();

        var index = Scip.Index.newBuilder().addDocuments(document).build();

        var result = ScipParser.parse(index);

        assertThat(result.relationships()).hasSize(1);
        var rel = result.relationships().get(0);
        assertThat(rel.fromSymbol()).isEqualTo("java maven . com/example/StripeProcessor#.");
        assertThat(rel.toSymbol()).isEqualTo("java maven . com/example/PaymentProcessor#.");
        assertThat(rel.kind()).isEqualTo("implements");
    }

    @Test
    void skipsReferenceOccurrences() {
        var refOccurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/Logger#info().")
                .addAllRange(List.of(20, 8, 20, 12))
                .setSymbolRoles(0)
                .build();

        var defOccurrence = Scip.Occurrence.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .addAllRange(List.of(10, 4, 10, 10))
                .setSymbolRoles(Scip.SymbolRole.Definition_VALUE)
                .build();

        var symbolInfo = Scip.SymbolInformation.newBuilder()
                .setSymbol("java maven . com/example/Payment#charge().")
                .setKind(Scip.SymbolInformation.Kind.Method)
                .setDisplayName("charge")
                .build();

        var document = Scip.Document.newBuilder()
                .setRelativePath("src/Payment.java")
                .addOccurrences(refOccurrence)
                .addOccurrences(defOccurrence)
                .addSymbols(symbolInfo)
                .build();

        var index = Scip.Index.newBuilder().addDocuments(document).build();

        var result = ScipParser.parse(index);

        assertThat(result.symbols()).hasSize(1);
        assertThat(result.symbols().get(0).scipSymbol()).isEqualTo("java maven . com/example/Payment#charge().");
    }

    @Test
    void handlesEmptyIndex() {
        var index = Scip.Index.newBuilder().build();
        var result = ScipParser.parse(index);

        assertThat(result.symbols()).isEmpty();
        assertThat(result.relationships()).isEmpty();
        assertThat(result.documentsProcessed()).isEqualTo(0);
    }
}

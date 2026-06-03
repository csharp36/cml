package oracle;

import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import oracle.model.ProgramEdges;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramExtractorTest {

    /** Parse a fixed-format test-resource .cbl into a ProLeap ASG Program. */
    private static Program parseResource(String resourcePath) throws Exception {
        URL url = ProgramExtractorTest.class.getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("test resource not found: " + resourcePath);
        }
        File file = new File(url.toURI());
        CobolParserParams params = new CobolParserParamsImpl();
        params.setCopyBookExtensions(List.of("cpy", "CPY"));
        params.setIgnoreSyntaxErrors(true);
        return new CobolParserRunnerImpl()
                .analyzeFile(file, CobolSourceFormatEnum.FIXED, params);
    }

    @Test
    void extractsCallXctlFileAndDb2Edges() throws Exception {
        Program program = parseResource("cobol/edges.cbl");

        ProgramEdges e = new ProgramExtractor().extract(program, "EDGES");

        assertEquals("EDGES", e.programId);
        assertEquals(Set.of("STATPGM"), new HashSet<>(e.staticCalls));
        assertEquals(Set.of("WS-PGM"), new HashSet<>(e.dynamicCallIdents));
        assertEquals(Set.of("XCTLLIT"), new HashSet<>(e.staticXctlLink));
        assertEquals(Set.of("WS-PGM"), new HashSet<>(e.dynamicXctlIdents));
        assertTrue(e.filesRead.contains("ACCTFILE"), "filesRead=" + e.filesRead);
        assertTrue(e.filesWritten.contains("ACCT-REC") || e.filesWritten.contains("ACCTFILE"),
                "filesWritten=" + e.filesWritten);
        assertTrue(e.db2Tables.contains("ACCOUNT"), "db2Tables=" + e.db2Tables);
    }

    /**
     * Defect 1: schema-qualified DB2 tables must be captured whole (schema.table), not collapsed to
     * the bare schema. {@code FROM CARDDEMO.TRANSACTION_TYPE} must yield {@code CARDDEMO.TRANSACTION_TYPE},
     * and two distinct tables under the same schema must remain distinct.
     */
    @Test
    void capturesSchemaQualifiedDb2Tables() throws Exception {
        Program program = parseResource("cobol/edges.cbl");

        ProgramEdges e = new ProgramExtractor().extract(program, "EDGES");

        assertTrue(e.db2Tables.contains("CARDDEMO.TRANSACTION_TYPE"),
                "expected fully-qualified table, db2Tables=" + e.db2Tables);
        assertTrue(e.db2Tables.contains("CARDDEMO.AUTHFRDS"),
                "expected fully-qualified table, db2Tables=" + e.db2Tables);
        assertFalse(e.db2Tables.contains("CARDDEMO"),
                "bare schema must not appear as a table, db2Tables=" + e.db2Tables);
    }

    /**
     * Defect 2: a raw-source MOVE-literal scan is the union fallback for ProLeap parse omissions.
     * It must be fixed-format aware (skip column-7 comment lines) and key the receiving field
     * (upper-cased) to the set of literal values moved into it.
     */
    @Test
    void scanMoveLiteralsParsesFixedFormatAndIgnoresComments() {
        String src = String.join("\n",
                "000100 PROCEDURE DIVISION.",
                "000200     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM.",
                "000300*    MOVE 'NOPE' TO X.",
                "000400     MOVE 'COMEN01C' TO WS-DEST.");

        Map<String, Set<String>> got = ProgramExtractor.scanMoveLiterals(src);

        assertTrue(got.containsKey("CDEMO-TO-PROGRAM"), "missing key, got=" + got);
        assertEquals(Set.of("COSGN00C"), got.get("CDEMO-TO-PROGRAM"), "got=" + got);
        assertEquals(Set.of("COMEN01C"), got.get("WS-DEST"), "got=" + got);
        assertFalse(got.containsKey("X"), "commented MOVE must be ignored, got=" + got);
    }
}

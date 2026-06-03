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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B2.5: a subscripted reference into an {@code OCCURS} table that {@code REDEFINES} a
 * {@code VALUE}-initialized group of program names must resolve to all those program names —
 * the marquee CICS menu-dispatch case (CardDemo COMEN02Y).
 *
 * <p>{@code OPT-PGM(WS-IDX)} (a 10-level field inside {@code OPT-ENT OCCURS 3 TIMES}, which
 * redefines the VALUE-initialized {@code OPT-LIST}) resolves to the 3 table program names.
 * {@code WS-PLAIN} (a plain field with no OCCURS / VALUE structure) stays unresolved — the
 * {@code knownPrograms} gate plus the OCCURS scoping keeps it from over-connecting.
 */
class MenuTableTest {

    private static Program parseResource(String resourcePath) throws Exception {
        URL url = MenuTableTest.class.getClassLoader().getResource(resourcePath);
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
    void resolvesOccursTableMenuDispatch() throws Exception {
        Program program = parseResource("cobol/menutbl.cbl");

        Set<String> known = Set.of("PGMAAA", "PGMBBB", "PGMCCC", "MENUT");
        ProgramEdges e = new ProgramExtractor().extract(program, "MENUT", known);

        assertEquals(Set.of("PGMAAA", "PGMBBB", "PGMCCC"),
                new java.util.HashSet<>(e.resolvedDynamicXctlLink));   // OPT-PGM resolves to the 3 table programs
        assertEquals(1, e.unresolvedDynamicCount);                     // WS-PLAIN stays unresolved
    }
}

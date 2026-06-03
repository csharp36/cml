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

class ConstantPropagatorTest {

    /** Parse a fixed-format test-resource .cbl into a ProLeap ASG Program. */
    private static Program parseResource(String resourcePath) throws Exception {
        URL url = ConstantPropagatorTest.class.getClassLoader().getResource(resourcePath);
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
    void resolvesDynamicXctlTargetsViaConstantPropagation() throws Exception {
        Program program = parseResource("cobol/constprop.cbl");

        ProgramEdges e = new ProgramExtractor().extract(program, "CPROP");

        assertEquals(Set.of("COMEN01C", "COADM01C"),
                new HashSet<>(e.resolvedDynamicXctlLink));
        assertEquals(1, e.unresolvedDynamicCount); // WS-UNK never received a literal
    }
}

package oracle;

import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * ProLeap v2.4.0 "parse-all" spike over the AWS CardDemo corpus.
 *
 * v2.4.0 API note: CobolParserParams has NO setFormat()/format field. The source
 * format is passed as a separate argument to the 3-arg overload
 * analyzeFile(File, CobolSourceFormatEnum, CobolParserParams). Copybook dirs,
 * extensions and ignoreSyntaxErrors are set on the params object.
 */
public final class ExtractorMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: ExtractorMain <corpusDir>"); System.exit(2); }
        Path corpus = Paths.get(args[0]);

        List<Path> cbls;
        try (Stream<Path> s = Files.walk(corpus)) {
            cbls = s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".cbl"))
                    .sorted().collect(Collectors.toList());
        }
        Set<File> copyDirs = new TreeSet<>();
        try (Stream<Path> s = Files.walk(corpus)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.toString().toLowerCase().endsWith(".cpy"))
             .forEach(p -> copyDirs.add(p.getParent().toFile()));
        }

        int ok = 0, fail = 0;
        List<String> failures = new ArrayList<>();
        for (Path p : cbls) {
            CobolParserParams params = new CobolParserParamsImpl();
            params.setCopyBookDirectories(new ArrayList<>(copyDirs));
            params.setCopyBookExtensions(List.of("cpy", "CPY"));
            params.setIgnoreSyntaxErrors(true);
            try {
                // v2.4.0: format is a separate arg, not a param setter.
                Program prog = new CobolParserRunnerImpl()
                        .analyzeFile(p.toFile(), CobolSourceFormatEnum.FIXED, params);
                if (prog != null && !prog.getCompilationUnits().isEmpty()) ok++;
                else { fail++; failures.add(p.getFileName() + " (empty model)"); }
            } catch (Throwable t) {
                fail++; failures.add(p.getFileName() + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }
        System.out.printf("COPYBOOK DIRS: %d%n", copyDirs.size());
        System.out.printf("PARSED ok=%d fail=%d total=%d%n", ok, fail, cbls.size());
        failures.forEach(f -> System.out.println("FAIL " + f));
    }
}

package oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.identification.IdentificationDivision;
import io.proleap.cobol.asg.metamodel.identification.ProgramIdParagraph;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import oracle.model.ProgramEdges;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CardDemo corpus emitter (Task B3).
 *
 * <p>Runs the full ProLeap v2.4.0 parse → {@link ProgramExtractor} pipeline over every {@code .cbl}
 * in the corpus, augments each program's edges with copybook dependencies scanned from the RAW
 * source ({@link CopyScanner}, since ProLeap erases {@code COPY} during preprocessing), and writes
 * a sorted {@code raw-edges.json} array via Jackson.
 *
 * <p>v2.4.0 API note: source format is a separate argument to the 3-arg
 * {@code analyzeFile(File, CobolSourceFormatEnum, CobolParserParams)} overload; copybook dirs,
 * extensions and {@code ignoreSyntaxErrors} are set on the params object.
 *
 * <p>Usage: {@code ExtractorMain <corpusDir> <outJsonPath>}
 */
public final class ExtractorMain {

    /** A parsed program carried from pass 1 (parse + collect IDs) to pass 2 (extract edges). */
    private record Parsed(Program program, String programId, String rawSource, Path path) {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ExtractorMain <corpusDir> <outJsonPath>");
            System.exit(2);
        }
        Path corpus = Paths.get(args[0]);
        Path outPath = Paths.get(args[1]);

        List<Path> cbls;
        try (Stream<Path> s = Files.walk(corpus)) {
            cbls = s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".cbl"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        // Auto-discover copybook dirs: every directory containing a .cpy file.
        TreeSet<File> copyDirs = new TreeSet<>();
        try (Stream<Path> s = Files.walk(corpus)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".cpy"))
                    .forEach(p -> copyDirs.add(p.getParent().toFile()));
        }

        List<ProgramEdges> all = new ArrayList<>();
        int parsed = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        long staticCallSum = 0;
        long dynXctlResolvedSum = 0;
        long dynUnresolvedSum = 0;

        // PASS 1 — parse every program and collect each PROGRAM-ID into the knownPrograms set. This
        // set is the over-connect gate for B2.5 OCCURS-table resolution: a structural menu-table
        // match resolves only to literals that name a real corpus program, so plain (non-table)
        // operands and stray VALUE strings never fan out into the call graph.
        List<Parsed> programs = new ArrayList<>();
        Set<String> knownPrograms = new java.util.LinkedHashSet<>();
        for (Path p : cbls) {
            String rawSource;
            try {
                rawSource = Files.readString(p);
            } catch (Throwable t) {
                failed++;
                failures.add(p.getFileName() + " (read: " + t.getClass().getSimpleName() + ")");
                continue;
            }
            try {
                CobolParserParams params = new CobolParserParamsImpl();
                params.setCopyBookDirectories(new ArrayList<>(copyDirs));
                params.setCopyBookExtensions(List.of("cpy", "CPY"));
                params.setIgnoreSyntaxErrors(true);

                Program program = new CobolParserRunnerImpl()
                        .analyzeFile(p.toFile(), CobolSourceFormatEnum.FIXED, params);

                if (program == null || program.getCompilationUnits().isEmpty()) {
                    failed++;
                    failures.add(p.getFileName() + " (empty model)");
                    continue;
                }

                String programId = deriveProgramId(program, p);
                programs.add(new Parsed(program, programId, rawSource, p));
                knownPrograms.add(programId);
            } catch (Throwable t) {
                failed++;
                failures.add(p.getFileName() + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }

        // PASS 2 — extract edges with the corpus PROGRAM-ID set threaded in, plus copybook scan.
        for (Parsed prog : programs) {
            try {
                ProgramEdges edges = new ProgramExtractor()
                        .extract(prog.program, prog.programId, knownPrograms);
                edges.copybooks = new ArrayList<>(CopyScanner.scan(prog.rawSource));

                all.add(edges);
                parsed++;
                staticCallSum += edges.staticCalls.size();
                dynXctlResolvedSum += edges.resolvedDynamicXctlLink.size();
                dynUnresolvedSum += edges.unresolvedDynamicCount;
            } catch (Throwable t) {
                failed++;
                failures.add(prog.path.getFileName() + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            }
        }

        all.sort(Comparator.comparing(e -> e.programId == null ? "" : e.programId));

        Path parent = outPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outPath.toFile(), all);

        // Diagnostics to stderr; the machine-readable summary line goes to stdout.
        System.err.printf("copybook dirs=%d | .cbl found=%d | parsed=%d | failed=%d%n",
                copyDirs.size(), cbls.size(), parsed, failed);
        for (String f : failures) {
            System.err.println("FAIL " + f);
        }
        System.err.println("wrote " + outPath.toAbsolutePath());

        System.out.printf("PROGRAMS=%d STATIC_CALL=%d DYN_XCTL_RESOLVED=%d DYN_UNRESOLVED=%d%n",
                all.size(), staticCallSum, dynXctlResolvedSum, dynUnresolvedSum);
    }

    /**
     * Derive the program id from the PROGRAM-ID paragraph of the first program unit, matching what
     * {@link ProgramExtractor} expects. Falls back to the filename stem when the model has no usable
     * PROGRAM-ID name.
     */
    private static String deriveProgramId(Program program, Path file) {
        for (CompilationUnit cu : program.getCompilationUnits()) {
            for (ProgramUnit pu : cu.getProgramUnits()) {
                IdentificationDivision id = pu.getIdentificationDivision();
                if (id == null) {
                    continue;
                }
                ProgramIdParagraph pid = id.getProgramIdParagraph();
                if (pid != null && pid.getName() != null && !pid.getName().isBlank()) {
                    return pid.getName().trim();
                }
            }
        }
        return fileStem(file);
    }

    private static String fileStem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

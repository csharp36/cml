package com.indexer.scip;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point for splitting a SCIP index into ≤ N-byte parts.
 *
 * Usage: scip-split &lt;input.scip&gt; --max-bytes &lt;N&gt; --out &lt;dir&gt;
 * Writes part-0001.scip … part-NNNN.scip and prints a JSON manifest to stdout.
 */
public final class ScipSplitCli {

    private static final long DEFAULT_MAX_BYTES = 47_185_920L; // 45 MiB (headroom under 50 MB server cap)

    private ScipSplitCli() {}

    public static void main(String[] args) {
        String input = null;
        long maxBytes = DEFAULT_MAX_BYTES;
        Path outDir = Path.of(".");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--max-bytes" -> maxBytes = Long.parseLong(args[++i]);
                case "--out" -> outDir = Path.of(args[++i]);
                case "-h", "--help" -> { usage(); return; }
                default -> {
                    if (args[i].startsWith("--")) { usage(); throw new IllegalArgumentException("Unknown flag: " + args[i]); }
                    input = args[i];
                }
            }
        }
        if (input == null) { usage(); throw new IllegalArgumentException("Missing input .scip file"); }

        Path inputPath = Path.of(input);
        try {
            Files.createDirectories(outDir);
            List<Long> sizes = new ArrayList<>();
            final Path dir = outDir;
            int count;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(inputPath))) {
                count = ScipSplitter.split(in, maxBytes, (n, bytes) -> {
                    Path part = dir.resolve(String.format("part-%04d.scip", n));
                    Files.write(part, bytes);
                    sizes.add((long) bytes.length);
                });
            }
            System.out.println(manifestJson(count, sizes));
        } catch (IOException e) {
            throw new RuntimeException("scip-split failed: " + e.getMessage(), e);
        }
    }

    private static String manifestJson(int parts, List<Long> sizes) {
        StringBuilder sb = new StringBuilder("{\"parts\":").append(parts).append(",\"sizes\":[");
        for (int i = 0; i < sizes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(sizes.get(i));
        }
        return sb.append("]}").toString();
    }

    private static void usage() {
        System.err.println("Usage: scip-split <input.scip> --max-bytes <N> --out <dir>");
    }
}

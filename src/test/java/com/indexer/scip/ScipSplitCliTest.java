package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScipSplitCliTest {

    @Test
    void writesNumberedPartsToOutputDir(@TempDir Path tmp) throws Exception {
        var index = Scip.Index.newBuilder()
                .setMetadata(Scip.Metadata.newBuilder().setProjectRoot("file:///repo").build());
        for (int i = 0; i < 6; i++) {
            index.addDocuments(Scip.Document.newBuilder()
                    .setRelativePath("src/F" + i + ".java").setText("y".repeat(8_000)).build());
        }
        Path input = tmp.resolve("index.scip");
        Files.write(input, index.build().toByteArray());
        Path outDir = tmp.resolve("parts");

        ScipSplitCli.main(new String[]{
                input.toString(), "--max-bytes", "20000", "--out", outDir.toString()});

        List<Path> parts = Files.list(outDir).sorted().toList();
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts.get(0).getFileName().toString()).isEqualTo("part-0001.scip");
        for (Path p : parts) {
            assertThat(Files.size(p)).isLessThanOrEqualTo(20_000L);
            Scip.Index.parseFrom(Files.readAllBytes(p)); // parses without throwing
        }
    }
}

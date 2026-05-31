package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScipSplitterTest {

    /** Build an Index with `count` documents, each carrying `padBytes` of filler to control size. */
    private byte[] buildIndex(int count, int padBytes) {
        var index = Scip.Index.newBuilder()
                .setMetadata(Scip.Metadata.newBuilder().setProjectRoot("file:///repo").build());
        String pad = "x".repeat(padBytes);
        for (int i = 0; i < count; i++) {
            index.addDocuments(Scip.Document.newBuilder()
                    .setRelativePath("src/File" + i + ".java")
                    .setLanguage("java")
                    .setText(pad)   // text field inflates the document size deterministically
                    .build());
        }
        return index.build().toByteArray();
    }

    private List<byte[]> split(byte[] input, long maxBytes) {
        List<byte[]> parts = new ArrayList<>();
        ScipSplitter.split(new ByteArrayInputStream(input), maxBytes,
                (n, bytes) -> parts.add(bytes));
        return parts;
    }

    @Test
    void splitsMultiDocumentIndexIntoMultipleValidParts() throws Exception {
        // 10 documents of ~10 KB each => ~100 KB; cap at 30 KB forces ~4 parts.
        byte[] input = buildIndex(10, 10_000);
        List<byte[]> parts = split(input, 30_000);

        assertThat(parts).hasSizeGreaterThan(1);

        // Every part is a valid Index and is within the cap.
        Set<String> allPaths = new java.util.HashSet<>();
        for (byte[] part : parts) {
            assertThat((long) part.length).isLessThanOrEqualTo(30_000L);
            Scip.Index parsed = Scip.Index.parseFrom(part);
            parsed.getDocumentsList().forEach(d -> allPaths.add(d.getRelativePath()));
        }

        // Union of documents across parts == original set.
        Set<String> expected = Scip.Index.parseFrom(input).getDocumentsList().stream()
                .map(Scip.Document::getRelativePath).collect(Collectors.toSet());
        assertThat(allPaths).isEqualTo(expected);
    }

    @Test
    void concatenationOfPartsReconstructsOriginalDocumentCount() throws Exception {
        byte[] input = buildIndex(10, 10_000);
        List<byte[]> parts = split(input, 30_000);

        // Protobuf concatenation property: concatenated parts parse as the merged Index.
        ByteArrayOutputStream cat = new ByteArrayOutputStream();
        for (byte[] p : parts) cat.write(p);
        Scip.Index merged = Scip.Index.parseFrom(cat.toByteArray());

        assertThat(merged.getDocumentsCount())
                .isEqualTo(Scip.Index.parseFrom(input).getDocumentsCount());
    }

    @Test
    void singleDocumentLargerThanBudgetFailsLoudly() {
        // One 50 KB document, cap at 20 KB => cannot fit under budget.
        byte[] input = buildIndex(1, 50_000);
        assertThatThrownBy(() -> split(input, 20_000))
                .isInstanceOf(ScipSplitException.class)
                .hasMessageContaining("exceeds max part size");
    }
}

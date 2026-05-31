package com.indexer.scip;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SCIP protobuf Index into N smaller valid sub-indexes, slicing at the
 * repeated `documents` (field 2) and `external_symbols` (field 3) boundaries. Because protobuf
 * merges repeated fields on concatenation, every emitted part — metadata (field 1) plus a subset
 * of items — is itself a fully-valid Index.
 *
 * Single streaming pass; peak memory is bounded by one part (≤ maxBytesPerPart) plus the
 * (tiny) metadata bytes. Assumes the standard SCIP layout where metadata precedes documents.
 */
public final class ScipSplitter {

    // Index field numbers (see src/main/proto/scip.proto).
    private static final int FIELD_METADATA = 1;
    private static final int FIELD_DOCUMENTS = 2;
    private static final int FIELD_EXTERNAL_SYMBOLS = 3;

    // Per-item wire overhead allowance (tag + length varint); generous upper bound.
    private static final int ITEM_FRAMING_SLACK = 16;

    /** Receives each emitted part. partNumber is 1-based. */
    @FunctionalInterface
    public interface PartSink {
        void accept(int partNumber, byte[] partBytes) throws IOException;
    }

    private ScipSplitter() {}

    /**
     * @return the number of parts emitted (always ≥ 1).
     * @throws ScipSplitException if a single item cannot fit under maxBytesPerPart.
     */
    public static int split(InputStream in, long maxBytesPerPart, PartSink sink) {
        try {
            CodedInputStream cis = CodedInputStream.newInstance(in);
            cis.setSizeLimit(Integer.MAX_VALUE); // default cap is too small for large indexes

            byte[] metadata = new byte[0];

            // Current bucket state.
            List<byte[]> bucketItems = new ArrayList<>();
            List<Integer> bucketFields = new ArrayList<>();
            long bucketSize = 0;
            int partNumber = 0;

            while (true) {
                int tag = cis.readTag();
                if (tag == 0) break; // EOF
                int field = WireFormat.getTagFieldNumber(tag);

                if (field == FIELD_METADATA) {
                    metadata = cis.readByteArray();
                    continue;
                }
                if (field != FIELD_DOCUMENTS && field != FIELD_EXTERNAL_SYMBOLS) {
                    cis.skipField(tag);
                    continue;
                }

                byte[] item = cis.readByteArray();
                long itemCost = item.length + ITEM_FRAMING_SLACK;
                long metaCost = metadata.length + ITEM_FRAMING_SLACK;

                if (metaCost + itemCost > maxBytesPerPart) {
                    throw new ScipSplitException(
                            "SCIP item (field " + field + ", " + item.length
                            + " bytes) exceeds max part size of " + maxBytesPerPart
                            + " bytes; raise --max-bytes");
                }

                // Flush current bucket if adding this item would overflow.
                if (!bucketItems.isEmpty() && bucketSize + itemCost > maxBytesPerPart) {
                    emit(sink, ++partNumber, metadata, bucketItems, bucketFields);
                    bucketItems = new ArrayList<>();
                    bucketFields = new ArrayList<>();
                    bucketSize = 0;
                }
                if (bucketItems.isEmpty()) {
                    bucketSize = metaCost; // metadata is replicated into every part
                }
                bucketItems.add(item);
                bucketFields.add(field);
                bucketSize += itemCost;
            }

            // Emit the trailing bucket (or an empty metadata-only part if the index had no items).
            if (!bucketItems.isEmpty() || partNumber == 0) {
                emit(sink, ++partNumber, metadata, bucketItems, bucketFields);
            }
            return partNumber;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to split SCIP index", e);
        }
    }

    private static void emit(PartSink sink, int partNumber, byte[] metadata,
                             List<byte[]> items, List<Integer> fields) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(baos);
        if (metadata.length > 0) {
            out.writeByteArray(FIELD_METADATA, metadata);
        }
        for (int i = 0; i < items.size(); i++) {
            out.writeByteArray(fields.get(i), items.get(i));
        }
        out.flush();
        sink.accept(partNumber, baos.toByteArray());
    }
}

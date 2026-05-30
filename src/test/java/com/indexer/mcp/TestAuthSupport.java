package com.indexer.mcp;

import com.indexer.audit.AuditEvent;
import com.indexer.audit.AuditSink;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;

/** Shared test helpers for authorization tests. */
final class TestAuthSupport {

    private TestAuthSupport() {}

    /** Captures audit events so tests can assert denials/successes are recorded. */
    static final class CapturingAuditSink implements AuditSink {
        final List<AuditEvent> events = new ArrayList<>();
        @Override public void record(AuditEvent event) { events.add(event); }
    }

    /** Concatenate the text content blocks of a tool result. */
    static String textOf(McpSchema.CallToolResult result) {
        var sb = new StringBuilder();
        for (var c : result.content()) {
            if (c instanceof McpSchema.TextContent tc) sb.append(tc.text());
        }
        return sb.toString();
    }
}

package com.indexer.audit;

public interface AuditSink {
    /**
     * Record an audit event. Synchronous — blocks until persisted.
     * @throws AuditException if the event cannot be recorded
     */
    void record(AuditEvent event);
}

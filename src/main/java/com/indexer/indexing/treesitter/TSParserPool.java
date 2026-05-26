package com.indexer.indexing.treesitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.TSParser;

import java.io.Closeable;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TSParserPool implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(TSParserPool.class);
    private static final long BORROW_TIMEOUT_SECONDS = 30;

    private final ArrayBlockingQueue<TSParser> pool;
    private volatile boolean closed = false;

    public TSParserPool(int size) {
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.offer(new TSParser());
        }
        log.info("TSParserPool initialized with {} parsers", size);
    }

    public TSParser borrow() {
        if (closed) {
            throw new IllegalStateException("TSParserPool is closed");
        }
        try {
            TSParser parser = pool.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (parser == null) {
                throw new IllegalStateException(
                        "Timed out waiting for TSParser after " + BORROW_TIMEOUT_SECONDS + "s — possible parser leak");
            }
            return parser;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for TSParser", e);
        }
    }

    public void release(TSParser parser) {
        if (parser != null && !closed) {
            pool.offer(parser);
        }
    }

    @Override
    public void close() {
        closed = true;
        // TSParser has no close() method — native finalizer handles cleanup.
        // Drain the queue to prevent further borrows from succeeding.
        while (pool.poll() != null) {
            // discard — native resources freed by GC/finalizer
        }
        log.info("TSParserPool closed");
    }
}

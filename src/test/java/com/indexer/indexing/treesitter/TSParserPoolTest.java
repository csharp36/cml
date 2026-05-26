package com.indexer.indexing.treesitter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.treesitter.TSParser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TSParserPoolTest {

    private TSParserPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
    }

    @Test
    void borrowReturnsNonNullParser() {
        pool = new TSParserPool(2);
        TSParser parser = pool.borrow();
        assertThat(parser).isNotNull();
        pool.release(parser);
    }

    @Test
    void releaseAllowsReuse() {
        pool = new TSParserPool(1);
        TSParser first = pool.borrow();
        pool.release(first);
        TSParser second = pool.borrow();
        assertThat(second).isNotNull();
        pool.release(second);
    }

    @Test
    void borrowBlocksWhenPoolExhausted() throws Exception {
        pool = new TSParserPool(1);
        TSParser only = pool.borrow();

        AtomicBoolean timedOut = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        Thread blocker = new Thread(() -> {
            started.countDown();
            try {
                pool.borrow();
            } catch (IllegalStateException e) {
                timedOut.set(true);
            }
        });
        blocker.start();
        started.await();

        Thread.sleep(100);
        pool.release(only);
        blocker.join(5000);
        assertThat(timedOut.get()).isFalse();
    }

    @Test
    void closeReleasesAllParsers() {
        pool = new TSParserPool(3);
        pool.close();
        assertThatThrownBy(() -> pool.borrow())
                .isInstanceOf(IllegalStateException.class);
        pool = null;
    }
}

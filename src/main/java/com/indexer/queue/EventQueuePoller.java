package com.indexer.queue;

import com.indexer.db.EventDao;
import com.indexer.model.IndexingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class EventQueuePoller implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(EventQueuePoller.class);
    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    public record ProcessableEvent(long eventId, String repoName, String repoPath, String previousSha, String currentSha, String branch, String refKind) {}

    private final EventDao eventDao;
    private final Consumer<ProcessableEvent> eventProcessor;
    private final String workerId;
    private final long pollIntervalMs;
    private final AtomicBoolean running;

    public EventQueuePoller(EventDao eventDao, Consumer<ProcessableEvent> eventProcessor, long pollIntervalMs) {
        this.eventDao = eventDao;
        this.eventProcessor = eventProcessor;
        this.pollIntervalMs = pollIntervalMs;
        this.running = new AtomicBoolean(false);
        this.workerId = "worker-" + randomSuffix(8);
    }

    private static String randomSuffix(int length) {
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(rng.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    @Override
    public void run() {
        running.set(true);
        log.info("EventQueuePoller started with workerId={}", workerId);

        while (running.get()) {
            try {
                var claimed = eventDao.claimNextPending(workerId);
                if (claimed.isEmpty()) {
                    Thread.sleep(pollIntervalMs);
                    continue;
                }

                IndexingEvent primary = claimed.get();
                String repoName = primary.repoName();

                // Check for additional pending events for the same repo (deduplication).
                // Only collapse events that share the primary's branch AND refKind — events for a
                // different ref (e.g. a tag push queued alongside a branch push) must remain pending
                // and be claimed in a later poll iteration.
                List<IndexingEvent> allPendingForRepo = eventDao.findPendingByRepo(repoName);
                List<IndexingEvent> additionalPending = allPendingForRepo.stream()
                        .filter(e -> Objects.equals(e.branch(), primary.branch())
                                && Objects.equals(e.refKind(), primary.refKind()))
                        .toList();

                ProcessableEvent processableEvent;
                List<Long> subsumedIds;

                if (additionalPending.isEmpty()) {
                    // Only the claimed event exists (for this ref)
                    processableEvent = new ProcessableEvent(
                            primary.id(),
                            primary.repoName(),
                            primary.repoPath(),
                            primary.previousSha(),
                            primary.currentSha(),
                            primary.branch(),
                            primary.refKind()
                    );
                    subsumedIds = List.of();
                } else {
                    // Build full list: primary event first, then same-ref additional pending ones
                    List<IndexingEvent> allEvents = new java.util.ArrayList<>();
                    allEvents.add(primary);
                    allEvents.addAll(additionalPending);

                    EventDeduplicator.CollapsedEvent collapsed = EventDeduplicator.collapse(allEvents);

                    processableEvent = new ProcessableEvent(
                            primary.id(),
                            primary.repoName(),
                            primary.repoPath(),
                            collapsed.previousSha(),
                            collapsed.currentSha(),
                            primary.branch(),
                            primary.refKind()
                    );
                    subsumedIds = collapsed.subsummedEventIds();

                    // Mark subsumed events as completed immediately
                    for (long subsumedId : subsumedIds) {
                        eventDao.markCompleted(subsumedId);
                        log.debug("Marked subsumed event {} as completed", subsumedId);
                    }
                }

                try {
                    eventProcessor.accept(processableEvent);
                    eventDao.markCompleted(primary.id());
                    log.info("Event {} processed and marked completed", primary.id());
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                    eventDao.markFailed(primary.id(), errorMsg);
                    log.error("Event {} failed: {}", primary.id(), errorMsg, e);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("EventQueuePoller interrupted, stopping");
                break;
            } catch (Exception e) {
                log.error("Unexpected error in EventQueuePoller loop", e);
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        running.set(false);
        log.info("EventQueuePoller stopped (workerId={})", workerId);
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getWorkerId() {
        return workerId;
    }
}

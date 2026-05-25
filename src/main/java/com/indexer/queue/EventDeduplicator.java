package com.indexer.queue;

import com.indexer.model.IndexingEvent;
import java.util.ArrayList;
import java.util.List;

public class EventDeduplicator {
    public record CollapsedEvent(String previousSha, String currentSha, List<Long> subsummedEventIds) {}

    public static CollapsedEvent collapse(List<IndexingEvent> events) {
        if (events.isEmpty()) throw new IllegalArgumentException("Cannot collapse empty event list");
        if (events.size() == 1) {
            var e = events.get(0);
            return new CollapsedEvent(e.previousSha(), e.currentSha(), List.of());
        }
        String previousSha = events.get(0).previousSha();
        String currentSha = events.get(events.size() - 1).currentSha();
        List<Long> subsumed = new ArrayList<>();
        for (int i = 1; i < events.size(); i++) {
            subsumed.add(events.get(i).id());
        }
        return new CollapsedEvent(previousSha, currentSha, subsumed);
    }
}

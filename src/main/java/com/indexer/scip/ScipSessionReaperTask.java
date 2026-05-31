package com.indexer.scip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic task that deletes abandoned (open, stale) SCIP upload sessions and their staging rows.
 * Modelled after ScipPruneTask: implements Runnable, per-session try/catch so one failure does
 * not abort the rest.
 */
public class ScipSessionReaperTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScipSessionReaperTask.class);

    private final ScipSessionDao sessionDao;
    private final int ttlHours;

    public ScipSessionReaperTask(ScipSessionDao sessionDao, int ttlHours) {
        this.sessionDao = sessionDao;
        this.ttlHours = ttlHours;
    }

    @Override
    public void run() {
        try {
            var stale = sessionDao.findOpenOlderThan(ttlHours);
            for (var session : stale) {
                try {
                    sessionDao.deleteSessionAndStaging(session);
                    log.info("Reaped abandoned SCIP upload session {} (repo_id={}, sha={})",
                            session.id(), session.repoId(), session.targetSha());
                } catch (Exception e) {
                    log.error("Failed to reap SCIP session {}: {}", session.id(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("SCIP session reaper failed: {}", e.getMessage(), e);
        }
    }
}

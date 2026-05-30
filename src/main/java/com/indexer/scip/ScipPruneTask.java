package com.indexer.scip;

import com.indexer.db.RepositoryDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodic task that prunes SCIP data for all indexed repositories.
 * <p>
 * For each repo, upload SHAs that are not the current main SHA, not a live
 * branch_index ref, and whose symbols are older than the grace window are
 * deleted from both scip_symbols and scip_relationships.
 * <p>
 * Modelled after {@link com.indexer.indexing.BranchCleanupTask}: implements Runnable,
 * per-repo try/catch so one failing repo does not abort the others.
 */
public class ScipPruneTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScipPruneTask.class);

    private final ScipDao scipDao;
    private final RepositoryDao repositoryDao;
    private final int graceDays;

    public ScipPruneTask(ScipDao scipDao, RepositoryDao repositoryDao, int graceDays) {
        this.scipDao = scipDao;
        this.repositoryDao = repositoryDao;
        this.graceDays = graceDays;
    }

    @Override
    public void run() {
        try {
            for (var repo : repositoryDao.findAll()) {
                try {
                    int removed = scipDao.prune(repo.id(), graceDays);
                    if (removed > 0) {
                        log.info("SCIP prune: removed {} rows for repo '{}'", removed, repo.name());
                    } else {
                        log.debug("SCIP prune: nothing to prune for repo '{}'", repo.name());
                    }
                } catch (Exception e) {
                    log.error("SCIP prune failed for repo '{}': {}", repo.name(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("SCIP prune task failed: {}", e.getMessage(), e);
        }
    }
}

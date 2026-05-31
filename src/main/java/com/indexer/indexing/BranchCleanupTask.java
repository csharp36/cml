package com.indexer.indexing;

import com.indexer.db.BranchIndexDao;
import com.indexer.db.FileDao;
import com.indexer.model.BranchIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Scheduled task that cleans up expired branch index data.
 * Branch files and their cascaded symbols/imports/contents are deleted
 * when the branch hasn't been accessed within the TTL period.
 */
public class BranchCleanupTask implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(BranchCleanupTask.class);

    private final BranchIndexDao branchIndexDao;
    private final FileDao fileDao;
    private final int ttlDays;

    public BranchCleanupTask(BranchIndexDao branchIndexDao, FileDao fileDao, int ttlDays) {
        this.branchIndexDao = branchIndexDao;
        this.fileDao = fileDao;
        this.ttlDays = ttlDays;
    }

    @Override
    public void run() {
        try {
            List<BranchIndex> expired = branchIndexDao.findExpired(ttlDays, ttlDays); // TODO B4: pass immutable TTL
            if (expired.isEmpty()) {
                log.debug("Branch cleanup: no expired branches found (TTL={}d)", ttlDays);
                return;
            }

            log.info("Branch cleanup: found {} expired branch indices (TTL={}d)", expired.size(), ttlDays);

            for (BranchIndex bi : expired) {
                try {
                    // Delete branch-specific files (symbols/imports/contents cascade via ON DELETE CASCADE)
                    fileDao.deleteByRepoAndBranch(bi.repoId(), bi.branch());
                    // Delete the branch_index record
                    branchIndexDao.delete(bi.repoId(), bi.branch());
                    log.info("Cleaned up branch index: repo={} branch={}", bi.repoId(), bi.branch());
                } catch (Exception e) {
                    log.error("Failed to clean up branch index: repo={} branch={}: {}", bi.repoId(), bi.branch(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Branch cleanup task failed: {}", e.getMessage(), e);
        }
    }
}

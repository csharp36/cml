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
    private final int branchTtlDays;
    private final int immutableTtlDays;

    public BranchCleanupTask(BranchIndexDao branchIndexDao, FileDao fileDao, int branchTtlDays, int immutableTtlDays) {
        this.branchIndexDao = branchIndexDao;
        this.fileDao = fileDao;
        this.branchTtlDays = branchTtlDays;
        this.immutableTtlDays = immutableTtlDays;
    }

    @Override
    public void run() {
        try {
            List<BranchIndex> expired = branchIndexDao.findExpired(branchTtlDays, immutableTtlDays);
            if (expired.isEmpty()) {
                log.debug("Branch cleanup: no expired refs (branchTTL={}d, immutableTTL={}d)", branchTtlDays, immutableTtlDays);
                return;
            }
            log.info("Branch cleanup: found {} expired refs (branchTTL={}d, immutableTTL={}d)",
                    expired.size(), branchTtlDays, immutableTtlDays);

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

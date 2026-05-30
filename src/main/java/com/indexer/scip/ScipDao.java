package com.indexer.scip;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-access operations for SCIP retention management.
 * Intentionally minimal: SCIP reads live in ScipService / QueryExecutor; this DAO holds only the prune (GC) operation.
 * Symbols and relationships for a given upload_sha are always written and deleted together,
 * so the prunable SHA set is derived from scip_symbols (which has uploaded_at) and applied
 * to BOTH tables. scip_relationships has NO uploaded_at column.
 */
public class ScipDao {

    private final Jdbi jdbi;

    public ScipDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Prune SCIP data for a single repo.
     * <p>
     * Retained set = { repositories.last_indexed_sha }
     *              ∪ { every branch_index.indexed_sha for this repo }
     *              ∪ { any upload_sha whose newest symbol upload is within graceDays }
     * <p>
     * Everything else is pruned from both scip_symbols and scip_relationships.
     * Returns the total number of rows deleted (symbols + relationships combined).
     */
    public int prune(int repoId, int graceDays) {
        return jdbi.inTransaction(handle -> {
            // Derive the set of prunable SHAs from scip_symbols.uploaded_at.
            // A SHA is prunable when it is:
            //   1. Not the repo's current main SHA (repositories.last_indexed_sha)
            //   2. Not referenced by any live branch_index row for this repo
            //   3. All its symbol rows are older than the grace window
            List<String> prunable = handle.createQuery("""
                    SELECT upload_sha
                    FROM scip_symbols
                    WHERE repo_id = :repoId
                      AND upload_sha IS DISTINCT FROM
                          (SELECT last_indexed_sha FROM repositories WHERE id = :repoId)
                      AND NOT EXISTS (
                          SELECT 1 FROM branch_index bi
                          WHERE bi.repo_id = :repoId
                            AND bi.indexed_sha = scip_symbols.upload_sha
                      )
                    GROUP BY upload_sha
                    HAVING MAX(uploaded_at) < NOW() - CAST(:graceDays || ' days' AS INTERVAL)
                    """)
                    .bind("repoId", repoId)
                    .bind("graceDays", graceDays)
                    .mapTo(String.class)
                    .list();

            if (prunable.isEmpty()) {
                return 0;
            }

            // Build IN clause manually to avoid JDBI bindList empty-list edge cases
            // and to ensure compatibility across JDBI 3 template engine variants.
            String inClause = buildInClause(prunable);

            int rels = handle.createUpdate(
                            "DELETE FROM scip_relationships WHERE repo_id = :repoId AND upload_sha IN (" + inClause + ")")
                    .bind("repoId", repoId)
                    .bindMap(indexedBindings(prunable))
                    .execute();

            int syms = handle.createUpdate(
                            "DELETE FROM scip_symbols WHERE repo_id = :repoId AND upload_sha IN (" + inClause + ")")
                    .bind("repoId", repoId)
                    .bindMap(indexedBindings(prunable))
                    .execute();

            return rels + syms;
        });
    }

    // Build ":s0, :s1, :s2, ..." positional placeholder clause for an IN list.
    private static String buildInClause(List<String> values) {
        List<String> placeholders = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            placeholders.add(":s" + i);
        }
        return String.join(", ", placeholders);
    }

    // Build { "s0" -> value0, "s1" -> value1, ... } for bindMap.
    private static java.util.Map<String, Object> indexedBindings(List<String> values) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            map.put("s" + i, values.get(i));
        }
        return map;
    }
}

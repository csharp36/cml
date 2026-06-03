package oracle;

import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.data.DataDivision;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryContainer;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import io.proleap.cobol.asg.metamodel.data.datadescription.OccursClause;
import io.proleap.cobol.asg.metamodel.data.datadescription.RedefinesClause;
import oracle.model.ProgramEdges;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B2.5 — OCCURS-table menu-dispatch resolution.
 *
 * <p>The CICS menu dispatch ({@code EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))})
 * indexes into an {@code OCCURS} table that {@code REDEFINES} a {@code VALUE}-initialized group of
 * program names (CardDemo copybook {@code COMEN02Y}). The program names are {@code VALUE}s on
 * unnamed {@code FILLER}s, so the name-keyed direct const-prop (B2) cannot reach them.
 *
 * <p>This pass runs for dynamic operands that are <em>still unresolved</em> after direct const-prop.
 * For each such operand field {@code F}:
 * <ol>
 *   <li>Locate {@code F}'s {@link DataDescriptionEntry} in the data division.</li>
 *   <li><b>Gate:</b> require {@code F} (or an ancestor) to participate in an {@code OCCURS} table.
 *       A plain field (e.g. {@code CDEMO-TO-PROGRAM}, {@code WS-PLAIN}) has no {@code OCCURS}
 *       ancestor and is left untouched — its honest unresolved status is preserved.</li>
 *   <li>Navigate to the redefined group: find the nearest ancestor (the {@code OCCURS} table's
 *       enclosing group) carrying a {@code REDEFINES} clause, resolve the redefined entry by name,
 *       and collect every {@code VALUE '<lit>'} literal in that group's subtree. If no
 *       {@code REDEFINES} is found, fall back to the nearest 01-level ancestor's subtree.</li>
 *   <li>Filter the collected literals to {@code knownPrograms} (the corpus PROGRAM-ID set). This
 *       gate is what prevents over-connecting: a structural match that yields no real program name
 *       resolves to nothing.</li>
 *   <li>If the filtered set is non-empty, the operand resolves to it (over-approximation: the whole
 *       table). Otherwise it remains unresolved.</li>
 * </ol>
 *
 * <p>Approach used: <b>REDEFINES-group navigation</b> on the v2.4.0 ASG entry tree
 * ({@link DataDescriptionEntry#getParentDataDescriptionEntryGroup()},
 * {@link DataDescriptionEntryGroup#getOccursClauses()},
 * {@link DataDescriptionEntryGroup#getRedefinesClause()}), with VALUE literals read from each
 * entry's source ctx text (matching the regex the rest of the extractor already uses).
 */
final class OccursTableResolver {

    // VALUE 'literal' on a single elementary entry. NB: an entry's ctx text is the source with
    // inter-token whitespace stripped (e.g. "05FILLERPICX(08)VALUE'PGMAAA'."), so the space after
    // VALUE / IS is optional here.
    private static final Pattern VALUE_LIT =
            Pattern.compile("(?is)\\bVALUE\\s*(?:IS\\s*)?'([^']+)'");

    private OccursTableResolver() {
    }

    /**
     * Resolve still-unresolved dynamic XCTL/LINK and CALL operands of {@code edges} against the
     * program's OCCURS-over-REDEFINES-of-VALUEs tables. Mutates {@code edges} in place: adds resolved
     * names to the resolved lists and decrements {@code unresolvedDynamicCount} for each operand it
     * newly resolves.
     *
     * @param edges          edges with B2 direct const-prop already applied
     * @param program        the parsed ASG (data division navigated here)
     * @param knownPrograms  corpus PROGRAM-ID set — the over-connect gate (empty ⇒ no-op)
     * @param fieldLiterals  field→reaching-literals map from direct const-prop; an operand with a
     *                       non-empty entry was already resolved (and already un-counted), so this
     *                       pass skips it to keep {@code unresolvedDynamicCount} exact.
     */
    static void resolve(ProgramEdges edges, Program program, Set<String> knownPrograms,
                        Map<String, Set<String>> fieldLiterals) {
        if (knownPrograms == null || knownPrograms.isEmpty()) {
            return;
        }

        // Index every named data entry by name (last writer wins — fine, names of interest are unique).
        Map<String, DataDescriptionEntry> byName = new HashMap<>();
        List<DataDescriptionEntry> roots = new ArrayList<>();
        for (CompilationUnit cu : program.getCompilationUnits()) {
            for (ProgramUnit pu : cu.getProgramUnits()) {
                DataDivision dd = pu.getDataDivision();
                if (dd == null) {
                    continue;
                }
                collectContainers(dd, roots, byName);
            }
        }
        if (byName.isEmpty()) {
            return;
        }

        // Resolve XCTL operands, then CALL operands. Each newly resolved operand un-counts one
        // unresolved (the direct-prop pass counted every operand with no reaching literal).
        edges.unresolvedDynamicCount -=
                resolveOperands(edges.dynamicXctlIdents, edges.resolvedDynamicXctlLink,
                        byName, knownPrograms, fieldLiterals);
        edges.unresolvedDynamicCount -=
                resolveOperands(edges.dynamicCallIdents, edges.resolvedDynamicCalls,
                        byName, knownPrograms, fieldLiterals);
        if (edges.unresolvedDynamicCount < 0) {
            edges.unresolvedDynamicCount = 0;
        }
    }

    /**
     * For each operand whose resolved set is still empty (not present in {@code alreadyResolved}'s
     * contributing literals — we re-derive from scratch and only act on OCCURS-gated operands),
     * attempt OCCURS-table resolution. Returns the number of operands newly resolved.
     */
    private static int resolveOperands(List<String> operands,
                                       List<String> resolvedOut,
                                       Map<String, DataDescriptionEntry> byName,
                                       Set<String> knownPrograms,
                                       Map<String, Set<String>> fieldLiterals) {
        if (operands == null || operands.isEmpty()) {
            return 0;
        }
        Set<String> resolvedSet = new LinkedHashSet<>(resolvedOut);
        int newlyResolved = 0;
        for (String operand : operands) {
            // Skip operands the direct const-prop pass already resolved — they were never counted
            // as unresolved, so decrementing for them would underflow the count.
            Set<String> direct = fieldLiterals.get(operand);
            if (direct != null && !direct.isEmpty()) {
                continue;
            }
            DataDescriptionEntry entry = byName.get(operand);
            if (entry == null) {
                continue; // operand not a data item we can see (no data-division match)
            }
            // Gate: the operand must live inside an OCCURS table.
            if (!hasOccursAncestor(entry)) {
                continue;
            }
            Set<String> names = collectTableProgramNames(entry, byName, knownPrograms);
            if (!names.isEmpty()) {
                resolvedSet.addAll(names);
                newlyResolved++;
            }
        }
        resolvedOut.clear();
        resolvedOut.addAll(resolvedSet);
        return newlyResolved;
    }

    // OCCURS keyword in an entry's own declaration text. We match on the entry's ctx text rather
    // than getOccursClauses(): in ProLeap v2.4.0 the structured OccursClause list is not populated
    // for these table entries (observed empty), but the clause text is present in the entry's ctx.
    // Note: a group entry's ctx is its children's source concatenated WITHOUT whitespace, so we
    // scope to the own-declaration slice (up to the first period) before matching.
    private static final Pattern OCCURS_OWN = Pattern.compile("(?i)\\bOCCURS\\b");

    /** True if {@code entry} or any ancestor group declares an OCCURS clause on its own line. */
    private static boolean hasOccursAncestor(DataDescriptionEntry entry) {
        for (DataDescriptionEntry e = entry; e != null; e = e.getParentDataDescriptionEntryGroup()) {
            if (e instanceof DataDescriptionEntryGroup g
                    && g.getOccursClauses() != null && !g.getOccursClauses().isEmpty()) {
                return true;
            }
            if (declaresOccurs(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if {@code e}'s own declaration line carries OCCURS. A group entry's ctx spans its whole
     * subtree, so we cut the ctx text at the first nested level number (the start of a child entry)
     * before matching — this keeps a non-OCCURS parent from inheriting an OCCURS child's keyword.
     */
    private static boolean declaresOccurs(DataDescriptionEntry e) {
        if (e == null || e.getCtx() == null) {
            return false;
        }
        String own = ownDeclText(e);
        return OCCURS_OWN.matcher(own).find();
    }

    /**
     * The entry's own declaration text (level + name + clauses up to its terminating period),
     * excluding any nested child entries whose text ProLeap folds into a group's ctx.
     */
    private static String ownDeclText(DataDescriptionEntry e) {
        String full = e.getCtx().getText();
        int dot = full.indexOf('.');
        return dot >= 0 ? full.substring(0, dot + 1) : full;
    }

    /**
     * Walk from {@code entry} up to the nearest REDEFINES-bearing ancestor; resolve the redefined
     * group by name and collect its VALUE literals ∈ knownPrograms. Falls back to the nearest
     * 01-level ancestor's own subtree when no REDEFINES is present.
     */
    private static Set<String> collectTableProgramNames(DataDescriptionEntry entry,
                                                        Map<String, DataDescriptionEntry> byName,
                                                        Set<String> knownPrograms) {
        DataDescriptionEntry topMost = entry;
        for (DataDescriptionEntry e = entry; e != null; e = e.getParentDataDescriptionEntryGroup()) {
            topMost = e;
            String redefined = redefinedName(e);
            if (redefined != null) {
                DataDescriptionEntry target = byName.get(redefined);
                if (target != null) {
                    Set<String> names = new LinkedHashSet<>();
                    collectValueLiterals(target, knownPrograms, names);
                    if (!names.isEmpty()) {
                        return names;
                    }
                }
            }
        }
        // Fallback: no usable REDEFINES — scan the nearest 01 ancestor's own subtree.
        Set<String> names = new LinkedHashSet<>();
        collectValueLiterals(topMost, knownPrograms, names);
        return names;
    }

    /** The data-name a REDEFINES clause points at, or null if {@code e} has no REDEFINES. */
    private static String redefinedName(DataDescriptionEntry e) {
        if (!(e instanceof DataDescriptionEntryGroup g)) {
            return null;
        }
        RedefinesClause rc = g.getRedefinesClause();
        if (rc == null || rc.getRedefinesCall() == null) {
            return null;
        }
        String n = rc.getRedefinesCall().getName();
        return (n == null || n.isBlank()) ? null : n;
    }

    /** Recursively collect VALUE '<lit>' literals (∈ knownPrograms) in {@code entry}'s subtree. */
    private static void collectValueLiterals(DataDescriptionEntry entry,
                                             Set<String> knownPrograms,
                                             Set<String> out) {
        if (entry == null) {
            return;
        }
        if (entry.getCtx() != null) {
            // Match only THIS entry's own VALUE clause, not descendants' — but ctx of a group spans
            // its children, so we scope to the leading clause text up to the first nested level
            // number is impractical via regex. Instead we match per-entry by descending the tree and
            // matching each entry's ctx, deduping via the knownPrograms set (idempotent).
            Matcher m = VALUE_LIT.matcher(entry.getCtx().getText());
            while (m.find()) {
                String lit = m.group(1).trim();
                if (knownPrograms.contains(lit)) {
                    out.add(lit);
                }
            }
        }
        if (entry instanceof DataDescriptionEntryGroup g && g.getDataDescriptionEntries() != null) {
            for (DataDescriptionEntry child : g.getDataDescriptionEntries()) {
                collectValueLiterals(child, knownPrograms, out);
            }
        }
    }

    /** Index every named data entry (recursively) reachable from a data division's containers. */
    private static void collectContainers(DataDivision dd,
                                          List<DataDescriptionEntry> roots,
                                          Map<String, DataDescriptionEntry> byName) {
        // Menu/option tables live in WORKING-STORAGE (CardDemo) or LINKAGE / LOCAL-STORAGE. The
        // FILE section uses a different model (FileDescriptionEntry) and never holds these tables.
        addContainer(dd.getWorkingStorageSection(), roots, byName);
        addContainer(dd.getLinkageSection(), roots, byName);
        addContainer(dd.getLocalStorageSection(), roots, byName);
    }

    private static void addContainer(DataDescriptionEntryContainer container,
                                     List<DataDescriptionEntry> roots,
                                     Map<String, DataDescriptionEntry> byName) {
        if (container == null || container.getRootDataDescriptionEntries() == null) {
            return;
        }
        for (DataDescriptionEntry root : container.getRootDataDescriptionEntries()) {
            roots.add(root);
            indexEntry(root, byName);
        }
    }

    private static void indexEntry(DataDescriptionEntry entry, Map<String, DataDescriptionEntry> byName) {
        if (entry == null) {
            return;
        }
        String name = entry.getName();
        if (name != null && !name.isBlank()) {
            byName.putIfAbsent(name, entry);
        }
        if (entry instanceof DataDescriptionEntryGroup g && g.getDataDescriptionEntries() != null) {
            for (DataDescriptionEntry child : g.getDataDescriptionEntries()) {
                indexEntry(child, byName);
            }
        }
    }
}

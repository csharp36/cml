package oracle;

import oracle.model.ProgramEdges;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flow-insensitive constant propagation for dynamic CICS {@code XCTL}/{@code LINK} and
 * {@code CALL} targets whose program name is held in a data item (e.g. {@code WS-NEXT}).
 *
 * <p>This is the locked differentiator of the study: a {@code grep}-style tool sees only the
 * variable operand (e.g. {@code XCTL PROGRAM(WS-NEXT)}) and cannot say which concrete program is
 * transferred to. Given a precomputed map {@code field-name → {literal program names ever assigned}}
 * (built by {@link ProgramExtractor} from every {@code MOVE 'literal' TO field} and any
 * {@code VALUE 'literal'} initializer — an over-approximation, intentionally), this resolver
 * replaces each dynamic identifier with its reaching literal set.
 *
 * <p>Semantics, per dynamic identifier operand:
 * <ul>
 *   <li>non-empty literal set ⇒ the names are added to the resolved-target list;</li>
 *   <li>empty set (no literal ever reaches the field) ⇒ {@code unresolvedDynamicCount} is
 *       incremented by one (the operand is honestly unresolvable).</li>
 * </ul>
 * The resolution is purely additive over {@link ProgramEdges}; it does not touch the static lists.
 */
public final class ConstantPropagator {

    /**
     * Resolve {@code edges.dynamicCallIdents} and {@code edges.dynamicXctlIdents} against
     * {@code fieldLiterals}, writing {@code resolvedDynamicCalls}, {@code resolvedDynamicXctlLink}
     * and {@code unresolvedDynamicCount} on the same {@link ProgramEdges} instance.
     *
     * @param edges         the edges POJO produced by B1 (mutated in place and returned)
     * @param fieldLiterals map of field name (hyphenated, as written) → set of literal program
     *                      names ever assigned to it; an absent/empty entry means "unresolvable"
     */
    public ProgramEdges resolve(ProgramEdges edges, Map<String, Set<String>> fieldLiterals) {
        Set<String> resolvedCalls = new LinkedHashSet<>();
        Set<String> resolvedXctl = new LinkedHashSet<>();
        int unresolved = 0;

        unresolved += resolveInto(edges.dynamicCallIdents, fieldLiterals, resolvedCalls);
        unresolved += resolveInto(edges.dynamicXctlIdents, fieldLiterals, resolvedXctl);

        edges.resolvedDynamicCalls = new ArrayList<>(resolvedCalls);
        edges.resolvedDynamicXctlLink = new ArrayList<>(resolvedXctl);
        edges.unresolvedDynamicCount = unresolved;
        return edges;
    }

    /**
     * Resolve each identifier in {@code idents}: add its reaching literals to {@code out}, or
     * return a count of the identifiers that had none.
     *
     * @return number of unresolvable identifiers in this list
     */
    private static int resolveInto(List<String> idents,
                                   Map<String, Set<String>> fieldLiterals,
                                   Set<String> out) {
        if (idents == null || idents.isEmpty()) {
            return 0;
        }
        int unresolved = 0;
        for (String ident : idents) {
            Set<String> literals = fieldLiterals.get(ident);
            if (literals == null || literals.isEmpty()) {
                unresolved++;
            } else {
                out.addAll(literals);
            }
        }
        return unresolved;
    }
}

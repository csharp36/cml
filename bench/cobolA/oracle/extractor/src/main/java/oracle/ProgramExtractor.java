package oracle;

import io.proleap.cobol.asg.metamodel.ASGElement;
import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.Scope;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.procedure.Paragraph;
import io.proleap.cobol.asg.metamodel.procedure.ProcedureDivision;
import io.proleap.cobol.asg.metamodel.procedure.Section;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.call.CallStatement;
import io.proleap.cobol.asg.metamodel.procedure.execcics.ExecCicsStatement;
import io.proleap.cobol.asg.metamodel.procedure.execsql.ExecSqlStatement;
import io.proleap.cobol.asg.metamodel.procedure.move.MoveStatement;
import io.proleap.cobol.asg.metamodel.procedure.move.MoveToStatement;
import io.proleap.cobol.asg.metamodel.procedure.read.ReadStatement;
import io.proleap.cobol.asg.metamodel.procedure.write.WriteStatement;
import io.proleap.cobol.asg.metamodel.valuestmt.CallValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.LiteralValueStmt;
import io.proleap.cobol.asg.metamodel.valuestmt.ValueStmt;
import oracle.model.ProgramEdges;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks one parsed ProLeap v2.4.0 ASG {@link Program} and produces a {@link ProgramEdges}
 * record: static / dynamic CALL targets, CICS XCTL/LINK targets, file I/O, and DB2 tables.
 *
 * <p>Strategy:
 * <ul>
 *   <li><b>CALL</b> — read the program operand via {@link CallStatement#getProgramValueStmt()}.
 *       A {@link LiteralValueStmt} ⇒ static (literal program name); a {@link CallValueStmt}
 *       (identifier reference) ⇒ dynamic (record the data-item identifier).</li>
 *   <li><b>CICS XCTL/LINK</b> — ProLeap stores {@code EXEC CICS} as opaque text
 *       ({@link ExecCicsStatement#getExecCicsText()}); regex it. A quoted operand ⇒ static,
 *       a bare identifier ⇒ dynamic.</li>
 *   <li><b>File I/O</b> — {@link ReadStatement#getFileCall()} (read) and
 *       {@link WriteStatement#getRecordCall()} (write), with a ctx-text regex fallback when the
 *       Call name does not resolve.</li>
 *   <li><b>DB2 tables</b> — opaque {@code EXEC SQL} text
 *       ({@link ExecSqlStatement#getExecSqlText()}); regex {@code FROM/INTO/UPDATE/JOIN}.</li>
 * </ul>
 *
 * <p>Statements are gathered by a recursive {@link ASGElement#getChildren()} descent so that
 * statements nested inside sentences / paragraphs / IF bodies are all visited.
 */
public final class ProgramExtractor {

    // group 1 = leading quote (⇒ static literal); group 2 = the program operand of XCTL/LINK.
    // The operand may be a quoted literal, a bare data-item, or a subscripted reference
    // (e.g. PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))) — for the latter we capture only the base
    // identifier, which is the data-item carrying the dynamic target. We stop the name at the first
    // of ' ( ) or whitespace, so a trailing subscript "(...)" and the closing ")" are not consumed.
    private static final Pattern CICS_XFER =
            Pattern.compile("(?i)(?:XCTL|LINK)\\s+PROGRAM\\(\\s*('?)([A-Z0-9-]+)");
    // Include '.' so a schema-qualified table (CARDDEMO.TRANSACTION_TYPE) is captured whole rather
    // than collapsed to its schema. Host vars (INTO :host) still start with ':' and are dropped below.
    private static final Pattern SQL_TABLE =
            Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+([A-Z0-9_.]+)");
    // Raw-source MOVE-literal fallback (fixed-format): MOVE 'lit' TO field. Used to union with the
    // ASG-derived MOVE literals so a parse-omitted MOVE is still folded into field->literals.
    private static final Pattern MOVE_LITERAL =
            Pattern.compile("(?i)\\bMOVE\\s+'([^']+)'\\s+TO\\s+([A-Z0-9-]+)");
    // ctx-text fallback for READ/WRITE: first token after the verb.
    private static final Pattern READ_OP = Pattern.compile("(?i)^READ\\s+([A-Z0-9-]+)");
    private static final Pattern WRITE_OP = Pattern.compile("(?i)^WRITE\\s+([A-Z0-9-]+)");
    // VALUE 'literal' data-item initializer: group 1 = field name, group 2 = literal.
    // Matches an elementary item: level number, field name, ... VALUE 'lit'.
    private static final Pattern VALUE_INIT = Pattern.compile(
            "(?is)\\b\\d{1,2}\\s+([A-Z0-9-]+)\\b[^.]*?\\bVALUE\\s+(?:IS\\s+)?'([^']+)'");

    public ProgramEdges extract(Program program, String programId) {
        return extract(program, programId, java.util.Collections.emptySet());
    }

    public ProgramEdges extract(Program program, String programId, Set<String> knownPrograms) {
        return extract(program, programId, knownPrograms, null);
    }

    /**
     * @param rawSource the program's raw fixed-format source (the same string passed to
     *                  {@link CopyScanner}). When non-null, {@code MOVE 'literal' TO field}
     *                  statements scanned from it are UNIONed into the field→literals map, recovering
     *                  any MOVE that ProLeap dropped under {@code ignoreSyntaxErrors}. Pass {@code null}
     *                  to skip the raw fallback (ASG path only).
     */
    public ProgramEdges extract(Program program, String programId, Set<String> knownPrograms,
                                String rawSource) {
        ProgramEdges edges = new ProgramEdges();
        edges.programId = programId;

        // De-dup while preserving discovery order.
        Set<String> staticCalls = new LinkedHashSet<>();
        Set<String> dynamicCalls = new LinkedHashSet<>();
        Set<String> staticXctl = new LinkedHashSet<>();
        Set<String> dynamicXctl = new LinkedHashSet<>();
        Set<String> filesRead = new LinkedHashSet<>();
        Set<String> filesWritten = new LinkedHashSet<>();
        Set<String> db2Tables = new LinkedHashSet<>();

        // field name -> { literal program names ever assigned } (flow-insensitive over-approximation).
        Map<String, Set<String>> fieldLiterals = new HashMap<>();
        collectValueInitializers(program, fieldLiterals);

        for (Statement stmt : collectStatements(program)) {
            if (stmt instanceof MoveStatement ms) {
                handleMove(ms, fieldLiterals);
            } else if (stmt instanceof CallStatement cs) {
                handleCall(cs, staticCalls, dynamicCalls);
            } else if (stmt instanceof ExecCicsStatement cics) {
                handleCics(cics.getExecCicsText(), staticXctl, dynamicXctl);
            } else if (stmt instanceof ExecSqlStatement sql) {
                handleSql(sql.getExecSqlText(), db2Tables);
            } else if (stmt instanceof ReadStatement rs) {
                String name = callName(rs.getFileCall());
                if (name == null) {
                    name = ctxMatch(rs.getCtx(), READ_OP);
                }
                if (name != null) {
                    filesRead.add(name);
                }
            } else if (stmt instanceof WriteStatement ws) {
                String name = callName(ws.getRecordCall());
                if (name == null) {
                    name = ctxMatch(ws.getCtx(), WRITE_OP);
                }
                if (name != null) {
                    filesWritten.add(name);
                }
            }
        }

        edges.staticCalls = new ArrayList<>(staticCalls);
        edges.dynamicCallIdents = new ArrayList<>(dynamicCalls);
        edges.staticXctlLink = new ArrayList<>(staticXctl);
        edges.dynamicXctlIdents = new ArrayList<>(dynamicXctl);
        edges.filesRead = new ArrayList<>(filesRead);
        edges.filesWritten = new ArrayList<>(filesWritten);
        edges.db2Tables = new ArrayList<>(db2Tables);

        // B6: raw-source MOVE-literal fallback. UNION (never replace) the literals scanned directly
        // from the fixed-format source into the ASG-derived field→literals map. This recovers a
        // MOVE 'literal' TO field that ProLeap dropped under ignoreSyntaxErrors (observed on the large
        // IMS/MQ program COPAUS0C, whose l.669 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM was omitted). It is
        // purely additive — only correct field→literal mappings are added — so the OCCURS over-connect
        // gate (which is keyed off knownPrograms) is unaffected.
        if (rawSource != null) {
            Map<String, Set<String>> rawMoves = scanMoveLiterals(rawSource);
            for (Map.Entry<String, Set<String>> e : rawMoves.entrySet()) {
                fieldLiterals.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
        }

        // B2: resolve dynamic CALL / XCTL identifiers to concrete program names via the
        // field -> literals map built above, and count the truly-unresolvable operands.
        new ConstantPropagator().resolve(edges, fieldLiterals);

        // B2.5: for operands STILL unresolved after direct const-prop, attempt OCCURS-table
        // resolution — a subscripted ref into an OCCURS table that REDEFINES a VALUE-initialized
        // group of program names (the CICS menu dispatch). Gated by knownPrograms to avoid
        // over-connecting plain (non-table) operands.
        OccursTableResolver.resolve(edges, program, knownPrograms, fieldLiterals);
        return edges;
    }

    /**
     * Raw-source fallback for the constant propagator: scan fixed-format COBOL text for
     * {@code MOVE 'literal' TO field} statements and return {@code field(upper) -> {literals}}.
     *
     * <p>This is a backstop for ProLeap parse omissions — when {@code ignoreSyntaxErrors=true} on a
     * large IMS/MQ program drops a statement node, {@code handleMove} never sees that MOVE, so the
     * literal is missing from the ASG-derived map. Scanning the raw source recovers it. The result is
     * always UNIONed with (never replaces) the ASG path, so it can only add correct field→literal
     * mappings.
     *
     * <p>Fixed-format aware: column 7 (1-based) is the indicator area — a {@code *} or {@code /} there
     * marks a full-line comment, which is skipped; code lives in columns 8–72 (sequence area 1–6 and
     * the col-73+ identification area are ignored). The regex permits a MOVE that wraps onto a
     * continuation line within the code area because matching is done on the concatenated code text.
     */
    public static Map<String, Set<String>> scanMoveLiterals(String rawSource) {
        Map<String, Set<String>> out = new HashMap<>();
        if (rawSource == null || rawSource.isEmpty()) {
            return out;
        }
        StringBuilder code = new StringBuilder();
        for (String line : rawSource.split("\n", -1)) {
            // Strip a trailing CR (CRLF sources).
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                line = line.substring(0, line.length() - 1);
            }
            // Column 7 (index 6) is the indicator area; '*' or '/' there is a comment line.
            if (line.length() >= 7) {
                char indicator = line.charAt(6);
                if (indicator == '*' || indicator == '/') {
                    continue;
                }
            }
            // Code area is columns 8–72 (index 7..71). Lines shorter than that contribute what they have.
            if (line.length() > 7) {
                int end = Math.min(line.length(), 72);
                code.append(line, 7, end);
            }
            code.append(' ');
        }
        Matcher m = MOVE_LITERAL.matcher(code);
        while (m.find()) {
            String literal = m.group(1);
            String field = m.group(2);
            if (field != null && literal != null && !literal.isEmpty()) {
                out.computeIfAbsent(field.toUpperCase(java.util.Locale.ROOT), k -> new LinkedHashSet<>())
                        .add(literal);
            }
        }
        return out;
    }

    // ---- MOVE (constant propagation source) -----------------------------------

    /**
     * Record a {@code MOVE 'literal' TO field [field ...]} into the field→literals map.
     * Only literal sending operands contribute; identifier sources (e.g. {@code MOVE WS-FOO TO X})
     * carry no constant and are skipped, so a field that never receives a literal stays unresolved.
     */
    private static void handleMove(MoveStatement ms, Map<String, Set<String>> fieldLiterals) {
        MoveToStatement to = ms.getMoveToStatement();
        if (to == null || to.getSendingArea() == null) {
            return;
        }
        ValueStmt sending = to.getSendingArea().getSendingAreaValueStmt();
        if (!(sending instanceof LiteralValueStmt lit)) {
            return; // non-literal source — no constant to propagate
        }
        String literal = literalText(lit);
        if (literal == null || literal.isEmpty()) {
            return;
        }
        List<Call> receivers = to.getReceivingAreaCalls();
        if (receivers == null) {
            return;
        }
        for (Call receiver : receivers) {
            String field = callName(receiver);
            if (field != null) {
                fieldLiterals.computeIfAbsent(field, k -> new LinkedHashSet<>()).add(literal);
            }
        }
    }

    /**
     * Fold {@code VALUE 'literal'} data-item initializers into the field→literals map by regexing
     * each program unit's data-division text. This is a best-effort over-approximation: it catches
     * elementary items declared with a literal VALUE so a field initialised (but never MOVEd) is
     * still resolvable. Numeric and figurative-constant VALUEs are ignored (only quoted literals).
     */
    private static void collectValueInitializers(Program program, Map<String, Set<String>> fieldLiterals) {
        for (CompilationUnit cu : program.getCompilationUnits()) {
            for (ProgramUnit pu : cu.getProgramUnits()) {
                if (pu.getDataDivision() == null || pu.getDataDivision().getCtx() == null) {
                    continue;
                }
                String text = pu.getDataDivision().getCtx().getText();
                Matcher m = VALUE_INIT.matcher(text);
                while (m.find()) {
                    String field = m.group(1);
                    String literal = m.group(2);
                    if (field != null && literal != null && !literal.isEmpty()) {
                        fieldLiterals.computeIfAbsent(field, k -> new LinkedHashSet<>()).add(literal);
                    }
                }
            }
        }
    }

    // ---- CALL -----------------------------------------------------------------

    private void handleCall(CallStatement cs, Set<String> staticCalls, Set<String> dynamicCalls) {
        ValueStmt vs = cs.getProgramValueStmt();
        if (vs instanceof LiteralValueStmt lit) {
            String name = literalText(lit);
            if (name != null) {
                staticCalls.add(name);
            }
        } else if (vs instanceof CallValueStmt cvs) {
            String name = callName(cvs.getCall());
            if (name != null) {
                dynamicCalls.add(name);
            }
        } else if (vs != null) {
            // Unknown ValueStmt subtype: fall back to its raw value text, classifying by quote.
            Object v = vs.getValue();
            if (v != null) {
                String raw = v.toString().trim();
                String unq = stripQuotes(raw);
                if (!unq.isEmpty()) {
                    if (raw.startsWith("'") || raw.startsWith("\"")) {
                        staticCalls.add(unq);
                    } else {
                        dynamicCalls.add(unq);
                    }
                }
            }
        }
    }

    private static String literalText(LiteralValueStmt lit) {
        if (lit.getLiteral() != null) {
            String nn = lit.getLiteral().getNonNumericLiteral();
            if (nn != null) {
                return stripQuotes(nn);
            }
            Object v = lit.getLiteral().getValue();
            if (v != null) {
                return stripQuotes(v.toString());
            }
        }
        Object v = lit.getValue();
        return v == null ? null : stripQuotes(v.toString());
    }

    // ---- CICS XCTL / LINK ------------------------------------------------------

    private void handleCics(String text, Set<String> staticXctl, Set<String> dynamicXctl) {
        if (text == null) {
            return;
        }
        Matcher m = CICS_XFER.matcher(text);
        while (m.find()) {
            boolean quoted = !m.group(1).isEmpty();
            String operand = m.group(2);
            if (quoted) {
                staticXctl.add(operand);
            } else {
                dynamicXctl.add(operand);
            }
        }
    }

    // ---- DB2 / SQL -------------------------------------------------------------

    private void handleSql(String text, Set<String> db2Tables) {
        if (text == null) {
            return;
        }
        Matcher m = SQL_TABLE.matcher(text);
        while (m.find()) {
            String table = m.group(1);
            if (table.startsWith(":")) {
                continue; // host variable, not a table
            }
            db2Tables.add(table);
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private static String callName(Call call) {
        if (call == null) {
            return null;
        }
        String n = call.getName();
        return (n == null || n.isBlank()) ? null : n;
    }

    private static String ctxMatch(ParserRuleContext ctx, Pattern p) {
        if (ctx == null) {
            return null;
        }
        Matcher m = p.matcher(ctx.getText());
        return m.find() ? m.group(1) : null;
    }

    private static String stripQuotes(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() >= 2
                && ((t.charAt(0) == '\'' && t.charAt(t.length() - 1) == '\'')
                || (t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"'))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    /**
     * Collect every {@link Statement} reachable from the program units.
     *
     * <p>Statements live on {@link Scope#getStatements()}, never on
     * {@link ASGElement#getChildren()} of the {@link ProcedureDivision} (that returns empty at the
     * division level). The procedure body is a nest of {@link Scope}s and we must descend all of it:
     * <ul>
     *   <li>The division's body is structured into {@link Section}s and {@link Paragraph}s (both
     *       {@link Scope}s). The division itself exposes <em>zero</em> direct statements — they hang
     *       off the paragraphs — so we seed the traversal with the division, its sections and its
     *       paragraphs. (A synthetic sample with statements directly under the division still works,
     *       because the division is itself seeded.)</li>
     *   <li>Block statements (IF, EVALUATE, PERFORM, SEARCH, ...) are <em>not</em> Scopes themselves;
     *       their bodies live on child Scopes ({@code IfStatement.getThen()/getElse()},
     *       EVALUATE {@code WHEN} branches, the PERFORM body, ...). Those child Scopes are reachable
     *       via {@link ASGElement#getChildren()} of the statement, so for every collected statement
     *       we recurse through its ASG children, descending into any Scope we find. This is the only
     *       way nested MOVE / EXEC CICS XCTL inside an IF/EVALUATE are seen — and in CardDemo the
     *       dispatch logic (literal MOVEs to the target field, the XCTL itself) lives there.</li>
     * </ul>
     */
    private static List<Statement> collectStatements(Program program) {
        List<Statement> out = new ArrayList<>();
        Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (CompilationUnit cu : program.getCompilationUnits()) {
            for (ProgramUnit pu : cu.getProgramUnits()) {
                ProcedureDivision pd = pu.getProcedureDivision();
                if (pd == null) {
                    continue;
                }
                collectFromScope(pd, out, seen);
                if (pd.getSections() != null) {
                    for (Section section : pd.getSections()) {
                        collectFromScope(section, out, seen);
                    }
                }
                if (pd.getParagraphs() != null) {
                    for (Paragraph paragraph : pd.getParagraphs()) {
                        collectFromScope(paragraph, out, seen);
                    }
                }
            }
        }
        return out;
    }

    /** Add every statement of {@code scope}, descending into nested block-statement body Scopes. */
    private static void collectFromScope(Scope scope, List<Statement> out, Set<Object> seen) {
        if (scope == null || !seen.add(scope)) {
            return;
        }
        List<Statement> stmts = scope.getStatements();
        if (stmts == null) {
            return;
        }
        for (Statement s : stmts) {
            out.add(s);
            // Block statements (IF/EVALUATE/PERFORM/...) carry their bodies as child Scopes,
            // reachable via getChildren() — descend into them.
            descendChildScopes(s, out, seen);
        }
    }

    private static void descendChildScopes(ASGElement element, List<Statement> out, Set<Object> seen) {
        if (element == null || element.getChildren() == null) {
            return;
        }
        for (ASGElement child : element.getChildren()) {
            if (child instanceof Scope childScope) {
                collectFromScope(childScope, out, seen);
            } else if (child != null && seen.add(child)) {
                // Intermediate wrapper (Then/Else/When/...) that isn't itself a Scope — keep walking.
                descendChildScopes(child, out, seen);
            }
        }
    }
}

package oracle;

import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.Scope;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.procedure.ProcedureDivision;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.call.CallStatement;
import io.proleap.cobol.asg.metamodel.procedure.execcics.ExecCicsStatement;
import io.proleap.cobol.asg.metamodel.procedure.execsql.ExecSqlStatement;
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
import java.util.LinkedHashSet;
import java.util.List;
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

    // group 1 = the program operand of XCTL/LINK; a leading quote in the raw match ⇒ static.
    private static final Pattern CICS_XFER =
            Pattern.compile("(?i)(?:XCTL|LINK)\\s+PROGRAM\\(\\s*('?)([A-Z0-9-]+)'?\\s*\\)");
    private static final Pattern SQL_TABLE =
            Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE|JOIN)\\s+([A-Z0-9_]+)");
    // ctx-text fallback for READ/WRITE: first token after the verb.
    private static final Pattern READ_OP = Pattern.compile("(?i)^READ\\s+([A-Z0-9-]+)");
    private static final Pattern WRITE_OP = Pattern.compile("(?i)^WRITE\\s+([A-Z0-9-]+)");

    public ProgramEdges extract(Program program, String programId) {
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

        for (Statement stmt : collectStatements(program)) {
            if (stmt instanceof CallStatement cs) {
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
        return edges;
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
     * <p>v2.4.0 note: statements are NOT exposed via {@link io.proleap.cobol.asg.metamodel.ASGElement#getChildren()}
     * (that returns empty on a {@link ProcedureDivision}); they live on {@link Scope#getStatements()}.
     * Some statements (IF, EVALUATE, PERFORM, ...) are themselves {@link Scope}s containing nested
     * statements, so we recurse through any statement that is also a Scope.
     */
    private static List<Statement> collectStatements(Program program) {
        List<Statement> out = new ArrayList<>();
        for (CompilationUnit cu : program.getCompilationUnits()) {
            for (ProgramUnit pu : cu.getProgramUnits()) {
                ProcedureDivision pd = pu.getProcedureDivision();
                if (pd != null) {
                    collectFromScope(pd, out);
                }
            }
        }
        return out;
    }

    private static void collectFromScope(Scope scope, List<Statement> out) {
        Deque<Scope> stack = new ArrayDeque<>();
        Set<Scope> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        stack.push(scope);
        while (!stack.isEmpty()) {
            Scope sc = stack.pop();
            if (sc == null || !seen.add(sc)) {
                continue;
            }
            List<Statement> stmts = sc.getStatements();
            if (stmts == null) {
                continue;
            }
            for (Statement s : stmts) {
                out.add(s);
                // IF / EVALUATE / PERFORM bodies etc. are themselves Scopes with nested statements.
                if (s instanceof Scope nested) {
                    stack.push(nested);
                }
            }
        }
    }
}

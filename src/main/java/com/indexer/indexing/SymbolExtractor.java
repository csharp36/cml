package com.indexer.indexing;

import com.indexer.indexing.treesitter.TreeSitterEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts symbols from source code using regex-based parsing.
 * This is a pragmatic initial implementation — Tree-sitter can replace the internals later.
 */
public class SymbolExtractor {

    private static final Logger log = LoggerFactory.getLogger(SymbolExtractor.class);
    private final TreeSitterEngine engine;

    public SymbolExtractor() {
        this.engine = null;
    }

    public SymbolExtractor(TreeSitterEngine engine) {
        this.engine = engine;
    }

    public List<ExtractedSymbol> extract(String source, String language) {
        if (source == null || source.isBlank()) return List.of();

        if (engine != null) {
            try {
                List<ExtractedSymbol> result = engine.parse(source, language);
                if (result != null) return result;
                // null means unsupported language — fall through to regex
            } catch (Exception e) {
                log.warn("Tree-sitter parse failed for {}, falling back to regex: {}",
                        language, e.getMessage());
            }
        }

        return extractWithRegex(source, language);
    }

    private List<ExtractedSymbol> extractWithRegex(String source, String language) {
        return switch (language.toLowerCase()) {
            case "java" -> extractJava(source);
            case "python" -> extractPython(source);
            case "typescript", "javascript" -> extractTypeScript(source);
            case "go" -> extractGo(source);
            default -> List.of();
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java
    // ─────────────────────────────────────────────────────────────────────────

    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);

    private static final Pattern JAVA_TYPE_DECL = Pattern.compile(
            "^\\s*(?:(public|protected|private)\\s+)?(?:(abstract|final)\\s+)?" +
            "(class|interface|enum)\\s+(\\w+)" +
            "(?:\\s+extends\\s+([\\w,\\s<>]+?))?" +
            "(?:\\s+implements\\s+([\\w,\\s<>]+?))?" +
            "\\s*\\{",
            Pattern.MULTILINE);

    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*" +           // optional annotations
            "(?:(public|protected|private)\\s+)?" +            // visibility
            "(static\\s+)?" +                                   // static
            "(?:final\\s+|abstract\\s+|synchronized\\s+|native\\s+)*" + // modifiers
            "(?:[\\w<>\\[\\],?\\s]+?\\s+)" +                  // return type
            "(\\w+)\\s*\\(" +                                   // method name + (
            "([^)]*)" +                                         // params
            "\\)\\s*(?:throws\\s+[\\w,\\s]+)?\\s*\\{",
            Pattern.MULTILINE);

    private List<ExtractedSymbol> extractJava(String source) {
        List<ExtractedSymbol> symbols = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        // Imports
        Matcher importMatcher = JAVA_IMPORT.matcher(source);
        while (importMatcher.find()) {
            int line = lineOf(source, importMatcher.start()) + 1;
            symbols.add(ExtractedSymbol.importSymbol(importMatcher.group(1), line));
        }

        // Type declarations (class/interface/enum)
        Matcher typeMatcher = JAVA_TYPE_DECL.matcher(source);
        while (typeMatcher.find()) {
            int startLine = lineOf(source, typeMatcher.start()) + 1;
            int endLine = findClosingBrace(source, typeMatcher.end() - 1);
            String visibility = typeMatcher.group(1) != null ? typeMatcher.group(1) : "package";
            String kind = typeMatcher.group(3);
            String name = typeMatcher.group(4);
            String extendsStr = typeMatcher.group(5);
            String implementsStr = typeMatcher.group(6);

            List<ExtractedSymbol.Relationship> rels = new ArrayList<>();
            if (extendsStr != null) {
                for (String base : extendsStr.split(",")) {
                    String trimmed = stripGenerics(base.trim());
                    if (!trimmed.isEmpty()) rels.add(new ExtractedSymbol.Relationship(trimmed, "extends"));
                }
            }
            if (implementsStr != null) {
                for (String iface : implementsStr.split(",")) {
                    String trimmed = stripGenerics(iface.trim());
                    if (!trimmed.isEmpty()) rels.add(new ExtractedSymbol.Relationship(trimmed, "implements"));
                }
            }

            String signature = buildTypeSignature(visibility, kind, name, extendsStr, implementsStr);
            symbols.add(new ExtractedSymbol(name, kind, signature, startLine, endLine,
                    null, visibility, false, List.copyOf(rels)));

            // Extract methods within this type block
            int blockStart = typeMatcher.end() - 1; // position of opening {
            int blockEnd = findClosingBracePos(source, blockStart);
            if (blockEnd > blockStart) {
                String block = source.substring(blockStart + 1, blockEnd);
                int blockLineOffset = lineOf(source, blockStart + 1);
                extractJavaMethods(block, blockLineOffset, name, symbols);
            }
        }

        return symbols;
    }

    private void extractJavaMethods(String block, int lineOffset, String parentName,
                                    List<ExtractedSymbol> symbols) {
        Matcher methodMatcher = JAVA_METHOD.matcher(block);
        while (methodMatcher.find()) {
            String visibility = methodMatcher.group(1) != null ? methodMatcher.group(1) : "package";
            boolean isStatic = methodMatcher.group(2) != null;
            String methodName = methodMatcher.group(3);
            String params = methodMatcher.group(4).trim();

            // Skip constructors that match the class name (they share the pattern)
            // We include them — callers can filter if needed

            // Skip common false-positives: if/for/while/switch etc.
            if (isControlKeyword(methodName)) continue;

            int startLine = lineOffset + lineOf(block, methodMatcher.start()) + 1;
            int endLine = lineOffset + findClosingBrace(block, methodMatcher.end() - 1);

            String signature = buildMethodSignature(visibility, isStatic, methodName, params);
            symbols.add(new ExtractedSymbol(methodName, "method", signature,
                    startLine, endLine, parentName, visibility, isStatic, List.of()));
        }
    }

    private boolean isControlKeyword(String name) {
        return switch (name) {
            case "if", "for", "while", "switch", "catch", "else", "do", "try", "finally",
                 "synchronized", "return", "new", "throw" -> true;
            default -> false;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Python
    // ─────────────────────────────────────────────────────────────────────────

    private static final Pattern PY_IMPORT_SIMPLE = Pattern.compile(
            "^import\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern PY_IMPORT_FROM = Pattern.compile(
            "^from\\s+([\\w.]+)\\s+import\\s+(\\w+(?:\\s*,\\s*\\w+)*)", Pattern.MULTILINE);
    private static final Pattern PY_CLASS = Pattern.compile(
            "^(class)\\s+(\\w+)(?:\\(([^)]*)\\))?\\s*:", Pattern.MULTILINE);
    private static final Pattern PY_DEF = Pattern.compile(
            "^(\\s*)(def)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:->\\s*[^:]+)?:", Pattern.MULTILINE);

    private List<ExtractedSymbol> extractPython(String source) {
        List<ExtractedSymbol> symbols = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        // Imports
        Matcher impSimple = PY_IMPORT_SIMPLE.matcher(source);
        while (impSimple.find()) {
            int line = lineOf(source, impSimple.start()) + 1;
            symbols.add(ExtractedSymbol.importSymbol(impSimple.group(1), line));
        }
        Matcher impFrom = PY_IMPORT_FROM.matcher(source);
        while (impFrom.find()) {
            int line = lineOf(source, impFrom.start()) + 1;
            String module = impFrom.group(1);
            for (String name : impFrom.group(2).split(",")) {
                String trimmed = name.trim();
                symbols.add(ExtractedSymbol.importSymbol(module + "." + trimmed, line));
            }
        }

        // Classes
        Matcher classMatcher = PY_CLASS.matcher(source);
        while (classMatcher.find()) {
            int startLine = lineOf(source, classMatcher.start()) + 1;
            int endLine = findPythonBlockEnd(lines, startLine - 1);
            String name = classMatcher.group(2);
            String basesStr = classMatcher.group(3);
            List<ExtractedSymbol.Relationship> rels = new ArrayList<>();
            if (basesStr != null && !basesStr.isBlank()) {
                for (String base : basesStr.split(",")) {
                    String trimmed = base.trim();
                    if (!trimmed.isEmpty() && !trimmed.equals("object")) {
                        rels.add(new ExtractedSymbol.Relationship(trimmed, "extends"));
                    }
                }
            }
            symbols.add(new ExtractedSymbol(name, "class", "class " + name,
                    startLine, endLine, null, "public", false, List.copyOf(rels)));
        }

        // Functions and methods
        Matcher defMatcher = PY_DEF.matcher(source);
        while (defMatcher.find()) {
            int startLine = lineOf(source, defMatcher.start()) + 1;
            int indent = defMatcher.group(1).length();
            int endLine = findPythonBlockEnd(lines, startLine - 1);
            String name = defMatcher.group(3);
            String params = defMatcher.group(4).trim();

            // Determine visibility from name convention
            String visibility;
            if (name.startsWith("__") && name.endsWith("__")) {
                visibility = "public"; // dunder methods are special but public
            } else if (name.startsWith("__")) {
                visibility = "private";
            } else if (name.startsWith("_")) {
                visibility = "protected";
            } else {
                visibility = "public";
            }

            // Find parent class (look for a class declaration with less indentation above)
            String parentName = findPythonParent(lines, startLine - 2, indent);
            String kind = (parentName != null) ? "method" : "function";

            symbols.add(new ExtractedSymbol(name, kind, "def " + name + "(" + params + ")",
                    startLine, endLine, parentName, visibility, false, List.of()));
        }

        return symbols;
    }

    private String findPythonParent(String[] lines, int beforeLineIdx, int methodIndent) {
        // Walk backwards to find a class with lower indentation
        for (int i = beforeLineIdx; i >= 0; i--) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int lineIndent = countLeadingSpaces(line);
            if (lineIndent < methodIndent) {
                Matcher m = PY_CLASS.matcher(line.stripLeading());
                if (m.find()) return m.group(2);
                // If we hit a lower-indent non-class, we're at module level
                if (lineIndent == 0) return null;
            }
        }
        return null;
    }

    private int findPythonBlockEnd(String[] lines, int startIdx) {
        if (startIdx >= lines.length) return startIdx + 1;
        String headerLine = lines[startIdx];
        int baseIndent = countLeadingSpaces(headerLine);

        for (int i = startIdx + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) continue;
            int indent = countLeadingSpaces(line);
            if (indent <= baseIndent) return i; // exclusive — the line at i is outside the block
        }
        return lines.length;
    }

    private int countLeadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 4;
            else break;
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TypeScript / JavaScript
    // ─────────────────────────────────────────────────────────────────────────

    private static final Pattern TS_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:[\\w*{}\\s,]+\\s+from\\s+)?['\"]([^'\"]+)['\"]\\s*;?",
            Pattern.MULTILINE);
    private static final Pattern TS_CLASS = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:abstract\\s+)?(class)\\s+(\\w+)" +
            "(?:\\s+extends\\s+([\\w<>,\\s]+?))?" +
            "(?:\\s+implements\\s+([\\w<>,\\s]+?))?\\s*\\{",
            Pattern.MULTILINE);
    private static final Pattern TS_INTERFACE = Pattern.compile(
            "^\\s*(?:export\\s+)?(interface)\\s+(\\w+)" +
            "(?:\\s+extends\\s+([\\w<>,\\s]+?))?\\s*\\{",
            Pattern.MULTILINE);
    private static final Pattern TS_FUNCTION = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.MULTILINE);
    private static final Pattern TS_METHOD = Pattern.compile(
            "^\\s*(?:(?:public|private|protected)\\s+)?(?:static\\s+)?(?:async\\s+)?" +
            "(?:get\\s+|set\\s+)?(\\w+)\\s*\\(([^)]*)\\)(?:\\s*:\\s*[\\w<>\\[\\]|,?\\s]+)?\\s*\\{",
            Pattern.MULTILINE);

    private List<ExtractedSymbol> extractTypeScript(String source) {
        List<ExtractedSymbol> symbols = new ArrayList<>();

        // Imports
        Matcher importMatcher = TS_IMPORT.matcher(source);
        while (importMatcher.find()) {
            int line = lineOf(source, importMatcher.start()) + 1;
            symbols.add(ExtractedSymbol.importSymbol(importMatcher.group(1), line));
        }

        // Classes
        Matcher classMatcher = TS_CLASS.matcher(source);
        while (classMatcher.find()) {
            int startLine = lineOf(source, classMatcher.start()) + 1;
            int endLine = findClosingBrace(source, classMatcher.end() - 1);
            String name = classMatcher.group(2);
            String extendsStr = classMatcher.group(3);
            String implementsStr = classMatcher.group(4);

            List<ExtractedSymbol.Relationship> rels = new ArrayList<>();
            if (extendsStr != null) {
                rels.add(new ExtractedSymbol.Relationship(stripGenerics(extendsStr.trim()), "extends"));
            }
            if (implementsStr != null) {
                for (String iface : implementsStr.split(",")) {
                    String trimmed = stripGenerics(iface.trim());
                    if (!trimmed.isEmpty()) rels.add(new ExtractedSymbol.Relationship(trimmed, "implements"));
                }
            }

            symbols.add(new ExtractedSymbol(name, "class", "class " + name,
                    startLine, endLine, null, "public", false, List.copyOf(rels)));

            // Methods inside the class
            int blockStart = classMatcher.end() - 1;
            int blockEnd = findClosingBracePos(source, blockStart);
            if (blockEnd > blockStart) {
                String block = source.substring(blockStart + 1, blockEnd);
                int blockLineOffset = lineOf(source, blockStart + 1);
                extractTsMethods(block, blockLineOffset, name, symbols);
            }
        }

        // Interfaces
        Matcher ifaceMatcher = TS_INTERFACE.matcher(source);
        while (ifaceMatcher.find()) {
            int startLine = lineOf(source, ifaceMatcher.start()) + 1;
            int endLine = findClosingBrace(source, ifaceMatcher.end() - 1);
            String name = ifaceMatcher.group(2);
            String extendsStr = ifaceMatcher.group(3);

            List<ExtractedSymbol.Relationship> rels = new ArrayList<>();
            if (extendsStr != null) {
                for (String base : extendsStr.split(",")) {
                    String trimmed = stripGenerics(base.trim());
                    if (!trimmed.isEmpty()) rels.add(new ExtractedSymbol.Relationship(trimmed, "extends"));
                }
            }
            symbols.add(new ExtractedSymbol(name, "interface", "interface " + name,
                    startLine, endLine, null, "public", false, List.copyOf(rels)));
        }

        // Top-level functions
        Matcher funcMatcher = TS_FUNCTION.matcher(source);
        while (funcMatcher.find()) {
            int startLine = lineOf(source, funcMatcher.start()) + 1;
            String name = funcMatcher.group(1);
            String params = funcMatcher.group(2).trim();
            symbols.add(new ExtractedSymbol(name, "function", "function " + name + "(" + params + ")",
                    startLine, startLine, null, "public", false, List.of()));
        }

        return symbols;
    }

    private void extractTsMethods(String block, int lineOffset, String parentName,
                                   List<ExtractedSymbol> symbols) {
        Matcher m = TS_METHOD.matcher(block);
        while (m.find()) {
            String name = m.group(1);
            String params = m.group(2).trim();
            if (isControlKeyword(name) || name.equals("if") || name.equals("for")) continue;
            int startLine = lineOffset + lineOf(block, m.start()) + 1;
            symbols.add(new ExtractedSymbol(name, "method", name + "(" + params + ")",
                    startLine, startLine, parentName, "public", false, List.of()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Go
    // ─────────────────────────────────────────────────────────────────────────

    private static final Pattern GO_IMPORT_SINGLE = Pattern.compile(
            "^\\s*import\\s+\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern GO_IMPORT_BLOCK = Pattern.compile(
            "\"([^\"]+)\"", Pattern.MULTILINE);
    private static final Pattern GO_IMPORT_BLOCK_OUTER = Pattern.compile(
            "import\\s*\\(([^)]+)\\)", Pattern.DOTALL);
    private static final Pattern GO_TYPE = Pattern.compile(
            "^type\\s+(\\w+)\\s+(struct|interface)\\s*\\{", Pattern.MULTILINE);
    private static final Pattern GO_FUNC = Pattern.compile(
            "^func\\s+(?:\\(\\s*\\w*\\s*(\\w+)\\s*\\)\\s+)?(\\w+)\\s*\\(([^)]*)\\)",
            Pattern.MULTILINE);

    private List<ExtractedSymbol> extractGo(String source) {
        List<ExtractedSymbol> symbols = new ArrayList<>();

        // Single imports
        Matcher singleImport = GO_IMPORT_SINGLE.matcher(source);
        while (singleImport.find()) {
            int line = lineOf(source, singleImport.start()) + 1;
            symbols.add(ExtractedSymbol.importSymbol(singleImport.group(1), line));
        }

        // Block imports
        Matcher blockOuter = GO_IMPORT_BLOCK_OUTER.matcher(source);
        while (blockOuter.find()) {
            String block = blockOuter.group(1);
            int blockOffset = blockOuter.start(1);
            Matcher blockInner = GO_IMPORT_BLOCK.matcher(block);
            while (blockInner.find()) {
                int line = lineOf(source, blockOffset + blockInner.start()) + 1;
                symbols.add(ExtractedSymbol.importSymbol(blockInner.group(1), line));
            }
        }

        // Type declarations
        Matcher typeMatcher = GO_TYPE.matcher(source);
        while (typeMatcher.find()) {
            int startLine = lineOf(source, typeMatcher.start()) + 1;
            int endLine = findClosingBrace(source, typeMatcher.end() - 1);
            String name = typeMatcher.group(1);
            String kind = typeMatcher.group(2).equals("struct") ? "class" : "interface";
            boolean exported = Character.isUpperCase(name.charAt(0));
            String visibility = exported ? "public" : "package";
            symbols.add(new ExtractedSymbol(name, kind, "type " + name + " " + typeMatcher.group(2),
                    startLine, endLine, null, visibility, false, List.of()));
        }

        // Functions
        Matcher funcMatcher = GO_FUNC.matcher(source);
        while (funcMatcher.find()) {
            int startLine = lineOf(source, funcMatcher.start()) + 1;
            String receiver = funcMatcher.group(1); // receiver type (may be null)
            String name = funcMatcher.group(2);
            String params = funcMatcher.group(3).trim();
            boolean exported = Character.isUpperCase(name.charAt(0));
            String visibility = exported ? "public" : "package";
            String kind = "function";
            String parent = null;
            if (receiver != null && !receiver.isBlank()) {
                parent = receiver.trim();
                kind = "method";
            }
            String signature = "func " + name + "(" + params + ")";
            symbols.add(new ExtractedSymbol(name, kind, signature,
                    startLine, startLine, parent, visibility, false, List.of()));
        }

        return symbols;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the 0-based line number for the character at position pos in source. */
    private int lineOf(String source, int pos) {
        int line = 0;
        for (int i = 0; i < pos && i < source.length(); i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    /**
     * Given the position of an opening '{', find the line number of the matching '}'.
     * Returns a 1-based line number.
     */
    private int findClosingBrace(String source, int openBracePos) {
        int pos = findClosingBracePos(source, openBracePos);
        return pos < 0 ? lineOf(source, source.length()) + 1 : lineOf(source, pos) + 1;
    }

    /** Returns the character position of the matching closing brace, or -1. */
    private int findClosingBracePos(String source, int openBracePos) {
        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = openBracePos; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;

            if (inLineComment) {
                if (c == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; }
                continue;
            }
            if (!inString && !inChar) {
                if (c == '/' && next == '/') { inLineComment = true; i++; continue; }
                if (c == '/' && next == '*') { inBlockComment = true; i++; continue; }
            }
            if (!inChar && c == '"' && !inBlockComment) {
                inString = !inString;
                continue;
            }
            if (!inString && c == '\'' && !inBlockComment) {
                inChar = !inChar;
                continue;
            }
            if (inString || inChar) continue;

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String stripGenerics(String s) {
        int idx = s.indexOf('<');
        return idx >= 0 ? s.substring(0, idx).trim() : s.trim();
    }

    private String buildTypeSignature(String visibility, String kind, String name,
                                      String extendsStr, String implementsStr) {
        StringBuilder sb = new StringBuilder();
        if (!"package".equals(visibility)) sb.append(visibility).append(" ");
        sb.append(kind).append(" ").append(name);
        if (extendsStr != null) sb.append(" extends ").append(extendsStr.trim());
        if (implementsStr != null) sb.append(" implements ").append(implementsStr.trim());
        return sb.toString();
    }

    private String buildMethodSignature(String visibility, boolean isStatic, String name, String params) {
        StringBuilder sb = new StringBuilder();
        if (!"package".equals(visibility)) sb.append(visibility).append(" ");
        if (isStatic) sb.append("static ");
        sb.append(name).append("(").append(params).append(")");
        return sb.toString();
    }
}

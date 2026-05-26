package com.indexer.indexing.treesitter;

import com.indexer.indexing.ExtractedSymbol;
import com.indexer.indexing.ExtractedSymbol.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.treesitter.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Core tree-sitter parsing engine. Loads .scm query files from classpath at startup,
 * registers languages with their TSLanguage instances and node-type-to-kind mappings,
 * and provides a parse() method that borrows a parser from TSParserPool, parses source
 * code, runs the query, and maps captures to ExtractedSymbol records.
 *
 * <p>Returns null for unsupported languages (signals regex fallback in SymbolExtractor).
 */
public class TreeSitterEngine {

    private static final Logger log = LoggerFactory.getLogger(TreeSitterEngine.class);

    private final TSParserPool parserPool;
    private final Map<String, LanguageConfig> languages;

    /**
     * Configuration for a single language: its TSLanguage instance, compiled query,
     * raw query string (for debugging), and node-type-to-kind mapping.
     */
    private record LanguageConfig(
            TSLanguage language,
            String queryString,
            Map<String, String> nodeTypeToKind
    ) {}

    public TreeSitterEngine(TSParserPool parserPool) {
        this.parserPool = parserPool;
        this.languages = new HashMap<>();

        registerJava();
        registerPython();
        registerTypeScript();
        registerGo();
        registerC();

        log.info("TreeSitterEngine initialized with {} languages: {}", languages.size(), languages.keySet());
    }

    /**
     * Parse source code and extract symbols.
     *
     * @param source   the source code text
     * @param language the language identifier (e.g., "java", "python", "typescript", "go", "c")
     * @return list of extracted symbols, or null if the language is unsupported
     */
    public List<ExtractedSymbol> parse(String source, String language) {
        if (source == null || source.isBlank()) {
            return List.of();
        }

        LanguageConfig config = languages.get(language.toLowerCase());
        if (config == null) {
            return null; // unsupported language — signal regex fallback
        }

        TSParser parser = parserPool.borrow();
        try {
            parser.setLanguage(config.language());
            TSTree tree = parser.parseString(null, source);
            try {
                return extractSymbols(tree, source, config);
            } finally {
                // TSTree does not implement AutoCloseable, but we null out reference
                // to allow GC/native finalizer to clean up
            }
        } catch (TSQueryException e) {
            log.error("Query compilation failed for language '{}': {}", language, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.error("Failed to parse {} source: {}", language, e.getMessage(), e);
            return List.of();
        } finally {
            parserPool.release(parser);
        }
    }

    private List<ExtractedSymbol> extractSymbols(TSTree tree, String source, LanguageConfig config) {
        TSNode root = tree.getRootNode();
        TSQuery query = new TSQuery(config.language(), config.queryString());
        TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, root);

        // Collect raw matches into structured capture groups
        List<CaptureGroup> captureGroups = collectCaptureGroups(query, cursor, source);

        // Convert capture groups to ExtractedSymbol instances
        List<ExtractedSymbol> symbols = new ArrayList<>();
        for (CaptureGroup group : captureGroups) {
            ExtractedSymbol symbol = mapToSymbol(group, config, source);
            if (symbol != null) {
                symbols.add(symbol);
            }
        }

        return symbols;
    }

    /**
     * A capture group represents all captures from a single query match.
     * Each match may have @node, @name, @path, @parent, @superclass, @interface,
     * @visibility, @parameters captures.
     */
    private record CaptureGroup(
            TSNode node,
            String nodeType,
            String name,
            String path,
            String parent,
            List<String> superclasses,
            List<String> interfaces,
            String visibility,
            String parameters,
            int startLine,
            int endLine,
            String nodeText
    ) {}

    /**
     * A mutable builder for accumulating captures across multiple matches that share the same
     * @node. Tree-sitter generates separate matches when a capture (like @interface) matches
     * multiple siblings in a list (e.g., "implements A, B" produces two matches for the same
     * class_declaration node, each with a different @interface).
     */
    private static class CaptureGroupBuilder {
        TSNode node;
        String nodeType;
        String name;
        String path;
        String parent;
        final List<String> superclasses = new ArrayList<>();
        final List<String> interfaces = new ArrayList<>();
        String visibility;
        String parameters;
        int startLine;
        int endLine;
        String nodeText;

        CaptureGroup build() {
            return new CaptureGroup(node, nodeType, name, path, parent,
                    List.copyOf(superclasses), List.copyOf(interfaces), visibility,
                    parameters, startLine, endLine, nodeText);
        }
    }

    private List<CaptureGroup> collectCaptureGroups(TSQuery query, TSQueryCursor cursor, String source) {
        // Use a LinkedHashMap keyed by node byte range to merge matches that share the same @node.
        // This handles the case where tree-sitter generates multiple matches for list captures
        // (e.g., multiple interfaces in "implements A, B, C").
        LinkedHashMap<String, CaptureGroupBuilder> builderMap = new LinkedHashMap<>();
        TSQueryMatch match = new TSQueryMatch();

        while (cursor.nextMatch(match)) {
            // First pass: find the @node capture to determine the merge key
            TSNode node = null;
            for (TSQueryCapture capture : match.getCaptures()) {
                if ("node".equals(query.getCaptureNameForId(capture.getIndex()))) {
                    node = capture.getNode();
                    break;
                }
            }
            if (node == null) continue;

            // Use byte range + pattern index as merge key. Same node from different patterns
            // should NOT merge (e.g., a class matching pattern 1 and a method matching pattern 2).
            // But same node from the same pattern (repeated due to list captures) SHOULD merge.
            String key = node.getStartByte() + ":" + node.getEndByte() + ":" + match.getPatternIndex();

            CaptureGroupBuilder builder = builderMap.computeIfAbsent(key, k -> new CaptureGroupBuilder());

            for (TSQueryCapture capture : match.getCaptures()) {
                String captureName = query.getCaptureNameForId(capture.getIndex());
                TSNode captureNode = capture.getNode();
                String text = safeSubstring(source, captureNode.getStartByte(), captureNode.getEndByte());

                switch (captureName) {
                    case "node" -> {
                        builder.node = captureNode;
                        builder.nodeType = captureNode.getType();
                        builder.startLine = captureNode.getStartPoint().getRow() + 1;
                        builder.endLine = captureNode.getEndPoint().getRow() + 1;
                        builder.nodeText = text;
                    }
                    case "name" -> builder.name = text;
                    case "path" -> builder.path = text;
                    case "parent" -> builder.parent = text;
                    case "superclass" -> {
                        if (!builder.superclasses.contains(text)) {
                            builder.superclasses.add(text);
                        }
                    }
                    case "interface" -> {
                        if (!builder.interfaces.contains(text)) {
                            builder.interfaces.add(text);
                        }
                    }
                    case "visibility" -> builder.visibility = text;
                    case "parameters" -> builder.parameters = text;
                }
            }
        }

        return builderMap.values().stream()
                .filter(b -> b.node != null)
                .map(CaptureGroupBuilder::build)
                .toList();
    }

    private ExtractedSymbol mapToSymbol(CaptureGroup group, LanguageConfig config, String source) {
        String kind = config.nodeTypeToKind().get(group.nodeType());

        if (kind == null) {
            // Unknown node type — skip
            log.trace("Unknown node type '{}' for language, skipping", group.nodeType());
            return null;
        }

        // Handle import symbols specially
        if ("import".equals(kind)) {
            String importPath = group.path() != null ? group.path() : group.nodeText();
            // Clean up import path: remove quotes, semicolons, and leading keywords
            importPath = cleanImportPath(importPath);
            return ExtractedSymbol.importSymbol(importPath, group.startLine());
        }

        // Determine parent name for methods inside classes
        String parentName = group.parent();
        if (parentName == null && ("method".equals(kind) || "constructor".equals(kind))) {
            // For Java/TypeScript methods: walk up the AST to find enclosing class
            parentName = findEnclosingClassName(group.node(), source);
        }

        // For Python: function_definition inside class_definition is a method
        if ("function".equals(kind) && parentName == null) {
            String enclosing = findEnclosingClassName(group.node(), source);
            if (enclosing != null) {
                parentName = enclosing;
                kind = "method";
            }
        }

        // Parse visibility from modifiers text
        String visibility = parseVisibility(group.visibility());

        // Detect static
        boolean isStatic = isStaticSymbol(group.visibility(), group.nodeText());

        // Build signature
        String signature = buildSignature(kind, group.name(), group.parameters(), group.nodeText());

        // Build relationships
        List<Relationship> relationships = new ArrayList<>();
        for (String superclass : group.superclasses()) {
            relationships.add(new Relationship(superclass, "extends"));
        }
        for (String iface : group.interfaces()) {
            relationships.add(new Relationship(iface, "implements"));
        }

        return new ExtractedSymbol(
                group.name(),
                kind,
                signature,
                group.startLine(),
                group.endLine(),
                parentName,
                visibility,
                isStatic,
                relationships
        );
    }

    /**
     * Walk up the AST from a node to find the enclosing class/struct name.
     */
    private String findEnclosingClassName(TSNode node, String source) {
        TSNode current = node.getParent();
        while (current != null && !current.isNull()) {
            String type = current.getType();
            if ("class_declaration".equals(type) || "class_definition".equals(type)
                    || "interface_declaration".equals(type) || "enum_declaration".equals(type)) {
                TSNode nameNode = current.getChildByFieldName("name");
                if (nameNode != null && !nameNode.isNull()) {
                    return safeSubstring(source, nameNode.getStartByte(), nameNode.getEndByte());
                }
            }
            // For class bodies — keep going up
            current = current.getParent();
        }
        return null;
    }

    /**
     * Clean import path by removing surrounding quotes, semicolons, and keywords.
     */
    private String cleanImportPath(String raw) {
        if (raw == null) return "";
        String cleaned = raw.strip();
        // Remove surrounding quotes
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        // Remove trailing semicolons
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
        }
        // For Java: remove leading "import " and "import static "
        if (cleaned.startsWith("import static ")) {
            cleaned = cleaned.substring("import static ".length());
        } else if (cleaned.startsWith("import ")) {
            cleaned = cleaned.substring("import ".length());
        }
        // For C: remove leading #include and angle brackets
        if (cleaned.startsWith("#include")) {
            cleaned = cleaned.substring("#include".length()).strip();
        }
        if (cleaned.startsWith("<") && cleaned.endsWith(">")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        // Remove surrounding quotes again (for nested cases)
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned.strip();
    }

    /**
     * Parse visibility modifier from the modifiers text.
     */
    private String parseVisibility(String modifiersText) {
        if (modifiersText == null || modifiersText.isBlank()) {
            return null;
        }
        String lower = modifiersText.toLowerCase();
        if (lower.contains("public")) return "public";
        if (lower.contains("private")) return "private";
        if (lower.contains("protected")) return "protected";
        // For TypeScript accessibility_modifier
        return null;
    }

    /**
     * Detect if a symbol is static.
     */
    private boolean isStaticSymbol(String modifiersText, String nodeText) {
        if (modifiersText != null && modifiersText.contains("static")) {
            return true;
        }
        // For C: check if function definition starts with "static"
        if (nodeText != null && nodeText.startsWith("static ")) {
            return true;
        }
        return false;
    }

    /**
     * Build a human-readable signature for the symbol.
     */
    private String buildSignature(String kind, String name, String parameters, String nodeText) {
        if (name == null) return nodeText != null ? truncate(nodeText, 120) : "";

        return switch (kind) {
            case "method", "function", "constructor" -> {
                if (parameters != null) {
                    yield name + parameters;
                }
                yield name + "()";
            }
            case "class", "interface", "enum" -> kind + " " + name;
            default -> name;
        };
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String safeSubstring(String source, int startByte, int endByte) {
        // tree-sitter reports byte offsets; for ASCII this equals char offset.
        // For multi-byte UTF-8, we need byte-based extraction.
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        int safeStart = Math.max(0, Math.min(startByte, bytes.length));
        int safeEnd = Math.max(safeStart, Math.min(endByte, bytes.length));
        return new String(bytes, safeStart, safeEnd - safeStart, StandardCharsets.UTF_8);
    }

    // ========================================================================
    // Language registrations
    // ========================================================================

    private void registerJava() {
        Map<String, String> kindMap = Map.of(
                "import_declaration", "import",
                "class_declaration", "class",
                "interface_declaration", "interface",
                "enum_declaration", "enum",
                "method_declaration", "method",
                "constructor_declaration", "constructor"
        );
        register("java", new TreeSitterJava(), "queries/java.scm", kindMap);
    }

    private void registerPython() {
        Map<String, String> kindMap = Map.of(
                "import_statement", "import",
                "import_from_statement", "import",
                "class_definition", "class",
                "function_definition", "function"
        );
        register("python", new TreeSitterPython(), "queries/python.scm", kindMap);
    }

    private void registerTypeScript() {
        Map<String, String> kindMap = Map.of(
                "import_statement", "import",
                "class_declaration", "class",
                "interface_declaration", "interface",
                "function_declaration", "function",
                "method_definition", "method"
        );
        register("typescript", new TreeSitterTypescript(), "queries/typescript.scm", kindMap);
    }

    private void registerGo() {
        Map<String, String> kindMap = Map.of(
                "import_declaration", "import",
                "import_spec", "import",
                "type_declaration", "class",
                "function_declaration", "function",
                "method_declaration", "method"
        );
        register("go", new TreeSitterGo(), "queries/go.scm", kindMap);
    }

    private void registerC() {
        Map<String, String> kindMap = Map.of(
                "preproc_include", "import",
                "function_definition", "function",
                "struct_specifier", "class",
                "enum_specifier", "enum",
                "type_definition", "type_alias"
        );
        register("c", new TreeSitterC(), "queries/c.scm", kindMap);
    }

    private void register(String languageName, TSLanguage language, String queryResource, Map<String, String> kindMap) {
        String queryString = loadQueryFromClasspath(queryResource);
        if (queryString == null) {
            log.error("Failed to load query file '{}' for language '{}'", queryResource, languageName);
            return;
        }
        // Verify the query compiles
        try {
            new TSQuery(language, queryString);
        } catch (TSQueryException e) {
            log.error("Query compilation failed for language '{}': {}", languageName, e.getMessage());
            return;
        }
        languages.put(languageName, new LanguageConfig(language, queryString, kindMap));
        log.debug("Registered language '{}' with {} node type mappings", languageName, kindMap.size());
    }

    private String loadQueryFromClasspath(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.error("Query resource not found: {}", resourcePath);
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to read query resource '{}': {}", resourcePath, e.getMessage());
            return null;
        }
    }
}

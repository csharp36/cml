package com.indexer.indexing;

import java.util.List;

public record ExtractedSymbol(
        String name,
        String kind,         // class, interface, method, function, enum, type_alias, import, field
        String signature,
        int startLine,
        int endLine,
        String parentName,   // null for top-level, class name for methods
        String visibility,   // public, private, protected, package
        boolean isStatic,
        List<Relationship> relationships
) {
    public record Relationship(String relatedName, String kind) {} // implements, extends

    public static ExtractedSymbol importSymbol(String importPath, int line) {
        return new ExtractedSymbol(importPath, "import", importPath, line, line, null, null, false, List.of());
    }
}

package com.indexer.scip;

public record ScipSymbolRow(
        String scipSymbol,
        String displayName,
        String kind,
        String documentation,
        String filePath,
        int startLine,
        int endLine
) {}

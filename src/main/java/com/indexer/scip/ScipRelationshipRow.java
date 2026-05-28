package com.indexer.scip;

public record ScipRelationshipRow(
        String fromSymbol,
        String toSymbol,
        String kind,
        String filePath,
        int line
) {}

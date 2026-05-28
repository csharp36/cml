package com.indexer.scip;

import java.util.List;

public record ScipParseResult(
        List<ScipSymbolRow> symbols,
        List<ScipRelationshipRow> relationships,
        int documentsProcessed
) {}

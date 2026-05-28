package com.indexer.scip;

import com.sourcegraph.scip.Scip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ScipParser {

    private static final Logger log = LoggerFactory.getLogger(ScipParser.class);

    public static ScipParseResult parse(Scip.Index index) {
        var symbols = new ArrayList<ScipSymbolRow>();
        var relationships = new ArrayList<ScipRelationshipRow>();

        for (var document : index.getDocumentsList()) {
            String filePath = document.getRelativePath();

            // Build a lookup from symbol string to SymbolInformation
            var symbolInfoMap = new HashMap<String, Scip.SymbolInformation>();
            for (var si : document.getSymbolsList()) {
                symbolInfoMap.put(si.getSymbol(), si);
            }

            // Find definition occurrences
            for (var occ : document.getOccurrencesList()) {
                if ((occ.getSymbolRoles() & Scip.SymbolRole.Definition_VALUE) == 0) {
                    continue; // skip references
                }

                String scipSymbol = occ.getSymbol();
                var range = occ.getRangeList();
                int startLine = range.size() >= 1 ? range.get(0) + 1 : 0; // 0-based to 1-based
                int endLine;
                // SCIP range: [startLine, startChar, endLine, endChar] or [line, startChar, endChar] for single-line
                if (range.size() == 3) {
                    endLine = startLine; // single-line occurrence
                } else {
                    endLine = range.size() >= 3 ? range.get(2) + 1 : startLine;
                }

                Scip.SymbolInformation info = symbolInfoMap.get(scipSymbol);
                String displayName = info != null && !info.getDisplayName().isEmpty() ? info.getDisplayName() : extractSimpleName(scipSymbol);
                String kind = info != null ? info.getKind().name() : "Unknown";
                String documentation = info != null && info.getDocumentationCount() > 0 ? info.getDocumentation(0) : null;

                symbols.add(new ScipSymbolRow(scipSymbol, displayName, kind, documentation, filePath, startLine, endLine));

                // Extract relationships from this symbol's SymbolInformation
                if (info != null) {
                    for (var rel : info.getRelationshipsList()) {
                        String relKind;
                        if (rel.getIsImplementation()) relKind = "implements";
                        else if (rel.getIsTypeDefinition()) relKind = "extends";
                        else if (rel.getIsReference()) relKind = "references";
                        else relKind = "references";

                        relationships.add(new ScipRelationshipRow(
                                scipSymbol, rel.getSymbol(), relKind, filePath, startLine));
                    }
                }
            }
        }

        log.info("Parsed SCIP: {} symbols, {} relationships from {} documents",
                symbols.size(), relationships.size(), index.getDocumentsCount());
        return new ScipParseResult(symbols, relationships, index.getDocumentsCount());
    }

    /** Extract a simple name from a SCIP symbol string (last segment after # or .). */
    private static String extractSimpleName(String scipSymbol) {
        if (scipSymbol == null || scipSymbol.isEmpty()) return "unknown";
        // SCIP symbols look like: java maven . com/example/Payment#charge().
        int hash = scipSymbol.lastIndexOf('#');
        if (hash >= 0 && hash < scipSymbol.length() - 1) {
            String after = scipSymbol.substring(hash + 1);
            // Remove trailing punctuation (., (), etc.)
            return after.replaceAll("[.()]+$", "");
        }
        return scipSymbol;
    }
}

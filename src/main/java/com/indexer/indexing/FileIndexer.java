package com.indexer.indexing;

import com.indexer.config.LanguageRegistry;
import com.indexer.db.FileDao;
import com.indexer.db.SymbolDao;
import com.indexer.model.Import;
import com.indexer.model.SourceFile;
import com.indexer.model.Symbol;
import com.indexer.model.TypeRelationship;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileIndexer {

    private static final Logger log = LoggerFactory.getLogger(FileIndexer.class);

    private final FileDao fileDao;
    private final SymbolDao symbolDao;
    private final Jdbi jdbi;
    private final LanguageRegistry languageRegistry;
    private final SymbolExtractor symbolExtractor;
    private final long maxFileSizeBytes;

    public FileIndexer(FileDao fileDao, SymbolDao symbolDao, Jdbi jdbi,
                       LanguageRegistry languageRegistry, SymbolExtractor symbolExtractor,
                       long maxFileSizeBytes) {
        this.fileDao = fileDao;
        this.symbolDao = symbolDao;
        this.jdbi = jdbi;
        this.languageRegistry = languageRegistry;
        this.symbolExtractor = symbolExtractor;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public void indexFile(int repoId, String branch, Path repoRoot, String relativePath, String commitSha) {
        Path filePath = repoRoot.resolve(relativePath);
        String filename = filePath.getFileName().toString();

        // 1. Check if binary → metadata only
        if (languageRegistry.isBinary(filename)) {
            indexMetadataOnly(repoId, branch, relativePath, filePath, commitSha);
            return;
        }

        // 2. Check file size → if too large → metadata only
        long fileSize;
        try {
            fileSize = Files.size(filePath);
        } catch (IOException e) {
            log.warn("Could not determine size of {}: {}", filePath, e.getMessage());
            indexMetadataOnly(repoId, branch, relativePath, filePath, commitSha);
            return;
        }

        if (fileSize > maxFileSizeBytes) {
            log.debug("File {} exceeds max size ({} > {}), indexing metadata only", relativePath, fileSize, maxFileSizeBytes);
            indexMetadataOnly(repoId, branch, relativePath, filePath, commitSha);
            return;
        }

        // 3. Detect language
        String language = languageRegistry.detectLanguage(filename);

        // 4. Read file content
        String content;
        try {
            content = Files.readString(filePath);
        } catch (IOException e) {
            log.warn("Could not read file {}: {}", filePath, e.getMessage());
            indexMetadataOnly(repoId, branch, relativePath, filePath, commitSha);
            return;
        }

        // 5. Upsert file record
        SourceFile sourceFile = new SourceFile(0, repoId, branch, relativePath, language, (int) fileSize, commitSha, Instant.now());
        int fileId = fileDao.upsert(sourceFile);

        // 6. Delete existing symbols/imports for this file (re-index)
        symbolDao.deleteSymbolsByFileId(fileId);
        symbolDao.deleteImportsByFileId(fileId);

        // 7. Insert content into file_contents (upsert ON CONFLICT)
        indexContent(fileId, content);

        // 8. If core language: run SymbolExtractor, insert symbols/imports/type_relationships
        if (languageRegistry.isCoreLanguage(language)) {
            indexSymbols(fileId, content, language);
        }
    }

    public void removeFile(int repoId, String relativePath) {
        fileDao.deleteByRepoAndPath(repoId, relativePath);
    }

    private void indexMetadataOnly(int repoId, String branch, String relativePath, Path filePath, String commitSha) {
        long fileSize = 0;
        try {
            fileSize = Files.exists(filePath) ? Files.size(filePath) : 0;
        } catch (IOException e) {
            log.debug("Could not get size for metadata-only index of {}: {}", filePath, e.getMessage());
        }
        String language = languageRegistry.detectLanguage(filePath.getFileName().toString());
        SourceFile sourceFile = new SourceFile(0, repoId, branch, relativePath, language, (int) fileSize, commitSha, Instant.now());
        fileDao.upsert(sourceFile);
    }

    private void indexContent(int fileId, String content) {
        jdbi.useHandle(handle ->
            handle.createUpdate("""
                INSERT INTO file_contents (file_id, content)
                VALUES (:fileId, :content)
                ON CONFLICT (file_id) DO UPDATE SET content = EXCLUDED.content
                """)
                .bind("fileId", fileId)
                .bind("content", content)
                .execute()
        );
    }

    private void indexSymbols(int fileId, String content, String language) {
        List<ExtractedSymbol> extracted = symbolExtractor.extract(content, language);

        // Map from symbol name to its DB id for parent tracking
        Map<String, Integer> nameToId = new HashMap<>();

        for (ExtractedSymbol sym : extracted) {
            if ("import".equals(sym.kind())) {
                // Insert as import record
                Import imp = new Import(0, fileId, sym.name(), null);
                symbolDao.insertImport(imp);
            } else {
                // Resolve parent id if present
                Integer parentId = null;
                if (sym.parentName() != null) {
                    parentId = nameToId.get(sym.parentName());
                }

                Symbol symbol = new Symbol(
                        0,
                        fileId,
                        sym.name(),
                        sym.kind(),
                        sym.signature(),
                        sym.startLine(),
                        sym.endLine(),
                        parentId,
                        sym.visibility(),
                        sym.isStatic()
                );
                int symbolId = symbolDao.insertSymbol(symbol);
                nameToId.put(sym.name(), symbolId);

                // Insert type relationships
                for (ExtractedSymbol.Relationship rel : sym.relationships()) {
                    TypeRelationship typeRel = new TypeRelationship(0, symbolId, rel.relatedName(), rel.kind());
                    symbolDao.insertTypeRelationship(typeRel);
                }
            }
        }
    }
}

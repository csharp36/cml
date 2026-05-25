package com.indexer.db;

import com.indexer.model.Import;
import com.indexer.model.Symbol;
import com.indexer.model.TypeRelationship;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

public class SymbolDao {

    private final Jdbi jdbi;

    public SymbolDao(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public int insertSymbol(Symbol symbol) {
        return jdbi.withHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO symbols (file_id, name, kind, signature, start_line, end_line, parent_id, visibility, is_static)
                        VALUES (:fileId, :name, :kind, :signature, :startLine, :endLine, :parentId, :visibility, :isStatic)
                        """)
                        .bind("fileId", symbol.fileId())
                        .bind("name", symbol.name())
                        .bind("kind", symbol.kind())
                        .bind("signature", symbol.signature())
                        .bind("startLine", symbol.startLine())
                        .bind("endLine", symbol.endLine())
                        .bind("parentId", symbol.parentId())
                        .bind("visibility", symbol.visibility())
                        .bind("isStatic", symbol.isStatic())
                        .executeAndReturnGeneratedKeys("id")
                        .mapTo(Integer.class)
                        .one()
        );
    }

    public void insertImport(Import imp) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO imports (file_id, import_path, alias)
                        VALUES (:fileId, :importPath, :alias)
                        """)
                        .bind("fileId", imp.fileId())
                        .bind("importPath", imp.importPath())
                        .bind("alias", imp.alias())
                        .execute()
        );
    }

    public void insertTypeRelationship(TypeRelationship rel) {
        jdbi.useHandle(handle ->
                handle.createUpdate("""
                        INSERT INTO type_relationships (symbol_id, related_name, kind)
                        VALUES (:symbolId, :relatedName, :kind)
                        """)
                        .bind("symbolId", rel.symbolId())
                        .bind("relatedName", rel.relatedName())
                        .bind("kind", rel.kind())
                        .execute()
        );
    }

    public void deleteSymbolsByFileId(int fileId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM symbols WHERE file_id = :fileId")
                        .bind("fileId", fileId)
                        .execute()
        );
    }

    public void deleteImportsByFileId(int fileId) {
        jdbi.useHandle(handle ->
                handle.createUpdate("DELETE FROM imports WHERE file_id = :fileId")
                        .bind("fileId", fileId)
                        .execute()
        );
    }

    public List<Symbol> findByFileId(int fileId) {
        return jdbi.withHandle(handle ->
                handle.createQuery("SELECT * FROM symbols WHERE file_id = :fileId ORDER BY start_line")
                        .bind("fileId", fileId)
                        .map((rs, ctx) -> new Symbol(
                                rs.getInt("id"),
                                rs.getInt("file_id"),
                                rs.getString("name"),
                                rs.getString("kind"),
                                rs.getString("signature"),
                                rs.getInt("start_line"),
                                rs.getInt("end_line"),
                                rs.getObject("parent_id") != null ? rs.getInt("parent_id") : null,
                                rs.getString("visibility"),
                                rs.getBoolean("is_static")
                        ))
                        .list()
        );
    }
}

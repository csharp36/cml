package com.indexer.model;

public record Symbol(int id, int fileId, String name, String kind, String signature, int startLine, int endLine, Integer parentId, String visibility, boolean isStatic) {}

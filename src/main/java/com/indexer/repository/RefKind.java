package com.indexer.repository;

import java.util.Locale;

/** The kind of git ref a {@code branch_index} row represents. */
public enum RefKind {
    BRANCH,
    TAG,
    SHA;

    /** Lowercase form stored in branch_index.ref_kind ("branch"/"tag"/"sha"). */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse the DB string form back to a RefKind; defaults to BRANCH if unknown/null. */
    public static RefKind fromDb(String value) {
        if (value == null) return BRANCH;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BRANCH;
        }
    }
}

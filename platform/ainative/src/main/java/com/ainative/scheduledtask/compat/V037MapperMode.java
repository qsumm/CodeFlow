package com.ainative.scheduledtask.compat;

public enum V037MapperMode {
    LEGACY("ainative-legacy"),
    SOURCE_AWARE("ainative-source-aware");

    private final String databaseId;

    V037MapperMode(String databaseId) {
        this.databaseId = databaseId;
    }

    public String databaseId() {
        return databaseId;
    }
}

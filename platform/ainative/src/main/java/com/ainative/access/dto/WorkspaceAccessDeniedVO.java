package com.ainative.access.dto;

import com.ainative.access.WorkspaceAccessLevel;

public class WorkspaceAccessDeniedVO {
    private final WorkspaceAccessLevel current;
    private final WorkspaceAccessLevel required;
    private final String action;

    public WorkspaceAccessDeniedVO(WorkspaceAccessLevel current, WorkspaceAccessLevel required, String action) {
        this.current = current;
        this.required = required;
        this.action = action;
    }

    public WorkspaceAccessLevel getCurrent() {
        return current;
    }

    public WorkspaceAccessLevel getRequired() {
        return required;
    }

    public String getAction() {
        return action;
    }
}

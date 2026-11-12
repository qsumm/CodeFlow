package com.ainative.workspace.dto;

import com.ainative.access.WorkspaceAccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SwitchWorkspaceResponse {
    private String accessToken;
    private WorkspaceAccessLevel accessLevel;

    public SwitchWorkspaceResponse(String accessToken, WorkspaceAccessLevel accessLevel) {
        this.accessToken = accessToken;
        this.accessLevel = accessLevel;
    }
}

package com.ainative.workspace.dto;

import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.WorkspaceDomain;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
public class CurrentMembershipVO {
    private Long userId;
    private String username;
    private String email;
    private String nickname;
    private Date joinedAt;
    private boolean owner;
    private WorkspaceAccessLevel accessLevel;
    private List<String> identityTags;
    private Long roleId;
    private String roleCode;
    private String roleName;
    private List<WorkspaceDomain> readOnlyDomains;
}

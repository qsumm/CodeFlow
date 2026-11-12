package com.ainative.workspace.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddMemberRequest {
    private Long userId;
    private Long roleId;
}

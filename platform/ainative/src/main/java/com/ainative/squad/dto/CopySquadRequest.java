package com.ainative.squad.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CopySquadRequest {
    private String requestId;
    private List<Long> targetWorkspaceIds;
}

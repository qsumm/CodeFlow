package com.ainative.repo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateRelationRequest {
    private Long fromRepoId;
    private Long toRepoId;
    private String relationType;
    private String description;
    private Long aiSessionId;
}

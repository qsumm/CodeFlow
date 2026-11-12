package com.ainative.skill.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSkillRequest {
    private String type;
    private String name;
    private String installSpec;
    private String description;
}

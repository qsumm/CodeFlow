package com.ainative.statemachine.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateTemplateRequest {
    private String name;
    private Boolean isDefault;
}

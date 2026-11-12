package com.ainative.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateIdpRequest {
    private String name;
    private String description;
}

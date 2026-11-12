package com.ainative.squad.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSquadRequest {
    private String name;
    private String description;
    private Long ownerId;
}

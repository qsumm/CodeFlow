package com.ainative.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemoryRefRequest {
    private Long memoryId;
    private String source;
}

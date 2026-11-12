package com.ainative.aiusage.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateQuotaRequest {
    private Long maxCalls;
    private Long maxTokens;
    private Integer concurrencyLimit;
}

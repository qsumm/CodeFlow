package com.ainative.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class CostKpiVO {
    private int range;
    private String agentId;
    private BigDecimal total;
    /** Token 维度总量(= costComposition.tokens,主展示维度)。 */
    private long tokens;
    private BigDecimal perTask;
    private BigDecimal perDay;
}

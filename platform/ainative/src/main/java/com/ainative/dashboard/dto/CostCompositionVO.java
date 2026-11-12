package com.ainative.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CostCompositionVO {
    private int range;
    private String scope;
    private BigDecimal total;
    /** Token 维度总量(主展示维度)。旧后端缺失时前端按 credits 兜底。 */
    private long tokens;
    private List<Source> sources;

    @Getter
    @Setter
    public static class Source {
        private String key;
        private String name;
        private BigDecimal credits;
        private BigDecimal share;
        /** 该来源 Token 消耗。 */
        private long tokens;
    }
}

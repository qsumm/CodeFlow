package com.ainative.aiusage;

import com.ainative.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiUsageDO extends BaseDO {
    private Long tenantId;
    private String period;
    private String scene;
    private Long callCount;
    private Long inputTokens;
    private Long outputTokens;
}

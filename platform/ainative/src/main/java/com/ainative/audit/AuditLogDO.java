package com.ainative.audit;

import com.ainative.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuditLogDO extends BaseDO {
    private Long tenantId;
    private Long actorId;
    private String module;
    private String action;
    private String riskLevel;
    private String targetType;
    private Long targetId;
    private String detailJson;
}

package com.ainative.notification;

import com.ainative.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NotifyPrefDO extends BaseDO {
    private Long tenantId;
    private Long userId;
    private String type;
    private Integer inApp;
    private Integer dingtalk;
    private Integer feishu;
}

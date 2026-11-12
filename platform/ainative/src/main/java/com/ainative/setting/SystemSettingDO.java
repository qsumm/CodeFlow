package com.ainative.setting;

import com.ainative.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SystemSettingDO extends BaseDO {
    private Long tenantId;
    private String settingGroup;
    private String settingKey;
    private String valueJson;
    private Integer isSecret;
    private String credentialRef;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}

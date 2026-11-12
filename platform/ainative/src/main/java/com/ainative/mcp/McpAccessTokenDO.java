package com.ainative.mcp;

import com.ainative.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class McpAccessTokenDO extends BaseDO {
    private String name;
    private Long userId;
    private String tokenHash;
    private String tokenPrefix;
    private Date lastUsedAt;
    private Date revokedAt;
}

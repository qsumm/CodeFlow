package com.ainative.executor.dto;

import lombok.Getter;
import lombok.Setter;

/** 批量配齐里单个成员的结果。 */
@Getter
@Setter
public class ProvisionItemVO {
    public static final String CREATED = "CREATED";
    public static final String SKIPPED_EXISTS = "SKIPPED_EXISTS";
    public static final String FAILED = "FAILED";

    private Long agentId;
    private String agentName;
    private String status;
    /** 新建成功时携带。 */
    private IssuedExecutorVO executor;
    /** SKIPPED_EXISTS 时指向该成员名下已有的执行器。 */
    private Long existingExecutorId;
    /** 就绪可直接复制的启动命令(含明文令牌,仅管理员可见)。 */
    private String command;
    private String error;
}

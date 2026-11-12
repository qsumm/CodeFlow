package com.ainative.executor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 编队一键配齐运行器的请求:逐个缺运行器的成员建一个执行器并生成启动命令。 */
@Getter
@Setter
public class BatchProvisionSquadExecutorsRequest {
    /** 要配运行器的成员(小队成员的 agentId 子集);空表示「全部缺运行器的成员」。 */
    private List<Long> agentIds;
    /** 客户端类型;缺省 QODER_CLI,与单建表单默认一致。 */
    private String clientKind;
    /** 记忆模式;缺省 platform。 */
    private String memoryMode;
    /** 并发上限;缺省 5。 */
    private Integer maxConcurrentDispatches;
    /** 启动命令的目标 shell 口径:posix 或 windows;缺省 posix。 */
    private String os;
}

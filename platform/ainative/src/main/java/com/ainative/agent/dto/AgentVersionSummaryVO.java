package com.ainative.agent.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AgentVersionSummaryVO {
    private Long id;
    private Integer versionNo;
    private String status;
    private String roleName;
    private String roleCode;
    private Date gmtCreate;
}

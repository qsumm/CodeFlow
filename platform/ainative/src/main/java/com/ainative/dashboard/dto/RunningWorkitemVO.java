package com.ainative.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RunningWorkitemVO {
    private Long workitemId;
    private String title;
    private String agentName;
    private String stepName;
    private int runningMinutes;
    private int runningDispatchCount;
}

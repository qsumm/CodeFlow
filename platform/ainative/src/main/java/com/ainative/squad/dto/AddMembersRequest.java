package com.ainative.squad.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class AddMembersRequest {
    private List<Long> agentIds;
}

package com.ainative.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SyncNowRequest {
    private List<String> issueIds;
}

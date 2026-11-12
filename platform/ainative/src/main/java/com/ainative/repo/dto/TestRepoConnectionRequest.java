package com.ainative.repo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TestRepoConnectionRequest {
    private String name;
    private String url;
    private String defaultBranch;
}

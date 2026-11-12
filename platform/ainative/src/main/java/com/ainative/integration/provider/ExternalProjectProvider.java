package com.ainative.integration.provider;

import com.ainative.integration.provider.ExternalPlatformConfig;

import java.util.List;

public interface ExternalProjectProvider {
    PageResult<ExternalProject> searchProjects(ExternalPlatformConfig config, String query, int page, int pageSize);
    ExternalProject getProject(ExternalPlatformConfig config, String externalProjectId);
    List<ExternalProjectMember> listMembers(ExternalPlatformConfig config, String externalProjectId);
}

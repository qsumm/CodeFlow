package com.ainative.integration.provider;

import com.ainative.integration.provider.ExternalPlatformConfig;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface ExternalWorkitemProvider {
    String provider();
    PageResult<ExternalWorkitemSummary> searchByIds(ExternalPlatformConfig config, String externalProjectId, List<String> ids);
    PageResult<ExternalWorkitemSummary> searchProject(ExternalPlatformConfig config, String externalProjectId, Date createdFrom, Date createdTo);
    PageResult<ExternalWorkitemSummary> searchProjectFirstPage(ExternalPlatformConfig config, String externalProjectId);
    ExternalWorkitemDetail getWorkitem(ExternalPlatformConfig config, String externalWorkitemId);
    Map<String, List<ExternalStatusOption>> listOperationalStatuses(ExternalPlatformConfig config, String staffId, List<String> externalWorkitemIds);
    List<ExternalComment> listComments(ExternalPlatformConfig config, List<String> externalWorkitemIds);
    ExternalComment createComment(ExternalPlatformConfig config, String externalWorkitemId, String staffId, String contentMd);
    void updateStatus(ExternalPlatformConfig config, String externalWorkitemId, String staffId, String statusName);
    void updateContent(ExternalPlatformConfig config, String externalWorkitemId, String staffId, String title, String contentMd);
}

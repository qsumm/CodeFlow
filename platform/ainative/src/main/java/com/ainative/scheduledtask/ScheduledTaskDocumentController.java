package com.ainative.scheduledtask;

import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.artifact.ArtifactOwnerRef;
import com.ainative.artifact.RequirementDocumentService;
import com.ainative.artifact.dto.ArtifactVO;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.dispatch.ExecutionSourceType;
import com.ainative.scheduledtask.compat.RequiresScheduledTaskCapability;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/scheduled-tasks")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看定时任务文档", actionKey = "access.action.view_scheduled_task_documents")
@RequiresScheduledTaskCapability(entry = "http")
public class ScheduledTaskDocumentController {

    private final RequirementDocumentService requirementDocumentService;

    public ScheduledTaskDocumentController(RequirementDocumentService requirementDocumentService) {
        this.requirementDocumentService = requirementDocumentService;
    }

    @GetMapping("/{id}/documents")
    public Result<List<ArtifactVO>> list(@PathVariable("id") Long id) {
        return Result.ok(requirementDocumentService.list(owner(id), currentWorkspaceId()));
    }

    @PostMapping("/{id}/documents")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "上传定时任务需求文档", actionKey = "access.action.upload_scheduled_task_requirement_documents")
    public Result<List<ArtifactVO>> upload(@PathVariable("id") Long id,
                                           @RequestParam("files") MultipartFile[] files) throws IOException {
        return Result.ok(requirementDocumentService.uploadWeb(
                owner(id), files, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}/documents/{artifactId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除定时任务需求文档", actionKey = "access.action.delete_scheduled_task_requirement_documents")
    public Result<Boolean> delete(@PathVariable("id") Long id,
                                  @PathVariable("artifactId") Long artifactId) {
        requirementDocumentService.delete(owner(id), artifactId, currentWorkspaceId(), currentUserId());
        return Result.ok(true);
    }

    private ArtifactOwnerRef owner(long id) {
        return new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, id);
    }

    private long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }

    private long currentUserId() {
        Long userId = RequestContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}

package com.ainative.sdlc;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.common.result.PageResult;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.sdlc.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sdlcs")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看流程", actionKey = "access.action.view_sdlc_workflows")
public class SdlcController {

    private final SdlcService sdlcService;

    public SdlcController(SdlcService sdlcService) {
        this.sdlcService = sdlcService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建流程", actionKey = "access.action.create_an_sdlc_workflow")
    public Result<SdlcVO> create(@RequestBody CreateSdlcRequest req) {
        return Result.ok(sdlcService.create(req, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<SdlcVO> get(@PathVariable("id") Long id) {
        return Result.ok(sdlcService.get(id));
    }

    @GetMapping
    public Result<List<SdlcVO>> list(
            @RequestParam(value = "workType", required = false) String workType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "squadIds", required = false) List<Long> squadIds,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(sdlcService.list(currentWorkspaceId(), workType, status, squadIds, page, size));
    }

    // Keep the array endpoint for existing integrations and reference-data consumers.
    @GetMapping("/page")
    public Result<PageResult<SdlcVO>> listPage(
            @RequestParam(value = "workType", required = false) String workType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "squadIds", required = false) List<Long> squadIds,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(sdlcService.listPage(currentWorkspaceId(), workType, status, squadIds, page, size));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新流程", actionKey = "access.action.update_an_sdlc_workflow")
    public Result<SdlcVO> update(@PathVariable("id") Long id, @RequestBody UpdateSdlcRequest req) {
        return Result.ok(sdlcService.update(id, req, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除流程", actionKey = "access.action.delete_an_sdlc_workflow")
    public Result<Void> delete(@PathVariable("id") Long id) {
        sdlcService.delete(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/steps")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "添加流程步骤", actionKey = "access.action.add_an_sdlc_step")
    public Result<StepVO> addStep(@PathVariable("id") Long id, @RequestBody CreateStepRequest req) {
        return Result.ok(sdlcService.addStep(id, req, currentWorkspaceId(), currentUserId()));
    }

    @PutMapping("/{id}/steps/{stepId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新流程步骤", actionKey = "access.action.update_an_sdlc_step")
    public Result<StepVO> updateStep(@PathVariable("id") Long id,
                                     @PathVariable("stepId") Long stepId,
                                     @RequestBody UpdateStepRequest req) {
        return Result.ok(sdlcService.updateStep(id, stepId, req, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}/steps/{stepId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除流程步骤", actionKey = "access.action.delete_an_sdlc_step")
    public Result<Void> deleteStep(@PathVariable("id") Long id, @PathVariable("stepId") Long stepId) {
        sdlcService.deleteStep(id, stepId, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/{id}/steps/reorder")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "重排流程步骤", actionKey = "access.action.reorder_sdlc_steps")
    public Result<Void> reorder(@PathVariable("id") Long id, @RequestBody ReorderRequest req) {
        sdlcService.reorderSteps(id, req, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "启用流程", actionKey = "access.action.enable_an_sdlc_workflow")
    public Result<SdlcVO> enable(@PathVariable("id") Long id,
                                 @RequestParam(value = "statusTemplateId", required = false) Long statusTemplateId) {
        return Result.ok(sdlcService.enable(id, statusTemplateId, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/{id}/disable")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "停用流程", actionKey = "access.action.disable_an_sdlc_workflow")
    public Result<Void> disable(@PathVariable("id") Long id) {
        sdlcService.disable(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = RequestContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}

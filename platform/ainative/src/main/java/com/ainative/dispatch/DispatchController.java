package com.ainative.dispatch;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.dispatch.dto.DispatchPageVO;
import com.ainative.dispatch.dto.DispatchVO;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dispatches")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看调度", actionKey = "access.action.view_dispatches")
public class DispatchController {

    private final DispatchQueryService dispatchQueryService;

    public DispatchController(DispatchQueryService dispatchQueryService) {
        this.dispatchQueryService = dispatchQueryService;
    }

    @GetMapping
    public Result<DispatchPageVO> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "agent_id", required = false) Long agentId,
            @RequestParam(value = "workitem_id", required = false) Long workitemId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(dispatchQueryService.list(
                currentWorkspaceId(), status, agentId, workitemId, timeRange, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<DispatchVO> get(@PathVariable("id") long id) {
        return Result.ok(dispatchQueryService.get(currentWorkspaceId(), id));
    }

    private long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}

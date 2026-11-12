package com.ainative.agent;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.executor.dto.CreateExecutorRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform-agent/setup")
@RequireWorkspaceAccess(value=WorkspaceAccessLevel.READ_ONLY, action="查看平台智能体状态", actionKey="access.action.view_platform_agent_status")
public class ChiefOfStaffSetupController {
    private final ChiefOfStaffSetupService service;
    public ChiefOfStaffSetupController(ChiefOfStaffSetupService service) { this.service=service; }
    @GetMapping
    public Result<ChiefOfStaffSetupService.Status> inspect() {
        return Result.ok(service.inspect(RequestContext.get().getCurrentWorkspaceId()));
    }
    @PostMapping
    @RequireWorkspaceAccess(value=WorkspaceAccessLevel.ADMIN, action="配置 AI 管家", actionKey="access.action.configure_chief_of_staff")
    public Result<ChiefOfStaffSetupService.Status> complete(@RequestBody CreateExecutorRequest request) {
        return Result.ok(service.complete(RequestContext.get().getCurrentWorkspaceId(),
                RequestContext.get().getUserId(), request));
    }
}

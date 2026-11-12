package com.ainative.agent;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform-intelligence")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看平台智能能力状态", actionKey = "access.action.view_platform_intelligence_status")
public class PlatformIntelligenceController {
    private final PlatformIntelligenceService service;

    public PlatformIntelligenceController(PlatformIntelligenceService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Result<PlatformIntelligenceService.CapabilityStatus> status() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Result.ok(service.getStatus(workspaceId));
    }
}

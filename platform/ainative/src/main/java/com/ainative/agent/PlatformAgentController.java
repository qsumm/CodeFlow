package com.ainative.agent;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.agent.dto.PlatformAgentStatusVO;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform-agent")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看平台智能体状态", actionKey = "access.action.view_platform_agent_status")
public class PlatformAgentController {

    private final PlatformAgentStatusService statusService;

    public PlatformAgentController(PlatformAgentStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public Result<PlatformAgentStatusVO> status() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return Result.ok(statusService.getStatus(workspaceId));
    }
}

package com.ainative.evolution;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/run")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进运行", actionKey = "access.action.view_evolution_runs")
public class EvolutionRunController {

    private final EvolutionAssetRouterLiteService routerService;

    public EvolutionRunController(EvolutionAssetRouterLiteService routerService) {
        this.routerService = routerService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "执行演进运行", actionKey = "access.action.execute_an_evolution_run")
    public Result<EvolutionRunResult> run(@RequestBody EvolutionRunCommand req) {
        return Result.ok(routerService.run(req, currentWorkspaceId(), currentUserId()));
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

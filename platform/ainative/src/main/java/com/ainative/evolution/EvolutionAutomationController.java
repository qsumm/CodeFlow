package com.ainative.evolution;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进自动化", actionKey = "access.action.view_evolution_automation")
public class EvolutionAutomationController {

    private final EvolutionOrchestratorLiteService orchestratorService;
    private final EvolutionReplayExecutorLiteService replayExecutorService;
    private final EvolutionCanaryPostprocessLiteService canaryPostprocessService;

    public EvolutionAutomationController(EvolutionOrchestratorLiteService orchestratorService,
                                         EvolutionReplayExecutorLiteService replayExecutorService,
                                         EvolutionCanaryPostprocessLiteService canaryPostprocessService) {
        this.orchestratorService = orchestratorService;
        this.replayExecutorService = replayExecutorService;
        this.canaryPostprocessService = canaryPostprocessService;
    }

    @PostMapping("/orchestrate")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "编排演进自动化", actionKey = "access.action.orchestrate_evolution_automation")
    public Result<EvolutionOrchestrateResult> orchestrate(@RequestBody EvolutionOrchestrateCommand req) {
        return Result.ok(orchestratorService.orchestrate(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/replay/execute")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "执行演进回放", actionKey = "access.action.run_an_evolution_replay")
    public Result<EvolutionReplayExecuteResult> executeReplay(@RequestBody EvolutionReplayExecuteCommand req) {
        return Result.ok(replayExecutorService.execute(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/canary/postprocess")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "处理演进灰度结果", actionKey = "access.action.process_evolution_canary_results")
    public Result<EvolutionCanaryPostprocessResult> postprocessCanary(
            @RequestBody EvolutionCanaryPostprocessCommand req) {
        return Result.ok(canaryPostprocessService.postprocess(req, currentWorkspaceId(), currentUserId()));
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

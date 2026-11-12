package com.ainative.evolution;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/proposals/{id}/trial")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进试验", actionKey = "access.action.view_evolution_trials")
public class EvolutionTrialController {

    private final EvolutionHypothesisTrialLiteService trialService;

    public EvolutionTrialController(EvolutionHypothesisTrialLiteService trialService) {
        this.trialService = trialService;
    }

    @PostMapping("/start")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "启动演进试验", actionKey = "access.action.start_an_evolution_trial")
    public Result<EvolutionTrialDecision> start(@PathVariable("id") Long id,
                                                @RequestBody EvolutionTrialStartRequest req) {
        return Result.ok(trialService.startTrial(id, req == null ? null : req.getTaskPatternKey(),
                currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/evidence")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "记录演进试验结果", actionKey = "access.action.record_evolution_trial_results")
    public Result<EvolutionTrialDecision> recordOutcome(@PathVariable("id") Long id,
                                                        @RequestBody EvolutionTrialEvidenceCommand req) {
        return Result.ok(trialService.recordOutcome(id, req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/decide")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "决策演进试验", actionKey = "access.action.decide_an_evolution_trial")
    public Result<EvolutionTrialDecision> decide(@PathVariable("id") Long id) {
        return Result.ok(trialService.decide(id, currentWorkspaceId(), currentUserId()));
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

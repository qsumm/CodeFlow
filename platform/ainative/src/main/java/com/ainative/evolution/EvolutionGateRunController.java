package com.ainative.evolution;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/gates")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进门禁运行", actionKey = "access.action.view_evolution_gate_runs")
public class EvolutionGateRunController {

    private final EvolutionGateRunLiteService gateRunService;

    public EvolutionGateRunController(EvolutionGateRunLiteService gateRunService) {
        this.gateRunService = gateRunService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "记录演进门禁运行", actionKey = "access.action.record_an_evolution_gate_run")
    public Result<EvolutionGateRunDO> record(@RequestBody EvolutionGateRunCommand req) {
        return Result.ok(gateRunService.record(req, currentWorkspaceId(), currentUserId()));
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

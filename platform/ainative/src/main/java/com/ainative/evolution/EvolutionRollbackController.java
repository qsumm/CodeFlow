package com.ainative.evolution;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/rollback")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进回滚", actionKey = "access.action.view_evolution_rollbacks")
public class EvolutionRollbackController {

    private final EvolutionReleaseRollbackLiteService rollbackService;

    public EvolutionRollbackController(EvolutionReleaseRollbackLiteService rollbackService) {
        this.rollbackService = rollbackService;
    }

    @PostMapping("/{proposalId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "回滚演进变更", actionKey = "access.action.roll_back_evolution_changes")
    public Result<EvolutionRollbackResult> rollback(@PathVariable("proposalId") Long proposalId) {
        return Result.ok(rollbackService.rollback(proposalId, currentWorkspaceId(), currentUserId()));
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

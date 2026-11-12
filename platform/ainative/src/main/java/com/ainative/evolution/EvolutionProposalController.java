package com.ainative.evolution;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/proposals")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进提案", actionKey = "access.action.view_evolution_proposals")
public class EvolutionProposalController {

    private final EvolutionProposalService proposalService;
    private final EvolutionAgentReleaseLiteService agentReleaseService;

    public EvolutionProposalController(EvolutionProposalService proposalService,
                                       EvolutionAgentReleaseLiteService agentReleaseService) {
        this.proposalService = proposalService;
        this.agentReleaseService = agentReleaseService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建演进提案", actionKey = "access.action.create_an_evolution_proposal")
    public Result<EvolutionProposalDO> propose(@RequestBody EvolutionProposalCommand req) {
        return Result.ok(proposalService.propose(req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/{id}/validate")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "校验演进提案", actionKey = "access.action.validate_an_evolution_proposal")
    public Result<Void> validate(@PathVariable("id") Long id) {
        proposalService.validate(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/replay")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "记录演进回放", actionKey = "access.action.record_an_evolution_replay")
    public Result<Void> recordReplay(@PathVariable("id") Long id, @RequestBody EvolutionReplayRequest req) {
        proposalService.recordReplay(id, currentWorkspaceId(), req.getReplayJson(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/approve")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "通过演进提案", actionKey = "access.action.approve_an_evolution_proposal")
    public Result<Void> approve(@PathVariable("id") Long id) {
        proposalService.approve(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/release")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "发布演进提案", actionKey = "access.action.publish_an_evolution_proposal")
    public Result<Void> release(@PathVariable("id") Long id) {
        proposalService.release(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/agent-release")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "发布演进智能体", actionKey = "access.action.publish_an_evolved_agent")
    public Result<EvolutionAgentReleaseResult> agentRelease(@PathVariable("id") Long id,
                                                            @RequestBody EvolutionAgentReleaseCommand req) {
        return Result.ok(agentReleaseService.release(id, req, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/{id}/reject")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "驳回演进提案", actionKey = "access.action.reject_an_evolution_proposal")
    public Result<Void> reject(@PathVariable("id") Long id, @RequestBody EvolutionRejectRequest req) {
        proposalService.reject(id, currentWorkspaceId(), req == null ? null : req.getReason(), currentUserId());
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

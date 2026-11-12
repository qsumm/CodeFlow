package com.ainative.insights;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.aiusage.dto.DispatchAiUsageBackfillResult;
import com.ainative.insights.dto.HumanAgentParticipationVO;
import com.ainative.insights.dto.HumanAgentSlowTailVO;
import com.ainative.insights.dto.InsightAuditPageVO;
import com.ainative.insights.dto.InsightMetricsVO;
import com.ainative.insights.dto.InsightWorkerVO;
import com.ainative.insights.participation.ParticipationRefreshResult;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/insights")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看洞察", actionKey = "access.action.view_insights")
public class InsightsController {

    private final InsightsService insightsService;

    public InsightsController(InsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/metrics")
    public Result<InsightMetricsVO> getMetrics(
            @RequestParam(value = "worker_id", required = false) Long workerId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(insightsService.getMetrics(currentWorkspaceId(), workerId, timeRange));
    }

    @GetMapping("/audit")
    public Result<InsightAuditPageVO> getAudit(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(value = "risk_level", required = false) String riskLevel,
            @RequestParam(value = "worker_id", required = false) Long workerId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(insightsService.getAudit(currentWorkspaceId(), riskLevel, workerId, timeRange, page, pageSize));
    }

    @GetMapping("/workers")
    public Result<List<InsightWorkerVO>> getWorkers() {
        return Result.ok(insightsService.getWorkers(currentWorkspaceId()));
    }

    @PostMapping("/usage/backfill")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "回填AI用量数据", actionKey = "access.action.backfill_ai_usage_data")
    public Result<DispatchAiUsageBackfillResult> backfillUsage() {
        return Result.ok(insightsService.backfillUsage(currentWorkspaceId()));
    }

    @GetMapping("/human-agent-participation")
    public Result<HumanAgentParticipationVO> getParticipation(
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "work_type", required = false) String workType) {
        return Result.ok(insightsService.getParticipation(
                currentWorkspaceId(),
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                workType));
    }

    @GetMapping("/human-agent-participation/slowest")
    public Result<HumanAgentSlowTailVO> getSlowTail(
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "work_type", required = false) String workType) {
        return Result.ok(insightsService.getSlowTail(
                currentWorkspaceId(),
                LocalDate.parse(startDate),
                LocalDate.parse(endDate),
                workType));
    }

    @PostMapping("/human-agent-participation/refresh")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "强制刷新人机协作数据", actionKey = "access.action.force_a_refresh_of_human_and_agent_collaboration_data")
    public Result<Void> forceRefreshParticipation() {
        ParticipationRefreshResult result = insightsService.forceParticipationRefresh(currentWorkspaceId());
        return switch (result) {
            case ACCEPTED -> Result.ok(null);
            case LOCK_CONTENTION -> Result.fail(ErrorCode.RETRY_LATER);
            case QUEUE_FULL -> Result.fail(ErrorCode.SYSTEM_BUSY);
        };
    }

    private long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}

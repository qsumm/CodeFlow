package com.ainative.insights;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.insights.dto.StageDurationVO;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/insights/stage-duration")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看阶段耗时", actionKey = "access.action.view_stage_duration")
public class StageDurationController {
    private final StageDurationService service;

    public StageDurationController(StageDurationService service) { this.service = service; }

    @GetMapping
    public Result<StageDurationVO> get(@RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "squad_id", required = false) Long squadId,
            @RequestParam(value = "sdlc_id", required = false) Long sdlcId) {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } catch (RuntimeException malformed) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return Result.ok(service.get(workspaceId, start, end, squadId, sdlcId));
    }
}

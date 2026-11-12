package com.ainative.insights;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.PageResult;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.insights.dto.CreatedWorkitemVO;
import com.ainative.insights.dto.DeliveryOverviewVO;
import com.ainative.insights.dto.PendingWorkitemVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 交付总览：近期任务统计（新增工单、交付吞吐）、新建工单明细与当前登录人待处理工单，只读。
 */
@RestController
@RequestMapping("/api/insights/delivery-overview")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看交付总览", actionKey = "access.action.view_delivery_overview")
public class DeliveryOverviewController {

    private final DeliveryOverviewService service;

    public DeliveryOverviewController(DeliveryOverviewService service) {
        this.service = service;
    }

    @GetMapping
    public Result<DeliveryOverviewVO> get(@RequestParam("start_date") String startDate,
                                          @RequestParam("end_date") String endDate) {
        return Result.ok(service.overview(currentWorkspaceId(), parseDate(startDate), parseDate(endDate)));
    }

    @GetMapping("/created")
    public Result<PageResult<CreatedWorkitemVO>> created(
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(service.created(currentWorkspaceId(), parseDate(startDate), parseDate(endDate), page, size));
    }

    @GetMapping("/pending")
    public Result<PageResult<PendingWorkitemVO>> pending(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.ok(service.pending(currentWorkspaceId(), currentUserId(), page, size));
    }

    private static long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }

    private static long currentUserId() {
        Long userId = RequestContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException malformed) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }
}

package com.ainative.aiusage;

import com.ainative.aiusage.dto.AiQuotaVO;
import com.ainative.aiusage.dto.AiUsageVO;
import com.ainative.aiusage.dto.UpdateQuotaRequest;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai-usage")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看AI用量", actionKey = "access.action.view_ai_usage")
public class AiUsageController {

    private final AiUsageService aiUsageService;

    public AiUsageController(AiUsageService aiUsageService) {
        this.aiUsageService = aiUsageService;
    }

    @GetMapping
    public Result<List<AiUsageVO>> listUsage(
            @RequestParam(value = "period", required = false) String period) {
        return Result.ok(aiUsageService.listUsage(currentWorkspaceId(), period));
    }

    @GetMapping("/quota")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "查看AI用量配额", actionKey = "access.action.view_ai_usage_quotas")
    public Result<AiQuotaVO> getQuota() {
        return Result.ok(aiUsageService.getQuota(currentWorkspaceId()));
    }

    @PutMapping("/quota")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "更新AI用量配额", actionKey = "access.action.update_ai_usage_quotas")
    public Result<Void> updateQuota(@RequestBody UpdateQuotaRequest req) {
        aiUsageService.updateQuota(req, currentWorkspaceId());
        return Result.ok(null);
    }

    private long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) { throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER); }
        return workspaceId;
    }
}

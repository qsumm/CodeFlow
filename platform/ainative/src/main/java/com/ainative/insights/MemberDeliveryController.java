package com.ainative.insights;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/insights/member-delivery")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看项目工作项交付统计", actionKey = "access.action.view_project_member_delivery_statistics")
public class MemberDeliveryController {
    private final MemberDeliveryService service;
    public MemberDeliveryController(MemberDeliveryService service) { this.service = service; }
    /** work_type 只收敛 summary 统计；members 明细始终返回全部类型，表格作用域在接口层锁死。 */
    @GetMapping
    public Result<MemberDeliveryService.Report> get(@RequestParam(value="start_date", required=false) String start,
                                                    @RequestParam(value="end_date", required=false) String end,
                                                    @RequestParam(value="work_type", required=false) String workType) {
        Long workspace = RequestContext.get().getCurrentWorkspaceId();
        if (workspace == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return Result.ok(service.get(workspace, start, end, workType));
    }
}

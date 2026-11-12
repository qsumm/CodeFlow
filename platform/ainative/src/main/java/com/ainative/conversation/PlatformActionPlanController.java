package com.ainative.conversation;

import com.ainative.access.*;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.mcp.McpToolService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/platform/conversations/{conversationId}/action-plans")
@RequireWorkspaceAccess(value=WorkspaceAccessLevel.READ_ONLY, action="查看平台管家对话", actionKey="access.action.view_platform_assistant_conversations")
public class PlatformActionPlanController {
    private final PlatformActionPlanService plans;
    private final McpToolService tools;
    public PlatformActionPlanController(PlatformActionPlanService plans,McpToolService tools) {this.plans=plans;this.tools=tools;}
    @GetMapping public Result<List<PlatformActionPlanService.Plan>> list(@PathVariable long conversationId) {
        var c=RequestContext.get(); return Result.ok(plans.list(c.getCurrentWorkspaceId(),c.getUserId(),conversationId));
    }
    public record Decision(boolean confirm,String payloadHash) {}
    @PostMapping("/{id}/resolve")
    @RequireWorkspaceAccess(value=WorkspaceAccessLevel.READ_WRITE, action="确认 AI 管家 操作", actionKey="access.action.confirm_chief_of_staff_actions")
    public Result<PlatformActionPlanService.Plan> resolve(@PathVariable long conversationId,@PathVariable String id,
            @RequestBody Decision decision) {
        var c=RequestContext.get();
        return Result.ok(plans.resolve(c.getCurrentWorkspaceId(),c.getUserId(),conversationId,id,decision.payloadHash(),decision.confirm(),
                action->tools.callConfirmedPlatformAction(c.getCurrentWorkspaceId(),c.getUserId(),conversationId,action)));
    }
}

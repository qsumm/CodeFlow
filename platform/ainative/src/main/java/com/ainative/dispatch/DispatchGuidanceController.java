package com.ainative.dispatch;

import com.ainative.access.*;
import com.ainative.context.RequestContext;
import com.ainative.common.error.*;
import com.ainative.common.result.Result;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/dispatches/{dispatchId}/guidance")
@RequireWorkspaceAccess(value=WorkspaceAccessLevel.READ_ONLY, action="查看执行引导", actionKey="access.action.view_dispatch_guidance")
public class DispatchGuidanceController {
    private final DispatchGuidanceService service;
    public DispatchGuidanceController(DispatchGuidanceService service) { this.service=service; }
    @GetMapping
    public Result<List<Map<String,Object>>> list(@PathVariable long dispatchId) {
        return Result.ok(service.list(workspace(),dispatchId));
    }
    public record Input(String content,String requestKey) {}
    @PostMapping
    @RequireWorkspaceAccess(value=WorkspaceAccessLevel.READ_WRITE, action="引导执行", actionKey="access.action.steer_dispatch")
    public Result<List<Map<String,Object>>> create(@PathVariable long dispatchId,@RequestBody Input input) {
        Long user=RequestContext.get().getUserId();
        if(user==null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return Result.ok(service.create(workspace(),dispatchId,user,input.content(),input.requestKey()));
    }
    private long workspace() {
        Long id=RequestContext.get().getCurrentWorkspaceId();
        if(id==null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return id;
    }
}

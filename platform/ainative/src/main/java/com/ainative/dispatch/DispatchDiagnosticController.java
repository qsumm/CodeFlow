package com.ainative.dispatch;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/dispatches/{dispatchId}/diagnostics")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
        action = "查看调度运行轨迹", actionKey = "access.action.view_dispatch_runtime_traces")
public class DispatchDiagnosticController {
    private final DispatchDiagnosticService service;
    public DispatchDiagnosticController(DispatchDiagnosticService service) { this.service = service; }

    @GetMapping
    public Result<List<DispatchDiagnosticService.Diagnostic>> list(@PathVariable long dispatchId) {
        return Result.ok(service.list(workspaceId(), dispatchId));
    }
    @GetMapping("/{artifactId}")
    public Result<DispatchDiagnosticService.Content> read(@PathVariable long dispatchId, @PathVariable long artifactId,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "8000") int limit) {
        return Result.ok(service.read(workspaceId(), dispatchId, artifactId, offset, limit));
    }
    @GetMapping("/{artifactId}/download")
    public Result<String> download(@PathVariable long dispatchId, @PathVariable long artifactId) {
        return Result.ok(service.download(workspaceId(), dispatchId, artifactId));
    }
    private long workspaceId() {
        Long id = RequestContext.get().getCurrentWorkspaceId();
        if (id == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return id;
    }
}

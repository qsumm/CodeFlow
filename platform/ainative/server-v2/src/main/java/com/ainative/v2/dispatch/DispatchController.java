package com.ainative.v2.dispatch;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 调度域接口:派发记录/运行轨迹/恢复控制/需求单/指引/进度。 */
@RestController
@RequestMapping("/api")
public class DispatchController {
    public record RecoveryRequest(String action, Long dispatchId, Boolean force) {}
    public record ReplyRequest(String answerJson) {}
    public record GuidanceRequest(String bodyMd) {}

    private final DispatchService service;

    public DispatchController(DispatchService service) {
        this.service = service;
    }

    @GetMapping("/dispatches")
    public ApiResult<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listRuns(status, pageNum, pageSize));
    }

    @GetMapping("/dispatches/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.run(id));
    }

    @GetMapping("/dispatches/{id}/runtime-trace")
    public ApiResult<Map<String, Object>> trace(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.runtimeTrace(id));
    }

    @GetMapping("/dispatches/{id}/runtime-trace/events")
    public ApiResult<Map<String, Object>> traceEvents(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.runtimeTrace(id));
    }

    @GetMapping("/workitems/{workitemId}/delivery-progress")
    public ApiResult<Map<String, Object>> progress(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.progress(workitemId));
    }

    @GetMapping("/workitems/{workitemId}/recovery")
    public ApiResult<Map<String, Object>> recovery(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.recoveryState(workitemId));
    }

    @PostMapping("/workitems/{workitemId}/recovery")
    public ApiResult<Void> recovery(@PathVariable long workitemId, @RequestBody RecoveryRequest req) {
        long uid = RequestContext.requireUserId();
        service.recoveryAction(workitemId, req.action(), req.dispatchId(), uid, Boolean.TRUE.equals(req.force()));
        return ApiResult.ok(null);
    }

    @PostMapping("/workitems/{workitemId}/dispatches/{dispatchId}/pause")
    public ApiResult<Void> pause(@PathVariable long workitemId, @PathVariable long dispatchId) {
        service.pause(workitemId, dispatchId, RequestContext.requireUserId());
        return ApiResult.ok(null);
    }

    @PostMapping("/workitems/{workitemId}/dispatches/{dispatchId}/continue")
    public ApiResult<Void> resume(@PathVariable long workitemId, @PathVariable long dispatchId) {
        service.resume(workitemId, dispatchId, RequestContext.requireUserId());
        return ApiResult.ok(null);
    }

    @GetMapping("/workitems/{workitemId}/dispatch-requests")
    public ApiResult<List<Map<String, Object>>> requests(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.requests(workitemId));
    }

    @PostMapping("/workitems/{workitemId}/dispatch-requests/{id}/reply")
    public ApiResult<Map<String, Object>> reply(@PathVariable long workitemId, @PathVariable long id,
                                                @RequestBody ReplyRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.replyRequest(id, req.answerJson()));
    }

    @GetMapping("/dispatches/{dispatchId}/guidance")
    public ApiResult<List<Map<String, Object>>> guidance(@PathVariable long dispatchId,
            @RequestParam long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.guidance(workitemId, dispatchId));
    }

    @PostMapping("/dispatches/{dispatchId}/guidance")
    public ApiResult<Map<String, Object>> addGuidance(@PathVariable long dispatchId,
            @RequestParam long workitemId, @RequestBody GuidanceRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.addGuidance(workitemId, dispatchId, req.bodyMd()));
    }
}

package com.ainative.v2.squad;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 流水线接口(/api/sdlcs)。 */
@RestController
@RequestMapping("/api/sdlcs")
public class SdlcController {
    public record SdlcRequest(String name, String description) {}
    public record StepRequest(Integer seq, String name, String kind, Long agentId, String instruction, Boolean required) {}
    public record ReorderRequest(List<Long> stepIds) {}

    private final SquadService service;

    public SdlcController(SquadService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody SdlcRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createSdlc(req.name(), req.description()));
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listSdlcs());
    }

    /**
     * 分页版流水线列表(前端 listSdlcTemplatesPage 翻页用)。
     * 列表契约:SdlcTemplate[stepCount 代替 steps],启用状态映射 ENABLED。
     */
    @GetMapping("/page")
    public ApiResult<com.ainative.v2.common.PageResult<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        RequestContext.requireUserId();
        List<Map<String, Object>> all = service.listSdlcs().stream()
                .map(SdlcController::templateShape).toList();
        int total = all.size();
        int limit = Math.max(1, Math.min(size, 100));
        int from = Math.max(0, (Math.max(page, 1) - 1) * limit);
        List<Map<String, Object>> rows = all.subList(Math.min(from, total), Math.min(from + limit, total));
        return ApiResult.ok(new com.ainative.v2.common.PageResult<>(rows, total, page, limit));
    }

    private static Map<String, Object> templateShape(Map<String, Object> s) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(s);
        Object status = m.get("status");
        m.put("status", "ACTIVE".equals(status) ? "ENABLED" : status);
        Object desc = m.get("description");
        m.put("description", desc == null ? "" : desc);
        m.put("workType", null);
        m.put("isDefault", 0);
        m.put("entryStepId", null);
        Object steps = m.get("steps");
        m.put("stepCount", steps instanceof List<?> l ? l.size() : 0);
        m.put("squadIds", null);
        m.put("squadNames", null);
        return m;
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.sdlcDetail(id));
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable long id, @RequestBody SdlcRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateSdlc(id, req.name(), req.description()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteSdlc(id);
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/steps")
    public ApiResult<Map<String, Object>> addStep(@PathVariable long id, @RequestBody StepRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.addStep(id, req.seq() == null ? 0 : req.seq(), req.name(), req.kind(),
                req.agentId(), req.instruction(), !Boolean.FALSE.equals(req.required())));
    }

    @PutMapping("/{id}/steps/{stepId}")
    public ApiResult<Map<String, Object>> updateStep(@PathVariable long id, @PathVariable long stepId,
                                                     @RequestBody StepRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateStep(id, stepId, req.name(), req.kind(), req.agentId(),
                req.instruction(), !Boolean.FALSE.equals(req.required())));
    }

    @DeleteMapping("/{id}/steps/{stepId}")
    public ApiResult<Map<String, Object>> deleteStep(@PathVariable long id, @PathVariable long stepId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.deleteStep(id, stepId));
    }

    @PutMapping("/{id}/steps/reorder")
    public ApiResult<Map<String, Object>> reorder(@PathVariable long id, @RequestBody ReorderRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.reorderSteps(id, req.stepIds()));
    }

    @PostMapping("/{id}/enable")
    public ApiResult<Map<String, Object>> enable(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.enableSdlc(id));
    }

    @PostMapping("/{id}/disable")
    public ApiResult<Map<String, Object>> disable(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.disableSdlc(id));
    }
}

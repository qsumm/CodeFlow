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

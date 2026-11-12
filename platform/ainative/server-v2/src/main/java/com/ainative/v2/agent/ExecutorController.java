package com.ainative.v2.agent;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 执行器接口(/api/executors + /api/agents/{id}/executors)。 */
@RestController
@RequestMapping("/api")
public class ExecutorController {
    public record CreateRequest(String name, String clientKind) {}
    public record HeartbeatRequest(String status) {}

    private final AgentService service;

    public ExecutorController(AgentService service) {
        this.service = service;
    }

    @GetMapping("/executors")
    public ApiResult<List<Map<String, Object>>> list() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listExecutors());
    }

    @GetMapping("/executors/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.executor(id));
    }

    @PostMapping("/executors/{id}/heartbeat")
    public ApiResult<Map<String, Object>> heartbeat(@PathVariable long id, @RequestBody(required = false) HeartbeatRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.heartbeat(id, req == null ? null : req.status()));
    }

    @DeleteMapping("/executors/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteExecutor(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/agents/{agentId}/executors")
    public ApiResult<List<Map<String, Object>>> ofAgent(@PathVariable long agentId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.executorsOf(agentId));
    }

    @PostMapping("/executors/{id}/token/rotate")
    public ApiResult<Map<String, Object>> rotate(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.rotateToken(id));
    }

    @GetMapping("/executors/{id}/launch-command")
    public ApiResult<Map<String, Object>> launchCommand(@PathVariable long id,
            @RequestParam(required = false) String token,
            @RequestParam(defaultValue = "posix") String os,
            @RequestParam(defaultValue = "foreground") String mode) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.launchCommand(id, token, os, mode));
    }

    @GetMapping("/executors/{id}/model-upgrade-policy")
    public ApiResult<Map<String, Object>> modelPolicy(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.modelPolicy(id));
    }

    @PutMapping("/executors/{id}/model-upgrade-policy")
    public ApiResult<Map<String, Object>> setModelPolicy(@PathVariable long id,
            @RequestBody(required = false) Map<String, String> req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.setModelPolicy(id, req == null ? null : req.get("policy")));
    }

    @PostMapping("/agents/{agentId}/executors")
    public ApiResult<Map<String, Object>> create(@PathVariable long agentId, @RequestBody CreateRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createExecutor(agentId, req.name(), req.clientKind()));
    }
}

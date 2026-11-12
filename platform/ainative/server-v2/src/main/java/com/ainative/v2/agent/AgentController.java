package com.ainative.v2.agent;

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

/** 智能体接口(/api/agents)。 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {
    public record CreateRequest(String name, String roleCode, String roleName, String soulMd) {}
    public record ConfigRequest(String roleName, String soulMd) {}

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createAgent(req.name(), req.roleCode(), req.roleName(), req.soulMd()));
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listAgents());
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.agentDetail(id));
    }

    @PutMapping("/{id}/config")
    public ApiResult<Map<String, Object>> config(@PathVariable long id, @RequestBody ConfigRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateConfig(id, req.roleName(), req.soulMd()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteAgent(id);
        return ApiResult.ok(null);
    }
}

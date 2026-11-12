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

/** 小队接口(/api/squads)。 */
@RestController
@RequestMapping("/api/squads")
public class SquadController {
    public record SquadRequest(String name, String description) {}
    public record MemberRequest(Long agentId, String roleCode) {}

    private final SquadService service;

    public SquadController(SquadService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody SquadRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createSquad(req.name(), req.description()));
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listSquads());
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.squadDetail(id));
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable long id, @RequestBody SquadRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateSquad(id, req.name(), req.description()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteSquad(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/{id}/members")
    public ApiResult<List<Map<String, Object>>> members(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok((List<Map<String, Object>>) service.squadDetail(id).get("members"));
    }

    @PostMapping("/{id}/members")
    public ApiResult<Void> addMember(@PathVariable long id, @RequestBody MemberRequest req) {
        RequestContext.requireUserId();
        if (req.agentId() == null) {
            throw new com.ainative.v2.common.BizException("10001", "成员不能为空");
        }
        service.addMember(id, req.agentId(), req.roleCode());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/members/{agentId}")
    public ApiResult<Void> removeMember(@PathVariable long id, @PathVariable long agentId) {
        RequestContext.requireUserId();
        service.removeMember(id, agentId);
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/copy")
    public ApiResult<Map<String, Object>> copy(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.copySquad(id));
    }
}

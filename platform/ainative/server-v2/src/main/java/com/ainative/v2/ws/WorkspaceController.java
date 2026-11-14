package com.ainative.v2.ws;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.PageResult;
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

/** 工作区(项目)接口:列表/创建/切换/成员/角色/申请/回收站。 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    public record CreateRequest(String name, String description) {}
    public record UpdateRequest(String name, String description, String background, Integer version) {}
    public record AddMemberRequest(Long userId, Long roleId, String role) {}
    public record RoleRequest(Long roleId, String role) {}
    public record TransferRequest(Long targetUserId, Long userId) {}

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/mine")
    public ApiResult<List<Map<String, Object>>> mine() {
        return ApiResult.ok(service.mine(RequestContext.requireUserId()));
    }

    @GetMapping("/all")
    public ApiResult<PageResult<Map<String, Object>>> all(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(service.all(keyword, page, size, RequestContext.requireUserId()));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest req) {
        return ApiResult.ok(service.create(req.name(), req.description(), RequestContext.requireUserId()));
    }

    @PutMapping("/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable long id, @RequestBody UpdateRequest req) {
        return ApiResult.ok(service.update(id, RequestContext.requireUserId(),
                req.name(), req.description(), req.background(), req.version()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Map<String, Object>> delete(@PathVariable long id) {
        return ApiResult.ok(service.delete(id, RequestContext.requireUserId()));
    }

    @PostMapping("/{id}/restore")
    public ApiResult<Map<String, Object>> restore(@PathVariable long id) {
        return ApiResult.ok(service.restore(id, RequestContext.requireUserId()));
    }

    @PostMapping("/{id}/switch")
    public ApiResult<Map<String, Object>> switchWorkspace(@PathVariable long id) {
        return ApiResult.ok(service.switchWorkspace(id, RequestContext.requireUserId()));
    }

    @GetMapping("/recycle-bin")
    public ApiResult<PageResult<Map<String, Object>>> recycleBin(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.recycleBin(keyword, page, size));
    }

    @GetMapping("/current")
    public ApiResult<Map<String, Object>> current() {
        return ApiResult.ok(service.current(RequestContext.workspaceId(), RequestContext.requireUserId()));
    }

    @GetMapping("/current/membership")
    public ApiResult<Map<String, Object>> membership() {
        return ApiResult.ok(service.membership(RequestContext.workspaceId(), RequestContext.requireUserId()));
    }

    @GetMapping("/current/members")
    public ApiResult<List<Map<String, Object>>> members() {
        long uid = RequestContext.requireUserId();
        return ApiResult.ok(service.members(RequestContext.workspaceId()));
    }

    @PostMapping("/current/members")
    public ApiResult<Void> addMember(@RequestBody AddMemberRequest req) {
        long uid = RequestContext.requireUserId();
        service.addMember(RequestContext.workspaceId(), uid,
                req.userId() == null ? 0L : req.userId(), req.role());
        return ApiResult.ok(null);
    }

    @PutMapping("/current/members/{userId}/role")
    public ApiResult<Void> updateMemberRole(@PathVariable long userId, @RequestBody RoleRequest req) {
        long uid = RequestContext.requireUserId();
        // 前端传 {roleId};v2 以 role 字符串承载,roleId 为空时仅忽略角色细化
        service.updateMemberRole(RequestContext.workspaceId(), uid, userId, req.role());
        return ApiResult.ok(null);
    }

    /** 身份标签(前端编辑成员弹窗);按成员维度持久化到 identity_tags_json。 */
    @PutMapping("/current/members/{userId}/identity-tags")
    public ApiResult<Void> identityTags(@PathVariable long userId,
                                        @RequestBody java.util.Map<String, Object> body) {
        long uid = RequestContext.requireUserId();
        service.updateIdentityTags(RequestContext.workspaceId(), uid, userId, body.get("identityTags"));
        return ApiResult.ok(null);
    }

    @DeleteMapping("/current/members/{userId}")
    public ApiResult<Void> removeMember(@PathVariable long userId) {
        long uid = RequestContext.requireUserId();
        service.removeMember(RequestContext.workspaceId(), uid, userId);
        return ApiResult.ok(null);
    }

    @PostMapping("/current/owner/transfer")
    public ApiResult<Void> transferOwner(@RequestBody TransferRequest req) {
        long uid = RequestContext.requireUserId();
        Long target = req.targetUserId() != null ? req.targetUserId() : req.userId();
        service.transferOwner(RequestContext.workspaceId(), uid, target == null ? 0L : target);
        return ApiResult.ok(null);
    }

    @GetMapping("/current/roles")
    public ApiResult<List<Map<String, Object>>> roles() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.roles(RequestContext.workspaceId()));
    }

    @GetMapping("/current/access-requests")
    public ApiResult<List<Map<String, Object>>> accessRequests() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.accessRequests(RequestContext.workspaceId()));
    }

    @PostMapping("/current/access-requests/{requestId}/approve")
    public ApiResult<Void> approve(@PathVariable long requestId) {
        long uid = RequestContext.requireUserId();
        service.decideAccessRequest(RequestContext.workspaceId(), requestId, uid, true);
        return ApiResult.ok(null);
    }

    @PostMapping("/current/access-requests/{requestId}/reject")
    public ApiResult<Void> reject(@PathVariable long requestId) {
        long uid = RequestContext.requireUserId();
        service.decideAccessRequest(RequestContext.workspaceId(), requestId, uid, false);
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/access-requests")
    public ApiResult<Map<String, Object>> submitAccessRequest(@PathVariable long id) {
        return ApiResult.ok(service.submitAccessRequest(id, RequestContext.requireUserId()));
    }

    @PostMapping("/{id}/access-requests/{requestId}/cancel")
    public ApiResult<Void> cancelAccessRequest(@PathVariable long id, @PathVariable long requestId) {
        service.cancelAccessRequest(id, requestId, RequestContext.requireUserId());
        return ApiResult.ok(null);
    }
}

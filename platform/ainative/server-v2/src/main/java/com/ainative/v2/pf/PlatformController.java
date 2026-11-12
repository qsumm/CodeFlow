package com.ainative.v2.pf;

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

/** 平台运维接口:品牌/设置/审计/通知/定时任务/IM 渠道/自进化/备份/调试日志。 */
@RestController
@RequestMapping("/api")
public class PlatformController {
    public record BrandingRequest(String platformName, String themeKey, String primaryColor, String domain) {}
    public record SettingsRequest(List<Map<String, Object>> items) {}
    public record NotifyRequest(String eventType, String title, String bodyMd, Long userId) {}
    public record TaskRequest(String name, String cronExpr, String templateJson) {}
    public record ImChannelRequest(String appKey, Boolean enabled) {}
    public record ImSelectionRequest(String provider) {}

    private final PfService service;

    public PlatformController(PfService service) {
        this.service = service;
    }

    // 品牌
    @GetMapping("/platform/branding/public")
    public ApiResult<Map<String, Object>> publicBranding() {
        return ApiResult.ok(service.publicBranding());
    }

    @GetMapping("/platform/branding")
    public ApiResult<Map<String, Object>> branding() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.publicBranding());
    }

    @PutMapping("/platform/branding")
    public ApiResult<Map<String, Object>> saveBranding(@RequestBody BrandingRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.saveBranding(req.platformName(), req.themeKey(), req.primaryColor(), req.domain()));
    }

    // 设置
    @GetMapping("/settings/{group}")
    public ApiResult<List<Map<String, Object>>> settings(@PathVariable String group) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.settings(group));
    }

    @PutMapping("/settings/{group}")
    public ApiResult<Void> saveSettings(@PathVariable String group, @RequestBody SettingsRequest req) {
        RequestContext.requireUserId();
        service.saveSettings(group, req.items());
        return ApiResult.ok(null);
    }

    // 审计
    @GetMapping("/audit-logs")
    public ApiResult<PageResult<Map<String, Object>>> auditLogs(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.auditLogs(pageNum, pageSize));
    }

    // 通知
    @GetMapping("/notifications")
    public ApiResult<PageResult<Map<String, Object>>> notifications(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResult.ok(service.notifications(RequestContext.requireUserId(), pageNum, pageSize));
    }

    @GetMapping("/notifications/unread-count")
    public ApiResult<Long> unread() {
        return ApiResult.ok(service.unreadCount(RequestContext.requireUserId()));
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResult<Void> read(@PathVariable long id) {
        RequestContext.requireUserId();
        service.markRead(id);
        return ApiResult.ok(null);
    }

    @PostMapping("/notifications/read-all")
    public ApiResult<Void> readAll() {
        service.markAllRead(RequestContext.requireUserId());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/notifications/{id}")
    public ApiResult<Void> deleteNotification(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteNotification(id);
        return ApiResult.ok(null);
    }

    /** 内部/测试用:发一条站内通知。 */
    @PostMapping("/notifications")
    public ApiResult<Map<String, Object>> notify(@RequestBody NotifyRequest req) {
        long uid = req.userId() == null ? RequestContext.requireUserId() : req.userId();
        return ApiResult.ok(service.notify(uid, req.eventType(), req.title(), req.bodyMd()));
    }

    // 定时任务
    @GetMapping("/scheduled-tasks")
    public ApiResult<List<Map<String, Object>>> tasks() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.tasks());
    }

    @PostMapping("/scheduled-tasks")
    public ApiResult<Map<String, Object>> createTask(@RequestBody TaskRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createTask(req.name(), req.cronExpr(), req.templateJson()));
    }

    @GetMapping("/scheduled-tasks/{id}")
    public ApiResult<Map<String, Object>> task(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.task(id));
    }

    @PutMapping("/scheduled-tasks/{id}")
    public ApiResult<Map<String, Object>> updateTask(@PathVariable long id, @RequestBody TaskRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateTask(id, req.name(), req.cronExpr(), req.templateJson()));
    }

    @PostMapping("/scheduled-tasks/{id}/enable")
    public ApiResult<Map<String, Object>> enableTask(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.setTaskEnabled(id, true));
    }

    @PostMapping("/scheduled-tasks/{id}/pause")
    public ApiResult<Map<String, Object>> pauseTask(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.setTaskEnabled(id, false));
    }

    @DeleteMapping("/scheduled-tasks/{id}")
    public ApiResult<Void> deleteTask(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteTask(id);
        return ApiResult.ok(null);
    }

    // IM 渠道
    @GetMapping("/platform/im-channels")
    public ApiResult<List<Map<String, Object>>> imChannels() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.imChannels());
    }

    @PutMapping("/platform/im-channels/{channel}")
    public ApiResult<List<Map<String, Object>>> saveImChannel(@PathVariable String channel, @RequestBody ImChannelRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.saveImChannel(channel, req.appKey(), Boolean.TRUE.equals(req.enabled())));
    }

    @GetMapping("/platform/im-channels/selection")
    public ApiResult<Map<String, Object>> imSelection() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.imSelection());
    }

    @PutMapping("/platform/im-channels/selection")
    public ApiResult<Map<String, Object>> selectImChannel(@RequestBody ImSelectionRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.selectImChannel(req.provider()));
    }

    // 其它只读
    @GetMapping("/evolution/proposals")
    public ApiResult<List<Map<String, Object>>> evolution() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.evolutionProposals());
    }

    @GetMapping("/platform/backups")
    public ApiResult<List<Map<String, Object>>> backups() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.backups());
    }

    @GetMapping("/workitems/{workitemId}/debug-logs")
    public ApiResult<List<Map<String, Object>>> debugLogs(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.debugLogs(workitemId));
    }
}

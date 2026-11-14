package com.ainative.v2.pf;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
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
    public record BackupRequest(String name) {}

    private final PfService service;
    private final ProjectBackupService backups;
    private final JdbcTemplate jdbc;
    private final String publicBaseUrl;

    public PlatformController(PfService service, ProjectBackupService backups, JdbcTemplate jdbc,
                              @Value("${ainative.public-base-url:http://localhost:7011}") String publicBaseUrl) {
        this.service = service;
        this.backups = backups;
        this.jdbc = jdbc;
        this.publicBaseUrl = publicBaseUrl;
    }

    private String publicBaseUrl() {
        return publicBaseUrl == null || publicBaseUrl.isBlank() ? "http://localhost:7011" : publicBaseUrl;
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

    @GetMapping("/platform/branding/capability")
    public ApiResult<Map<String, Object>> brandingCapability() {
        long uid = RequestContext.requireUserId();
        boolean admin = jdbc.queryForList(
                "SELECT 1 FROM id_user WHERE id=? AND is_admin=1 AND deleted_at IS NULL", uid).size() > 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canManage", admin);
        out.put("maxLogoBytes", 5 * 1024 * 1024);
        out.put("allowedTypes", List.of("image/png", "image/svg+xml", "image/jpeg"));
        return ApiResult.ok(out);
    }

    /** 平台 Logo:返回裸图片;未上传时 404,前端回落到默认图标。 */
    @GetMapping("/platform/branding/logo")
    public ResponseEntity<byte[]> brandingLogo() {
        Path file = logoFile();
        if (file == null || !Files.isReadable(file)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String name = file.getFileName().toString();
            String type = name.endsWith(".svg") ? "image/svg+xml"
                    : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : "image/png";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, type)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(Files.readAllBytes(file));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/platform/branding/logo")
    public ApiResult<Map<String, Object>> uploadLogo(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        RequestContext.requireUserId();
        if (file == null || file.isEmpty()) {
            throw new com.ainative.v2.common.BizException("10001", "Logo 不能为空");
        }
        String original = file.getOriginalFilename() == null ? "logo.png" : file.getOriginalFilename();
        String ext = original.toLowerCase().endsWith(".svg") ? "svg"
                : original.toLowerCase().endsWith(".jpg") || original.toLowerCase().endsWith(".jpeg") ? "jpg" : "png";
        try {
            Files.createDirectories(brandingDir());
            for (String candidate : List.of("png", "jpg", "svg")) {
                Files.deleteIfExists(brandingDir().resolve("logo." + candidate));
            }
            Path target = brandingDir().resolve("logo." + ext);
            file.transferTo(target);
            return ApiResult.ok(Map.of("logoUrl", "/api/platform/branding/logo?v=" + System.currentTimeMillis(),
                    "sizeBytes", file.getSize()));
        } catch (Exception e) {
            throw new com.ainative.v2.common.BizException("10505", "Logo 保存失败:" + e.getMessage());
        }
    }

    @DeleteMapping("/platform/branding/logo")
    public ApiResult<Void> deleteLogo() {
        RequestContext.requireUserId();
        try {
            for (String ext : List.of("png", "jpg", "svg")) {
                Files.deleteIfExists(brandingDir().resolve("logo." + ext));
            }
        } catch (Exception ignored) {
            // 删除尽力而为:文件不存在即视为已恢复默认
        }
        return ApiResult.ok(null);
    }

    /** 平台智能体状态横幅:平台内置执行器是否就绪。 */
    @GetMapping("/platform-agent/status")
    public ApiResult<Map<String, Object>> platformAgentStatus() {
        RequestContext.requireUserId();
        long ws = RequestContext.workspaceId();
        List<Map<String, Object>> agents = jdbc.queryForList(
                "SELECT id FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1", ws);
        long agentId = agents.isEmpty() ? 0 : ((Number) agents.get(0).get("id")).longValue();
        Long executors = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_executor WHERE workspace_id=? AND deleted_at IS NULL", Long.class, ws);
        Long online = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_executor WHERE workspace_id=? AND status='ONLINE' AND deleted_at IS NULL",
                Long.class, ws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", agentId > 0 && online != null && online > 0 ? "OK" : "NOT_READY");
        out.put("agentId", agentId);
        out.put("executorCount", executors == null ? 0 : executors);
        out.put("onlineExecutorCount", online == null ? 0 : online);
        return ApiResult.ok(out);
    }

    @GetMapping("/platform/runtime-auto-update")
    public ApiResult<Map<String, Object>> runtimeAutoUpdate() {
        RequestContext.requireUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executorAutoUpdateEnabled", false);
        out.put("targetVersion", "0.3.32");
        return ApiResult.ok(out);
    }

    @GetMapping("/platform/im-channels/{channel}/group-chat")
    public ApiResult<Map<String, Object>> imGroupChat(@PathVariable String channel) {
        RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT channel, app_key AS appKey, enabled FROM pf_im_config WHERE channel=?", channel);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", channel);
        out.put("configured", !rows.isEmpty() && Integer.valueOf(1).equals(rows.get(0).get("enabled")));
        out.put("chatId", null);
        out.put("name", channel + " 群聊");
        out.put("message", rows.isEmpty() ? "该渠道尚未配置" : "群聊需在渠道后台创建后回填");
        return ApiResult.ok(out);
    }

    private Path brandingDir() {
        String dir = System.getProperty("java.io.tmpdir") + "/ainative-artifacts";
        return Paths.get(dir).toAbsolutePath().resolve("branding");
    }

    private Path logoFile() {
        for (String ext : List.of("png", "jpg", "svg")) {
            Path p = brandingDir().resolve("logo." + ext);
            if (Files.isReadable(p)) {
                return p;
            }
        }
        return null;
    }

    @GetMapping("/platform/backups")
    public ApiResult<List<Map<String, Object>>> backups() {
        RequestContext.requireUserId();
        return ApiResult.ok(backups.list(1, 200));
    }

    @PostMapping("/platform/backups")
    public ApiResult<Map<String, Object>> createBackup(@RequestBody(required = false) BackupRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(backups.backup(req == null ? null : req.name()));
    }

    @PostMapping("/platform/backups/{id}/restore")
    public ApiResult<Map<String, Object>> restoreBackup(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(backups.restore(id));
    }

    @GetMapping("/platform/backups/{id}/file")
    public ResponseEntity<byte[]> backupFile(@PathVariable long id) {
        RequestContext.requireUserId();
        byte[] bytes = backups.archiveBytes(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + backups.archiveFileName(id) + "\"")
                .body(bytes);
    }

    // 工作区视图(前端设置页使用)
    @GetMapping("/workspaces/current/backups")
    public ApiResult<List<Map<String, Object>>> workspaceBackups(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        RequestContext.requireUserId();
        return ApiResult.ok(backups.list(page, size));
    }

    @PostMapping("/workspaces/current/backups")
    public ApiResult<Map<String, Object>> createWorkspaceBackup(@RequestBody(required = false) BackupRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(backups.backup(req == null ? null : req.name()));
    }

    @GetMapping("/workspaces/current/backups/{id}/download")
    public ApiResult<Map<String, Object>> downloadBackup(@PathVariable long id) {
        RequestContext.requireUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", publicBaseUrl() + "/api/platform/backups/" + id + "/file");
        out.put("fileName", backups.archiveFileName(id));
        return ApiResult.ok(out);
    }

    @GetMapping("/workitems/{workitemId}/debug-logs")
    public ApiResult<List<Map<String, Object>>> debugLogs(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.debugLogs(workitemId));
    }
}

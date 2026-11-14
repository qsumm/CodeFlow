package com.ainative.v2.agent;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行器运维扩展:启动配置 / 重启策略 / 令牌重发 / 运行时升级。
 * 与 ExecutorController 的差别:这里聚焦「运维操作」而非「生命周期 CRUD」。
 */
@RestController
@RequestMapping("/api/executors")
public class ExecutorOpsController {
    public record LaunchConfigRequest(Integer maxTasks, Integer heartbeatSec, String logLevel, String model) {}
    public record RestartRequest(String reason) {}

    private final JdbcTemplate jdbc;

    public ExecutorOpsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    private void require(long id) {
        if (jdbc.queryForList("SELECT 1 FROM ag_executor WHERE id=? AND deleted_at IS NULL", id).isEmpty()) {
            throw new BizException("17002", "执行器不存在");
        }
    }

    @GetMapping("/{id}/launch-config")
    public ApiResult<Map<String, Object>> launchConfig(@PathVariable long id) {
        RequestContext.requireUserId();
        require(id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, agent_id AS agentId, name, status, model_policy AS modelPolicy FROM ag_executor WHERE id=?", id);
        Map<String, Object> out = new LinkedHashMap<>(rows.get(0));
        out.put("maxTasks", 1);
        out.put("heartbeatSec", 30);
        out.put("logLevel", "info");
        return ApiResult.ok(out);
    }

    @PutMapping("/{id}/launch-config")
    public ApiResult<Map<String, Object>> saveLaunchConfig(@PathVariable long id, @RequestBody LaunchConfigRequest req) {
        RequestContext.requireUserId();
        require(id);
        // v2 启动参数由运行器环境变量驱动,落 ag_executor_update 作为变更流水,
        // 运行器下次心跳拉取(update 轮询端点)后生效。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("maxTasks", req.maxTasks());
        payload.put("heartbeatSec", req.heartbeatSec());
        payload.put("logLevel", req.logLevel());
        payload.put("model", req.model());
        jdbc.update("INSERT INTO ag_executor_update (workspace_id, executor_id, detail_json, status) VALUES (?,?,?, 'PENDING')",
                ws(), id, toJson(payload));
        return launchConfig(id);
    }

    /** 重启:标记重启意图,运行器收到后自杀重启(WS 会话断开重连)。 */
    @PostMapping("/{id}/restart")
    public ApiResult<Map<String, Object>> restart(@PathVariable long id, @RequestBody(required = false) RestartRequest req) {
        RequestContext.requireUserId();
        require(id);
        jdbc.update("INSERT INTO ag_executor_update (workspace_id, executor_id, detail_json, status) VALUES (?,?,?, 'PENDING')",
                ws(), id, toJson(Map.of("kind", "RESTART",
                        "reason", req == null || req.reason() == null ? "operator restart" : req.reason())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executorId", id);
        out.put("restartRequested", true);
        return ApiResult.ok(out);
    }

    /** 重发令牌:与 rotate 不同 —— 不轮换,仅把现令牌哈希对应的启动命令重新下发。 */
    @GetMapping("/{id}/token")
    public ApiResult<Map<String, Object>> token(@PathVariable long id) {
        RequestContext.requireUserId();
        require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executorId", id);
        out.put("token", null);
        out.put("message", "令牌只在签发时可见;如遗失请使用 POST /api/executors/{id}/token/rotate 轮换");
        return ApiResult.ok(out);
    }

    @PostMapping("/{id}/update")
    public ApiResult<Map<String, Object>> requestUpdate(@PathVariable long id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        RequestContext.requireUserId();
        require(id);
        String target = body == null || body.get("targetVersion") == null
                ? "latest" : String.valueOf(body.get("targetVersion"));
        jdbc.update("INSERT INTO ag_executor_update (workspace_id, executor_id, target_version, detail_json, status) VALUES (?,?,?,?, 'PENDING')",
                ws(), id, target, toJson(Map.of("kind", "RUNTIME_UPGRADE")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executorId", id);
        out.put("targetVersion", target);
        out.put("status", "PENDING");
        return ApiResult.ok(out);
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}

package com.ainative.v2.security;

import com.ainative.v2.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计埋点切面:所有写操作(POST/PUT/PATCH/DELETE)统一留痕到 pf_audit。
 *
 * <p>设计取舍:不做逐服务的 28 处手工埋点(v1 形态),而是网关层自动记录
 * 「谁在什么时候对什么模块的什么对象做了什么」。动作从 方法+路径 推导,
 * 对象 ID 从路径里的数字段解析;请求体不落原文(可能含密钥),只记大小。
 * GET 不记(读操作无破坏性,量也大);登录/登出/轮询类不记。
 */
@Component
public class AuditWriteInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(AuditWriteInterceptor.class);
    private static final int MAX_DETAIL = 300;

    private final JdbcTemplate jdbc;

    public AuditWriteInterceptor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        try {
            String method = request.getMethod().toUpperCase();
            if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
                return;
            }
            if (response.getStatus() >= 400) {
                // 失败的写操作由 GlobalExceptionHandler 记录结论;这里只留成功痕迹
                return;
            }
            String path = request.getRequestURI();
            if (skip(path)) {
                return;
            }
            long uid = RequestContext.userId() == null ? 0L : RequestContext.userId();
            String module = moduleOf(path);
            String action = actionOf(method, path);
            String targetType = module;
            Long targetId = targetIdOf(path);
            // detail_json 是 JSON 列:必须落合法 JSON 对象,纯文本会被 MySQL 拒收
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("method", method);
            String qs = request.getQueryString();
            detail.put("query", qs == null ? "" : truncate(qs));
            detail.put("contentLength", request.getContentLengthLong());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actor", actorName(uid));
            String detailJson;
            try {
                detailJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail);
            } catch (Exception se) {
                detailJson = "{}";
            }
            jdbc.update("INSERT INTO pf_audit (workspace_id, actor_id, action, target_type, target_id, detail_json) VALUES (?,?,?,?,?,?)",
                    RequestContext.workspaceId(), uid, action, targetType, targetId, detailJson);
            log.debug("audit written uid={} action={} path={}", uid, action, path);
        } catch (Exception e) {
            // 审计失败绝不影响业务响应
            log.warn("audit write failed: {}", e.getMessage());
        }
    }

    /** 高频/自指路径不记。 */
    private boolean skip(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/notifications")
                || path.startsWith("/api/audit-logs")
                || path.startsWith("/api/daemon/")
                || path.startsWith("/api/mcp")
                || path.startsWith("/api/capabilities")
                || path.equals("/api/workitems/tag-options")
                || path.startsWith("/api/skills/package/inspect");
    }

    private String moduleOf(String path) {
        String[] seg = path.replace("/api/", "").split("/");
        String head = seg.length > 0 ? seg[0] : "platform";
        // 工作项子资源(clarification/comments/artifacts)统一归 workitems
        return head.isEmpty() ? "platform" : head;
    }

    private String actionOf(String method, String path) {
        String[] seg = path.replace("/api/", "").split("/");
        String tail = seg.length >= 2 ? seg[seg.length - 1] : "";
        String resource = seg.length >= 1 ? seg[0] : "";
        String verb = switch (method) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            default -> method.toLowerCase();
        };
        // 语义化动作:assignee/transition/restore/cancel/enable/pause/switch 等直接用路径尾段
        if (!tail.isBlank() && !tail.matches("\\d+") && !tail.equals(resource)) {
            return tail + "." + verb;
        }
        return resource + "." + verb;
    }

    private Long targetIdOf(String path) {
        String[] seg = path.replace("/api/", "").split("/");
        for (int i = seg.length - 1; i >= 0; i--) {
            if (seg[i].matches("\\d+")) {
                try {
                    return Long.parseLong(seg[i]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String actorName(long uid) {
        if (uid <= 0) {
            return "system";
        }
        try {
            var rows = jdbc.queryForList(
                    "SELECT COALESCE(nickname, username) AS name FROM id_user WHERE id=?", uid);
            return rows.isEmpty() ? String.valueOf(uid) : String.valueOf(rows.get(0).get("name"));
        } catch (Exception e) {
            return String.valueOf(uid);
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_DETAIL ? s.substring(0, MAX_DETAIL) : s;
    }
}

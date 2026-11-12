package com.ainative.v2.security;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 访问控制矩阵(与 v1 语义对齐):
 * - 公开:登录/注册/公开品牌/执行器包下载(/api/daemon/**)
 * - 登录即可:业务读写接口
 * - 仅管理员:平台设置、品牌写入、IM 渠道写入、审计查看、定时任务写入、编队运行器批量创建
 * 未登录 → 10401;非管理员访问管理员域 → 10403。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/api/auth/register", "/api/auth/login", "/api/platform/branding/public", "/api/daemon/");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public PermissionInterceptor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        Long uid = RequestContext.userId();
        if (uid == null) {
            return reject(response, "10401", "未登录或登录已失效");
        }
        if (adminRequired(path, request.getMethod()) && !isAdmin(uid)) {
            return reject(response, "10403", "无权限");
        }
        return true;
    }

    static boolean adminRequired(String path, String method) {
        boolean write = !"GET".equalsIgnoreCase(method);
        if (path.startsWith("/api/settings/")) {
            return write;
        }
        if (path.equals("/api/platform/branding")) {
            return write;
        }
        if (path.startsWith("/api/platform/im-channels")) {
            return write;
        }
        if (path.startsWith("/api/audit-logs")) {
            return true;
        }
        if (path.startsWith("/api/scheduled-tasks")) {
            return write;
        }
        if (path.matches("/api/squads/[^/]+/executors.*")) {
            return true;
        }
        return false;
    }

    private boolean isAdmin(long uid) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT is_admin FROM id_user WHERE id=? AND deleted_at IS NULL", Integer.class, uid);
        return !rows.isEmpty() && rows.get(0) != null && rows.get(0) == 1;
    }

    private boolean reject(HttpServletResponse response, String code, String message) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(new String(mapper.writeValueAsBytes(ApiResult.fail(code, message)), StandardCharsets.UTF_8));
        return false;
    }
}

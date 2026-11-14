package com.ainative.v2.mcp;

import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import com.ainative.v2.daemon.TokenHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务端(Streamable HTTP + 兼容旧版 HTTP+SSE 的入口事件)。
 *
 * <p>鉴权:个人 MCP 令牌(token 以 mcp_ 开头)。支持三种携带方式
 * Authorization: Bearer / ?token= / 路径段 /api/mcp/{token},与 v1 客户端一致。
 * 令牌校验用 sha256 后比对 ag_mcp_token.token_hash,明文不落库。
 *
 * <p>传输:POST /api/mcp 与 /api/mcp/rpc 接收 JSON-RPC 2.0;
 * 通知(无 id)返回 202;GET /api/mcp 与 /api/mcp/sse 返回 endpoint 事件。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpServerController {
    private final JdbcTemplate jdbc;
    private final McpToolService tools;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpServerController(JdbcTemplate jdbc, McpToolService tools) {
        this.jdbc = jdbc;
        this.tools = tools;
    }

    // ---------------------------------------------------------------- 传输入口

    @GetMapping(value = {"", "/sse"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void sse(@RequestHeader(value = "Authorization", required = false) String authorization,
                    @RequestParam(value = "token", required = false) String queryToken,
                    HttpServletResponse response) throws Exception {
        Principal principal = authenticate(authorization, queryToken);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        String suffix = principal.rawToken() == null ? "" : "?token=" + principal.rawToken();
        response.getWriter().write("event: endpoint\ndata: /api/mcp/rpc" + suffix + "\n\n");
        response.getWriter().flush();
    }

    @PostMapping(value = {"", "/rpc", "/{pathToken}", "/{pathToken}/rpc"},
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> rpc(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable(value = "pathToken", required = false) String pathToken,
                                 @RequestParam(value = "token", required = false) String queryToken,
                                 @RequestBody Map<String, Object> request) {
        Principal principal = authenticate(authorization, firstNonBlank(pathToken, queryToken));
        Object id = request.get("id");
        try {
            RequestContext.setUserId(principal.userId());
            RequestContext.setWorkspaceId(principal.workspaceId());
            if (id == null) {
                // 通知(initialized / cancelled 等):无响应体
                return ResponseEntity.accepted().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(handle(principal, id, request));
        } catch (BizException e) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(error(id, -32000, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(error(id, -32603, e.getMessage() == null ? "内部错误" : e.getMessage()));
        } finally {
            RequestContext.clear();
        }
    }

    @GetMapping("/tools")
    public Map<String, Object> listToolsCompat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestParam(value = "token", required = false) String token) {
        Principal principal = authenticate(authorization, token);
        return Map.of("success", true, "code", "0", "message", "", "data", tools.listTools());
    }

    @PostMapping("/tools/call")
    public Map<String, Object> callToolCompat(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestParam(value = "token", required = false) String token,
                                              @RequestBody Map<String, Object> request) {
        Principal principal = authenticate(authorization, token);
        try {
            RequestContext.setUserId(principal.userId());
            RequestContext.setWorkspaceId(principal.workspaceId());
            Object result = tools.call(String.valueOf(request.get("name")), asMap(request.get("arguments")));
            return Map.of("success", true, "code", "0", "message", "", "data", result);
        } finally {
            RequestContext.clear();
        }
    }

    // ---------------------------------------------------------------- JSON-RPC

    private Map<String, Object> handle(Principal principal, Object id, Map<String, Object> request) {
        String method = String.valueOf(request.get("method"));
        switch (method) {
            case "initialize":
                return result(id, Map.of(
                        "protocolVersion", "2025-06-18",
                        "serverInfo", Map.of("name", "ainative", "version", "2.0.0"),
                        "capabilities", Map.of("tools", Map.of())));
            case "ping":
                return result(id, Map.of());
            case "tools/list":
                return result(id, Map.of("tools", tools.listTools()));
            case "tools/call": {
                Map<String, Object> params = asMap(request.get("params"));
                Object value = tools.call(String.valueOf(params.get("name")), asMap(params.get("arguments")));
                Object structured = value instanceof List<?> list ? Map.of("items", list) : value;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("content", List.of(Map.of("type", "text",
                        "text", toJson(structured))));
                payload.put("structuredContent", structured);
                payload.put("isError", false);
                return result(id, payload);
            }
            default:
                return error(id, -32601, "Method not found: " + method);
        }
    }

    private Map<String, Object> result(Object id, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("result", value);
        return body;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return body;
    }

    // ---------------------------------------------------------------- 令牌

    private record Principal(long userId, long workspaceId, String rawToken) {}

    private Principal authenticate(String authorization, String token) {
        String raw = firstNonBlank(bearer(authorization), token);
        if (raw == null || raw.isBlank()) {
            throw new BizException("10401", "缺少 MCP 令牌");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, workspace_id, expires_at FROM ag_mcp_token WHERE token_hash=? AND deleted_at IS NULL",
                TokenHash.sha256(raw.trim()));
        if (rows.isEmpty()) {
            throw new BizException("10403", "MCP 令牌无效或已撤销");
        }
        Map<String, Object> row = rows.get(0);
        Object expires = row.get("expires_at");
        if (expires instanceof java.sql.Timestamp ts && ts.toLocalDateTime().isBefore(java.time.LocalDateTime.now())) {
            throw new BizException("10403", "MCP 令牌已过期");
        }
        long userId = row.get("user_id") instanceof Number n ? n.longValue() : 0L;
        long workspaceId = row.get("workspace_id") instanceof Number n ? n.longValue() : 1L;
        if (userId == 0L) {
            throw new BizException("10403", "MCP 令牌未绑定用户");
        }
        return new Principal(userId, workspaceId, raw.trim());
    }

    private String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String v = authorization.trim();
        return v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(7).trim() : v;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}

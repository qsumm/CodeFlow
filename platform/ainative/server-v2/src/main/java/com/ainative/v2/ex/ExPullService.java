package com.ainative.v2.ex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部平台拉取同步:按绑定配置 GET 远端条目并落成工作项(幂等:同 external_id 不重复)。
 * 禅道族接口约定:GET {baseUrl}/api.php/v1/bugs?product={projectId},
 * Header Token: {credential}。返回数组或 {data:[...]},条目 {id,title,description,status}。
 */
@Service
public class ExPullService {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private static final Logger log = LoggerFactory.getLogger(ExPullService.class);
    private final ExDao dao;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ExPullService(ExDao dao, JdbcTemplate jdbc) {
        this.dao = dao;
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> pull(long bindingId) {
        ExDao.Binding binding = dao.findBinding(bindingId).orElseThrow(() -> new RuntimeException("绑定不存在"));
        String baseUrl = binding.configJson() == null ? null : extractBaseUrl(binding.configJson());
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("绑定缺少 baseUrl 配置");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/api.php/v1/bugs?product=" + binding.projectId();
        JsonNode payload = fetch(url, credentialOf(bindingId));
        List<JsonNode> items = itemsOf(payload);
        int imported = 0;
        int skipped = 0;
        for (JsonNode item : items) {
            String externalId = item.path("id").asText("");
            if (externalId.isBlank()) {
                continue;
            }
            List<Long> existing = jdbc.queryForList(
                    "SELECT ticket_id FROM ex_item_link WHERE binding_id=? AND external_id=?",
                    Long.class, bindingId, externalId);
            if (!existing.isEmpty()) {
                skipped++;
                continue;
            }
            String title = item.path("title").asText("外部条目 " + externalId);
            String body = item.path("description").asText("");
            Long stateId = mappedState(bindingId, item.path("status").asText(""));
            Long flowId = mappedFlow(bindingId);
            Long ticketId = insertTicket(title, body, "ZENTAO", externalId, flowId, stateId);
            jdbc.update("INSERT INTO ex_item_link (workspace_id, binding_id, external_id, ticket_id) VALUES (" + ws() + ",?,?,?)",
                    bindingId, externalId, ticketId);
            jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, from_val, to_val) VALUES (" + ws() + ",?, 'EXTERNAL_IMPORT', 'SYSTEM', NULL, ?)",
                    ticketId, externalId);
            imported++;
        }
        log.info("external pull bindingId={} imported={} skipped={}", bindingId, imported, skipped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindingId", bindingId);
        out.put("imported", imported);
        out.put("skipped", skipped);
        out.put("total", items.size());
        return out;
    }

    private String extractBaseUrl(String configJson) {
        try {
            JsonNode node = mapper.readTree(configJson);
            return node.path("baseUrl").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String credentialOf(long bindingId) {
        List<String> rows = jdbc.queryForList("SELECT credential_ref FROM ex_binding WHERE id=?", String.class, bindingId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    private JsonNode fetch(String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Token", token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("外部平台返回 HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("外部平台请求失败: " + e.getMessage(), e);
        }
    }

    private static List<JsonNode> itemsOf(JsonNode payload) {
        List<JsonNode> items = new ArrayList<>();
        JsonNode array = payload.isArray() ? payload : payload.path("data");
        if (array.isArray()) {
            array.forEach(items::add);
        } else if (array.has("items") && array.get("items").isArray()) {
            array.get("items").forEach(items::add);
        }
        return items;
    }

    private Long mappedState(long bindingId, String externalStatus) {
        List<Long> rows = jdbc.queryForList(
                "SELECT state_id FROM ex_status_map WHERE binding_id=? AND external_status=?",
                Long.class, bindingId, externalStatus);
        if (rows.isEmpty()) {
            List<Long> fallback = jdbc.queryForList(
                    "SELECT id FROM wi_flow_state WHERE category='NEW' ORDER BY sort_no LIMIT 1", Long.class);
            return fallback.isEmpty() ? null : fallback.get(0);
        }
        return rows.get(0);
    }

    private Long mappedFlow(long bindingId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT f.id FROM wi_flow f JOIN ex_status_map m ON m.state_id IN (SELECT id FROM wi_flow_state WHERE flow_id=f.id) WHERE m.binding_id=? LIMIT 1",
                Long.class, bindingId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        List<Long> fallback = jdbc.queryForList(
                "SELECT id FROM wi_flow WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1", Long.class);
        return fallback.isEmpty() ? null : fallback.get(0);
    }

    private Long insertTicket(String title, String body, String source, String externalId, Long flowId, Long stateId) {
        org.springframework.jdbc.support.KeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO wi_ticket (workspace_id, title, body_md, type, priority, flow_id, state_id, external_source, external_id, version) VALUES (" + ws() + ",?,?, 'REQ', 2,?,?,?,?, 0)",
                    java.sql.PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, body);
            if (flowId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, flowId);
            if (stateId == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, stateId);
            ps.setString(5, source);
            ps.setString(6, externalId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }
}

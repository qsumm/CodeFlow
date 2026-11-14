package com.ainative.v2.statemachine;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 状态机模板编辑:节点(状态)与转移边。 */
@RestController
@RequestMapping("/api/status-templates")
public class StatusTemplateEditController {
    public record NodeRequest(String name, String category, Integer sortNo) {}
    public record EdgeRequest(Long fromNodeId, Long toNodeId) {}

    private final JdbcTemplate jdbc;

    public StatusTemplateEditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    private void requireFlow(long flowId) {
        if (jdbc.queryForList("SELECT 1 FROM wi_flow WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                flowId, ws()).isEmpty()) {
            throw new BizException("13003", "状态模板不存在");
        }
    }

    // ---------------------------------------------------------------- 节点

    @PostMapping("/{id}/nodes")
    public ApiResult<Map<String, Object>> addNode(@PathVariable long id, @RequestBody NodeRequest req) {
        RequestContext.requireUserId();
        requireFlow(id);
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("10001", "状态名称不能为空");
        }
        String category = req.category() == null || req.category().isBlank() ? "IN_PROGRESS" : req.category();
        int sortNo = req.sortNo() == null ? nextSort(id) : req.sortNo();
        jdbc.update("INSERT INTO wi_flow_state (workspace_id, flow_id, name, category, sort_no) VALUES (?,?,?,?,?)",
                ws(), id, req.name().trim(), category, sortNo);
        Long nodeId = jdbc.queryForObject("SELECT MAX(id) FROM wi_flow_state WHERE flow_id=?", Long.class, id);
        return ApiResult.ok(nodeMap(nodeId));
    }

    @PutMapping("/{id}/nodes/{nodeId}")
    public ApiResult<Map<String, Object>> updateNode(@PathVariable long id, @PathVariable long nodeId,
                                                     @RequestBody NodeRequest req) {
        RequestContext.requireUserId();
        requireFlow(id);
        if (req.name() != null && !req.name().isBlank()) {
            jdbc.update("UPDATE wi_flow_state SET name=? WHERE id=? AND flow_id=?", req.name().trim(), nodeId, id);
        }
        if (req.category() != null && !req.category().isBlank()) {
            jdbc.update("UPDATE wi_flow_state SET category=? WHERE id=? AND flow_id=?", req.category(), nodeId, id);
        }
        if (req.sortNo() != null) {
            jdbc.update("UPDATE wi_flow_state SET sort_no=? WHERE id=? AND flow_id=?", req.sortNo(), nodeId, id);
        }
        return ApiResult.ok(nodeMap(nodeId));
    }

    @DeleteMapping("/{id}/nodes/{nodeId}")
    public ApiResult<Void> deleteNode(@PathVariable long id, @PathVariable long nodeId) {
        RequestContext.requireUserId();
        requireFlow(id);
        Long tickets = jdbc.queryForObject("SELECT COUNT(*) FROM wi_ticket WHERE state_id=?", Long.class, nodeId);
        if (tickets != null && tickets > 0) {
            throw new BizException("13006", "该状态仍有工单使用,不能删除");
        }
        jdbc.update("DELETE FROM wi_flow_edge WHERE flow_id=? AND (from_state_id=? OR to_state_id=?)", id, nodeId, nodeId);
        jdbc.update("DELETE FROM wi_flow_state WHERE id=? AND flow_id=?", nodeId, id);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 转移边

    @PostMapping("/{id}/transitions")
    public ApiResult<Map<String, Object>> addEdge(@PathVariable long id, @RequestBody EdgeRequest req) {
        RequestContext.requireUserId();
        requireFlow(id);
        if (req.fromNodeId() == null || req.toNodeId() == null) {
            throw new BizException("10001", "转移起点/终点不能为空");
        }
        requireState(id, req.fromNodeId());
        requireState(id, req.toNodeId());
        if (req.fromNodeId().equals(req.toNodeId())) {
            throw new BizException("13007", "转移起点与终点不能相同");
        }
        jdbc.update("INSERT INTO wi_flow_edge (workspace_id, flow_id, from_state_id, to_state_id) VALUES (?,?,?,?)",
                ws(), id, req.fromNodeId(), req.toNodeId());
        Long edgeId = jdbc.queryForObject(
                "SELECT MAX(id) FROM wi_flow_edge WHERE flow_id=?", Long.class, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", edgeId);
        out.put("fromNodeId", req.fromNodeId());
        out.put("toNodeId", req.toNodeId());
        return ApiResult.ok(out);
    }

    @DeleteMapping("/{id}/transitions/{edgeId}")
    public ApiResult<Void> deleteEdge(@PathVariable long id, @PathVariable long edgeId) {
        RequestContext.requireUserId();
        requireFlow(id);
        jdbc.update("DELETE FROM wi_flow_edge WHERE id=? AND flow_id=?", edgeId, id);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 工具

    private Map<String, Object> nodeMap(Long nodeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, flow_id AS flowId, name, category, sort_no AS sortNo FROM wi_flow_state WHERE id=?", nodeId);
        if (rows.isEmpty()) {
            throw new BizException("13008", "状态不存在");
        }
        return rows.get(0);
    }

    private void requireState(long flowId, long stateId) {
        if (jdbc.queryForList("SELECT 1 FROM wi_flow_state WHERE id=? AND flow_id=?", stateId, flowId).isEmpty()) {
            throw new BizException("13008", "状态不存在:" + stateId);
        }
    }

    private int nextSort(long flowId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_no),0) FROM wi_flow_state WHERE flow_id=?", Integer.class, flowId);
        return (max == null ? 0 : max) + 1;
    }
}

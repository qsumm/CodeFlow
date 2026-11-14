package com.ainative.v2.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作区初始化:新项目必须自带一条默认状态机,否则工单建出来没有初始状态、
 * 状态模板列表为空。模板来源优先取平台内置流程(workspace 1 的默认流程),
 * 没有内置流程时按「新建 → 开发中 → 验证中 → 已发布 / 已取消」建一条。
 */
@Component
public class WorkspaceProvisioner {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceProvisioner.class);

    private static final String[][] DEFAULT_STATES = {
            {"新建", "NEW"},
            {"开发中", "IN_PROGRESS"},
            {"验证中", "IN_PROGRESS"},
            {"已发布", "DONE"},
            {"已取消", "CANCELED"},
    };

    private final JdbcTemplate jdbc;

    public WorkspaceProvisioner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保工作区有默认流程,返回其 id。 */
    public Long ensureDefaultFlow(long workspaceId) {
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM wi_flow WHERE workspace_id=? AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1",
                Long.class, workspaceId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Long templateFlow = templateFlowId(workspaceId);
        Long flowId = templateFlow == null ? createBuiltinFlow(workspaceId) : cloneFlow(workspaceId, templateFlow);
        log.info("workspace provisioned workspaceId={} flowId={} template={}", workspaceId, flowId, templateFlow);
        return flowId;
    }

    private Long templateFlowId(long workspaceId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM wi_flow WHERE workspace_id<>? AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1",
                Long.class, workspaceId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 复制模板流程(状态 + 转移边),主键由自增生成后重新映射。 */
    private Long cloneFlow(long workspaceId, long templateFlowId) {
        List<Map<String, Object>> flow = jdbc.queryForList(
                "SELECT name, description FROM wi_flow WHERE id=?", templateFlowId);
        String name = flow.isEmpty() ? "默认流程" : String.valueOf(flow.get(0).get("name"));
        String description = flow.isEmpty() || flow.get(0).get("description") == null
                ? null : String.valueOf(flow.get(0).get("description"));
        jdbc.update("INSERT INTO wi_flow (workspace_id, name, description, is_default, status) VALUES (?,?,?,1,'ACTIVE')",
                workspaceId, name, description);
        Long newFlowId = jdbc.queryForObject("SELECT MAX(id) FROM wi_flow WHERE workspace_id=?", Long.class, workspaceId);

        Map<Long, Long> stateMap = new LinkedHashMap<>();
        List<Map<String, Object>> states = jdbc.queryForList(
                "SELECT id, name, category, sort_no FROM wi_flow_state WHERE flow_id=? ORDER BY sort_no", templateFlowId);
        for (Map<String, Object> s : states) {
            jdbc.update("INSERT INTO wi_flow_state (workspace_id, flow_id, name, category, sort_no) VALUES (?,?,?,?,?)",
                    workspaceId, newFlowId, s.get("name"), s.get("category"),
                    s.get("sort_no") instanceof Number n ? n.intValue() : 0);
            Long newStateId = jdbc.queryForObject(
                    "SELECT MAX(id) FROM wi_flow_state WHERE flow_id=?", Long.class, newFlowId);
            stateMap.put(((Number) s.get("id")).longValue(), newStateId);
        }
        List<Map<String, Object>> edges = jdbc.queryForList(
                "SELECT from_state_id, to_state_id FROM wi_flow_edge WHERE flow_id=?", templateFlowId);
        for (Map<String, Object> e : edges) {
            Long from = stateMap.get(((Number) e.get("from_state_id")).longValue());
            Long to = stateMap.get(((Number) e.get("to_state_id")).longValue());
            if (from == null || to == null) {
                continue;
            }
            jdbc.update("INSERT INTO wi_flow_edge (workspace_id, flow_id, from_state_id, to_state_id) VALUES (?,?,?,?)",
                    workspaceId, newFlowId, from, to);
        }
        return newFlowId;
    }

    /** 没有可复制模板时建内置流程。 */
    private Long createBuiltinFlow(long workspaceId) {
        jdbc.update("INSERT INTO wi_flow (workspace_id, name, description, is_default, status) VALUES (?,?,?,1,'ACTIVE')",
                workspaceId, "默认流程", "内置状态机");
        Long flowId = jdbc.queryForObject("SELECT MAX(id) FROM wi_flow WHERE workspace_id=?", Long.class, workspaceId);
        long[] stateIds = new long[DEFAULT_STATES.length];
        for (int i = 0; i < DEFAULT_STATES.length; i++) {
            jdbc.update("INSERT INTO wi_flow_state (workspace_id, flow_id, name, category, sort_no) VALUES (?,?,?,?,?)",
                    workspaceId, flowId, DEFAULT_STATES[i][0], DEFAULT_STATES[i][1], i + 1);
            stateIds[i] = jdbc.queryForObject("SELECT MAX(id) FROM wi_flow_state WHERE flow_id=?",
                    Long.class, flowId);
        }
        // 主链:新建 → 开发中 → 验证中 → 已发布;任意状态可取消。
        int[][] chain = {{0, 1}, {1, 2}, {2, 3}, {0, 4}, {1, 4}, {2, 4}, {3, 4}};
        for (int[] pair : chain) {
            jdbc.update("INSERT INTO wi_flow_edge (workspace_id, flow_id, from_state_id, to_state_id) VALUES (?,?,?,?)",
                    workspaceId, flowId, stateIds[pair[0]], stateIds[pair[1]]);
        }
        return flowId;
    }
}

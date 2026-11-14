package com.ainative.v2.mcp;

import com.ainative.v2.agent.AgentService;
import com.ainative.v2.asset.AssetService;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.mem.MemoryService;
import com.ainative.v2.pf.PfService;
import com.ainative.v2.workitem.WorkitemDao;
import com.ainative.v2.workitem.WorkitemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具实现(平台对外暴露给 IDE / Agent 的操作面)。
 *
 * <p>工具命名沿用 v1 的 ainative.* 前缀,便于既有 MCP 客户端无感切换;
 * 每个工具只做一层参数映射,业务规则全部复用对应域服务,避免出现
 * "MCP 一套规则、REST 另一套"的双写分叉。
 */
@Service
public class McpToolService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final WorkitemService workitems;
    private final AssetService assets;
    private final MemoryService memories;
    private final AgentService agents;
    private final PfService platform;

    public McpToolService(JdbcTemplate jdbc, WorkitemService workitems, AssetService assets,
                          MemoryService memories, AgentService agents, PfService platform) {
        this.jdbc = jdbc;
        this.workitems = workitems;
        this.assets = assets;
        this.memories = memories;
        this.agents = agents;
        this.platform = platform;
    }

    // ------------------------------------------------------------ 工具清单

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("ainative.list_projects", "列出当前用户可见的项目(工作区)",
                Map.of()));
        tools.add(tool("ainative.list_workitems", "按关键词/类型/状态/负责人筛选工单",
                Map.of("keyword", str("关键词"), "type", str("工单类型,如 REQ/BUG"),
                        "stateId", num("状态 ID"), "assigneeId", num("负责人/智能体 ID"),
                        "pageNum", num("页码,默认 1"), "pageSize", num("每页条数,默认 20"))));
        tools.add(tool("ainative.get_workitem", "读取工单详情",
                Map.of("id", num("工单 ID"))));
        tools.add(tool("ainative.create_workitem", "创建工单",
                Map.of("title", str("标题"), "contentMd", str("描述(Markdown)"),
                        "workType", str("类型,默认 REQ"), "priority", num("优先级 1-4,默认 2"))));
        tools.add(tool("ainative.update_workitem", "修改工单标题/描述",
                Map.of("id", num("工单 ID"), "title", str("标题"), "contentMd", str("描述"),
                        "expectedVersion", num("乐观锁版本(可选)"))));
        tools.add(tool("ainative.assign_workitem", "指派工单;指派给智能体即触发派发",
                Map.of("id", num("工单 ID"), "assigneeType", str("AGENT 或 HUMAN"),
                        "assigneeRef", num("智能体/用户 ID"), "sdlcId", num("SDLC 流程 ID(可选)"))));
        tools.add(tool("ainative.transition_workitem", "流转工单状态",
                Map.of("id", num("工单 ID"), "toStateId", num("目标状态 ID"),
                        "fromStateId", num("来源状态 ID(可选)"), "expectedVersion", num("乐观锁版本(可选)"))));
        tools.add(tool("ainative.add_workitem_comment", "给工单添加评论",
                Map.of("id", num("工单 ID"), "contentMd", str("评论内容"))));
        tools.add(tool("ainative.list_workitem_comments", "列出工单评论",
                Map.of("id", num("工单 ID"))));
        tools.add(tool("ainative.delete_workitem", "删除(回收站)工单",
                Map.of("id", num("工单 ID"))));
        tools.add(tool("ainative.list_task_data", "读取工单的任务数据",
                Map.of("ticketId", num("工单 ID"))));
        tools.add(tool("ainative.put_task_data", "写入工单的任务数据(按 run 维度 upsert)",
                Map.of("ticketId", num("工单 ID"), "runId", num("运行 ID(可选)"),
                        "payload", obj("任意 JSON 对象"))));
        tools.add(tool("ainative.list_memory", "检索平台记忆",
                Map.of("keyword", str("关键词"), "pageNum", num("页码"), "pageSize", num("每页条数"))));
        tools.add(tool("ainative.create_memory", "写入一条平台记忆",
                Map.of("title", str("标题"), "contentMd", str("内容(Markdown)"))));
        tools.add(tool("ainative.list_agents", "列出智能体"));
        tools.add(tool("ainative.list_executors", "列出执行器及其在线状态",
                Map.of("agentId", num("只看某个智能体的执行器(可选)"))));
        tools.add(tool("ainative.list_repos", "列出代码仓库资产"));
        tools.add(tool("ainative.list_scheduled_tasks", "列出定时任务"));
        tools.add(tool("ainative.list_artifacts", "列出工单的产物",
                Map.of("workitemId", num("工单 ID"))));
        return tools;
    }

    private Map<String, Object> tool(String name, String description) {
        return tool(name, description, Map.of());
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : properties);
        List<String> required = new ArrayList<>();
        for (String key : List.of("title", "contentMd", "id")) {
            if (properties != null && properties.containsKey(key)) {
                required.add(key);
            }
        }
        schema.put("required", required);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        return tool;
    }

    private Map<String, Object> str(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    private Map<String, Object> num(String desc) {
        return Map.of("type", "integer", "description", desc);
    }

    private Map<String, Object> obj(String desc) {
        return Map.of("type", "object", "description", desc);
    }

    // ------------------------------------------------------------ 工具调用

    public Object call(String name, Map<String, Object> args) {
        if (name == null || name.isBlank()) {
            throw new BizException("10601", "缺少工具名");
        }
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (name) {
            case "ainative.list_projects" -> listProjects();
            case "ainative.list_workitems" -> listWorkitems(a);
            case "ainative.get_workitem" -> workitems.get(longArg(a, "id", true));
            case "ainative.create_workitem" -> workitems.create(
                    strArg(a, "workType"), strArg(a, "title"), strArg(a, "contentMd"),
                    intArg(a, "priority"), currentUserId());
            case "ainative.update_workitem" -> workitems.updateContent(
                    longArg(a, "id", true), strArg(a, "title"), strArg(a, "contentMd"),
                    intArgOrNull(a, "expectedVersion"));
            case "ainative.assign_workitem" -> workitems.assign(
                    longArg(a, "id", true), strArgOr(a, "assigneeType", "AGENT"),
                    longArg(a, "assigneeRef", true), longArgOrNull(a, "squadId"), longArgOrNull(a, "sdlcId"));
            case "ainative.transition_workitem" -> workitems.transition(
                    longArg(a, "id", true), longArg(a, "toStateId", true),
                    intArgOrNull(a, "fromStateId"), intArgOrNull(a, "expectedVersion"));
            case "ainative.add_workitem_comment" -> workitems.comment(
                    longArg(a, "id", true), currentUserId(), strArg(a, "contentMd"));
            case "ainative.list_workitem_comments" -> workitems.comments(longArg(a, "id", true));
            case "ainative.delete_workitem" -> {
                workitems.delete(longArg(a, "id", true));
                yield Map.of("deleted", true);
            }
            case "ainative.list_task_data" -> listTaskData(longArg(a, "ticketId", true));
            case "ainative.put_task_data" -> putTaskData(a);
            case "ainative.list_memory" -> memories.list(strArg(a, "keyword"),
                    intArgOr(a, "pageNum", 1), intArgOr(a, "pageSize", 20));
            case "ainative.create_memory" -> memories.create(strArg(a, "title"), strArg(a, "contentMd"));
            case "ainative.list_agents" -> agents.listAgents();
            case "ainative.list_executors" -> listExecutors(a);
            case "ainative.list_repos" -> assets.listRepos();
            case "ainative.list_scheduled_tasks" -> platform.tasks();
            case "ainative.list_artifacts" -> assets.artifactsOf(longArg(a, "workitemId", true));
            default -> throw new BizException("10602", "未知工具:" + name);
        };
    }

    // ------------------------------------------------------------ 各工具实现

    private List<Map<String, Object>> listProjects() {
        long uid = currentUserId();
        List<Map<String, Object>> rows;
        try {
            // 项目 = 组织(v1 org/org_member 迁移到 id_org/id_org_member),
            // 只列当前用户是成员的项目;管理员可见全部。
            rows = jdbc.queryForList(
                    "SELECT o.id, o.name FROM id_org o"
                            + " LEFT JOIN id_org_member m ON m.org_id=o.id AND m.user_id=?"
                            + " WHERE o.deleted_at IS NULL AND (m.user_id IS NOT NULL OR EXISTS ("
                            + "   SELECT 1 FROM id_user u WHERE u.id=? AND u.is_admin=1)) ORDER BY o.id",
                    uid, uid);
        } catch (Exception e) {
            rows = List.of();
        }
        if (rows.isEmpty()) {
            return List.of(Map.of("id", currentWorkspaceId(), "name", "默认项目"));
        }
        return rows;
    }

    private PageResult<WorkitemService.WorkitemVO> listWorkitems(Map<String, Object> a) {
        WorkitemDao.ListQuery q = new WorkitemDao.ListQuery(
                strArg(a, "keyword"), strArg(a, "type"), longArgOrNull(a, "stateId"),
                strArg(a, "assigneeType"), longArgOrNull(a, "assigneeId"),
                intArgOrNull(a, "priority"), strArg(a, "tag"), boolArg(a, "recycled"),
                intArgOr(a, "pageNum", 1), intArgOr(a, "pageSize", 20));
        return workitems.list(q);
    }

    private List<Map<String, Object>> listTaskData(long ticketId) {
        return jdbc.queryForList(
                "SELECT id, ticket_id AS ticketId, run_id AS runId, payload_json AS payload, gmt_modified AS updatedAt"
                        + " FROM as_task_data WHERE ticket_id=? AND deleted_at IS NULL ORDER BY id DESC", ticketId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> putTaskData(Map<String, Object> a) {
        long ticketId = longArg(a, "ticketId", true);
        Long runId = longArgOrNull(a, "runId");
        Object payload = a.get("payload");
        String json;
        try {
            json = payload instanceof String s ? s : MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new BizException("10603", "payload 不是合法 JSON");
        }
        List<Map<String, Object>> existing = runId == null
                ? jdbc.queryForList("SELECT id FROM as_task_data WHERE ticket_id=? AND run_id IS NULL AND deleted_at IS NULL", ticketId)
                : jdbc.queryForList("SELECT id FROM as_task_data WHERE ticket_id=? AND run_id=? AND deleted_at IS NULL", ticketId, runId);
        long id;
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO as_task_data (workspace_id, ticket_id, run_id, payload_json) VALUES (?,?,?,?)",
                    currentWorkspaceId(), ticketId, runId, json);
            id = jdbc.queryForObject("SELECT MAX(id) FROM as_task_data WHERE ticket_id=?", Long.class, ticketId);
        } else {
            id = ((Number) existing.get(0).get("id")).longValue();
            jdbc.update("UPDATE as_task_data SET payload_json=? WHERE id=?", json, id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("ticketId", ticketId);
        out.put("runId", runId);
        out.put("payload", payload);
        return out;
    }

    private List<Map<String, Object>> listExecutors(Map<String, Object> a) {
        Long agentId = longArgOrNull(a, "agentId");
        return jdbc.queryForList(
                "SELECT id, agent_id AS agentId, name, status, last_heartbeat AS lastHeartbeat"
                        + " FROM ag_executor WHERE deleted_at IS NULL"
                        + (agentId == null ? "" : " AND agent_id=" + agentId) + " ORDER BY id");
    }

    // ------------------------------------------------------------ 参数与上下文

    private long currentUserId() {
        return com.ainative.v2.common.RequestContext.requireUserId();
    }

    private long currentWorkspaceId() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private String strArg(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String strArgOr(Map<String, Object> a, String key, String fallback) {
        String v = strArg(a, key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private Integer intArgOrNull(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v));
    }

    private int intArgOr(Map<String, Object> a, String key, int fallback) {
        Integer v = intArgOrNull(a, key);
        return v == null ? fallback : v;
    }

    private int intArg(Map<String, Object> a, String key) {
        return intArgOr(a, key, 2);
    }

    private Long longArgOrNull(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private long longArg(Map<String, Object> a, String key, boolean required) {
        Long v = longArgOrNull(a, key);
        if (v == null && required) {
            throw new BizException("10604", "缺少参数:" + key);
        }
        return v == null ? 0L : v;
    }

    private boolean boolArg(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v != null && ("true".equalsIgnoreCase(String.valueOf(v)) || "1".equals(String.valueOf(v)));
    }
}

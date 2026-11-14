package com.ainative.v2.squad;

import com.ainative.v2.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 小队与流水线域服务。 */
@Service
public class SquadService {
    private final SquadDao dao;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public SquadService(SquadDao dao, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.dao = dao;
        this.jdbc = jdbc;
    }

    // ── 小队 ──
    public Map<String, Object> createSquad(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "小队名称不能为空");
        }
        long id = dao.insertSquad(name.trim(), description);
        return squadDetail(id);
    }

    public Map<String, Object> squadDetail(long id) {
        SquadDao.Squad s = dao.findSquad(id).orElseThrow(() -> new BizException("15001", "小队不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("description", s.description());
        m.put("status", s.status());
        List<Map<String, Object>> members = new ArrayList<>();
        for (SquadDao.Member mem : dao.membersOf(id)) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("agentId", mem.agentId());
            mm.put("roleCode", mem.roleCode());
            // 前端「首步执行 Agent」下拉直接用 members 渲染,必须有可读名字;
            // 顺带带上在线执行器数,UI 才能在选人时提示是否可执行。
            List<Map<String, Object>> agent = jdbc.queryForList(
                    "SELECT name, role_name AS roleName FROM ag_agent WHERE id=?", mem.agentId());
            mm.put("name", agent.isEmpty() ? null : agent.get(0).get("name"));
            // 交付弹窗读 agentName(见 StartDeliveryModal),与 name 同时给出。
            mm.put("agentName", agent.isEmpty() ? null : agent.get(0).get("name"));
            mm.put("agentKind", "AI");
            mm.put("roleName", agent.isEmpty() ? null : agent.get(0).get("roleName"));
            Long online = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL",
                    Long.class, mem.agentId());
            mm.put("onlineExecutors", online == null ? 0 : online);
            members.add(mm);
        }
        m.put("memberAgentIds", members.stream().map(x -> x.get("agentId")).toList());
        m.put("members", members);
        return m;
    }

    public List<Map<String, Object>> listSquads() {
        return dao.listSquads().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("name", s.name());
            m.put("description", s.description());
            m.put("status", s.status());
            m.put("memberCount", dao.membersOf(s.id()).size());
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> updateSquad(long id, String name, String description) {
        dao.findSquad(id).orElseThrow(() -> new BizException("15001", "小队不存在"));
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "小队名称不能为空");
        }
        dao.updateSquad(id, name.trim(), description);
        return squadDetail(id);
    }

    @Transactional
    public void deleteSquad(long id) {
        dao.findSquad(id).orElseThrow(() -> new BizException("15001", "小队不存在"));
        dao.deleteSquad(id);
    }

    @Transactional
    public void addMember(long squadId, long agentId, String roleCode) {
        dao.findSquad(squadId).orElseThrow(() -> new BizException("15001", "小队不存在"));
        dao.addMember(squadId, agentId, roleCode);
    }

    @Transactional
    public void removeMember(long squadId, long agentId) {
        dao.findSquad(squadId).orElseThrow(() -> new BizException("15001", "小队不存在"));
        dao.removeMember(squadId, agentId);
    }

    @Transactional
    public Map<String, Object> copySquad(long sourceId) {
        return copySquad(sourceId, null);
    }

    /** 复制小队;requestId 相同的重复请求直接返回首次结果(前端失败重试不产生副本风暴)。 */
    public Map<String, Object> copySquad(long sourceId, String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            List<Map<String, Object>> dup = jdbc.queryForList(
                    "SELECT target_id FROM sq_copy_op WHERE source_squad_id=? AND request_id=? AND status='DONE' AND deleted_at IS NULL",
                    sourceId, requestId.trim());
            if (!dup.isEmpty() && dup.get(0).get("target_id") != null) {
                return squadDetail(((Number) dup.get(0).get("target_id")).longValue());
            }
        }
        Map<String, Object> src = squadDetail(sourceId);
        long newId = dao.insertSquad(src.get("name") + "(副本)", (String) src.get("description"));
        for (SquadDao.Member mem : dao.membersOf(sourceId)) {
            dao.addMember(newId, mem.agentId(), mem.roleCode());
        }
        if (requestId != null && !requestId.isBlank()) {
            jdbc.update("INSERT INTO sq_copy_op (workspace_id, source_squad_id, target_id, status) VALUES (?,?,?, 'DONE')",
                    com.ainative.v2.common.RequestContext.workspaceId(), sourceId, newId);
        }
        return squadDetail(newId);
    }

    // ── 流水线 ──
    public Map<String, Object> createSdlc(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "流水线名称不能为空");
        }
        return sdlcDetail(dao.insertSdlc(name.trim(), description));
    }

    public Map<String, Object> sdlcDetail(long id) {
        SquadDao.Sdlc s = dao.findSdlc(id).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("description", s.description());
        m.put("status", s.status());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (SquadDao.SdlcStep st : dao.stepsOf(id)) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", st.id());
            sm.put("seq", st.seq());
            sm.put("name", st.name());
            sm.put("kind", st.kind());
            sm.put("agentId", st.agentId());
            sm.put("instruction", st.instruction());
            sm.put("required", st.required());
            steps.add(sm);
        }
        m.put("steps", steps);
        return m;
    }

    public List<Map<String, Object>> listSdlcs() {
        return dao.listSdlcs().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("name", s.name());
            m.put("description", s.description());
            m.put("status", s.status());
            m.put("stepCount", dao.stepsOf(s.id()).size());
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> updateSdlc(long id, String name, String description) {
        dao.findSdlc(id).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        dao.updateSdlc(id, name.trim(), description);
        return sdlcDetail(id);
    }

    @Transactional
    public void deleteSdlc(long id) {
        dao.findSdlc(id).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        dao.deleteSdlc(id);
    }

    @Transactional
    public Map<String, Object> addStep(long sdlcId, int seq, String name, String kind, Long agentId,
                                       String instruction, boolean required) {
        dao.findSdlc(sdlcId).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "步骤名称不能为空");
        }
        dao.addStep(sdlcId, seq <= 0 ? dao.stepsOf(sdlcId).size() + 1 : seq, name.trim(),
                kind == null ? "WORK" : kind, agentId, instruction, required);
        return sdlcDetail(sdlcId);
    }

    @Transactional
    public Map<String, Object> updateStep(long sdlcId, long stepId, String name, String kind, Long agentId,
                                          String instruction, boolean required) {
        dao.findSdlc(sdlcId).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        dao.updateStep(sdlcId, stepId, name, kind == null ? "WORK" : kind, agentId, instruction, required);
        return sdlcDetail(sdlcId);
    }

    @Transactional
    public Map<String, Object> deleteStep(long sdlcId, long stepId) {
        dao.findSdlc(sdlcId).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        dao.deleteStep(sdlcId, stepId);
        return sdlcDetail(sdlcId);
    }

    @Transactional
    public Map<String, Object> reorderSteps(long sdlcId, List<Long> stepIds) {
        dao.findSdlc(sdlcId).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        dao.reorderSteps(sdlcId, stepIds);
        return sdlcDetail(sdlcId);
    }

    @Transactional
    public Map<String, Object> enableSdlc(long id) {
        dao.findSdlc(id).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        if (dao.stepsOf(id).isEmpty()) {
            throw new BizException("15003", "流水线没有步骤,不能启用");
        }
        dao.setSdlcStatus(id, "ACTIVE");
        return sdlcDetail(id);
    }

    @Transactional
    public Map<String, Object> disableSdlc(long id) {
        dao.findSdlc(id).orElseThrow(() -> new BizException("15002", "流水线不存在"));
        dao.setSdlcStatus(id, "DISABLED");
        return sdlcDetail(id);
    }
}

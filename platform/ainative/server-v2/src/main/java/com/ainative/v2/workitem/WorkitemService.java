package com.ainative.v2.workitem;

import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.dispatch.DispatchLaunchService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作项域服务:创建/查询/流转/指派/标签/评论/时间线/关注。
 * 契约与 v1 /api/workitems/* 对齐;状态机合法性由 wi_flow_edge 判定。
 */
@Service
public class WorkitemService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private final WorkitemDao dao;
    private final DispatchLaunchService dispatchLaunch;
    private final com.ainative.v2.ws.WorkspaceProvisioner provisioner;

    public WorkitemService(WorkitemDao dao, DispatchLaunchService dispatchLaunch,
                           com.ainative.v2.ws.WorkspaceProvisioner provisioner) {
        this.dao = dao;
        this.dispatchLaunch = dispatchLaunch;
        this.provisioner = provisioner;
    }

    /** 工作项视图(与 v1 前端字段对齐的精简版)。 */
    public record WorkitemVO(long id, String workType, String title, String contentMd,
                             Long templateId, Long statusNodeId, String statusName, String statusCategory,
                             Long sdlcId, String assigneeType, Long assigneeRef, String assigneeName,
                             String assigneeDisplayName, long creatorId, String creatorName,
                             int priority, int version, String gmtCreate, String gmtModified,
                             List<String> tags) {}

    @Transactional
    public WorkitemVO create(String workType, String title, String contentMd, Integer priority, long creatorId) {
        if (title == null || title.isBlank()) {
            throw new BizException("10001", "标题不能为空");
        }
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("10001", "描述不能为空");
        }
        String type = workType == null || workType.isBlank() ? "REQ" : workType;
        Long resolvedFlow = dao.defaultFlowId(ws()).orElse(null);
        if (resolvedFlow == null) {
            // 新工作区(或刚清空模板)没有状态机:先按内置模板开户,再建单。
            resolvedFlow = provisioner.ensureDefaultFlow(ws());
        }
        final Long flowId = resolvedFlow;
        Long stateId = flowId == null ? null
                : dao.firstStateOfFlow(flowId, "NEW").or(() -> dao.firstStateOfFlow(flowId, "INIT")).orElse(null);
        long id = dao.insert(ws(), title.trim(), contentMd, type,
                priority == null ? 2 : priority, flowId, stateId, creatorId);
        dao.addJournal(ws(), id, "CREATE", "HUMAN", creatorId, null, "new", null);
        return get(id);
    }

    public WorkitemVO get(long id) {
        WorkitemDao.Ticket t = dao.findById(id, false)
                .orElseThrow(() -> new BizException("13003", "工作项不存在"));
        return toVO(t);
    }

    public PageResult<WorkitemVO> list(WorkitemDao.ListQuery q) {
        List<WorkitemVO> list = dao.list(q).stream().map(this::toVO).toList();
        return new PageResult<>(list, dao.count(q), q.pageNum(), q.pageSize());
    }

    @Transactional
    public WorkitemVO updateContent(long id, String title, String contentMd, Integer expectedVersion) {
        WorkitemDao.Ticket t = requireTicket(id);
        if (title == null || title.isBlank()) {
            throw new BizException("10001", "标题不能为空");
        }
        dao.updateContent(id, title.trim(), contentMd, expectedVersion == null ? t.version() : expectedVersion);
        dao.addJournal(ws(), id, "EDIT", "HUMAN", null, null, null, null);
        return get(id);
    }

    @Transactional
    public WorkitemVO transition(long id, long toStateId, Integer fromStateId, Integer expectedVersion) {
        WorkitemDao.Ticket t = requireTicket(id);
        if (t.stateId() == null) {
            throw new BizException("13004", "工作项未挂状态机,无法流转");
        }
        WorkitemDao.State from = dao.findState(t.stateId())
                .orElseThrow(() -> new BizException("13004", "当前状态不存在"));
        if (fromStateId != null && fromStateId != from.id()) {
            throw new BizException("13005", "工作项已被修改,请重试");
        }
        WorkitemDao.State to = dao.findState(toStateId)
                .orElseThrow(() -> new BizException("13004", "目标状态不存在"));
        boolean legal = dao.edgesOfFlow(from.flowId()).stream()
                .anyMatch(e -> e.fromStateId() == from.id() && e.toStateId() == to.id());
        if (!legal) {
            throw new BizException("13004", "非法状态流转");
        }
        dao.updateState(id, to.id(), expectedVersion == null ? t.version() : expectedVersion);
        dao.addJournal(ws(), id, "STATUS_CHANGE", "HUMAN", null, from.name(), to.name(), null);
        return get(id);
    }

    @Transactional
    public WorkitemVO assign(long id, String assigneeType, Long assigneeRef, Long squadId, Long sdlcId) {
        requireTicket(id);
        if (assigneeType == null || assigneeRef == null) {
            throw new BizException("10001", "指派对象不能为空");
        }
        String type = assigneeType.toUpperCase();
        if (!type.equals("AGENT") && !type.equals("HUMAN")) {
            throw new BizException("10001", "指派类型不合法");
        }
        dao.updateAssignee(id, type, assigneeRef, squadId, sdlcId);
        dao.addJournal(ws(), id, "ASSIGN", "HUMAN", null, null,
                type + ":" + assigneeRef, null);
        if ("AGENT".equals(type)) {
            // 指派即派发:创建派发记录并向在线执行器下发 TASK_DISPATCH(无在线则 PENDING)
            dispatchLaunch.start(id, assigneeRef, sdlcId);
        }
        return get(id);
    }

    @Transactional
    public WorkitemVO replaceTags(long id, List<String> tags) {
        requireTicket(id);
        dao.replaceTags(ws(), id, tags == null ? List.of() : tags);
        dao.addJournal(ws(), id, "TAGS", "HUMAN", null, null, null, null);
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        requireTicket(id);
        dao.softDelete(id);
        dao.addJournal(ws(), id, "DELETE", "HUMAN", null, null, null, null);
    }

    @Transactional
    public WorkitemVO restore(long id) {
        WorkitemDao.Ticket t = dao.findById(id, true)
                .orElseThrow(() -> new BizException("13003", "工作项不存在"));
        if (t.deletedAt() == null) {
            throw new BizException("13009", "工作项未被删除，无需恢复");
        }
        dao.restore(id);
        return get(id);
    }

    public PageResult<WorkitemVO> recycled(int pageNum, int pageSize) {
        return list(new WorkitemDao.ListQuery(null, null, null, null, null, null, null, true, pageNum, pageSize));
    }

    public Map<String, Object> comment(long id, long authorId, String contentMd) {
        requireTicket(id);
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("10001", "评论内容不能为空");
        }
        long noteId = dao.insertNote(ws(), id, "HUMAN", authorId, contentMd);
        dao.addJournal(ws(), id, "COMMENT", "HUMAN", authorId, null, null, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", noteId);
        out.put("workitemId", id);
        out.put("authorType", "HUMAN");
        out.put("authorRef", authorId);
        out.put("contentMd", contentMd);
        return out;
    }

    public List<Map<String, Object>> comments(long id) {
        requireTicket(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkitemDao.Note n : dao.notesOf(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("workitemId", n.ticketId());
            m.put("authorType", n.authorType());
            m.put("authorRef", n.authorId());
            m.put("contentMd", n.bodyMd());
            m.put("gmtCreate", n.gmtCreate() == null ? null : TS.format(n.gmtCreate()));
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> timeline(long id) {
        requireTicket(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkitemDao.Journal j : dao.journalOf(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id());
            m.put("eventType", j.eventType());
            m.put("fromVal", j.fromVal());
            m.put("toVal", j.toVal());
            m.put("actorType", j.actorType());
            m.put("actorRef", j.actorId());
            m.put("gmtCreate", j.gmtCreate() == null ? null : TS.format(j.gmtCreate()));
            out.add(m);
        }
        return out;
    }

    /** 统一时间线:事件 + 评论按时间合并。 */
    /**
     * 统一时间线(前端 TimelineItem 契约):type='comment'|'system'、content 必为字符串
     * (前端 formatSystemContent 对 content 调 .match,null 会崩)、isAgent/authorName 可读。
     */
    public List<Map<String, Object>> unifiedTimeline(long id) {
        requireTicket(id);
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkitemDao.Journal j : dao.journalOf(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id());
            m.put("kind", "EVENT");
            m.put("type", "system");
            m.put("eventType", j.eventType());
            m.put("fromVal", j.fromVal());
            m.put("toVal", j.toVal());
            m.put("fromValDisplay", j.fromVal());
            m.put("toValDisplay", j.toVal());
            m.put("content", "");
            m.put("authorId", j.actorId());
            m.put("authorType", j.actorType());
            m.put("authorName", displayName(j.actorType(), j.actorId()));
            m.put("isAgent", "AGENT".equalsIgnoreCase(j.actorType()));
            m.put("gmtCreate", j.gmtCreate() == null ? "" : TS.format(j.gmtCreate()));
            out.add(m);
        }
        for (WorkitemDao.Note n : dao.notesOf(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("kind", "COMMENT");
            m.put("type", "comment");
            m.put("noteId", n.id());
            m.put("actorType", n.authorType());
            m.put("authorType", n.authorType());
            m.put("authorId", n.authorId());
            m.put("authorName", displayName(n.authorType(), n.authorId()));
            m.put("isAgent", "AGENT".equalsIgnoreCase(n.authorType()));
            m.put("contentMd", n.bodyMd() == null ? "" : n.bodyMd());
            m.put("content", n.bodyMd() == null ? "" : n.bodyMd());
            m.put("gmtCreate", n.gmtCreate() == null ? "" : TS.format(n.gmtCreate()));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("gmtCreate"))));
        return out;
    }

    public List<Map<String, Object>> participants(long id) {
        WorkitemDao.Ticket t = requireTicket(id);
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        if (t.assigneeType() != null && t.assigneeId() != null) {
            seen.put(t.assigneeType() + ":" + t.assigneeId(),
                    Map.of("type", t.assigneeType(), "ref", t.assigneeId()));
        }
        for (Long uid : dao.watchersOf(id)) {
            seen.putIfAbsent("HUMAN:" + uid, Map.of("type", "HUMAN", "ref", uid));
        }
        for (WorkitemDao.Note n : dao.notesOf(id)) {
            seen.putIfAbsent(n.authorType() + ":" + n.authorId(),
                    Map.of("type", n.authorType(), "ref", n.authorId()));
        }
        return new ArrayList<>(seen.values());
    }

    @Transactional
    public void watch(long id, long userId) {
        requireTicket(id);
        dao.watch(ws(), id, userId);
    }

    @Transactional
    public void unwatch(long id, long userId) {
        requireTicket(id);
        dao.unwatch(id, userId);
    }

    public List<Long> watchers(long id) {
        requireTicket(id);
        return dao.watchersOf(id);
    }

    public List<String> tagOptions() {
        return dao.tagOptions(ws());
    }

    private WorkitemDao.Ticket requireTicket(long id) {
        return dao.findById(id, false).orElseThrow(() -> new BizException("13003", "工作项不存在"));
    }

    /** 时间线/备注的操作人可读名;AGENT 查智能体,真人查昵称。 */
    private String displayName(String actorType, Long actorId) {
        if (actorId == null) {
            return null;
        }
        try {
            List<String> names = "AGENT".equalsIgnoreCase(actorType)
                    ? dao.jdbc().queryForList("SELECT name FROM ag_agent WHERE id=?", String.class, actorId)
                    : dao.jdbc().queryForList("SELECT COALESCE(nickname, username) FROM id_user WHERE id=?", String.class, actorId);
            return names.isEmpty() ? null : names.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private WorkitemVO toVO(WorkitemDao.Ticket t) {
        String stateName = null;
        String stateCategory = null;
        if (t.stateId() != null) {
            WorkitemDao.State s = dao.findState(t.stateId()).orElse(null);
            if (s != null) {
                stateName = s.name();
                stateCategory = s.category();
            }
        }
        // 列表/详情都要显示「谁在负责、谁建的」,这里把 id 解析成名字;
        // 智能体与真人分表,按 assignee_type 决定查哪张表。
        String assigneeName = null;
        if (t.assigneeId() != null) {
            if ("AGENT".equalsIgnoreCase(t.assigneeType())) {
                List<String> names = dao.jdbc().queryForList(
                        "SELECT name FROM ag_agent WHERE id=?", String.class, t.assigneeId());
                assigneeName = names.isEmpty() ? null : names.get(0);
            } else if ("HUMAN".equalsIgnoreCase(t.assigneeType())) {
                List<String> names = dao.jdbc().queryForList(
                        "SELECT COALESCE(nickname, username) FROM id_user WHERE id=?", String.class, t.assigneeId());
                assigneeName = names.isEmpty() ? null : names.get(0);
            }
        }
        List<String> creators = dao.jdbc().queryForList(
                "SELECT COALESCE(nickname, username) FROM id_user WHERE id=?", String.class, t.creatorId());
        String creatorName = creators.isEmpty() ? null : creators.get(0);
        return new WorkitemVO(t.id(), t.type(), t.title(), t.bodyMd(), t.flowId(), t.stateId(),
                stateName, stateCategory, t.sdlcId(), t.assigneeType(), t.assigneeId(), assigneeName,
                assigneeName, t.creatorId(), creatorName,
                t.priority(), t.version(),
                t.gmtCreate() == null ? null : TS.format(t.gmtCreate()),
                t.gmtModified() == null ? null : TS.format(t.gmtModified()),
                dao.tagsOf(t.id()));
    }
}

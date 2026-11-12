package com.ainative.v2.dispatch;

import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交付调度域服务:进度聚合 / 恢复控制 / 需求单 / 指引。
 * 说明:执行器 WebSocket 协议(领取、心跳、任务帧)是独立子系统,不在本域重写范围。
 */
@Service
public class DispatchService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DispatchDao dao;

    public DispatchService(DispatchDao dao) {
        this.dao = dao;
    }

    public Map<String, Object> progress(long ticketId) {
        List<DispatchDao.Run> runs = dao.runsOfTicket(ticketId);
        List<Map<String, Object>> executions = new ArrayList<>();
        for (DispatchDao.Run run : runs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dispatchId", run.id());
            m.put("agentId", run.agentId());
            m.put("executorId", run.executorId());
            m.put("attempt", run.attempt());
            m.put("status", run.status());
            m.put("error", run.error());
            m.put("startedAt", run.startedAt() == null ? null : TS.format(run.startedAt()));
            m.put("endedAt", run.endedAt() == null ? null : TS.format(run.endedAt()));
            m.put("durationMs", run.startedAt() != null && run.endedAt() != null
                    ? Duration.between(run.startedAt(), run.endedAt()).toMillis() : null);
            m.put("stepTimings", stepTimings(run.id()));
            executions.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executions", executions);
        return out;
    }

    /** 从事件流聚合步骤轨迹(step.started/completed)。 */
    private List<Map<String, Object>> stepTimings(long runId) {
        Map<String, Map<String, Object>> byStep = new LinkedHashMap<>();
        for (DispatchDao.Event e : dao.eventsOf(runId, 500)) {
            if (e.stepId() == null) {
                continue;
            }
            String key = e.stepId();
            Map<String, Object> step = byStep.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("stepId", k);
                m.put("name", null);
                m.put("status", "pending");
                m.put("durationMs", null);
                return m;
            });
            if ("step.started".equals(e.eventType())) {
                step.put("status", "active");
                step.put("startedAt", e.ts());
            } else if ("step.completed".equals(e.eventType())) {
                step.put("status", "done");
                step.put("endedAt", e.ts());
                if (step.get("startedAt") instanceof java.time.LocalDateTime st && e.ts() != null) {
                    step.put("durationMs", Duration.between(st, e.ts()).toMillis());
                }
            }
        }
        byStep.values().forEach(s -> s.remove("startedAt"));
        byStep.values().forEach(s -> s.remove("endedAt"));
        return new ArrayList<>(byStep.values());
    }

    public PageResult<Map<String, Object>> listRuns(String status, int pageNum, int pageSize) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DispatchDao.Run r : dao.listRuns(status, pageNum, pageSize)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("workitemId", r.ticketId());
            m.put("agentId", r.agentId());
            m.put("executorId", r.executorId());
            m.put("attempt", r.attempt());
            m.put("status", r.status());
            m.put("error", r.error());
            m.put("startedAt", r.startedAt() == null ? null : TS.format(r.startedAt()));
            m.put("endedAt", r.endedAt() == null ? null : TS.format(r.endedAt()));
            list.add(m);
        }
        return new PageResult<>(list, dao.countRuns(status), pageNum, pageSize);
    }

    public Map<String, Object> run(long id) {
        DispatchDao.Run r = dao.findRun(id).orElseThrow(() -> new BizException("14004", "派发不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("workitemId", r.ticketId());
        m.put("agentId", r.agentId());
        m.put("executorId", r.executorId());
        m.put("attempt", r.attempt());
        m.put("status", r.status());
        m.put("error", r.error());
        m.put("startedAt", r.startedAt() == null ? null : TS.format(r.startedAt()));
        m.put("endedAt", r.endedAt() == null ? null : TS.format(r.endedAt()));
        return m;
    }

    public Map<String, Object> runtimeTrace(long id) {
        DispatchDao.Run r = dao.findRun(id).orElseThrow(() -> new BizException("14004", "派发不存在"));
        List<Map<String, Object>> events = new ArrayList<>();
        for (DispatchDao.Event e : dao.eventsOf(id, 500)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", e.seq());
            m.put("eventType", e.eventType());
            m.put("stepId", e.stepId());
            m.put("detail", e.detailJson());
            m.put("eventTime", e.ts() == null ? null : TS.format(e.ts()));
            events.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchId", id);
        out.put("status", r.status());
        out.put("events", events);
        return out;
    }

    public Map<String, Object> recoveryState(long ticketId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workitemId", ticketId);
        out.put("executions", progress(ticketId).get("executions"));
        return out;
    }

    @Transactional
    public void recoveryAction(long ticketId, String action, Long dispatchId, Long actorId, boolean force) {
        if (action == null) {
            throw new BizException("10001", "恢复动作不能为空");
        }
        switch (action.toLowerCase()) {
            case "cancel" -> {
                Long target = resolveDispatch(ticketId, dispatchId);
                dao.markStatus(target, "CANCELED");
                dao.recordRecovery(target, "CANCEL", actorId, "{\"force\":" + force + "}");
            }
            case "close" -> {
                for (DispatchDao.Run r : dao.runsOfTicket(ticketId)) {
                    if (isTerminal(r.status())) {
                        dao.markStatus(r.id(), "CANCELED");
                    }
                }
            }
            case "reopen" -> {
                // 重新开交付:当前实现仅清除终态标记由新一轮派发承担
            }
            default -> throw new BizException("10001", "不支持的恢复操作");
        }
    }

    @Transactional
    public void pause(long ticketId, long dispatchId, long actorId) {
        DispatchDao.Run run = requireRunOfTicket(ticketId, dispatchId);
        if (!"RUNNING".equals(run.status()) && !"PENDING".equals(run.status())) {
            throw new BizException("14001", "当前状态不允许暂停");
        }
        dao.markStatus(dispatchId, "PAUSED");
        dao.recordRecovery(dispatchId, "PAUSE", actorId, null);
        dao.appendEvent(dispatchId, nextSeq(dispatchId), "run.paused", null, null);
    }

    @Transactional
    public void resume(long ticketId, long dispatchId, long actorId) {
        DispatchDao.Run run = requireRunOfTicket(ticketId, dispatchId);
        if (!"PAUSED".equals(run.status())) {
            throw new BizException("14001", "当前状态不允许继续");
        }
        dao.markStatus(dispatchId, "RUNNING");
        dao.recordRecovery(dispatchId, "RESUME", actorId, null);
        dao.appendEvent(dispatchId, nextSeq(dispatchId), "run.resumed", null, null);
    }

    public List<Map<String, Object>> requests(long ticketId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DispatchDao.Request r : dao.requestsOfTicket(ticketId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("kind", r.kind());
            m.put("status", r.status());
            m.put("body", r.bodyJson());
            m.put("answer", r.answerJson());
            m.put("gmtCreate", r.gmtCreate() == null ? null : TS.format(r.gmtCreate()));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> replyRequest(long requestId, String answerJson) {
        DispatchDao.Request req = dao.findRequest(requestId)
                .orElseThrow(() -> new BizException("14004", "需求单不存在"));
        if (!"PENDING".equals(req.status())) {
            throw new BizException("14002", "需求单已处理");
        }
        dao.replyRequest(requestId, answerJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", requestId);
        out.put("status", "ANSWERED");
        return out;
    }

    public List<Map<String, Object>> guidance(long ticketId, Long dispatchId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<DispatchDao.Guidance> rows = dispatchId == null ? dao.guidanceOf(ticketId) : dao.guidanceOfRun(dispatchId);
        for (DispatchDao.Guidance g : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id());
            m.put("bodyMd", g.bodyMd());
            m.put("status", g.status());
            m.put("gmtCreate", g.gmtCreate() == null ? null : TS.format(g.gmtCreate()));
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> addGuidance(long ticketId, Long dispatchId, String bodyMd) {
        if (bodyMd == null || bodyMd.isBlank()) {
            throw new BizException("10001", "指引内容不能为空");
        }
        dao.addGuidance(ticketId, dispatchId, bodyMd);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketId", ticketId);
        out.put("status", "OPEN");
        return out;
    }

    private DispatchDao.Run requireRunOfTicket(long ticketId, long dispatchId) {
        DispatchDao.Run run = dao.findRun(dispatchId).orElseThrow(() -> new BizException("14004", "派发不存在"));
        if (run.ticketId() != ticketId) {
            throw new BizException("14004", "派发不属于该工作项");
        }
        return run;
    }

    private Long resolveDispatch(long ticketId, Long dispatchId) {
        if (dispatchId != null) {
            requireRunOfTicket(ticketId, dispatchId);
            return dispatchId;
        }
        return dao.runsOfTicket(ticketId).stream()
                .filter(r -> !isTerminal(r.status()))
                .findFirst()
                .map(DispatchDao.Run::id)
                .orElseThrow(() -> new BizException("14004", "没有进行中的派发"));
    }

    private int nextSeq(long runId) {
        List<DispatchDao.Event> events = dao.eventsOf(runId, 500);
        return events.isEmpty() ? 1 : events.get(events.size() - 1).seq() + 1;
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status)
                || "CANCELED".equals(status) || "TIMED_OUT".equals(status);
    }
}

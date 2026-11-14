package com.ainative.v2.dispatch;

import com.ainative.v2.common.BizException;
import com.ainative.v2.daemon.ExecutorHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派发启动:分配 Agent 时创建 rt_run、构建 capsule、向在线执行器下发 TASK_DISPATCH;
 * 并生成带签名的包下载地址(执行器按 URL 直接下载,无需登录态)。
 */
@Service
public class DispatchLaunchService {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private static final Logger log = LoggerFactory.getLogger(DispatchLaunchService.class);

    private final JdbcTemplate jdbc;
    private final DispatchDao dispatchDao;
    private final CapsuleBuilder capsuleBuilder;
    private final ExecutorHub hub;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${ainative.public-base-url:http://localhost:7011}")
    private String publicBaseUrl;

    private final PackageSigner signer;

    public DispatchLaunchService(JdbcTemplate jdbc, DispatchDao dispatchDao,
                                 CapsuleBuilder capsuleBuilder, ExecutorHub hub, PackageSigner signer) {
        this.jdbc = jdbc;
        this.dispatchDao = dispatchDao;
        this.capsuleBuilder = capsuleBuilder;
        this.hub = hub;
        this.signer = signer;
    }

    /** 返回 dispatchId;无在线执行器时 run 置 PENDING 并返回 id。 */
    @Transactional
    public long start(long ticketId, long agentId, Long sdlcId) {
        Map<String, Object> ticket = jdbc.queryForMap(
                "SELECT title, body_md, sdlc_id FROM wi_ticket WHERE id=? AND deleted_at IS NULL", ticketId);
        Long effectiveSdlcId = sdlcId != null ? sdlcId : (Long) ticket.get("sdlc_id");
        List<Map<String, Object>> steps = stepsOf(effectiveSdlcId);
        List<Map<String, Object>> agents = jdbc.queryForList(
                "SELECT name, role_code, role_name FROM ag_agent WHERE id=? AND deleted_at IS NULL", agentId);
        if (agents.isEmpty()) {
            throw new com.ainative.v2.common.BizException("17001", "智能体不存在");
        }
        Map<String, Object> agent = agents.get(0);
        List<Map<String, Object>> roster = roster(ws());
        CapsuleBuilder.Capsule capsule = capsuleBuilder.build(
                String.valueOf(ticket.get("title")), (String) ticket.get("body_md"), steps,
                String.valueOf(agent.get("name")), (String) agent.get("role_code"),
                (String) agent.get("role_name"), roster);
        int attempt = maxAttempt(ticketId) + 1;
        // 先定执行器再落 run:rt_run.executor_id 必须等于真正收到指令的那个执行器,
        // 否则回写通道(备注鉴权按 rt_run.executor_id 关联执行器令牌)会取到错误的令牌。
        Long executorId = pickOnlineExecutor(agentId);
        long dispatchId = dispatchDao.insertRun(ws(), ticketId, agentId, executorId, effectiveSdlcId, attempt);

        // 发帧必须等事务提交:运行器收到帧会立刻下载包体,而下载端点要按 dispatchId
        // 查 rt_run 算签名 —— 事务未提交时查不到,直接 404(package_download_failed)。
        Runnable sender = () -> sendDispatch(dispatchId, executorId, ticketId, agentId,
                effectiveSdlcId, attempt, capsule);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sender.run();
                }
            });
        } else {
            sender.run();
        }
        return dispatchId;
    }

    /** 实际下发 TASK_DISPATCH 帧(必须在事务提交后调用)。 */
    private void sendDispatch(long dispatchId, Long executorId, long ticketId, long agentId,
                              Long sdlcId, int attempt, CapsuleBuilder.Capsule capsule) {
        if (executorId == null) {
            dispatchDao.markStatus(dispatchId, "PENDING");
            log.info("dispatch queued (no online executor) dispatchId={} agentId={}", dispatchId, agentId);
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "TASK_DISPATCH");
        frame.put("dispatchId", dispatchId);
        frame.put("executorId", executorId);
        frame.put("tenantId", 1);
        frame.put("workitemId", ticketId);
        frame.put("attempt", attempt);
        frame.put("agentId", agentId);
        frame.put("sdlcId", sdlcId);
        frame.put("downloadUrl", packageUrl(dispatchId, attempt));
        frame.put("md5", capsule.md5());
        frame.put("artifactUploadUrl", artifactUploadUrl(dispatchId, attempt));
        frame.put("artifactUploadToken", "artifact:" + dispatchId);
        boolean sent = hub.send(executorId, frame);
        log.info("dispatch sent dispatchId={} executorId={} sent={}", dispatchId, executorId, sent);
        if (!sent) {
            dispatchDao.markStatus(dispatchId, "PENDING");
        }
    }

    /** 按 dispatchId 重建 capsule 字节(下载端点用,天然幂等)。 */
    public CapsuleBuilder.Capsule rebuild(long dispatchId) {
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT ticket_id, sdlc_id, workspace_id FROM rt_run WHERE id=?", dispatchId);
        if (runs.isEmpty()) {
            throw new BizException("14004", "派发不存在");
        }
        long ticketId = ((Number) runs.get(0).get("ticket_id")).longValue();
        // 重建必须以该 run 记录的工作区为准:下载端点是 daemon 通道(无 Bearer),
        // 请求上下文会回落到默认工作区,导致 roster 与派发时不同 → 包字节不一致 → md5 校验失败。
        long runWorkspace = runs.get(0).get("workspace_id") instanceof Number w ? w.longValue() : ws();
        Object sdlc = runs.get(0).get("sdlc_id");
        Long sdlcId = sdlc == null ? null : ((Number) sdlc).longValue();
        Map<String, Object> ticket = jdbc.queryForMap(
                "SELECT title, body_md, sdlc_id FROM wi_ticket WHERE id=?", ticketId);
        Long effectiveSdlcId = sdlcId != null ? sdlcId : (Long) ticket.get("sdlc_id");
        List<Map<String, Object>> runsAgent = jdbc.queryForList(
                "SELECT agent_id FROM rt_run WHERE id=?", dispatchId);
        Long agentId = runsAgent.isEmpty() || runsAgent.get(0).get("agent_id") == null ? null
                : ((Number) runsAgent.get(0).get("agent_id")).longValue();
        Map<String, Object> agent = agentId == null
                ? Map.of("name", "AI 成员", "role_code", "FS_DEV", "role_name", "")
                : jdbc.queryForMap("SELECT name, role_code, role_name FROM ag_agent WHERE id=?", agentId);
        return capsuleBuilder.build(String.valueOf(ticket.get("title")), (String) ticket.get("body_md"),
                stepsOf(effectiveSdlcId), String.valueOf(agent.get("name")),
                (String) agent.get("role_code"), (String) agent.get("role_name"), roster(runWorkspace));
    }

    public String packageUrl(long dispatchId, int attempt) {
        return publicBaseUrl + "/api/daemon/dispatches/" + dispatchId + "/package?k=" + signer.sign(dispatchId + "." + attempt);
    }

    /** 产物回传地址(与包下载同一签名口径)。 */
    public String artifactUploadUrl(long dispatchId, int attempt) {
        return publicBaseUrl + "/api/daemon/dispatches/" + dispatchId + "/artifacts?k="
                + signer.sign(dispatchId + "." + attempt);
    }

    public boolean verifyPackageKey(long dispatchId, int attempt, String key) {
        return signer.verify(dispatchId + "." + attempt, key);
    }

    private List<Map<String, Object>> stepsOf(Long sdlcId) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (sdlcId != null) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, seq, name, instruction FROM sq_sdlc_step WHERE sdlc_id=? ORDER BY seq", sdlcId)) {
                steps.add(step(row.get("id"), row.get("seq"), row.get("name"), row.get("instruction")));
            }
        }
        if (steps.isEmpty()) {
            steps.add(step(-1, 1, "分析与分支准备", "阅读工单与澄清材料,确认需求边界后开始交付"));
            steps.add(step(-2, 2, "编码与测试", "实现需求并补充测试"));
            steps.add(step(-3, 3, "验证与交付", "自测并整理交付产物"));
            steps.add(step(-4, 4, "交接评审", "输出交接说明与评审材料"));
        }
        return steps;
    }

    private static Map<String, Object> step(Object id, Object seq, Object name, Object instruction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepId", String.valueOf(id));
        m.put("stepOrder", seq == null ? 1 : ((Number) seq).intValue());
        m.put("name", name);
        m.put("goal", instruction == null ? "" : instruction);
        m.put("deliverables", List.of());
        m.put("doneGate", "");
        return m;
    }

    /** 名册按指定工作区取:必须与派发时同一工作区,否则包体不可复现。 */
    private List<Map<String, Object>> roster(long workspaceId) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT a.id, a.name FROM ag_agent a WHERE a.workspace_id=" + workspaceId + " AND a.deleted_at IS NULL ORDER BY a.id LIMIT 20")) {
            members.add(Map.of("agentId", row.get("id"), "name", row.get("name")));
        }
        return members;
    }

    /**
     * 选一个真正在线的执行器。
     * DB 里的 ONLINE 可能是僵尸(运行器已退出),逐个用 hub 校验真实连接;
     * 没有真实连接时返回 null(run 落 PENDING,等待执行器上线后重放)。
     */
    private Long pickOnlineExecutor(long agentId) {
        List<Long> online = jdbc.queryForList(
                "SELECT id FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL ORDER BY id",
                Long.class, agentId);
        return online.stream().filter(hub::isOnline).findFirst().orElse(null);
    }

    private int maxAttempt(long ticketId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(attempt),0) FROM rt_run WHERE ticket_id=?", Integer.class, ticketId);
        return max == null ? 0 : max;
    }
}

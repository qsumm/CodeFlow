package com.ainative.v2.dispatch;

import com.ainative.v2.agent.Persona;
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

    /**
     * 工单正文进入任务包的字节上限(UTF-8)。
     *
     * <p>取值留足余量给运行节点侧的接收缓冲(4MB),同时拦住「正文长到没法派发」的工单,
     * 给出可读提示而不是让运行节点收到一句 1009 断连。
     */
    static final int MAX_WORKITEM_BODY_BYTES = 512 * 1024;

    /** 返回 dispatchId;无在线执行器时 run 置 PENDING 并返回 id。 */
    @Transactional
    public long start(long ticketId, long agentId, Long sdlcId) {
        return start(ticketId, agentId, sdlcId, ws());
    }

    /**
     * 显式指定工作区的派发入口。
     *
     * <p>后台触发(定时任务)没有请求上下文,RequestContext.workspaceId() 会回退到 1 ——
     * 工单和 run 就会挂到 1 号项目名下。这类场景必须由任务自己的 workspace_id 驱动。
     */
    @Transactional
    public long start(long ticketId, long agentId, Long sdlcId, long workspaceId) {
        Map<String, Object> ticket = jdbc.queryForMap(
                "SELECT title, body_md, sdlc_id FROM wi_ticket WHERE id=? AND deleted_at IS NULL", ticketId);
        Long effectiveSdlcId = sdlcId != null ? sdlcId : (Long) ticket.get("sdlc_id");
        List<Map<String, Object>> steps = stepsOf(effectiveSdlcId, agentId);
        Persona.Snapshot persona = personaOf(agentId);
        List<Map<String, Object>> roster = roster(workspaceId);
        // 快照在派发时冻结:人设/名册/工单/步骤任一项被改动,重建包体都会与派发时字节不同,
        // 运行器按帧里的 md5 校验下载就会失败。
        // 派发前置校验:任务包会原样带上工单正文。超限时运行节点只会收到一句
        // "message was too big" 的断连,现场无从判断是需求太长还是别的毛病。
        // 这里先拦住并给可读的提示,让需求方知道该往哪改。
        String bodyMd = (String) ticket.get("body_md");
        int bodyBytes = bodyMd == null ? 0 : bodyMd.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bodyBytes > MAX_WORKITEM_BODY_BYTES) {
            throw new com.ainative.v2.common.BizException("15030",
                    "工单正文过长（" + bodyBytes + " 字节），任务包放不下。上限 "
                            + MAX_WORKITEM_BODY_BYTES + " 字节。请把正文精简为执行要点"
                            + "（完整规格放产物区，并在正文里给出链接与要点摘要）后重试。");
        }
        DispatchSnapshot snapshot = new DispatchSnapshot(persona,
                String.valueOf(ticket.get("title")), bodyMd, steps, roster);
        CapsuleBuilder.Capsule capsule = capsuleBuilder.build(
                snapshot.ticketTitle(), snapshot.ticketBodyMd(), snapshot.steps(),
                snapshot.persona(), snapshot.roster());
        int attempt = maxAttempt(ticketId) + 1;
        // 先定执行器再落 run:rt_run.executor_id 必须等于真正收到指令的那个执行器,
        // 否则回写通道(备注鉴权按 rt_run.executor_id 关联执行器令牌)会取到错误的令牌。
        Long executorId = pickOnlineExecutor(agentId);
        long dispatchId = dispatchDao.insertRun(workspaceId, ticketId, agentId, executorId, effectiveSdlcId,
                attempt, snapshot.toJson());

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
                "SELECT ticket_id, sdlc_id, workspace_id, agent_id, dispatch_snapshot FROM rt_run WHERE id=?",
                dispatchId);
        if (runs.isEmpty()) {
            throw new BizException("14004", "派发不存在");
        }
        Map<String, Object> run = runs.get(0);
        // 有快照:只用快照重建,包体与派发时逐字节一致(帧里的 md5 才校验得过)。
        DispatchSnapshot snapshot = DispatchSnapshot.fromJson((String) run.get("dispatch_snapshot"));
        if (snapshot != null) {
            return capsuleBuilder.build(snapshot.ticketTitle(), snapshot.ticketBodyMd(), snapshot.steps(),
                    snapshot.persona(), snapshot.roster());
        }
        // 老派发(无快照):按实时数据重建,行为与改造前一致。
        long ticketId = ((Number) run.get("ticket_id")).longValue();
        // 重建必须以该 run 记录的工作区为准:下载端点是 daemon 通道(无 Bearer),
        // 请求上下文会回落到默认工作区,导致 roster 与派发时不同 → 包字节不一致 → md5 校验失败。
        long runWorkspace = run.get("workspace_id") instanceof Number w ? w.longValue() : ws();
        Object sdlc = run.get("sdlc_id");
        Long sdlcId = sdlc == null ? null : ((Number) sdlc).longValue();
        Map<String, Object> ticket = jdbc.queryForMap(
                "SELECT title, body_md, sdlc_id FROM wi_ticket WHERE id=?", ticketId);
        Long effectiveSdlcId = sdlcId != null ? sdlcId : (Long) ticket.get("sdlc_id");
        Long agentId = run.get("agent_id") == null ? null : ((Number) run.get("agent_id")).longValue();
        Persona.Snapshot persona = agentId == null
                ? Persona.snapshotOf("AI 成员", "FS_DEV", "", "", "")
                : personaOf(agentId);
        long stepAgentId = agentId == null ? -1L : agentId;
        return capsuleBuilder.build(String.valueOf(ticket.get("title")), (String) ticket.get("body_md"),
                stepsOf(effectiveSdlcId, stepAgentId), persona, roster(runWorkspace));
    }

    /** 读数字员工人设(SOUL.md/AGENT.md),装配成可下发的快照。 */
    private Persona.Snapshot personaOf(long agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, role_code, role_name, soul_md, agent_md FROM ag_agent WHERE id=? AND deleted_at IS NULL",
                agentId);
        if (rows.isEmpty()) {
            throw new BizException("17001", "智能体不存在");
        }
        Map<String, Object> a = rows.get(0);
        return Persona.snapshotOf((String) a.get("name"), (String) a.get("role_code"),
                (String) a.get("role_name"), (String) a.get("soul_md"), (String) a.get("agent_md"));
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

    /**
     * 取出本次派发该执行哪些步骤。
     *
     * <p>一次派发只跑**这个数字员工负责的步骤**,不是整条流程:SDLC 的多步是不同角色
     * 接力。此前把整条流程塞进任务包,运行节点会从第 1 步开始 —— 于是派给
     * 「前后端全栈开发」的任务,拿着开发人设去执行「需求澄清」,反复澄清到撞
     * 40 步上限(2026-11-26 实测:dispatch 4 failed,error_max_turns)。
     *
     * <p>取该 agent 绑定的步骤;没有绑定就取通用步骤(agent_id 为空);都没有就
     * 退化成单步「按工单执行」,让运行节点照工单干,而不是替别人做澄清。
     */
    private List<Map<String, Object>> stepsOf(Long sdlcId, long agentId) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (sdlcId != null) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, seq, name, instruction FROM sq_sdlc_step"
                            + " WHERE sdlc_id=? AND (agent_id=? OR agent_id IS NULL) ORDER BY seq",
                    sdlcId, agentId)) {
                steps.add(step(row.get("id"), row.get("seq"), row.get("name"), row.get("instruction")));
            }
        }
        if (steps.isEmpty()) {
            steps.add(step(-5L, 1, "按工单执行",
                    "按工单描述完成本次交付;需求边界见工单正文与规格,不替其它角色做澄清。"));
            return steps;
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
     *
     * <p>候选与澄清会话同一套语义:绑定该智能体的执行器优先,其后是共享节点池
     * (daemon 能替任意数字员工干活,dispatch 包自带 persona);并复用健康优先
     * 选法 —— 缺 ANTHROPIC 鉴权的旧 daemon 注册在低 id 上,按 id 升序会抢到任务
     * 然后回复 "Not logged in"。
     */
    private Long pickOnlineExecutor(long agentId) {
        List<Long> bound = jdbc.queryForList(
                "SELECT id FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL ORDER BY id",
                Long.class, agentId);
        List<Long> shared = jdbc.queryForList(
                "SELECT id FROM ag_executor WHERE status='ONLINE' AND deleted_at IS NULL ORDER BY id",
                Long.class);
        List<Long> candidates =
                com.ainative.v2.cv.ConversationTurnService.mergeExecutorCandidates(bound, shared);
        return com.ainative.v2.cv.ConversationTurnService.pickExecutor(
                candidates, hub::isOnline, hub::isHealthy);
    }

    private int maxAttempt(long ticketId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(attempt),0) FROM rt_run WHERE ticket_id=?", Integer.class, ticketId);
        return max == null ? 0 : max;
    }
}

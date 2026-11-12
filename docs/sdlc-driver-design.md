# 10 · SDLC 驱动器真实现设计(二次确认版)

> 状态:**已作废(方向改判)** —— 三次通读发现类头注释(上游工单 #55395):"Dispatch completion no longer drives SDLC routing",即恒停桩是上游**刻意架构决策**:SDLC 路由职责已移至执行器侧(TASK_HANDOFF / 平台 API 显式发起)。服务端补丁方案与上游架构相悖,予以废弃。正确实现位置:runtime 侧跨角色接棒(经平台 API,架构明文许可)——见修订 v2 与 docs/03 修订记录。 · 基线:`bd341f9` · 本文全部结论以源码行号为证,不引用注释理想语义
> 关联变更:03 号设计文档 §"多步执行"将被本文 §5 取代(见修订记录)

## 1. 目标

把 `SdlcDriver.onSuccess/onFail` 从恒停桩实现为协议 §7.3 的真实驱动:工单在步骤间自动推进、失败按预算重试、风险边界交还真人——**只改 `SdlcDriver.java` 一个文件**,其余全部复用既有管道。

## 2. 调用链(实测,行号为证)

```
onResult(success) :1609
  → sdlcDriver.onSuccess(workspaceId, workitemId, stepId) :1705
  → act(d, DriveResult) :1707/:1915
      ENQUEUE → enqueue(tenant, workitemId, nextStepId, nextAgentId, attempt=1, SYSTEM) + runPending
      RETRY   → retry(failed, maxAttempts) :1993
                  attempt+1 ≤ max → 同步重派(attempt+1)
                  attempt+1 >  max → sdlcDriver.onFail(...) 且【返回值被丢弃】= 静默停止
      STOP    → 无操作
onResult(fail) → failAndDrive :1288 → sdlcDriver.onFail :1292 → 同上 act
```

## 3. 语义决策(逐条给出依据)

| 决策点 | 结论 | 依据 |
|---|---|---|
| 步骤成功、`on_success` 为空 | **NEXT_STEP**(自动接棒) | `StepOutcome.parseSuccess`:null → NEXT_STEP |
| 步骤失败、`on_fail` 为空 | **HANDOFF_HUMAN**(交真人) | `parseFail`:null → HANDOFF_HUMAN;种子数据 4 步 on_fail 均为 NULL |
| `handler_type` 为 NULL(种子数据现状) | **沿用当前 agent 继续** | 现实数据无 handler 配置;§7.3 的按角色解析仅对 `AGENT` 类型启用 |
| `handler_type=AGENT` + `handler_role_ref` | 按工单 squad 成员的 roleCode 解析 agent;解析不到 → HANDOFF_HUMAN | §7.3;成员查询走既有 SquadMember 表 |
| RETRY 预算 | `maxAttempts` 取 `fail.maxAttempts`,缺省回落步骤 `retry_budget`,再缺省 1;**耗尽 = 停止待人工**(§2 的丢弃语义,不实现注释宣称的自动 human handoff) | retry():1993 实测行为 |
| `END` | 工单流转到 DONE 类首个节点(复用 `applyTransition` 模式:新鲜读 + 版本号 + SYSTEM actor);冲突仅告警不重试 | autoAdvanceOnDeliveryStart:679 同款模式 |
| 到达下一步时 | 不动 status_node(INIT→IN_PROGRESS 已由 onDeliveryStart:1116 处理;步间保持 IN_PROGRESS) | 既有行为不改 |
| HANDOFF_HUMAN 执行体 | **注入并调用 `HandoffService.handleHumanHandoff` 同款逻辑**(改 assignee_type=HUMAN + 写事件);通知复用其链路 | HandoffService:209 现成实现 |

## 4. SdlcDriver 实现规格(唯一改动文件)

```java
onSuccess(tenantId, workitemId, stepId):
  w = workitemDao.findById; s = stepDao.findById;      // 既有校验保留
  Success out = StepOutcome.parseSuccess(s.getOnSuccess());
  switch (out.action):
    NEXT_STEP → next = 按 stepOrder 在 stepDao.listBySdlc 中取 s 的后继;
                无后继 → 按 END 处理;
                agent  = resolveAgent(w, next);          // §3 决策
                agent 可用 → DriveResult.enqueue(next.id, agent.id)
                否则      → humanHandoff(w) → stop
    GOTO_STEP → 目标步存在 → 同上 enqueue;不存在 → END
    END       → transitionToDone(w); stop

onFail(tenantId, workitemId, stepId):
  Fail f = StepOutcome.parseFail(s.getOnFail());
  switch (f.action):
    RETRY        → max = f.maxAttempts ?: s.retryBudget ?: 1; return retry(max)
    HANDOFF_HUMAN→ humanHandoff(w); stop
    GOTO_STEP    → enqueue(目标步)
    END_FAIL     → stop(工单保持当前节点,人工介入)
```

**新增依赖注入**:`SdlcStepDao`(已有)、`SquadMemberDao`、`HandoffService`(注意构造环:HandoffService 不依赖 SdlcDriver,无环)。**零改动**:`DispatchService/act()/retry()/enqueue/WorkitemService` 及一切前端。

## 5. 连带变更:runtime 回退单步执行(必做,否则双跑)

上游语义 = 每步一个 dispatch(`TASK_DISPATCH` 包内 manifest.sdlcStepId;`act()` 按 nextStepId 逐棒 enqueue)。驱动器上线后,runtime 的"一次跑完整份 sdlc.json"会导致第 2 步起被驱动器重复派发。因此:

- `runtime/daemon.py::_run_dispatch` 回退为**只执行 `capsule.manifest.sdlcStepId` 对应步骤**(P0 原始行为);
- 保留多步能力为配置开关 `RUNTIME_FULL_SDLC=1`(仅用于无驱动器的旧服务端);
- `--resume` 会话链跨 dispatch 传递(第 2 步 dispatch 复用第 1 步 session,上下文接续)。

## 6. 测试计划

| 层 | 用例 |
|---|---|
| 单测(平台) | 驱动器四动作 × handler(NULL/AGENT 缺成员/AGENT 有成员/HUMAN);END→DONE 节点;预算耗尽停止;GOTO 目标缺失 |
| 集成(本地全栈) | 双角色小队(开发+评审):建单 → 自动推进 4 步 → 评审角色被自动派发 → END 后工单节点=DONE,**全程零人工** |
| 回归 | runtime 32 单测 + 09 号报告全部用例重跑;`RUNTIME_FULL_SDLC=1` 路径冒烟 |

## 7. 风险与上游同步

- 唯一补丁文件 = SdlcDriver.java;上游若发布真实现,以 `git diff` 对齐语义后二选一(采纳上游/保留补丁),UPSTREAM.md 记录;
- 行为变化对外可见:工单会"自己动起来"——通知、待决策分类等既有 UI 逻辑无需改(它们订阅 assignee/事件);

## 修订记录
- 2026-10-27 v1:二次确认稿。修正一次通读的两个偏差:①retry 预算耗尽为静默停止而非自动 human handoff;②种子步骤 handler 全 NULL,需定义 NULL→当前 agent 的默认语义。

## 修订记录(续)
- 2026-10-27 v2 **方向改判**:通读 SdlcDriver 类头 javadoc 后确认恒停为设计意图而非缺口;废弃服务端补丁方案。P0.5 重定义为「runtime 跨角色接棒」:多步执行保持(即上游 agent-internal SDLC 语义),末步成功后按 roster.json 经平台 reassign API 派给下一角色 agent;TASK_HANDOFF 帧继续上报(上游记录,Phase 2 将权威化)。docs/02 FR-6/AC-6、docs/05 DT-1~4 相应改判为「经平台 API 的编排」用例;docs/04 补丁清单条目作废。

## 修订记录(续)
- 2026-10-27 v3 **实测修正**:①平台为每个角色建**独立 SDLC**(开发 SDLC 4 步 / 代码评审 SDLC 3 步),角色边界由 SDLC 划分而非 handler_role_ref(种子数据全 NULL);②平台收到 TASK_HANDOFF(AGENT) 会**真实派发下一角色**的 dispatch(实测 dispatch 10012 自动落到评审 agent),协议文档"仅记录"的描述与社区版实现有出入;③我们的经 API 通道(assignee PUT)是**补充与兜底**(平台 handoff 为主路径),并已修正 405;④新增能力:终点自动收敛到 DONE 节点 + 失败自动交真人(roster 真人 userId)。DT-3/DT-4 已实测通过。

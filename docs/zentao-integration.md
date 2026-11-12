# 17 · 外部工单平台多源支持——禅道

> 目标平台:禅道 ZenTao(实测环境:**18.10** Docker,`localhost:8888`)
> 关联:docs/18 禅道闭环 E2E · 待办台账 BL-18
> 日期:2026-10-31(2026-11-03 更新为当前实现)

## 1. 背景与价值

**目标**:平台集成页支持绑定禅道产品,把禅道 Bug/需求自动拉取为本地工单,派发数字员工交付,交付结论回写禅道,形成"外部缺陷 → 数字员工处理 → 结果回写"闭环。

**价值**:禅道是国内研发团队覆盖率最高的开源项目管理工具(私有化部署为主)。打通后,交付闭环可直接接入团队的既有缺陷/需求流程。

**数据层基础**:绑定/映射存储天生 provider 无关——`external_project_binding.provider` 为通用字段,`external_workitem_link`/`external_workitem_import_record`/`external_status_mapping`/`external_comment_link`/`external_principal` 全部按 `(tenant, provider, external_id)` 建模。**接入新平台无需新建表、无需改执行器**。

## 2. 禅道 API 实测结论(全部实测,非文档推断)

实测环境:本地 ZenTao 18.10(官方 Docker 镜像 `hub.zentao.net/app/zentao:18.10`)。

| 项 | 实测结论 |
|---|---|
| 认证 | `POST /api.php/v1/tokens` 传 `{account,password}` → `{token}`;后续请求头 `Token: <token>`;**连续失败 3 次锁定**(需重启容器清 session 或等冷却) |
| 密码存储 | 普通无盐 md5(`zt_user.password`);集成专用账号可直接 DB 建号 + `zt_usergroup` 授组 |
| 产品 | `GET /products`(分页 `{page,total,limit,products[]}`,含 PO/QD 负责人对象) |
| Bug 列表 | `GET /products/{id}/bugs?page&limit` → `{page,total,limit,bugs[]}` |
| Bug 详情 | `GET /bugs/{id}` → 78 字段:`title/steps(HTML)/status/severity/pri/assignedTo{account}/openedBy/openedDate/resolution/...` |
| 需求列表/详情 | `GET /products/{id}/stories`、`GET /stories/{id}`(字段 `spec` 承载描述) |
| 回写 | `PUT /bugs/{id}`:状态流转(`status`+`resolution`)实测生效;**v1 PUT 字段白名单不含 `comment`/`spec`(备注被静默丢弃)——备注载体:bug 追加 `steps`、story 写 `keywords`(均白名单内,实测可写)**;lastEditedBy 显示为集成账号 |
| 状态机约束 | **非法转换被拒**(实测 `resolved→active` 直接 PUT 不生效,需走激活操作)→ 状态映射必须按禅道合法转换表设计 |
| 分页 | `page/limit` 参数;响应内含 `total` |

**禅道 Bug 状态机(合法转换)**:`active → resolved(resolution: fixed/notrepro/duplicate/...)/closed`;`resolved → closed`;`closed → active(激活)`;`resolved → active(激活,需 assignedTo)`。

> **实测补充(2026-11-04)**:v1 API 对 `closed` 的 PUT/POST 在部分部署上静默不生效(返回 200 带对象但状态不变)——平台回写语义收敛为 **修复→resolved**;`resolved → closed` 由禅道侧验证后人工关闭。

**版本要求**:v1 REST API 需禅道 ≥15;更老版本(仅 getModel 式接口)列入不支持。

## 3. 实现

### 3.1 Provider 抽象层

对外能力收敛为接口 `ExternalWorkitemProvider`:

```
testConnection(config)            连接与凭证校验
searchProjects(config, q)         托管项目搜索(禅道=产品列表)
listMembers(config, projectId)    项目成员
getWorkitem(config, externalId)   单个工单详情
searchProject(config, projectId, cursor, ...)  增量拉取(游标:lastEditedDate 水位)
applyTransition(config, externalWorkitemId, targetCategory)  状态回写(按合法转换表)
pushComment(config, externalWorkitemId, comment)             备注回写
```

- `ExternalPlatformConfig(baseUrl, clientKey, accessSecret, regionId)` 承载平台凭证;regionId 为可选字段,禅道不使用;
- 能力接口 `GET /api/integrations/capabilities` 返回 `{zentaoEnabled: true}`;
- 共享基础设施 provider 无关:`InboundPoller`(轮询)、`InboundSyncService`(入库/事件)、`OutboxService`/`OutboxDispatcher`(回写)、`WorkitemRefreshService`(手动刷新)。

### 3.2 禅道 Provider

| 项 | 设计 |
|---|---|
| 绑定表单 | baseUrl(如 `http://zentao:8888`)+ 集成账号/密码 + **产品选择**(调 searchProjects)= 禅道 product 绑定 |
| 凭证 | 账号密码经 SecretCrypto 加密存 `credential_ref`;token 运行时换取并缓存(401/过期自动重换) |
| 拉取 | `InboundPoller` 按绑定轮询 `GET /products/{pid}/bugs` 与 `/stories`,增量水位 = `lastEditedDate`;映射:bug.id→external_workitem_id、title→工单标题、steps/spec(HTML→Markdown)→contentMd、severity/pri→本地优先级、assignedTo→external_principal |
| 状态映射 | 默认:`active→待处理(新建/激活)`、`resolved→待验证`、`closed→已完成`;经 `external_status_mapping` 绑定级可配 |
| 回写 | 本地工单流转到映射的完成类节点时 → `PUT /bugs/{id}` 置 `resolved`(含 resolution=fixed),备注写入 steps/keywords;交付报告经备注通道回写,幂等 marker 防重复。**closed 由禅道侧验证后人工关闭**(v1 API 无法静默 close,且更符合"关闭前需人工验证"的团队流程) |
| 身份 | external_principal 记禅道账号,展示名 realname |

### 3.3 范围

**已实现**:Bug 与需求(Story)对象;入站拉取导入(含变更与评论增量);状态回写(按合法转换);备注/交付结论回写;绑定/测试连接/产品搜索;平台集成页禅道页签;手动同步。

**未做(登记后续)**:Task 对象;本地编辑内容反向覆盖禅道标题/描述;Webhook(轮询足够);禅道云(SaaS)形态验证;状态映射 UI 编辑器(第一版用默认映射+DB 配置)。

### 3.4 联调环境

本地 18.10 容器 + 集成专用账号;CI/自动化测试用 mock。端到端闭环已在本仓库集成页提供验证入口。

## 4. 验收标准

1. 平台集成页出现"禅道"平台选择,建绑定需 baseUrl+账号+产品,测试连接通过;
2. 绑定后自动拉取所选产品的 Bug/需求为本地工单(标题/描述/优先级/人员映射正确),重复拉取不产生重复工单(V089 唯一约束生效);
3. 本地工单流转到完成类节点 → 禅道对应对象按合法转换变更,备注含交付说明;
4. 共享基础设施(轮询/同步/回写/刷新)回归通过;
5. 后端单测(含禅道 provider,用打桩禅道 API)+ 前端全量 + runtime 全量不回退;
6. credentials 不入库不入日志(SecretCrypto + 脱敏)。

## 5. 变更记录

- 2026-10-31:初版,S0 禅道 API 实测(认证/产品/Bug/回写/状态机五项全部实测)。
- 2026-11-03:按当前实现更新为禅道单平台接入;闭环 E2E 结论见 docs/18。

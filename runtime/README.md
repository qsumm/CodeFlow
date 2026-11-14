# CodeFlow 执行器运行时(ainative-runtime)

数字员工的执行端:通过 WebSocket 连上 CodeFlow 平台,接收派发任务,驱动你自己的
Agent CLI(Claude Code / Codex / Cursor 等),把产物与执行结论回传平台。

- **纯标准库**:运行时零第三方依赖(Python ≥ 3.11)
- **协议**:`ainative.taskPackage.v1` 任务包;帧协议见平台文档 `platform/ainative/docs/`
- **多源工单**:支持从禅道 / TAPD 拉取需求,交付备注回写源头

## 安装

```bash
pip install ainative-runtime
```

或从 GitHub Release 直接装:

```bash
pip install https://github.com/qsumm/CodeFlow/releases/download/runtime-v0.3.33/ainative_runtime-0.3.33-py3-none-any.whl
```

## 启动

平台「执行器」页新建执行器后,点「启动命令」复制,替换 `<平台地址>` 与 `<令牌>`:

```bash
ainative connect \
  --ws-url ws://<平台地址>/ws/executor \
  --token <令牌> \
  --executor-id <执行器ID> \
  --provider claude \
  --memory-mode provider-local \
  --max-tasks 2
```

常用参数:

| 参数 | 说明 |
|---|---|
| `--ws-url` | 平台 WebSocket 地址 |
| `--token` | 平台签发的执行器令牌(页面「启动命令」一键复制) |
| `--executor-id` | 执行器 ID |
| `--provider` | `claude` / `codex` / `cursor` / `mock` |
| `--max-tasks` | 并发任务数 |
| `--once-smoke` | 连一次并发心跳后退出(自检用) |
| `--check-config` | 只校验配置 |

## 环境变量

| 变量 | 说明 |
|---|---|
| `AINATIVE_API_BASE_URL` | 平台 API 基址(默认 `http://localhost:7001`) |
| `AINATIVE_DEBUG_DIR` | 失败调试日志目录(默认 `~/.ainative/debug`) |
| `RUNTIME_DATA_DIR` | 运行数据目录(默认 `./runtime-data`) |
| `ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_MODEL` | 模型端点(任意 Anthropic 兼容端点) |
| `CLAUDE_CONFIG_DIR` | Claude Code 登录态目录(默认自动指向 `~/.claude`) |

## 许可

Apache-2.0(与 CodeFlow 主仓一致)。

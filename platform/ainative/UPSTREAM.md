# 源码基线与许可声明

本目录(`platform/ainative/`)是 AINative 平台的服务端与前端完整源码,用于"克隆即全栈部署"。

- **许可证**:MIT。许可副本见本目录 `LICENSE`(含原始版权声明,随分发保留)。
- **构建产物**:`target/`、`logs/` 不随库分发;`frontend/dist/` 为部署所需的预构建产物。
- **代码状态**:平台业务(工单、小队、SDLC、执行器调度、外部工单集成、MCP、帮助中心)均在此目录内实现与维护。

## 再分发义务

1. MIT 许可文本(`LICENSE`)必须随分发保留;
2. 修改后的源码可自由分发,无需公开修改内容;
3. 平台对外协议(WebSocket 帧、任务包 schema、MCP 工具)以本目录实现为准,文档见 `docs/`。

## 实现约定

- 服务端包名 `com.ainative.*`,配置前缀 `ainative.*`,环境变量前缀 `AINATIVE_`;
- 任务包 `schemaVersion=ainative.taskPackage.v1`,MCP 服务器名 `ainative`;
- 前端主题令牌与文案集中在 `frontend/src/shared/theme/` 与 `frontend/src/shared/i18n/`。

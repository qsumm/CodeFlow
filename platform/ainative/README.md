# AINative 平台

AI Native 软件需求交付平台的服务端与前端源码:需求接入、数字员工小队、SDLC 流水线、执行器调度、外部工单平台集成。

- 构建与部署见仓库根 `README.md`,以及本目录 [`UPSTREAM.md`](UPSTREAM.md)(上游基线与许可)
- 运行配置见 [`docs/community/runtime-configuration.md`](docs/community/runtime-configuration.md)
- 数据库结构见 [`docs/ainative-schema.sql`](docs/ainative-schema.sql)

技术栈:Spring Boot 2.7 + MyBatis + MySQL 8 + Redis + MinIO/S3;React + Vite 前端。

## 本地构建

```bash
cd platform
bash build.sh          # 构建镜像(源码 Maven 构建)
bash bootstrap.sh      # 起依赖 + 应用 + 初始化桶
```

## 目录

- `src/main/java/com/ainative/` —— 服务端
- `src/main/resources/mapping/` —— MyBatis mapper
- `frontend/` —— 前端源码与其预构建产物 `frontend/dist/`
- `docs/` —— 平台运行、运维与集成文档

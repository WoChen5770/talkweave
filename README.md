# Talkweave

Java 21 / Spring Boot 单应用容器微信助手：外部 MySQL、可选 Redis 历史缓存、独立用户、动态时间上下文与真实用量统计。

> `main` 推送通过构建、测试和双架构离线镜像检查后，自动正式发布 `ghcr.io/wochen5770/talkweave:latest`；`v*` 标签发布对应版本。NAS 验收可在部署后继续，不再阻断镜像发布，但发布不代表完整运行验收。新版本不迁移 SQLite、不删除旧数据；MySQL V001→V002 是追加式布局升级，不能与旧 SQLite V1→V2 混淆。双架构外部服务运行、独立 CI 外部集成、真实账号及备份恢复仍待验。

- [NAS 初始化、权限、备份与回滚](docs/mysql-redis-operations.md)
- [构建、外部集成与 CI 发布流程](docs/mysql-redis-development.md)
- [两账号协议验证](docs/multi-user-protocol.md)
- [本轮交付结果与未完成项](openspec/changes/adopt-mysql-redis-conversation-runtime/implementation-status.md)

构建：`mvn verify`（Java 21）；前端逻辑回归：`node --test src/test/js/*.test.cjs`。
默认镜像仅支持 managed；必须配置外部 MySQL，缓存启用时还需 Redis。连接模板为 `config.external-services.example.yml`，模型 Key 在受认证页面设置。Compose 仅发布回环 8680，健康端口不公开。

NAS 默认值：MySQL SSL `DISABLED`、Redis TLS `false`、镜像运行用户 `0:0`，自定义 Compose 可省略这三项；连接地址、专用库和账号仍需填写。仅适用于可信受限内网，显式 TLS 和非 root 用户可覆盖默认值。支持直接通过 `environment` 配置连接，不必额外挂载 YAML；见 [精简环境变量配置](docs/mysql-redis-operations.md#精简环境变量配置)。这些默认值需使用包含本次改动的新镜像，旧镜像不会自动改变。

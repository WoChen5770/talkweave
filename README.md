# Talkweave

Java 21 / Spring Boot 单容器微信助手管理端：独立用户、会话空闲超时、兼容模型配置与真实用量统计。

> 当前为待完整验收版本。已依据腾讯上游 2.4.9 调用链及双账号/重扫实测接入自动绑定，未知协议或节点仍拒绝授权。多用户 V1 数据库启动时升级至 V2，升级前须停机完整备份；旧单用户数据仍需使用独立目录。完整管理端与 NAS 验收尚未完成。

- [NAS 初始化、管理入口、备份与回滚](docs/operations.md)
- [构建与 GHCR 发布流程](docs/github-actions.md)
- [两账号协议验证](docs/multi-user-protocol.md)
- [开发与合成测试](docs/managed-development.md)
- [本轮交付结果与未完成项](docs/managed-delivery-status.md)

构建：`mvn verify`（Java 21）；前端逻辑回归：`node --test src/test/js/*.test.cjs`。
默认镜像启动管理端，不要求外部模型配置文件；模型 Key 在受认证页面中设置。Compose 默认仅发布回环 8680，健康端口不公开。

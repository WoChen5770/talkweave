# Talkweave

Java 21 / Spring Boot 单容器微信助手管理端：独立用户、会话空闲超时、兼容模型配置与真实用量统计。

> 当前为待验收版本。真实微信扫码身份映射尚未验证，自动授权保持关闭；请使用新目录部署，不替换原实例。合成测试不代表微信或 NAS 验收通过。

- [NAS 初始化、管理入口、备份与回滚](docs/operations.md)
- [构建与 GHCR 发布流程](docs/github-actions.md)
- [两账号协议验证](docs/multi-user-protocol.md)
- [开发与合成测试](docs/managed-development.md)
- [本轮交付结果与未完成项](docs/managed-delivery-status.md)

构建：`mvn verify`（Java 21）；前端逻辑回归：`node --test src/test/js/*.test.cjs`。
默认镜像启动管理端，不要求外部模型配置文件；模型 Key 在受认证页面中设置。Compose 默认仅发布回环 8080，健康端口不公开。

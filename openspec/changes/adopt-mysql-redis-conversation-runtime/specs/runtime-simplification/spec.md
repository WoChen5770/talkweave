## Purpose

在新的 MySQL 与 Redis 运行路径接管后，去除已退役的 SQLite 和单用户部署能力，减少正式包与维护流程中的历史负担。精简以真实运行职责和行为覆盖为依据，不把删除安全回归测试或保留隐蔽旧授权入口作为降低复杂度的手段。

## ADDED Requirements

### Requirement: One supported production runtime and storage path
正式发布包 MUST 仅支持当前多用户 MySQL 业务运行路径，不包含可启用旧文件授权和 SQLite 业务持久化的兼容入口，也不携带已无使用方的 SQLite 驱动与专用运行资产。旧部署配置 MUST 不触发隐式回退；仍被多用户路径依赖的微信协议、文件安全、健康检查与模型能力 MUST 保留。

#### Scenario: Attempt to use legacy configuration
- **WHEN** 操作者仅提供旧单用户配置或试图通过 profile 恢复旧链路
- **THEN** 无法绕过当前管理授权启动旧服务，缺失新依赖时给出明确配置错误，不打开旧数据库

#### Scenario: Inspect the production artifact
- **WHEN** 检查正式 JAR 与镜像
- **THEN** 没有旧 SQLite 业务实现、驱动和旧登录运行入口，当前健康检查和必要私有材料能力仍可工作

### Requirement: Diagnostic tools are explicit nonproduction artifacts
真实微信/模型联调、双账号观察、容器验收探针和合成浏览器夹具 MUST 不作为正式业务 JAR 的附带入口。仍需要的诊断工具 SHALL 有独立且可重现的构建/运行方式，并保留真实网络调用的显式授权边界；容器验收 MUST 针对实际发布的应用产物而非另一套替代实现。

#### Scenario: Run CI or an operator diagnostic
- **WHEN** CI 执行容器验收，或操作者显式运行独立诊断工具
- **THEN** 工具可按文档获得并作用于目标应用，生产默认启动不加载工具，自动化不访问真实微信或付费模型

### Requirement: Retire obsolete contracts without reducing active safety coverage
测试清理 MUST 区分退役契约与仍有效行为。旧单用户/SQLite 专属契约可随功能退役移除；认证、身份隔离、去重、未知投递、会话边界、用量、私有材料和页面竞态保障 MUST 在新架构下继续有可执行覆盖，新增 MySQL/Redis 真实引擎的集成与故障测试。交付 SHALL 提供删除、保留及替代的覆盖映射，不以减少测试数量作为完成标准。

#### Scenario: Replace SQLite-specific tests
- **WHEN** 删除旧数据库实现和相关测试
- **THEN** 仅验证旧实现细节的测试退出，仍有效的事务、唯一性、恢复和隔离场景在 MySQL 下重新验证，不能只靠内存假实现宣称通过

#### Scenario: Review cleanup and documentation
- **WHEN** 完成本次精简并更新部署/开发说明
- **THEN** 活跃文档与构建命令不再指向失效旧入口，历史资料明确标注适用旧版本，覆盖清单保留尚未实际验收的项目

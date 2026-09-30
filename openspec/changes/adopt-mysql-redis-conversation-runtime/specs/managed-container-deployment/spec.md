## Purpose

让一个应用容器复用 NAS 已有的 MySQL 和 Redis，提供清晰的新安装、外部连接配置、共享服务隔离和备份恢复边界。应用不再依赖本地 SQLite 保存业务，但仍须保护二维码材料、管理入口和秘密，并诚实区分合成验证与真实 NAS 验收。

## ADDED Requirements

### Requirement: One application container using existing external services
正式部署示例 SHALL 只定义应用容器，连接操作者提供的 MySQL 与 Redis，不自动部署、升级或重配置 NAS 数据服务。MySQL 连接为必需；正常配置启用 Redis 历史缓存，并允许显式关闭以便诊断。配置 MUST 覆盖连接端点、认证、安全传输选项、专用 schema、缓存命名空间及超时，缺少必需配置或参数非法时 MUST 提前失败并隐藏秘密。

本 change 的实施与集成测试 SHALL 同样通过显式配置连接已有外部服务，MUST 不以拉取或部署独立 MySQL/Redis 镜像作为前提。操作者明确选择不启用 SSL 时 SHALL 遵循该配置；TLS 失败时 MUST 不自动降级。

#### Scenario: Reuse existing NAS services
- **WHEN** 操作者配置现有服务端点与专用资源
- **THEN** 应用及显式启用的集成测试直接使用授权范围内的外部服务，不新增数据库容器或要求拉取数据库镜像，不要求宿主机全局改时区或升级服务，不对外发布 MySQL/Redis 端口

#### Scenario: Missing endpoint versus temporarily unavailable Redis
- **WHEN** MySQL 配置缺失、缓存启用但 Redis 配置缺失，或配置完整的 Redis 暂时不可达
- **THEN** 配置缺失明确拒绝启动；运行连接故障按缓存降级规则处理，不泄露连接密码

### Requirement: Shared infrastructure and secret isolation
MySQL 操作 MUST 限于本应用已识别的专用 schema，Redis 操作 MUST 限于授权的项目/安装键范围；应用 MUST 不清空共享实例、不修改全局内存淘汰策略或其他应用数据。Redis 中聊天副本 MUST 使用受限账号权限与过期策略，数据库与 Redis 密码 MUST 不出现在日志、管理 API、仓库或测试报告中。

外部集成测试 MUST 显式指定并验证已授权的项目专用库及合成缓存子前缀，不得把真实业务数据当作可重置夹具。故障测试 SHALL 使用应用侧测试自有连接中止、本机代理断连/延迟/超时或合成回调控制，MUST 不停止、重启共享服务、终止其他连接、修改全局设置或清库。

#### Scenario: Other applications share Redis and MySQL
- **WHEN** 本应用启动、清理到期材料、处理缓存故障或重新初始化
- **THEN** 其他 schema 和键保持不变，不执行全库清理或服务全局配置修改，越权被拒绝

#### Scenario: Authentication or certificate error
- **WHEN** 外部服务拒绝认证或配置的证书校验失败
- **THEN** 返回脱敏可操作诊断，不自动关闭证书校验、不打印密码或完整敏感连接串

### Requirement: Fresh bootstrap and private local materials
全新业务 schema MUST 要求显式初始管理员凭据，已初始化 schema MUST 不被残留引导变量重置。模型配置为空时管理端 MUST 可用于配置；微信用户须重新开通。私有本地目录 SHALL 仅承担仍需落盘的临时二维码等材料，不用来隐式恢复旧业务，旧 SQLite 和登录文件 MUST 保持不变。

#### Scenario: First start and subsequent restart
- **WHEN** 新 schema 首次启动，随后移除引导密码并重启
- **THEN** 首次无有效管理员秘密时拒绝开放管理；已初始化后原管理员和 MySQL 业务保留，重启不要求重新创建所有账号

#### Scenario: Restricted runtime identity
- **WHEN** 操作者使用现有 Compose 默认 `0:0` 或覆盖为拥有材料目录权限的非 root UID/GID
- **THEN** 私有材料仍受限，权限不足明确失败，不使用 `777`、特权模式或递归更改宿主机目录权限

### Requirement: Restricted ingress and bounded lifecycle
系统 MUST 保留受认证、CSRF/来源保护的管理入口，默认仅发布回环管理端口 8680，健康端口保持内部使用。活性与依赖就绪 MUST 区分：MySQL 不可用影响业务就绪，单独 Redis 故障为可降级状态。默认完整关闭预算 SHALL 不超过 8 秒，停止期间不得无限等待 Redis 或重放未知调用。

#### Scenario: Redis fails but MySQL is available
- **WHEN** Redis 故障且数据库、运行权和其他全局条件正常
- **THEN** 应用保持可用的数据库路径并显示缓存退化，不把整个进程判死或强制要求用户重扫码

#### Scenario: Stop with in-flight remote work
- **WHEN** 容器在模型调用或 Redis 超时期间正常停止
- **THEN** 停止新增调度、有限取消与记录后在关闭预算内退出；无法确认结果的工作不被当作成功，异常收尾如实报告

### Requirement: Versioned verification and safe backup recovery
交付 MUST 记录实际验证的外部 MySQL、Redis、驱动和应用镜像版本及执行环境，不以“最新版”替代兼容证据。备份说明 MUST 以本应用 MySQL schema 和必要配置为中心，Redis 缓存可丢弃并在恢复后隔离重建。旧镜像回退 MUST 使用其原独立数据，不让旧程序打开新布局；自动化 MUST 仅使用显式授权的外部专用资源和合成数据，不默认读取操作者 NAS/生产配置。已有授权范围内的建表与合成数据/缓存读写可继续执行，真实微信、付费模型及覆盖式恢复仍需独立授权。

无法在现有资源上安全执行的恢复或布局故障验收 MUST 保留待验并标明原因，不得用模拟冒充实际恢复或将未执行任务勾选完成；这些待验项 SHALL 不阻断其余可安全推进的实现。单元测试 MUST 不要求外部连接；CI 外部集成 MUST 显式提供已授权配置，未配置或未运行不能计为通过。

按操作者选择，CI SHALL 在工作流/脚本校验、Java/Node 测试、amd64/arm64 构建与离线产物/材料检查全部通过后，正式发布允许 ref 的同次已检查镜像。默认分支推送 SHALL 更新 `latest` 和提交标签，`v*` 标签推送 SHALL 发布相应版本和提交标签；PR/其他分支 MUST 不发布，手动运行 SHALL 仅在允许 ref 且显式请求发布时发布。外部服务及 NAS 运行验收 SHALL 独立记录、可在部署后继续，不作为发布前置条件；发布 MUST 不冒充完整部署验收，必需构建/检查失败 MUST 阻断发布。

#### Scenario: Restore a new deployment
- **WHEN** 获得对应授权并具备安全恢复目标，停止唯一应用实例后备份本应用 schema，并在兼容环境恢复
- **THEN** 账号、绑定、会话和用量保持归属，较新 Redis 内容不污染恢复结果，失效微信凭据仍需重新认证；不停止共享数据库服务，未满足安全执行条件时该恢复场景保持待验

#### Scenario: Publish evidence for both target architectures
- **WHEN** 执行 amd64/arm64 构建、容器、外部服务集成和浏览器验证
- **THEN** 分别记录通过、失败、未运行及原生/仿真环境；允许 ref 的必需构建/检查全通过后可正式发布同次镜像，即使外部/NAS 验收仍待验；外部连接秘密仅由显式受保护配置提供，不进入镜像或报告，不把发布、合成或单架构结果标为完整部署验收，也不要求单独数据库镜像

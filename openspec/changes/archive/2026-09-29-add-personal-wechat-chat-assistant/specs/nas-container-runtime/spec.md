## Purpose

使个人助手能够在 NAS 的单实例容器环境中无人值守启动与恢复，同时为需要人工参与的扫码登录提供安全的本地运维路径。定义持久化、凭据保护、运行状态、关闭以及备份恢复要求，避免把容器存活误认为微信和模型始终可用。

## ADDED Requirements

### Requirement: Single-instance outbound-only deployment
系统 SHALL 提供面向 `linux/amd64`（x86-64）和 `linux/arm64` 的可复现容器构建及单实例 Compose 部署说明，首版不包含 32 位平台。正常微信收发和模型调用不要求公网入站端口。系统 SHALL 以非 root 身份运行，通过预配置启动而不等待交互输入。交付文档 MUST 对两个目标平台分别记录构建支持、实际镜像构建、Java/SQLite 运行测试和基础镜像；仿真结果 MUST 注明环境，不将未测试架构或未执行的 NAS 实机检查标为已验证可用。具体 NAS 型号、本机 Docker 状态与实机验收 SHALL 不作为业务实现的前置条件。镜像构建验证 SHALL 由 GitHub Actions 执行，NAS 部署及实机验收由操作者负责；两类验证结果分开记录。

#### Scenario: Two target architectures have independent verification results
- **WHEN** 操作者按构建说明为 `linux/amd64` 和 `linux/arm64` 构建容器镜像
- **THEN** 两者使用同一套应用与部署接口、各自匹配的运行时及 SQLite 原生库，并分别记录构建和运行验证结果；未实测的平台标为未验证，不以另一架构成功或仅生成镜像代替运行测试
#### Scenario: Unattended NAS restart
- **WHEN** NAS 重启并按部署策略启动容器
- **THEN** 应用从挂载配置和持久状态启动；已具备有效登录及身份绑定时自动恢复服务，否则进入可诊断的等待状态而非卡在标准输入

#### Scenario: A second instance uses the same data directory
- **WHEN** 第二个实例尝试并发使用正在被占用的数据目录
- **THEN** 系统拒绝第二实例启动，避免双重轮询和并发执行同一会话

### Requirement: Verified CI image publication
系统 SHALL 提供 GitHub Actions 工作流，运行离线业务测试，分别构建并检查 linux/amd64、linux/arm64 镜像的 Java/SQLite 与容器重建持久数据行为。默认分支和版本标签通过两架构检查后 SHALL 使用短期 GITHUB_TOKEN 发布已测试镜像到 GHCR。PR MUST 只验证不发布，不使用真实微信或模型秘密；构建任务与发布写权限 MUST 分离。工作流文件已配置、CI 已执行通过和 NAS 已验收 MUST 明确区分。

#### Scenario: Pull request verifies without publication credentials
- **WHEN** GitHub Actions 为 pull request 执行构建和合成数据测试
- **THEN** 构建任务仅有读取权限，不登录镜像仓库、不发布镜像，不获取微信凭据或模型 API Key

#### Scenario: Publish only the two verified image artifacts
- **WHEN** 默认分支或版本标签触发发布，但任一目标架构尚未通过镜像内 Java/SQLite 与容器重建检查
- **THEN** 系统不发布该次运行的正式多架构标签；两者均成功后仅发布已验证的镜像制品，并保存两种架构的检查和摘要证据

### Requirement: Persistent data and explicit configuration boundary
系统 SHALL 将会话、消息处理记录、机器人凭据及轮询游标存入容器外持久挂载目录，配置与密钥 SHALL 通过环境变量或受保护的只读配置挂载提供，不写入镜像或版本控制。必需目录不可写、数据库不可用或关键配置无效时 MUST 在开始消费消息前失败并报告脱敏原因。

#### Scenario: Container replacement
- **WHEN** 操作者使用相同挂载目录替换或重建容器
- **THEN** 已持久化的聊天记录、当前会话及可用连接状态保持可恢复，而不是随旧容器删除

#### Scenario: Data mount is unavailable
- **WHEN** 持久目录不可写或数据库无法打开
- **THEN** 应用不开始收消息，不悄悄改用容器临时目录或内存保存关键状态

### Requirement: Protected local login operations
系统 SHALL 在仅操作者可访问的本地目录提供当前二维码、登录阶段和挑战标识，并提供与当前挑战绑定的配对码提交方式。过期或完成登录后 MUST 清理临时二维码及一次性配对码，不接受过期挑战；普通日志 MUST 不包含二维码内容、完整 token、API Key 或配对码。

#### Scenario: Operator completes login without a terminal prompt
- **WHEN** 操作者通过 NAS 文件管理读取当前二维码，并为指定挑战提交配对码
- **THEN** 应用无需重启或公开管理端口即可继续登录，并在完成或过期后清理敏感临时材料

#### Scenario: Stale verification code is submitted
- **WHEN** 本地提交的配对码对应已过期或不同的挑战标识
- **THEN** 系统拒绝该提交，不将旧配对码发送给新登录挑战

### Requirement: Separate liveness and dependency readiness
系统 SHALL 提供进程存活与对话就绪的独立状态，能够区分等待登录、等待所有者绑定、微信重连、模型降级和可对话状态。等待扫码或外部服务故障 MUST 不单独作为容器反复重启的理由；状态输出 MUST 不泄露敏感数据。

#### Scenario: Waiting for a user scan
- **WHEN** 应用正常运行但尚未完成扫码登录
- **THEN** 存活检查通过，对话状态显示未就绪及等待原因，容器不会仅因该原因进入自动重启循环

### Requirement: Safe shutdown and recovery diagnostics
系统 SHALL 在停止时停止接收新工作，在配置的宽限期内结束或取消进行中的操作并持久记录状态；超出宽限期的外部调用 MUST 按不确定结果处理，不伪装为成功。系统 SHALL 提供脱敏关联标识、操作类别、耗时、错误类型和未完成轮次状态，不默认记录聊天正文。

#### Scenario: Stop during a model call
- **WHEN** 容器收到停止信号且模型调用未在宽限期内结束
- **THEN** 系统结束进程并留下可恢复的中断记录，重启后按对话恢复规则处理，不盲目重放外部调用

### Requirement: Consistent backup and restore procedure
交付文档 MUST 给出基于 NAS 本地磁盘持久目录的一致性备份、恢复和版本回滚步骤，明确停止写入后备份或使用数据库支持的备份方式，禁止将运行中仅复制单个数据库文件描述为可靠备份。备份 SHALL 被当作包含聊天隐私和凭据的敏感数据管理。

#### Scenario: Restore a stopped-instance backup
- **WHEN** 操作者按文档停止应用、备份完整所需数据并在兼容版本中恢复
- **THEN** 当前会话、聊天记录及处理状态可恢复；已失效的微信凭据仍需重新扫码，不承诺备份能延长凭据有效期

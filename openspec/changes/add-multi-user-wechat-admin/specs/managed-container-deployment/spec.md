## Purpose

保持 NAS 上一个容器即可部署的使用方式，同时为新增管理页面、管理员初始化和多用户持久数据提供明确的安全边界。新版本不迁移或破坏既有单用户数据，并保留可验证的重启恢复、备份回滚、运行身份选择和两种目标架构支持。

## ADDED Requirements

### Requirement: Single container with restricted management ingress
系统 SHALL 将管理页面与后端打包在同一容器，通过一个管理端口访问，不依赖独立前端服务、Redis 或消息队列。微信连接仍主动出站；健康检查 MUST 保持容器内部可用且不作为公开管理接口。默认部署 MUST 不将管理端口开放到所有宿主机网络接口，文档 SHALL 说明如何绑定指定内网地址或使用受保护入口。

#### Scenario: Deploy with the documented Compose file
- **WHEN** 操作者使用新版本示例部署
- **THEN** 一个应用容器提供管理页面、后台连接和持久存储，默认仅受限地址可访问管理端口，微信不需要公网回调端口

#### Scenario: Access through a protected remote entry
- **WHEN** 操作者需要从另一台设备访问管理页面
- **THEN** 文档明确要求配置受限内网地址及受保护传输入口，不把未认证端口或明文公网暴露作为默认步骤

### Requirement: Explicit administrator bootstrap
首次使用全新数据目录时系统 MUST 要求操作者设置初始管理员凭据，不内置公共默认密码、不在日志中输出密码。管理员已经初始化后，后续启动 MUST 不因残留初始化参数重置密码或新增管理员。缺少模型配置 MUST 不妨碍完成管理员初始化与配置页面访问。

#### Scenario: First installation has no administrator secret
- **WHEN** 新数据目录启动但没有提供初始化所需管理员凭据
- **THEN** 系统明确报告初始化要求，不自动开启无认证管理权限，不自动生成公开默认账号密码

#### Scenario: Restart after initialization
- **WHEN** 已有管理员持久状态并重启容器
- **THEN** 原账号状态被保留，初始化环境变量不能覆盖原密码，管理会话重新登录不影响微信连接持久状态

### Requirement: Fresh data without legacy destruction
新版本 SHALL 使用独立的新多用户数据布局，并提供新的宿主机数据目录示例。不要求迁移旧绑定或聊天；若发现旧版单用户数据库或不兼容版本，系统 MUST 拒绝就地初始化、覆盖、删除或隐式迁移，并说明使用新目录。旧文件 MUST 保持不变。

#### Scenario: Existing single-user directory is mounted
- **WHEN** 新版本被误配置到包含旧单用户数据库的数据目录
- **THEN** 启动拒绝对该布局写入，返回可操作的错误说明，不删除数据库、登录材料或配置来继续启动

#### Scenario: Fresh independent installation
- **WHEN** 操作者选择新的空数据目录
- **THEN** 系统创建独立管理与多用户状态，要求重新开通用户，不访问或修改原部署目录

#### Scenario: Upgrade a recognized managed database
- **WHEN** 操作者备份并升级已识别的多用户 V1 数据库
- **THEN** 系统在启动运行面前以独占事务迁移至 V2，保留管理员、用户、绑定、事件原始机器人身份、会话和用量；迁移失败回滚结构及版本，未知或旧单用户布局仍拒绝写入；降级要求恢复升级前备份

### Requirement: Configurable runtime identity with private state
本版 Compose 默认 SHALL 显式使用 UID/GID `0:0`，并允许操作者覆盖。系统 MUST 仍限制数据目录、数据库及敏感状态访问，不使用 `777`、特权容器或 Docker socket 挂载作为权限修复。文档 MUST 说明 root 的风险、文件所有权影响及其不能绕过只读挂载、NAS ACL 或网络文件系统限制。

#### Scenario: Root and non-root deployments
- **WHEN** 操作者使用默认 `0:0` 或配置另一个拥有数据目录访问及权限设置能力的 UID/GID
- **THEN** 应用可在对应身份下运行，状态保留私有权限；文件权限不满足时明确失败而不是自动扩大整个宿主机目录权限

### Requirement: Shutdown matches the default container stop window
本版部署示例 SHALL 省略 stop_grace_period，并将应用默认完整收尾预算限制为不超过 8 秒，以适配默认容器停止等待。系统 MUST 优先停止接收、取消在途调用并保存可恢复状态，不以延长等待或盲目重试掩盖不确定结果。

#### Scenario: Stop with a model request in progress
- **WHEN** 按默认 Compose 部署的容器在模型调用中被正常停止
- **THEN** 应用在其收尾预算内退出，或在存储故障等异常中报告无法完成收尾；已知与不确定工作状态不被误标为成功，恢复后不自动重放可能已计费的请求

### Requirement: Backup recovery and verified publication
系统 SHALL 提供停止实例后备份整个新数据目录及配置、恢复到兼容版本和回滚至原独立部署的步骤；恢复 MUST 保持管理员、用户绑定、会话时间和已知用量归属。交付 SHALL 保留 Linux amd64/arm64 镜像验证，并区分合成测试、实际容器验证、微信协议联调和操作者 NAS 验收，未完成项不得标为通过。

#### Scenario: Restore and resume
- **WHEN** 停止实例后的完整备份被恢复到兼容版本且仅启动一个实例
- **THEN** 各用户的绑定、历史边界、空闲时间和统计记录保留；过期微信凭据仍需重新认证，不确定外部操作不自动重放

#### Scenario: Protocol checks are still pending
- **WHEN** 自动化测试与页面测试通过但真实两账号扫码验证尚未完成
- **THEN** 交付说明如实列出未完成验证，不宣称扫码自动绑定和多账号接入已通过端到端验收

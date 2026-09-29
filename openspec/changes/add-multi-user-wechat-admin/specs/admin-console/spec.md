## Purpose

为单个管理员提供统一的中文管理入口，以受控方式为不同微信用户开通独立机器人、查看脱敏运行状态并调整全局配置。普通微信用户无需管理端账号，管理权限不自动包含读取用户聊天正文或导出用户凭据的能力。

## ADDED Requirements

### Requirement: Single administrator authentication
系统 MUST 只允许完成初始化的单管理员访问管理页面数据、二维码、绑定状态和管理 API，不提供公开注册或已知默认密码。管理员密码 MUST 仅以适合密码验证的单向哈希持久化；登录尝试 SHALL 限流，会话 SHALL 支持退出与过期，修改操作 SHALL 防止跨站请求伪造。

#### Scenario: Anonymous management access
- **WHEN** 未登录或会话已过期的访问者请求用户列表、二维码、设置或用量
- **THEN** 系统拒绝访问，不返回敏感数据，也不通过静态文件路径绕过认证

#### Scenario: Forged mutation and logout
- **WHEN** 请求缺少有效的跨站请求防护，或使用已退出的管理员会话提交新增、停用或重绑
- **THEN** 系统拒绝操作，用户与绑定状态保持不变

#### Scenario: Login throttling
- **WHEN** 连续失败的密码尝试达到登录限流阈值
- **THEN** 后续尝试受到限制，响应不泄露密码或账号存在性，普通微信对话不因登录限流而被暂停

### Requirement: Administrator controlled user management
系统 SHALL 允许管理员新增带备注名的用户、创建和取消绑定任务、停用和恢复用户、明确确认重新绑定。第一版 MUST 不开放普通用户注册、公开绑定链接、批量删除历史或多管理员角色管理。

#### Scenario: Create and bind from the page
- **WHEN** 管理员新增用户并发起绑定
- **THEN** 页面展示该用户专属二维码、有效期、登录阶段、必要的配对输入及最终结果，无须操作者查看宿主机文件或编辑身份配置

#### Scenario: Disable and restore user
- **WHEN** 管理员停用或恢复已有用户
- **THEN** 页面分别显示停用状态或实际可恢复的连接状态，操作无须重启应用，恢复不跳过凭据与身份有效性校验

### Requirement: Validated global settings
系统 SHALL 在管理端维护一套全局模型设置与全局会话空闲超时。空闲超时默认 30 分钟，允许 1 至 1440 的整数分钟。模型设置 SHALL 包括兼容 API 地址、写入但不可读回的 Key、模型名称、系统提示词、上下文/输出预算及调用限制；保存前校验约束，失败时保留旧配置。每用户独立模型不属于第一版。

#### Scenario: Invalid settings do not replace working settings
- **WHEN** 管理员提交超时为 0、非法接口地址或输出预算不小于上下文容量等无效设置
- **THEN** 页面显示对应字段错误，既有有效设置和正在处理的请求不被替换

#### Scenario: Save a new global model configuration
- **WHEN** 管理员保存有效的模型配置
- **THEN** 新开始的模型调用使用新配置，已发出的调用保持原配置，用量保留各自配置归属，Key 不会被响应读回

#### Scenario: Configure an installation without a model
- **WHEN** 首次启动时尚未配置模型服务
- **THEN** 管理端仍可登录和保存配置，用户状态明确显示模型未配置，在配置有效前不发起模型请求

### Requirement: Privacy preserving dashboard
系统 SHALL 展示用户备注、绑定状态、独立连接状态、最近活跃时间、脱敏错误及按用户/会话聚合的用量。第一版 MUST 不提供聊天正文浏览或导出，MUST 不在状态、普通日志或错误响应中返回模型 Key、微信 token、配对码或完整模型请求响应；二维码仅通过当前管理员会话中的专用展示入口短时返回且禁止缓存。

#### Scenario: Inspect a user without reading conversations
- **WHEN** 管理员打开用户详情及用量页面
- **THEN** 可查看连接和统计信息，但不能由页面或相应 API 读取聊天正文、历史附件或凭据

#### Scenario: Poll binding status
- **WHEN** 页面轮询某次绑定任务进度
- **THEN** 返回该任务的脱敏阶段和过期时间，不返回微信 token；二维码内容不嵌入普通状态或审计事件

#### Scenario: Untrusted display strings
- **WHEN** 用户备注、模型名或服务错误中包含 HTML 或脚本片段
- **THEN** 管理页面将其作为普通文本展示，不执行脚本、不泄露管理员会话或改变管理状态

### Requirement: Administrative audit trail
系统 SHALL 对管理员初始化、登录安全事件、用户新增/停用/恢复、绑定/取消/重绑和设置变更保留操作时间、操作者、目标及结果。审计 MUST 不保存聊天正文、密码、Key、配对码、二维码内容或完整敏感配置快照。

#### Scenario: Trace a rebind operation
- **WHEN** 管理员对用户确认重新绑定并最终成功或失败
- **THEN** 操作记录能够关联目标用户、绑定任务和结果，不包含旧或新微信凭据

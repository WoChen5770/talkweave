## Purpose

将管理员创建的用户记录与该用户扫码确认的独立机器人连接安全关联，使正常开通不再依赖本地口令文件或手工填写身份。一次性绑定任务必须限制授权范围、抵御过期与并发结果，并在无法确认扫码身份时拒绝授权而不是猜测所有者。

## ADDED Requirements

### Requirement: Scoped one time binding invitation
系统 SHALL 仅由已认证管理员为一个指定用户创建短时、一次性的绑定任务。二维码 MUST 与任务、目标用户和过期时间绑定；只在管理端展示。系统信任管理员将二维码交给预期扫码者，不承诺备注名可证明扫码者的现实身份。

#### Scenario: Separate simultaneous invitations
- **WHEN** 管理员分别为用户 A 和 B 发起绑定并交错查看两个二维码
- **THEN** 两个任务的二维码、状态和登录结果保持独立，A 的成功结果不能写入 B 的记录

#### Scenario: Refresh or cancel a QR code
- **WHEN** 任务过期、管理员刷新或取消二维码后旧扫码结果才到达
- **THEN** 旧结果不能激活或覆盖绑定，新任务不继承旧任务的确认结果

#### Scenario: Pairing is required by WeChat
- **WHEN** 微信要求配对码或其他确认步骤
- **THEN** 页面提示并允许向当前有效任务提交所需配对码，失效任务的输入被拒绝，程序不绕过微信要求的验证

### Requirement: Verified scanner becomes the authorized owner
系统 MUST 在微信确认登录且扫码身份能通过已验证的协议语义或可信映射关联到消息发送者身份后，自动将该身份绑定至目标用户；MUST 不要求正常开通用户发送绑定口令、手工抄写 ID 或重启服务。仅有扫码事件或字段看似相同，不足以证明身份关系。

#### Scenario: Automatically activate a verified scanner
- **WHEN** 当前有效任务取得确认登录结果、可靠的扫码到消息身份关联，且目标未被其他操作替换
- **THEN** 系统一次性持久化授权与连接并立即激活用户，页面显示已绑定，此后只接受该身份的消息

#### Scenario: Identity mapping is missing or unsupported
- **WHEN** 登录已确认但扫码身份缺失、映射未知或与可信协议关系不一致
- **THEN** 页面显示无法确认绑定身份，任务不授予对话或历史访问权限，不以第一条收到的消息完成绑定

#### Scenario: Another person sends the first message
- **WHEN** 第一个发消息的人不是已验证的扫码授权身份
- **THEN** 该消息不能建立或改变所有者，不调用模型，也不获得历史内容

### Requirement: Unique and atomic user binding
一个系统用户 MUST 最多有一套当前有效绑定；同一机器人或同一已可靠识别的微信账号 MUST 不同时属于两个系统用户。系统 MUST 在最终激活时重新校验任务有效性、用户启用状态和身份唯一性，以拒绝重复回调、并发扫码及停用后的迟到结果。

#### Scenario: Duplicate account across two user slots
- **WHEN** 两个任务返回同一已验证微信账号或同一机器人
- **THEN** 最多一个任务完成激活，另一任务明确报告冲突，不抢占已有用户的绑定或历史

#### Scenario: User disabled before confirmation
- **WHEN** 管理员停用用户后其扫码确认结果到达
- **THEN** 该结果不能重新启用用户或安装有效连接

### Requirement: Reauthentication does not transfer ownership
系统 SHALL 区分同身份登录凭据恢复和管理员确认的身份更换。凭据过期或容器重建 MUST 不自动开放重新认领；同身份重新认证保留原历史与正常会话超时规则。更换为不同微信身份 MUST 经管理员明确重绑，隔离旧历史并撤销旧绑定的在途权限。

#### Scenario: Login credentials expire
- **WHEN** 已绑定用户凭据失效后出现重新扫码请求
- **THEN** 页面要求重新认证原身份，其他扫码身份不能自动替换所有者

#### Scenario: Administrator confirms a different identity
- **WHEN** 管理员明确确认重绑且另一微信身份通过可靠验证
- **THEN** 新绑定立即使用新的历史和缓存范围，旧身份不能继续收发，新身份不能访问旧绑定的历史

### Requirement: Real protocol evidence gates automatic binding acceptance
自动绑定的交付验收 MUST 有经用户授权的真实验证，覆盖扫码身份与消息身份关系、两个账号连接并存以及重复扫码行为；证据 MUST 脱敏记录适用协议范围和限制。合成测试或一次 ID 相等 MUST 不被当作通用身份保证。能力不成立时 MUST 保持禁止授权并报告未通过验收。

#### Scenario: Validate two independent accounts
- **WHEN** 两个经操作者同意的微信账号分别扫码并交错发送带独立测试标记的消息
- **THEN** 两套连接和授权独立有效，证据记录身份关联依据、连接是否相互失效及重复登录观察结果，不泄露真实 token 或二维码

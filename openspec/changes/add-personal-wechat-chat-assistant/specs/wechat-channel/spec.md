## Purpose

提供个人微信 ClawBot / iLink 的单机器人文字消息通道，使长期运行的服务能够在无需公网回调的情况下完成扫码连接、接收消息和发送回复，并在网络波动、凭据失效及进程重启时保持可诊断、可恢复的连接状态。

## ADDED Requirements

### Requirement: Headless single-account login
系统 SHALL 在无有效连接凭据时进入等待登录状态，为本地操作者提供有有效期的二维码，并在服务端要求时支持输入数字配对码；登录过程 MUST 不依赖容器交互式标准输入。系统 SHALL 校验登录结果并持久保存机器人身份、服务节点和连接凭据，且仅运行一个机器人连接。

#### Scenario: First login requires a pairing code
- **WHEN** 首次启动没有有效凭据，操作者扫描二维码且服务端要求数字配对码
- **THEN** 系统通过受保护的本地运维入口接收当前挑战对应的配对码，完成验证后保存连接状态并开始收消息

#### Scenario: QR code expires or login fails
- **WHEN** 当前登录挑战过期、被取消或配对码被拒绝
- **THEN** 系统不进入已连接状态，作废过期材料，并以有界重试或重新申请二维码提供可继续操作的状态

### Requirement: Restore and recover connection state
系统 SHALL 在重启时优先复用已保存的连接凭据、账号节点和消息游标。系统 MUST 区分临时网络错误与凭据明确失效；前者使用有上限的退避重试，后者停止使用失效凭据并等待重新登录，不得通过无限重启或紧密轮询尝试恢复。

#### Scenario: Restart with usable credentials
- **WHEN** 应用重启且已保存凭据仍可用
- **THEN** 系统无需再次扫码即恢复相同机器人连接，并从持久化游标继续轮询

#### Scenario: Service rejects the token
- **WHEN** 服务端以约定业务错误（包括已验证的 `ret=-14` 或 `errcode=-14`）明确拒绝当前 token
- **THEN** 系统停止该凭据的轮询和发送，进入等待重新登录状态，且保留聊天记录

#### Scenario: Temporary network outage
- **WHEN** 微信请求发生临时断网或可恢复的限流
- **THEN** 系统按有上限的退避策略重试并暴露连接降级状态，不持续高速请求，也不把错误游标写成有效游标

### Requirement: Durable polling boundary
系统 SHALL 主动长轮询收消息而不要求公网入站回调。系统 MUST 仅在响应成功且本批消息已被持久接收或明确判定为可忽略后推进游标；游标和持久接收结果 MUST 保持一致，重放消息不得触发重复对话处理。运行中持久存储不可用或积压达到配置阈值时，系统 SHALL 暂停新增轮询并报告原因，不静默丢弃消息以推进游标。

#### Scenario: Crash before accepting a batch
- **WHEN** 进程在一批有效消息和其新游标持久提交之前退出
- **THEN** 重启后允许重新获取该批消息，而不是因提前推进游标而静默丢失消息

#### Scenario: Model response is slow
- **WHEN** 已接收消息的模型请求仍在运行
- **THEN** 微信接收流程不被该调用占用，后续消息可以先持久接收再按顺序处理

#### Scenario: Storage or backlog prevents safe intake
- **WHEN** 数据目录不可写或持久积压达到配置上限
- **THEN** 系统暂停新增轮询，保留已接收事件及最后成功游标，暴露原因并在安全条件恢复后继续

### Requirement: Validated text delivery
系统 SHALL 仅向通过身份检查的发送者回复，使用触发本次回复的入站消息关联令牌及唯一发送标识。系统 MUST 同时校验 HTTP、JSON 和微信业务返回值，不得将 HTTP 200 本身视作投递成功。

#### Scenario: Business failure in an HTTP success response
- **WHEN** 发送接口返回 HTTP 200，但响应的业务返回码表示失败
- **THEN** 系统将该发送记为失败或待人工处理，不把它标记为已投递成功

#### Scenario: Missing or expired reply context
- **WHEN** 回复缺少有效关联令牌，或微信明确拒绝该令牌
- **THEN** 系统不借用另一个用户或另一条消息的令牌重发，保留回复及失败状态供诊断

#### Scenario: Delivery outcome is unknown
- **WHEN** 请求可能已发出，但连接中断导致无法确认微信是否接收
- **THEN** 系统保留不确定状态，不盲目重复发送或重新调用模型，且诊断信息说明可能需要用户确认

### Requirement: Bounded text-only behavior
系统 SHALL 只将受支持的文字消息交给对话服务；第一版 MUST 不下载或解释图片、文件及语音。已授权用户发送不支持内容时 SHALL 获得能力提示。回复超过经验证并配置的通道长度上限时 SHALL 安全截断并加明确提示，同时保存完整模型结果，不能静默把截断结果伪装成完整回复。

#### Scenario: User sends an image
- **WHEN** 已授权用户发送不含受支持文字内容的图片消息
- **THEN** 系统返回仅支持文字的提示，不调用模型、不执行媒体下载

#### Scenario: Generated reply is too long
- **WHEN** 模型生成的回答超过配置的通道发送上限
- **THEN** 系统发送不破坏字符边界且包含截断标记的文本，保留完整结果及实际发送文本


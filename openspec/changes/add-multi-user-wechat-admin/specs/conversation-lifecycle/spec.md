## Purpose

为每个用户的独立机器人定义可预测的对话边界，使近期连续提问能够携带正确历史，而长时间空闲后的消息自动开始新的上下文。空闲规则可由管理员统一设置，跨重启有效，并与微信登录、历史保留及模型提供商的缓存保留时间相互独立。

## ADDED Requirements

### Requirement: Sliding idle timeout
系统 SHALL 默认使用 30 分钟的会话空闲超时，以同一用户和绑定内最后一条通过授权、去重并被持久接受的普通文字消息或 `/new` 的接收时间为基准。新消息与该时间的间隔达到或超过当前超时值时 MUST 开启新会话，否则继续原会话；未授权消息、重复消息、轮询、模型响应、`/help` 和不支持的内容 MUST 不延长该计时。

#### Scenario: Sliding window remains active
- **WHEN** 用户在 10:00 和 10:20 分别发送有效文字，超时设置为 30 分钟
- **THEN** 两条消息属于同一会话，下一次超时边界从 10:20 计算，而不是从 10:00 计算

#### Scenario: Boundary and next conversation
- **WHEN** 上次有效消息于 10:20 被接受，此后没有新有效消息，用户在 10:50 或之后再次发消息
- **THEN** 该消息归入新会话，不携带原会话的问答历史

#### Scenario: Duplicate and unauthorized traffic
- **WHEN** 系统在会话即将超时时收到重复消息、其他发送者消息或仅有状态轮询
- **THEN** 原会话的最后有效活动时间不变，这些事件不能使会话继续存活

### Requirement: Runtime configurable timeout
系统 SHALL 允许管理员设置 1 至 1440 的整数分钟作为全局空闲超时，默认 30 分钟，配置持久保存且无需重启。设置更新后下一条有效新消息 MUST 按更新值判断当前会话；已结束的会话 MUST 不因配置改变而重新激活。

#### Scenario: Reduce timeout for an existing conversation
- **WHEN** 管理员将超时从 30 分钟改为 10 分钟，用户距离上次有效消息已过去 15 分钟并再次发消息
- **THEN** 新消息开启新会话，无须管理员或用户重启服务

#### Scenario: Increase timeout does not resurrect history
- **WHEN** 会话 A 已结束且 B 已创建，管理员调大超时
- **THEN** B 可按新设置继续，但 A 不重新成为当前会话，其历史不并入 B

### Requirement: Session boundary preserves history and identity
超时或 `/new` MUST 只结束当前上下文范围，不删除旧消息、用量或绑定，不注销微信连接。新会话 MUST 不读取旧会话的私有历史或显式缓存资源；共享且不含用户私有内容的系统提示词仍可使用。

#### Scenario: Explicit new conversation
- **WHEN** 用户发送 `/new` 后继续提问
- **THEN** 后续提问使用新会话，旧记录保留，该命令不触发模型请求，也不影响其他用户会话

#### Scenario: Idle expiration is not a WeChat logout
- **WHEN** 当前会话超过空闲超时
- **THEN** 下一条有效消息自动开启新会话，不要求重新扫码，旧历史只被保留而不进入新模型请求

### Requirement: Durable and race safe message assignment
最后有效活动时间与消息会话归属 MUST 持久保存。超时判断 MUST 使用消息被系统接受的时间，而非模型执行时间；同一用户并发到达的新消息 MUST 按确定顺序选择会话。已入队消息 MUST 保持既定会话归属，不因设置更新、后续超时或重启被重新分类。

#### Scenario: Restart after a long idle period
- **WHEN** 用户最后发言后服务重启，重新启动时已超过当前空闲超时
- **THEN** 下一条新消息创建新会话，重启不把最后活动时间重置为启动时间

#### Scenario: Processing delay does not create artificial inactivity
- **WHEN** 两条用户消息间隔 1 分钟被接受，但第二条因模型或队列延迟超过 30 分钟才开始处理
- **THEN** 第二条保留接收时确定的会话归属，不因排队耗时被错误分成新会话

#### Scenario: Concurrent first messages after timeout
- **WHEN** 同一用户的两条消息在超时后几乎同时到达
- **THEN** 系统只创建一个新的当前会话，并按接收顺序将消息归入该会话

## Purpose

在一个部署实例中运行多套微信机器人连接，同时使身份授权、会话上下文、消息顺序和错误恢复保持用户级隔离。该能力保证一个用户的接入、慢请求、停用或重绑不会把其他用户的数据带入模型请求或错误地转发给其他微信账号。

## ADDED Requirements

### Requirement: Independent channel state
系统 MUST 为每个有效绑定独立持久化和恢复机器人凭据、服务节点、轮询游标、登录代次及连接状态。新增、断线或重新登录某个用户 MUST 不覆盖或停止其他用户的连接。

#### Scenario: One connection reconnects while another remains active
- **WHEN** 用户 A 的连接断开并重连，而用户 B 正常收发
- **THEN** 仅 A 进入重连状态，B 的凭据、游标、登录代次和会话保持不变

#### Scenario: Restart with several active users
- **WHEN** 容器重启且已有多套有效绑定
- **THEN** 系统恢复各自的持久状态，原用户不因重启重新认领，无效凭据只使对应用户等待重新认证

### Requirement: Authorization scoped conversation data
系统 MUST 在消息接收、历史查询、模型请求、辅助微信调用和回复发送时核对系统用户、绑定版本、机器人身份与消息发送者。历史、事件、会话和应用侧缓存资源 MUST 不能跨用户或跨不同身份的绑定版本复用；管理端不提供聊天正文查询。

#### Scenario: Interleaved user histories
- **WHEN** A 和 B 分别发送不同的测试信息并交错追问
- **THEN** A 的模型请求与回复只包含 A 当前会话的授权历史，B 同理，任意错误关联的会话或缓存标识被拒绝

#### Scenario: Stale work after rebind
- **WHEN** 旧绑定的模型请求完成时该用户已重绑到另一身份
- **THEN** 旧结果不能发送给新身份，也不能成为新绑定的历史；其用量仍归属原请求范围

### Requirement: Bounded and fair processing
系统 SHALL 在用户间允许有界并发，并保持同一用户已接受消息的处理顺序；单个慢用户 MUST 不独占所有处理机会。系统 SHALL 约束总并发和各用户积压，积压或存储容量不足时安全暂停对应接收，不默默丢弃已接受消息。

#### Scenario: Slow model call for one user
- **WHEN** A 的模型请求长时间未返回且系统仍有可用并发配额
- **THEN** B 的正常请求可以继续处理，A 的后续消息保持顺序，不并行污染同一会话

#### Scenario: Per-user backlog limit reached
- **WHEN** A 达到其待处理消息上限而 B 未达到限制
- **THEN** 系统对 A 暂停新增接收并显示积压原因，不将 A 的消息转入 B 的队列；除全局资源不足外 B 可继续工作

### Requirement: Disable revokes runtime authorization
管理员停用用户后系统 MUST 停止为该用户启动新模型请求及微信业务操作，并在在途任务发送前重新校验授权。已实际发出的模型请求或微信发送 MUST 按真实已知状态记录，不能声称撤回已发生的调用或抹去可能产生的费用。

#### Scenario: Disable during a model call
- **WHEN** 管理员停用 A 时其模型请求已发出
- **THEN** 系统尽力取消请求且禁止停用后发起回复发送，保留已返回的用量与未确定结果，不影响 B

### Requirement: Durable deduplication and uncertain delivery
系统 MUST 持久去重同一机器人范围的稳定消息标识，不因重试或重启再次调用模型处理已完成消息。模型请求超时或发送结果不确定时 MUST 保留不确定状态，不自动重放可能已计费或已送达的操作；停机与恢复 MUST 保持各用户范围。

#### Scenario: Duplicate and restart
- **WHEN** 已处理消息再次出现，或发送中途应用崩溃后恢复
- **THEN** 重复消息不触发新模型请求；无法确定是否送达的回复保持可诊断的不确定状态，不向任何用户盲目重发

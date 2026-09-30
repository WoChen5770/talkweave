## Purpose

为模型提供由服务端产生的可信日期与时间依据，使关于今天、明天和当前时间的问题有明确参照。该上下文随每轮请求更新，同时保持历史原文、会话归属、固定提示词前缀和上下文预算不受错误的时间缓存或启动时快照影响。

## ADDED Requirements

### Requirement: Trusted per-turn local time context
系统 MUST 在每轮模型请求组装时从可控服务端时钟采样当前时刻，提供日期、本地时间、星期、时区标识及 UTC 偏移，默认时区为 `Asia/Shanghai` 并允许通过服务配置修改。动态时间 MUST 与用户文本明确分离，不把用户声称的时间当作服务端当前时间；未知或非法时区 MUST 在启动校验时明确失败。

#### Scenario: Current time question without manual prompt editing
- **WHEN** 用户询问今天日期或当前时间且管理员没有把日期写入提示词
- **THEN** 发给模型的请求包含本轮服务端时间与明确时区，而不是模型知识截止时间、应用启动时间或示例日期

#### Scenario: Alternate timezone or invalid configuration
- **WHEN** 同一瞬间配置为另一个合法时区，或填写无效时区
- **THEN** 合法配置生成相应本地日期、星期与偏移；无效配置拒绝启动，不悄悄回退到容器默认时区

### Requirement: Request time is distinct from message receipt time
当前时间 MUST 表示组装本轮模型请求的时间，不使用排队消息的接收时间冒充当前时间。请求 SHALL 同时标明原消息接收时间供模型解释相对日期，但 MUST 不因此改变已持久化会话归属或最后活动时间。

#### Scenario: Message waits across midnight
- **WHEN** 消息在午夜前接收、午夜后组装模型请求
- **THEN** 请求分别提供真实接收时间与新的当前时间，二者标签明确；会话仍是接收时确定的会话

### Requirement: Stable retries and uncached dynamic context
同一轮内允许的模型重试 MUST 复用一次采样的时间快照，后续新一轮 MUST 重新采样。动态时间 MUST 不写回用户消息、已确认历史、管理员系统提示词或 Redis 历史缓存；固定系统提示和选定原始历史 MUST 保持原内容及顺序，动态上下文放在历史之后、当前问题之前。

#### Scenario: Retry and then a new turn
- **WHEN** 一轮请求发生允许的重试，随后用户继续发问
- **THEN** 重试请求的时间一致，新一轮时间重新生成；MySQL 和 Redis 中的问答原文未被插入时间前缀

#### Scenario: Compatible gateway receives the request
- **WHEN** 请求发送到只接受约定常规字段的兼容模型网关
- **THEN** 时间以消息内容表达，不添加专用协议参数或工具调用，固定系统和已有历史前缀不被重写；不保证跨轮整个请求前缀完全一致

### Requirement: Time context obeys input budget
系统 MUST 将时间上下文与固定提示、历史和当前问题共同计入输入预算，优先裁剪最旧完整问答。如果最小请求仍超限，MUST 在模型调用前给出本地预算提示，不绕过限制或隐藏地移除可信时间来冒充完整请求。

#### Scenario: Time text pushes the request over budget
- **WHEN** 加入动态时间后超出输入容量
- **THEN** 整轮裁剪历史直至满足限制；即使没有历史仍超限时不发出模型调用，也不创建虚假的已计费用量

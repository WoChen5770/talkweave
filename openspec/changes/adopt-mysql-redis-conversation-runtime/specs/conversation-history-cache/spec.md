## Purpose

利用已有 Redis 减少活跃会话历史正文的重复读取，同时将缓存限定为可丢弃、可重建的性能副本。缓存命中不得改变模型看到的授权历史、完整问答顺序、会话边界或数据库故障时的安全行为，也不得冒充提供商的 token 缓存。

## ADDED Requirements

### Requirement: Isolated bounded history cache
系统 SHALL 缓存近期模型成功且确认送达的原始问答，按项目安装、用户、绑定、会话与模型配置版本隔离，并设置可配置的过期时间、轮数及单窗口字节上限。缓存 MUST 不包含模型 Key、微信凭据、管理员会话或包含动态时间的最终提示词；Redis 过期 MUST 不代表业务会话结束或历史被删除。

#### Scenario: Two users and a new conversation
- **WHEN** A 与 B 交错聊天，A 通过 `/new` 或超时进入新会话
- **THEN** 每次读取仅属于相应用户当前事件的绑定和会话，旧缓存不能作为新会话输入，其他用户的缓存不受影响

#### Scenario: Expired or oversized cache window
- **WHEN** 缓存已过期，或完整所需窗口超过缓存字节上限
- **THEN** 从 MySQL 获取合法有界历史并继续按模型预算处理，不把不完整窗口伪装成完整命中，不突破缓存限制

#### Scenario: Model configuration is updated
- **WHEN** 管理员更换模型配置，新请求开始而旧配置调用仍在途
- **THEN** 新旧调用各自使用对应版本的缓存范围和预算，旧回填不能覆盖新配置窗口，原始历史仍以数据库为准

### Requirement: Authorization and snapshot validation on every read
缓存命中 MUST 以 MySQL 确认的当前授权、原事件范围、历史版本和消息边界为前提，并验证缓存格式、顺序、配置所需窗口覆盖及完整性。来自旧绑定、旧版本、当前事件之后或不足以覆盖 N 轮的缓存 MUST 被拒绝使用；缓存内容不能授予任何权限。

#### Scenario: Disable then resume during a cached request
- **WHEN** 已缓存历史的用户停用后恢复，而旧工作仍持有先前授权代次
- **THEN** 旧工作被拒绝，不能因缓存命中恢复模型或发送权限；新工作重新验证权限

#### Scenario: Stale refill or larger history setting
- **WHEN** 延迟回填携带旧历史版本，或当前配置要求的轮数大于缓存可证明的覆盖范围
- **THEN** 旧或不足的窗口不被用于回答，当前调用回查数据库获得正确历史

### Requirement: Incremental cache maintenance after confirmed delivery
系统 SHALL 在确认送达的成功问答已提交 MySQL 后幂等增量维护缓存，保持完整问答和顺序并裁剪旧轮次。未送达、失败或投递结果未知的回复 MUST 不进入缓存；缓存写入失败 MUST 不回滚成功投递、不触发重新发送或再次调用模型。

#### Scenario: Consecutive successful turns keep a warm window
- **WHEN** 首轮回填后连续产生确认送达的成功问答且缓存服务正常
- **THEN** 缓存逐轮更新，下一轮可复用完整窗口，不要求每轮清空缓存后再全量回源

#### Scenario: Duplicate update or a gap in updates
- **WHEN** 同一已提交问答被重复通知，或缓存错过某一版本后收到更晚更新
- **THEN** 不重复追加问答，不跳过缺失轮次生成错误窗口；检测到版本间隙后回源重建或放弃更新

#### Scenario: Crash or Redis failure after database commit
- **WHEN** MySQL 已提交确认送达，但进程崩溃或 Redis 更新失败
- **THEN** 数据库历史仍正确，下次读取通过版本校验发现陈旧缓存并重建，不因旧缓存丢失最近一轮

### Requirement: Bounded degradation and recovery
Redis 连接、命令和重建工作 MUST 受有限时间与并发预算约束；不可用、权限不足、格式损坏或超限时 SHALL 降级到有界 MySQL 历史查询并报告脱敏退化状态。缓存故障 MUST 不导致无限重试、无界任务堆积或绕过数据库授权；缓存恢复后 SHALL 能重新填充。

#### Scenario: Cache outage under concurrent traffic
- **WHEN** 多个用户请求期间 Redis 超时或拒绝访问，而 MySQL 正常
- **THEN** 每次请求在缓存预算内转为数据库路径，回源并发受控，无新增模型重试或跨用户阻塞

#### Scenario: Corrupt cache or cache recovery
- **WHEN** 缓存正文无法解析、顺序不合法，或 Redis 在故障后恢复
- **THEN** 不将损坏数据发给模型，安全重建当前范围历史；恢复不需要清空共享 Redis

### Requirement: Restarts and database restores cannot reuse unrelated cache state
应用重启、全新数据库初始化或备份恢复后的缓存 MUST 被视为可重建状态，不能仅依赖碰巧相同的用户 ID、历史版本或 TTL 复用上次运行内容。数据库记录恢复与 Redis 快照 MUST 不需要作为分布式事务共同恢复。

#### Scenario: Restore an older MySQL backup with newer Redis keys
- **WHEN** MySQL 恢复到较早备份，而 Redis 仍保存恢复前的较新聊天
- **THEN** 新运行不读取该较新历史，缓存仅从恢复后的数据库重建，其他项目缓存保持不变

### Requirement: Honest performance and cache reporting
系统 SHALL 区分历史缓存命中、未命中、版本拒绝、回源、错误及历史读取耗时；验证 SHALL 对比同一数据集、配置和模型延迟下优化后的 MySQL 直读与 Redis 冷/热缓存，记录数据库正文查询量、历史加载及整轮耗时的分布。指标 MUST 不含聊天、凭据或高基数身份标签，不把 Redis 命中算作提供商缓存 token 或保证总响应加速。

#### Scenario: Warm cache benchmark
- **WHEN** 在长会话、多用户合成负载下执行相同工作量的直读和缓存测试
- **THEN** 热缓存有效命中不再查询历史正文，报告冷启动、热运行和故障数据及测试环境；授权/版本元数据查询与模型耗时单独说明

#### Scenario: Provider reports no cache usage
- **WHEN** Redis 命中，但模型服务没有返回缓存 token 信息
- **THEN** 管理端模型缓存用量仍显示未知，不用 Redis 命中伪造 token 数或费用优惠

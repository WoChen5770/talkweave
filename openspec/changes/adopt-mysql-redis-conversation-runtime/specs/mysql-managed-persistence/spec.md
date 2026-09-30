## Purpose

以 NAS 上已有的 MySQL 承载多用户助手的全部可靠业务状态，在不迁移或破坏旧 SQLite 数据的前提下提供全新安装、有限历史查询和可恢复处理。数据库换型不得降低身份隔离、消息顺序、真实用量及不确定外部操作保护。

## ADDED Requirements

### Requirement: Fresh exclusive MySQL business storage
系统 MUST 仅以配置的专用 MySQL schema 作为生产业务状态来源，覆盖管理员、用户、绑定及连接身份历史、会话、事件、回复、配置、用量和审计。全新安装 MUST 重新初始化管理员、模型配置与微信绑定；系统 MUST 不导入、打开写入或删除旧 SQLite 数据，不在 MySQL 故障时切换为 SQLite 或 Redis 持久业务存储。

#### Scenario: Initialize an empty dedicated schema
- **WHEN** 操作者提供受支持的 MySQL 连接、专用空 schema 和有效初始化参数
- **THEN** 系统创建本应用布局并允许初始化管理端，没有任何旧账号、微信绑定或聊天被隐式导入

#### Scenario: Unrecognized schema or old files are present
- **WHEN** 目标 schema 存在无法识别的表或不兼容版本，或者本地材料路径误指向旧部署目录
- **THEN** 系统在变更该资源前拒绝初始化并给出脱敏说明，不清空已有表、不修改旧文件来继续启动

### Requirement: Recognized additive layout upgrades and stable pagination
系统 SHALL 保留已执行的 MySQL V001，并通过版本化 V002 只追加会话的 64 位自增分页序号、唯一索引与按用户分页索引，保留原 UUID、字段、记录与归属约束。系统 MUST 仅在精确识别已知 READY V1 的脚本和布局摘要且取得独占运行权后升级，使用持久的升级中标记；新建库与升级库最终采用相同 V2 布局。升级未完成时 MUST 拒绝业务启动和自动重试 DDL，不删除或重建业务表。管理端 SHALL 保留数字 `sequence`/`before` 接口，并在用户范围内使用稳定游标分页。

#### Scenario: Upgrade the recognized V1 layout
- **WHEN** 已授权目标为完整匹配的 READY V1，当前进程取得独占运行锁
- **THEN** V002 只增加分页字段与索引，保留安装 ID 和旧行；成功置为 READY V2 后才推进运行 epoch，后续重开不重新升级或编号

#### Scenario: Interrupted upgrade or competing instance
- **WHEN** 升级在标记之后、完成之前中断，或第二实例在升级期间尝试进入
- **THEN** 中断布局保留可诊断标记并拒绝后续业务启动；第二实例不能进行升级或恢复写入，不清表、不盲目重放 ALTER

#### Scenario: Stable scoped conversation pagination
- **WHEN** 管理员分页读取同一用户的会话，其间出现新会话或应用重开
- **THEN** 已分配序号保持不变，按该用户范围及小于原游标的条件返回有界倒序元数据，不串入其他用户，不依赖 SQLite rowid 或 OFFSET

### Requirement: Scoped identity and durable state constraints
系统 MUST 在数据库中保持用户、绑定、连接身份、会话、事件及用量的归属约束、稳定消息标识去重和活动身份唯一性。身份标识的比较 MUST 保持精确语义，不因数据库默认大小写或字符排序规则合并不同身份；停用再恢复、重认证与身份更换 MUST 继续以授权代次和绑定范围阻止旧工作复活。

#### Scenario: Concurrent binding and message duplication
- **WHEN** 两个用户并发认领同一可靠账号或机器人，或者同一机器人消息重复进入
- **THEN** 最多一个有效身份归属成立，重复消息仅产生一份业务事件，不产生重复模型调用或用量

#### Scenario: Reauthenticate or replace an identity
- **WHEN** 原账号换机器人重认证，或管理员明确更换为另一身份
- **THEN** 同身份重认证保留原范围历史并撤销旧连接代次；不同身份使用新绑定与新上下文，不能读取旧身份历史或接收旧结果

### Requirement: Atomic work transitions with bounded concurrency
系统 MUST 将接收批次的事件、去重、会话分配和游标作为一个事务提交；领取任务、保存模型结果、记录尝试及确认投递 MUST 具备并发安全和幂等语义。系统 SHALL 保持同用户按序、跨用户有界并行、有限积压与短事务，事务内 MUST 不等待微信、模型或 Redis 网络操作。

#### Scenario: Batch fails before commit
- **WHEN** 接收批次超出容量或提交失败
- **THEN** 该批次事件、会话变更和游标共同回滚，不丢消息，不留下部分接受状态

#### Scenario: Competing workers and a slow remote call
- **WHEN** 同用户的两个调用竞争领取工作，同时另一用户的模型调用长时间等待
- **THEN** 同用户最多一个业务阶段被有效领取，网络等待不占有数据库事务或阻塞其他有配额用户的数据库处理

### Requirement: Exclusive application ownership before recovery
本版 MUST 只允许一个活动应用实例管理同一业务 schema。第二实例 MUST 在业务初始化、恢复写入、扫码或消息轮询前被拒绝；运行所有权丢失时原实例 MUST 停止启动新的外部业务操作，不能无条件重连并继续旧工作。

#### Scenario: A second instance starts
- **WHEN** 第一个实例仍拥有该 schema 的运行权，第二实例尝试启动
- **THEN** 第二实例失败关闭，不改写第一个实例的处理中任务、绑定邀请或模型尝试

#### Scenario: Runtime ownership becomes uncertain
- **WHEN** 所有权连接失效或数据库无法确认实例拥有权
- **THEN** 应用停止新增接收、模型和发送调度并报告故障；已发出调用按原范围保留已知或未知结果，不自动重放

### Requirement: Bounded authorized history reads
系统 MUST 仅返回当前事件之前、同用户/绑定/会话且模型成功并确认送达的最近 N 轮完整问答，N 来自当前调用的配置快照。过滤 MUST 发生在选取最近 N 轮之前，结果按接收序号正序交给上下文预算器；N 为零时 MUST 不加载历史正文。数据源查询 MUST 不先把整段会话正文加载到应用内存再裁剪。

#### Scenario: Long history contains failed and future turns
- **WHEN** 会话包含大量历史、失败或未知投递，以及序号不早于当前事件的记录
- **THEN** 只选取符合条件的最近 N 轮，不夹带失败、未来或其他范围数据，返回量不随会话总长度无限增长

#### Scenario: History configuration changes
- **WHEN** 管理员修改历史轮数或容量，下一次调用使用新配置快照
- **THEN** 新调用按新限制读取并裁剪，历史轮数为零时只组装固定提示、动态时间与当前问题，在途调用仍沿用旧快照

### Requirement: Stable conversation lifecycle across storage changes
系统 MUST 保留默认 30 分钟、可热更新为 1 至 1440 整数分钟的会话空闲规则，以有效新用户消息的持久接收时间判断。重复、未授权、帮助消息、模型耗时与 Redis 访问 MUST 不刷新活动时间；`/new` 和超时 MUST 不删除记录或注销微信，排队事件 MUST 保留接收时确定的会话归属。

#### Scenario: Queue crosses an idle or midnight boundary
- **WHEN** 同一会话的消息已被接受，随后排队跨过空闲期限或午夜才开始生成
- **THEN** 该消息仍属于接收时固定的会话，动态当前时间不触发重新分配

#### Scenario: Exact idle boundary and restart
- **WHEN** 下一条有效消息在最后活动时间加超时值时到达，期间可发生应用重启
- **THEN** 创建新会话且不加载旧历史，原账号、旧记录和已知用量保留

### Requirement: Recover uncertain operations without replay
系统 MUST 在独占运行权下区分未调用、处理中、回复待发、发送中及最终状态，保持模型请求可能计费和微信消息可能送达时不自动重放。已知用量 MUST 幂等归属原用户、会话、配置和实际尝试；MySQL 无法确认授权或可靠写入时 MUST 停止新的相关业务操作。

#### Scenario: Crash after generation or during sending
- **WHEN** 模型已被调用但结果未能可靠保存，或微信发送已开始但最终状态未知后应用重启
- **THEN** 对应工作保持中断或未知，不重复调用模型或盲目重发；已持久化且尚未开始发送的回复可按原范围恢复

#### Scenario: MySQL fails while Redis is healthy
- **WHEN** Redis 含有历史但 MySQL 不可用
- **THEN** 不依赖缓存继续授权、生成或发送，故障可诊断，恢复后不重放不确定外部操作

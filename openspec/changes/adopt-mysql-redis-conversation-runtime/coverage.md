# Requirement / scenario 覆盖与退役映射

当前全部 **51 场景**及测试退役/迁移/保留状态以 [coverage-current.md](coverage-current.md) 为准；最新数量、版本、失败修复和未完成项以 [实施记录](implementation-status.md) 顶部为准，性能数据见 [performance.md](performance.md)。本文件下方保留任务 1.4 原规划和分阶段历史证据，不能把旧“拟建/未接线/待测”状态当作当前状态，也不能将早期通过数混入最终报告。

历史表中 `时间合成已验` 和 `待实施/验证` 仅表示当时阶段。当前已完成默认缓存预算、长历史/性能矩阵、在途关闭和实际合成浏览器验收；实际损坏布局、备份恢复、完整真实账号和双架构正式镜像仍未验收。

## 先前业务续作补充（macOS，2026-09-30，历史阶段）

最新进度 **26/45**；真实 MySQL 8.0.46 / Redis 7.4.9 的显式 IT **73/73** 通过，9 suites，零失败/错误/跳过；收尾 Java 单元 **228/228**、Node **14/14**、候选正式 JAR 实际检查和 OpenSpec strict/diff 检查通过。详细命令及失败修复见 [实施记录](implementation-status.md) 最上方。此段覆盖下方早期“尚未接线/缺少配置”的历史说明。

| 当前 requirement/scenario 范围 | 实际入口/证据 | 状态/限制 |
| --- | --- | --- |
| Atomic work transitions / Batch fails before commit；Competing workers and a slow remote call | ManagedRepositoryIT 的全局配额双用户竞争、同用户并发领取和整批事件/会话/游标回滚；RuntimeManagerIT 的慢用户及独立用户队列；ExternalTransactionsIT 的双池连接并行、回滚、64 位主键；MysqlTransactionsTest 的安全有限重试 | 2.4、3.3、3.4、3.7 已验；没有外部调用包在可重试事务中 |
| Scoped identity / Concurrent binding；Reauthenticate or replace | ManagedRepositoryIT、BindingCoordinatorIT 的账号/机器人冲突、并发激活、ABA、generation/auth_epoch、跨用户注入、原身份新机器人与明确换身份 | 3.1 已验 |
| Fresh bootstrap；Stable scoped conversation pagination | AdminApiIT、ManagedRepositoryIT、ExternalPaginationIT 的管理员不重置、Key 不读回、版本设置、范围分页/审计和重开保留 | 3.2 已验；正式镜像引导仍待 7.5 |
| Exclusive ownership / A second instance starts | secondStoreCannotRecoverAnyLiveWorkOrAdvanceEpoch：同时保留 PROCESSING/SENDING/STARTED/有效邀请及 epoch；释放后才恢复 | 2.5 已验 |
| Exclusive ownership / Runtime ownership becomes uncertain | lateExternalSuccessAfterLockAbortAndNewEpochCannotCommitOrReplay 的模型返回/回复保存/发送结果三断点；RuntimeManagerIT 全 channel 关闭及在途取消；BindingCoordinatorIT 全二维码任务/材料清理 | 2.6 已验：仅中止测试自有物理连接，不 KILL 服务端其他会话 |
| Recover uncertain operations / Crash after generation or during sending | 原 restart/uncertainSend 回归 + 新 epoch 迟到结果测试，已保存用量保留、未保存为未知、回复/发送不重放 | 3.4、3.5、3.7 已验；真实备份覆盖不包含在内 |
| Stable conversation lifecycle / Queue crosses boundary；Exact idle boundary and restart | ManagedRepositoryIT 的 idleWindowSlides、timeoutUpdate、newCommand、queuedTurn 等，用可控 Clock 在真实 MySQL 验证 | 3.3 已验 |
| Bounded history；Incremental cache / Consecutive successful turns | ExternalHistoryIT 的先过滤 LIMIT、0 轮、未来边界、幂等 revision、真实 EXPLAIN、热命中不查正文及增量 | 部分已验；3.6 长历史、4.x 边界/故障与默认预算仍待补测 |
| One production runtime / Legacy configuration；Inspect artifact | FoundationContextTest、WorkflowContractTest、独立 ManagedContainerProbe 检查正式 JAR；旧仓储/驱动/SQL/授权入口已退役，MySQL 替代业务回归通过 | 6.2、6.4、7.1 已验；正式镜像运行仍待验 |
| Shared infrastructure / Secret isolation；Versioned backup | 当前 mysql-redis-operations/development 文档；README 指向新入口，旧 SQLite 文档明确历史标记 | 7.3 文档完成；最小权限实际 ACL、真实恢复与双架构不是文档完成的证据 |

下方表格保留原 51 场景规划清单。此处未覆盖的场景仍按下方“待实施/验证”处理，特别是缓存恢复、性能、实际浏览器/镜像/备份和全应用停止。6.5 的全部有效安全替代覆盖仍未闭合，不因文档标记历史或通过数增加提前完成。

任务 6.4 已完成：`ManagedContainerProbeTest` 覆盖缺失必需组件、旧 SQL/授权/SQLite/诊断/夹具误入正式包的拒绝；`WorkflowContractTest` 验证独立诊断加载、仅应用的 Compose 和不放行发布的离线边界。独立构建的 diagnostics JAR 实际加载并检查同次正式候选 JAR 通过，正式包不含探针或测试夹具。微信、模型、双账号的合成探针回归继续保留，真实调用仍要求显式授权。

先前独立诊断阶段 Java **228/228**、Node **14/14** 通过；当时尚无引擎结果，现已由上方最新 IT 证据补充，但仍没有正式容器或浏览器 E2E 通过声明。先前 `target` 残留旧 SQL 已被产物检查拒绝；没有删除旧产物。

以下早期表格与映射是规划及历史证据，不代表旧类仍存在：`ManagedRepositoryTest`、`RuntimeManagerTest`、`BindingCoordinatorTest`、`AdminApiTest` 已迁移为对应 `*IT`；当前布局/事务/历史入口为 `ExternalSchemaIT`、`ExternalPaginationIT`、`ExternalTransactionsIT`、`ExternalHistoryIT`。SQLite 专属生产实现及测试已在此前提交退役，6.2 现有真实业务替代证据，6.5 全覆盖闭合仍待完成。

## 2026-09-30 授权 NAS 基础验证补充

续作补充（优先于本节早期记录）：任务 1.3 已完成；原两项 NAS 测试迁为 `ExternalServicesIT` / `ExternalSchemaIT`，通过显式 `external-services` profile 直连已有服务，配置/版本/授权库名/前缀校验、随机合成子范围和空业务保护已实现。新增 `ExternalIntegrationTargetTest` 无网络测试；2 项 NAS IT 实测通过，缺少参数的 profile 实测在连接前失败，不能计作通过。默认测试不加载 NAS 配置，不再依赖数据库镜像。业务故障/恢复、CI 发布门禁仍待验。

- `ExternalServicesTest`（33 项）：配置类型/范围、必需项、缓存关闭、显式明文、默认 TLS、秘密脱敏；正式启动接线和故障降级未完成，不能将部署场景整体标为通过。
- `HistoryCacheFrameTest`（2 项）与 `NasCompatibilityIT`：定宽 64 位版本/UTF-8 字节界限、实际 Lettuce Lua 原子比较及 TTL；尚非完整缓存窗口/授权/更新测试。
- `NasCompatibilityIT`：固定 NAS 版本、独立 MySQL 命名锁、仅自有连接中止、Hikari 隔离/回滚、Unicode/大整数。它是显式授权的手动测试，不属于任务 1.3 的隔离 CI。
- `NasSchemaIT`：实际建立 20 张表、唯一性/跨范围外键/精确身份比较、安装与 epoch 重开、第二实例拒绝；合成业务行回滚。部分初始化、未知布局、业务恢复和旧材料拒绝尚未完整覆盖，因此以下相关 scenario 仍保留待验。
- 已完成任务增加 1.1、1.2、2.1、2.2。2.3–2.6 和缓存各项均未因基础类存在而勾选；未删除任何旧测试或生产运行链路。

## 2026-09-30 V002 升级补充

新增 `Recognized additive layout upgrades and stable pagination` 的三个场景（总计 51 个 scenario）。`MysqlLayoutTest` 固定不变的 V001 摘要，覆盖已知/未知/部分标记、DDL 前持久标记、DDL 失败及 DDL 后失败不发布 READY；这是无网络状态机覆盖，不冒充 NAS 故障恢复。

事务基础补充：`MysqlTransactionsTest` 16 项无网络测试覆盖提交/回滚、有限纯 SQL 死锁重试、提交结果不确定不重试、回滚失败中止自有连接、旧 epoch、运行权丢失和 JDBC 大整数主键；`ExternalTransactionsIT` 真实验证并行池连接、合成行回滚、JDBC 主键、测试自有锁连接中止及新 owner 建立后旧对象持续失效。尚未接入业务 repositories/外部调用前复核，不能据此将 2.4–2.6 或整个恢复/所有权 scenario 勾选完成。

| 新增场景 | 本次证据 | 仍需验证 |
| --- | --- | --- |
| Upgrade the recognized V1 layout | `ExternalSchemaIT` 实测 fromVersion=1 → 2，安装 ID 保留，epoch 在升级完成后增加，V2 重开通过，旧外键/唯一性仍通过；业务合成行回滚。 | 有业务的完整 schema 升级不是本次 NAS 场景；不将其视为已验。 |
| Interrupted upgrade or competing instance | `MysqlLayoutTest` 验证中断不置 READY/不推进 epoch/不盲目重跑；实际 MySQL 第二实例在升级前及升级后均拒绝。 | 实际损坏布局/进程崩溃持久状态依照 2.3 待验，不破坏共享库来制造证据。 |
| Stable scoped conversation pagination | `ExternalPaginationIT` 在真实 MySQL 临时表执行生产 V002，验证非空原文保留、跨用户隔离、插入后旧游标稳定和 64 位精度；正式 V2 表的合成回滚事务验证序号及用户分页。 | 管理 repository/API 的 MySQL 接线属 3.2，未完成。 |

## conversation-history-cache

| Requirement | Scenario | 覆盖/替代入口 | 状态 |
| --- | --- | --- | --- |
| Isolated bounded history cache | Two users and a new conversation | 拟建 RedisHistoryIT：双用户、新会话/重绑、TTL/字节上限、原文和配置版本隔离；仅真实 Redis 验证（4.1、4.2、4.6）。 | 待实施/验证 |
| Isolated bounded history cache | Expired or oversized cache window | 拟建 RedisHistoryIT：双用户、新会话/重绑、TTL/字节上限、原文和配置版本隔离；仅真实 Redis 验证（4.1、4.2、4.6）。 | 待实施/验证 |
| Isolated bounded history cache | Model configuration is updated | 拟建 RedisHistoryIT：双用户、新会话/重绑、TTL/字节上限、原文和配置版本隔离；仅真实 Redis 验证（4.1、4.2、4.6）。 | 待实施/验证 |
| Authorization and snapshot validation on every read | Disable then resume during a cached request | 拟建 RedisHistoryIT：每次元数据授权、ABA、旧版本/容量不全、watermark 截止，N=0 不访问正文/Redis（4.2–4.3、4.6）。 | 待实施/验证 |
| Authorization and snapshot validation on every read | Stale refill or larger history setting | 拟建 RedisHistoryIT：每次元数据授权、ABA、旧版本/容量不全、watermark 截止，N=0 不访问正文/Redis（4.2–4.3、4.6）。 | 待实施/验证 |
| Incremental cache maintenance after confirmed delivery | Consecutive successful turns keep a warm window | 拟建 RedisHistoryIT：提交后增量、版本连续性、幂等、乱序/丢更新，精确 64 位 CAS、淘汰后迟到回填（4.3–4.4、4.6）。 | 待实施/验证 |
| Incremental cache maintenance after confirmed delivery | Duplicate update or a gap in updates | 拟建 RedisHistoryIT：提交后增量、版本连续性、幂等、乱序/丢更新，精确 64 位 CAS、淘汰后迟到回填（4.3–4.4、4.6）。 | 待实施/验证 |
| Incremental cache maintenance after confirmed delivery | Crash or Redis failure after database commit | 拟建 RedisHistoryIT：提交后增量、版本连续性、幂等、乱序/丢更新，精确 64 位 CAS、淘汰后迟到回填（4.3–4.4、4.6）。 | 待实施/验证 |
| Bounded degradation and recovery | Cache outage under concurrent traffic | 拟建 RedisFailureIT：延迟代理、断连、ACL、损坏/超大值、总预算、有界队列与同键合并；断言无事务等待和模型重试（4.5–4.6）。 | 待实施/验证 |
| Bounded degradation and recovery | Corrupt cache or cache recovery | 拟建 RedisFailureIT：延迟代理、断连、ACL、损坏/超大值、总预算、有界队列与同键合并；断言无事务等待和模型重试（4.5–4.6）。 | 待实施/验证 |
| Restarts and database restores cannot reuse unrelated cache state | Restore an older MySQL backup with newer Redis keys | 拟建 RedisHistoryIT：新安装/每次运行 epoch、旧 MySQL 恢复与新 Redis 数据不混用（4.1、4.6）。 | 待实施/验证 |
| Honest performance and cache reporting | Warm cache benchmark | 拟建 HistoryBenchmark/RedisDiagnosticsIT：100/1000/10000 轮、并发 1/4、直读/冷/热/故障路径，正文 SQL=0 热命中、元数据与真实 token 分开（4.7、8.2）。 | 待实施/验证 |
| Honest performance and cache reporting | Provider reports no cache usage | 拟建 HistoryBenchmark/RedisDiagnosticsIT：100/1000/10000 轮、并发 1/4、直读/冷/热/故障路径，正文 SQL=0 热命中、元数据与真实 token 分开（4.7、8.2）。 | 待实施/验证 |

## conversation-time-context

| Requirement | Scenario | 覆盖/替代入口 | 状态 |
| --- | --- | --- | --- |
| Trusted per-turn local time context | Current time question without manual prompt editing | ConversationTimeContextTest（采样、6 组时区/DST、无效值脱敏）和 ConversationTimeConfigurationTest（实际配置工厂默认/覆盖/初始化失败）。 | 时间合成已验 |
| Trusted per-turn local time context | Alternate timezone or invalid configuration | ConversationTimeContextTest（采样、6 组时区/DST、无效值脱敏）和 ConversationTimeConfigurationTest（实际配置工厂默认/覆盖/初始化失败）。 | 时间合成已验 |
| Request time is distinct from message receipt time | Message waits across midnight | ConversationTimeContextTest.samplesOnceAndDistinguishesQueuedReceiptAcrossMidnight；ManagedRepositoryTest.queuedTurnUsesAssemblyTimeAndNeverPersistsDynamicSystemText。 | 时间合成已验 |
| Stable retries and uncached dynamic context | Retry and then a new turn | CompatibleChatClientTest.strictRelayAcceptsStablePrefixAndFullHistoryWithoutSpecialCacheFields：429 后请求相同、新轮尾部变化、严格常规字段与角色；ManagedRepositoryTest 原文/配置不变。Redis 未实现，后续须在新引擎补验。 | 时间合成已验 |
| Stable retries and uncached dynamic context | Compatible gateway receives the request | CompatibleChatClientTest.strictRelayAcceptsStablePrefixAndFullHistoryWithoutSpecialCacheFields：429 后请求相同、新轮尾部变化、严格常规字段与角色；ManagedRepositoryTest 原文/配置不变。Redis 未实现，后续须在新引擎补验。 | 时间合成已验 |
| Time context obeys input budget | Time text pushes the request over budget | ConversationPolicyTest.dynamicSystemCostIsRequiredEvenWithNoHistoryAndExactBoundaryFits / trimsOnlyWholeOldTurnsAndNeverDropsRequiredTime；ManagedRepositoryTest.timeBudgetOverflowStaysLocalWithoutModelAttempt。 | 时间合成已验 |

## managed-container-deployment

| Requirement | Scenario | 覆盖/替代入口 | 状态 |
| --- | --- | --- | --- |
| One application container using existing external services | Reuse existing NAS services | 拟建 ExternalServicesConfigurationTest 与 ManagedImageIT：只含应用的 Compose、缺失/非法参数、缓存启停；NAS 实际版本另外授权（1.2、7.1、8.5）。 | 待实施/验证 |
| One application container using existing external services | Missing endpoint versus temporarily unavailable Redis | 拟建 ExternalServicesConfigurationTest 与 ManagedImageIT：只含应用的 Compose、缺失/非法参数、缓存启停；NAS 实际版本另外授权（1.2、7.1、8.5）。 | 待实施/验证 |
| Shared infrastructure and secret isolation | Other applications share Redis and MySQL | 拟建 ExternalServicesConfigurationTest/SharedResourcesIT：秘密脱敏、TLS 不降级、专用 schema/Redis ACL、旁置哨兵资源保持原样；审查危险命令（7.3、7.5）。 | 待实施/验证 |
| Shared infrastructure and secret isolation | Authentication or certificate error | 拟建 ExternalServicesConfigurationTest/SharedResourcesIT：秘密脱敏、TLS 不降级、专用 schema/Redis ACL、旁置哨兵资源保持原样；审查危险命令（7.3、7.5）。 | 待实施/验证 |
| Fresh bootstrap and private local materials | First start and subsequent restart | AdminApiTest/ManagedStoreTest 引导不重置、旧目录不写迁至 MySQL；PrivateStateFilesTest 保留；ManagedImageIT 实际 root/non-root 权限（2.3、3.2、7.5）。 | 待实施/验证 |
| Fresh bootstrap and private local materials | Restricted runtime identity | AdminApiTest/ManagedStoreTest 引导不重置、旧目录不写迁至 MySQL；PrivateStateFilesTest 保留；ManagedImageIT 实际 root/non-root 权限（2.3、3.2、7.5）。 | 待实施/验证 |
| Restricted ingress and bounded lifecycle | Redis fails but MySQL is available | AdminApiTest/HealthServerTest 保留；拟建 RedisDiagnosticsIT/MysqlOwnershipIT/ManagedImageIT 补健康分类、在途停止总预算（7.2、7.5）。 | 待实施/验证 |
| Restricted ingress and bounded lifecycle | Stop with in-flight remote work | AdminApiTest/HealthServerTest 保留；拟建 RedisDiagnosticsIT/MysqlOwnershipIT/ManagedImageIT 补健康分类、在途停止总预算（7.2、7.5）。 | 待实施/验证 |
| Versioned verification and safe backup recovery | Restore a new deployment | 拟建 ManagedImageIT amd64/arm64 实际运行及 MySQL 恢复；版本/性能/浏览器报告；NAS 扫码/付费验证仅获另行授权后执行（8.1–8.5）。 | 待实施/验证 |
| Versioned verification and safe backup recovery | Publish evidence for both target architectures | 拟建 ManagedImageIT amd64/arm64 实际运行及 MySQL 恢复；版本/性能/浏览器报告；NAS 扫码/付费验证仅获另行授权后执行（8.1–8.5）。 | 待实施/验证 |

## mysql-managed-persistence

| Requirement | Scenario | 覆盖/替代入口 | 状态 |
| --- | --- | --- | --- |
| Fresh exclusive MySQL business storage | Initialize an empty dedicated schema | 拟建 MysqlSchemaIT：空 schema、部分 DDL/未知布局失败关闭；保留旧文件散列/权限。现有 ManagedStoreTest/ManagedMigrationTest 迁移（2.1–2.3）。 | 待实施/验证 |
| Fresh exclusive MySQL business storage | Unrecognized schema or old files are present | 拟建 MysqlSchemaIT：空 schema、部分 DDL/未知布局失败关闭；保留旧文件散列/权限。现有 ManagedStoreTest/ManagedMigrationTest 迁移（2.1–2.3）。 | 待实施/验证 |
| Scoped identity and durable state constraints | Concurrent binding and message duplication | ManagedRepositoryTest/BindingCoordinatorTest 的同账号冲突、重认证、替换/ABA 迁至 MysqlIdentityIT；检查精确排序与外键（2.1、3.1）。 | 待实施/验证 |
| Scoped identity and durable state constraints | Reauthenticate or replace an identity | ManagedRepositoryTest/BindingCoordinatorTest 的同账号冲突、重认证、替换/ABA 迁至 MysqlIdentityIT；检查精确排序与外键（2.1、3.1）。 | 待实施/验证 |
| Atomic work transitions with bounded concurrency | Batch fails before commit | ManagedRepositoryTest/RuntimeManagerTest 的批次回滚、积压、慢用户隔离迁至 MysqlWorkflowIT，补真实行锁并发（2.4、3.3–3.4、3.7）。 | 待实施/验证 |
| Atomic work transitions with bounded concurrency | Competing workers and a slow remote call | ManagedRepositoryTest/RuntimeManagerTest 的批次回滚、积压、慢用户隔离迁至 MysqlWorkflowIT，补真实行锁并发（2.4、3.3–3.4、3.7）。 | 待实施/验证 |
| Exclusive application ownership before recovery | A second instance starts | 拟建 MysqlOwnershipIT：两实例、专用锁连接 KILL/断网、epoch 迟到结果，不允许恢复写入（2.5–2.6）。 | 待实施/验证 |
| Exclusive application ownership before recovery | Runtime ownership becomes uncertain | 拟建 MysqlOwnershipIT：两实例、专用锁连接 KILL/断网、epoch 迟到结果，不允许恢复写入（2.5–2.6）。 | 待实施/验证 |
| Bounded authorized history reads | Long history contains failed and future turns | 拟建 MysqlHistoryIT：合格完整轮次先过滤再 LIMIT、执行计划、N=0、配置版本、未来记录与重复完成（3.6）。 | 待实施/验证 |
| Bounded authorized history reads | History configuration changes | 拟建 MysqlHistoryIT：合格完整轮次先过滤再 LIMIT、执行计划、N=0、配置版本、未来记录与重复完成（3.6）。 | 待实施/验证 |
| Stable conversation lifecycle across storage changes | Queue crosses an idle or midnight boundary | ManagedRepositoryTest 的 idleWindowSlides/timeoutUpdate/newCommand/queuedTurn 迁至 MysqlWorkflowIT，保留受控 Clock（3.3、3.7）。 | 待实施/验证 |
| Stable conversation lifecycle across storage changes | Exact idle boundary and restart | ManagedRepositoryTest 的 idleWindowSlides/timeoutUpdate/newCommand/queuedTurn 迁至 MysqlWorkflowIT，保留受控 Clock（3.3、3.7）。 | 待实施/验证 |
| Recover uncertain operations without replay | Crash after generation or during sending | ManagedRepositoryTest/RuntimeManagerTest 的恢复、未知发送、用量归属迁至 MysqlWorkflowIT/MysqlOwnershipIT，加入 DB 故障与健康缓存（2.6、3.4–3.7）。 | 待实施/验证 |
| Recover uncertain operations without replay | MySQL fails while Redis is healthy | ManagedRepositoryTest/RuntimeManagerTest 的恢复、未知发送、用量归属迁至 MysqlWorkflowIT/MysqlOwnershipIT，加入 DB 故障与健康缓存（2.6、3.4–3.7）。 | 待实施/验证 |

## runtime-simplification

| Requirement | Scenario | 覆盖/替代入口 | 状态 |
| --- | --- | --- | --- |
| One supported production runtime and storage path | Attempt to use legacy configuration | 更新 WorkflowContractTest/FoundationContextTest，拟建 ProductionArtifactIT 检查 profile、旧类/SQLite 驱动缺失（6.2、7.4–7.5）。 | 待实施/验证 |
| One supported production runtime and storage path | Inspect the production artifact | 更新 WorkflowContractTest/FoundationContextTest，拟建 ProductionArtifactIT 检查 profile、旧类/SQLite 驱动缺失（6.2、7.4–7.5）。 | 待实施/验证 |
| Diagnostic tools are explicit nonproduction artifacts | Run CI or an operator diagnostic | 协议/模型探针合成测试保留；`ManagedContainerProbeTest` 与独立 diagnostics JAR 实际检查正式候选；`WorkflowContractTest` 验证诊断接线和未验收发布阻断（6.4）。正式镜像内运行仍属 7.5/8.4。 | 本机构建/加载已验；镜像运行待验 |
| Retire obsolete contracts without reducing active safety coverage | Replace SQLite-specific tests | 本文件逐项退役映射；MySQL 行为接管通过后才删旧测试，WorkflowContractTest 更新命令/产物门禁，所有遗漏均保留未完成（6.5、8.1、8.6）。 | 待实施/验证 |
| Retire obsolete contracts without reducing active safety coverage | Review cleanup and documentation | 本文件逐项退役映射；MySQL 行为接管通过后才删旧测试，WorkflowContractTest 更新命令/产物门禁，所有遗漏均保留未完成（6.5、8.1、8.6）。 | 待实施/验证 |

## 旧测试和入口的处理规则

没有删除任何测试类。除明确无调用的 `ManagedTurnWorker.compatibleModel()`，旧实现暂留到真实 MySQL 替代测试通过；`AssistantProperties.Model` 已抽为 `ModelConfiguration`，字段/验证与 JSON 序列化契约保留。

| 当前测试/夹具 | 处理 | 必须保留或替代的保障 |
| --- | --- | --- |
| SqliteStoreTest、SchemaMigrationTest | 随单用户 SQLite 退出 | PRAGMA/旧迁移契约退役；空布局、未知布局拒绝、原文件不写由 MysqlSchemaIT 覆盖 |
| ConversationRepositoryTest、DurableWorkflowTest | 行为迁移后退出旧实现测试 | 去重、会话分配、确认历史、未知发送、事务失败，迁至 MysqlWorkflowIT/MysqlHistoryIT |
| AssistantRuntimeTest、FoundationContextTest | 随旧运行路径退出/改写入口契约 | 停止、就绪、旧 profile 禁用与材料保护迁至 RuntimeManagerTest/ProductionArtifactIT |
| LoginCoordinatorTest、WechatOutboundTest | 随旧单用户包装退出 | 身份复核、发送不确定性保留于 BindingCoordinatorTest/RuntimeManagerTest/MysqlWorkflowIT |
| AssistantPropertiesTest | 拆分模型与退役配置保障 | 模型 URL/预算/秘密校验继续执行于抽取后的 ModelConfiguration；删除外壳前搬至 ModelConfigurationTest |
| ManagedStoreTest、ManagedMigrationTest | 换成真实 MySQL 布局/所有权测试 | 初始化失败无破坏、独占锁、版本检查、恢复、旧材料拒绝；旧 V1→V2 SQLite 升级不沿用为 MySQL 证据 |
| ManagedRepositoryTest | 原安全行为迁至真实 MySQL；新增时间用例同迁 | 用户绑定隔离、ABA、热更新、分页、配额、用量、批次、会话/队列/原文 |
| RuntimeManagerTest、BindingCoordinatorTest、AdminApiTest | 保留，替换持久化夹具 | 授权、并发、生命周期、管理安全、二维码归属；不可用假库声称验证 MySQL |
| ManagedModelPoolTest、FairUserSchedulerTest | 保留 | 租约、热切换、每用户顺序与公平配额 |
| CompatibleChatClientTest、TokenUsageTest、ConversationPolicyTest | 保留并扩展 | 常规协议字段、真实用量、重试/超时、全轮裁剪；前缀断言已调整为固定 system/相同原文稳定 |
| PrivateStateFilesTest、HealthServerTest、SafeDiagnosticsTest | 保留 | 私有权限/符号链接防护、内部健康边界、脱敏；Windows 的 native symlink 用例跳过须在 Linux 补验 |
| WechatApiClientTest、ScannerIdentityResolverTest | 保留 | 微信协议、可信主机、身份解析，均用合成数据 |
| ContainerStorageProbeTest | 随 SQLite 探针退出 | 替为真实 MySQL/Redis + 正式镜像验证，不以本地 SQLite 读写替代 |
| ManagedContainerProbeTest | 改造并迁诊断产物 | root/non-root、旧目录无写入与正式应用健康；当前两个 POSIX 用例 Windows 报错，不删除/伪装通过 |
| WechatConnectivityProbeTest、TwoAccountWechatProbeTest、ModelConnectivityProbeTest | 随探针移到独立诊断 classpath | 保留显式授权、秘密保护、严格合成协议；测试不得访问真实服务 |
| WorkflowContractTest | 更新 | 发布门禁、测试镜像来源、诊断分离和构建命令 |
| binding-ui.test.cjs、admin-details-ui.test.cjs | 保留 | 迟到响应、跨用户、退出/失效、文本渲染、防止竞态覆盖；不是浏览器 E2E 替代 |
| ManagedBrowserFixture、AdminUsageFixture、FakeHttpService、TestProperties | 保留在 test classpath 并适配 | 不打包生产；不连接 NAS、不真实扫码、不付费 |
| ConversationTimeContextTest、ConversationTimeConfigurationTest、ModelConfigurationTest | 本轮新增，保留 | 可控时间、时区失败关闭和共享配置 JSON 兼容 |

## 原多用户 change 的未完成项

`add-multi-user-wechat-admin` 保持原 47/52 状态，未修改其 tasks：9.2（权限/布局烟测）、9.3（8 秒退出）、10.3（真实浏览器端到端）、10.4（双架构镜像实际验证）、10.5（授权后 NAS/真实扫码/模型验收）。其 SQLite/部署/完整请求前缀前提在新 change 接管并同步规格时须协调，不自动继承为通过。

# 当前场景覆盖（2026-09-30）

本文件取代 `coverage.md` 下半部的早期规划状态。历史执行记录保留在 `implementation-status.md`；没有修改旧 change 的任务。

“已验”指本机 Java 21 + 显式外部 MySQL 8.0.46 / Redis 7.4.9 的合成证据，不代表真实微信、付费模型、Linux 容器或备份恢复通过。所有外部测试只用已授权 schema、独占空业务夹具与随机 TTL 子键；不清表、不重置序号、不停止共享服务。

## conversation-history-cache（13）

| Scenario | 当前证据 | 状态 |
| --- | --- | --- |
| Two users and a new conversation | ExternalHistoryIT 两用户真实缓存、跨用户帧注入拒绝、重认证保留历史但撤销旧代次、换身份与 /new 空历史；HistoryBenchmarkIT 四用户独立缓存 | 合成已验 |
| Expired or oversized cache window | ExternalRedisIT 真实 TTL/STRLEN 有界读取；HistoryServiceTest 完整窗口超限不回填、不丢数据库结果 | 已验 |
| Model configuration is updated | HistoryServiceTest 新旧版本独立键、旧配置窗口拒绝；ManagedRepositoryIT 热更新快照 | 已验 |
| Disable then resume during a cached request | ExternalHistoryIT 缓存后的停用/恢复 ABA 拒绝；HistoryServiceTest 等待回源后重授权 | 已验 |
| Stale refill or larger history setting | HistoryServiceTest 陈旧 revision、增大 N、历史切片；ExternalRedisIT 延迟旧值和较小窗口 CAS 拒绝 | 已验 |
| Consecutive successful turns keep a warm window | ExternalHistoryIT 真实确认后增量；HistoryBenchmarkIT 连续完整轮次热命中且正文 SQL=0 | 已验 |
| Duplicate update or a gap in updates | HistoryServiceTest 重复/间隙通知不追加；ExternalHistoryIT 真实 Redis 上新通知先到、旧通知迟到、重复通知均不破坏当前窗口 | 已验 |
| Crash or Redis failure after database commit | ExternalHistoryIT 确认提交后受控丢更新，MySQL 版本保留，真实陈旧键回源修复；RuntimeManagerIT 不确定发送不重放 | 应用侧故障已验；非服务崩溃实验 |
| Cache outage under concurrent traffic | RedisHistoryCacheTest 本机慢 peer、32 并发旁路；HistoryServiceTest 单飞；HistoryBenchmarkIT 1/4 并发真实持久阶段，模型次数不增加 | 已验 |
| Corrupt cache or cache recovery | ExternalRedisIT 损坏/超大值修复、测试自有代理断连及默认预算恢复；无服务重启 | 已验 |
| Restore an older MySQL backup with newer Redis keys | HistoryServiceTest 新运行随机命名空间；真实 store 重开 epoch 验证。没有执行备份覆盖或服务级淘汰 | **实际恢复待验（4.6/8.5）** |
| Warm cache benchmark | HistoryBenchmarkIT：100/1000/10000 × 1/4 × 直读/冷/热/故障，见 performance.md | 已验；无稳定整轮加速承诺 |
| Provider reports no cache usage | ManagedUsage/TokenUsage/Node 回归保持未知；调参 benchmark 明确断言热 Redis 下 cached token 和比率仍为 null | 已验 |

## conversation-time-context（6）

| Scenario | 当前证据 | 状态 |
| --- | --- | --- |
| Current time question without manual prompt editing | ConversationTimeContextTest、ManagedRepositoryIT 每轮请求装配 | 合成已验 |
| Alternate timezone or invalid configuration | ConversationTimeContextTest 多偏移/DST；ConversationTimeConfigurationTest 启动拒绝非法时区 | 已验 |
| Message waits across midnight | 可控 Clock 区分接收与请求时间；ManagedRepositoryIT 固定接收时会话 | 已验 |
| Retry and then a new turn | CompatibleChatClientTest 重试消息字节稳定、新轮重新采样；原文不改写 | 已验 |
| Compatible gateway receives the request | 严格合成本机网关只接受常规消息字段、角色和顺序 | 合成已验；真实网关待独立授权 |
| Time text pushes the request over budget | ConversationPolicyTest 整轮裁剪；ManagedRepositoryIT 本地拒绝且无模型 attempt | 已验 |

## mysql-managed-persistence（17）

| Scenario | 当前证据 | 状态 |
| --- | --- | --- |
| Initialize an empty dedicated schema | ExternalSchemaIT 实际 0→V2，20 表，后续重开；没有导入 SQLite | 已验 |
| Unrecognized schema or old files are present | MysqlLayoutTest 未知/部分/不兼容布局拒绝；ManagedMaterialsTest/独立 probe 旧目录原样保留 | 无网络/本机已验，实际故障布局待安全目标（2.3） |
| Upgrade the recognized V1 layout | 已授权 V1→V2 历史执行记录；ExternalPaginationIT 真实追加列、保留数据，V2 重开序号稳定 | 已验；未降级重跑 |
| Interrupted upgrade or competing instance | MysqlLayoutTest 持久 INITIALIZING/中断拒绝；真实第二实例不能写入或推进 epoch | 部分：实际中断布局待验（2.3） |
| Stable scoped conversation pagination | ExternalPaginationIT、ManagedRepositoryIT 数字游标/范围/64 位序号 | 已验 |
| Concurrent binding and message duplication | ManagedRepositoryIT、BindingCoordinatorIT 唯一身份、精确比较、并发去重 | 已验 |
| Reauthenticate or replace an identity | ManagedRepositoryIT、BindingCoordinatorIT、RuntimeManagerIT 同身份/不同身份、generation/auth_epoch/ABA | 已验 |
| Batch fails before commit | ManagedRepositoryIT 原子游标/事件/会话/容量回滚；ExternalTransactionsIT | 已验 |
| Competing workers and a slow remote call | RuntimeManagerIT 慢用户隔离；ManagedRepositoryIT 并发 claim/全局容量；连接池短事务 | 已验 |
| A second instance starts | ManagedRepositoryIT live 工作、attempt、邀请与 epoch 不被第二实例改写 | 已验 |
| Runtime ownership becomes uncertain | 测试自有 JDBC 连接 abort，RuntimeManagerIT 全连接取消、BindingCoordinatorIT QR 清理、迟到工作被围栏 | 已验 |
| Long history contains failed and future turns | ExternalHistoryIT 10000 轮、失败/未知、未来边界、先过滤再 LIMIT、实际索引计划 | 已验 |
| History configuration changes | ExternalHistoryIT N=0/2/20；HistoryServiceTest 大小窗口与版本；配置热更新回归 | 已验 |
| Queue crosses an idle or midnight boundary | ManagedRepositoryIT 受控时钟、排队归属和时间快照 | 已验 |
| Exact idle boundary and restart | ManagedRepositoryIT 边界值/热超时；RuntimeManagerIT 重开后保留记录 | 已验 |
| Crash after generation or during sending | ManagedRepositoryIT/RuntimeManagerIT PROCESSING/SENDING/已保存回复分流，未知不重放、用量归属 | 应用重开与故障已验 |
| MySQL fails while Redis is healthy | HistoryServiceTest 数据库失败不被缓存掩盖；真实所有权 abort 与旧 owner 永久失效 | 已验 |

## managed-container-deployment（10）

| Scenario | 当前证据 | 状态 |
| --- | --- | --- |
| Reuse existing NAS services | 外部 IT 显式无 SSL 直连；Compose 仅应用；WorkflowContractTest | 本机已验，双架构镜像待验 |
| Missing endpoint versus temporarily unavailable Redis | ExternalServicesTest 缺失值拒绝；ExternalRedisIT 应用侧故障；浏览器展示 DEGRADED 且 RUNNING | 已验 |
| Other applications share Redis and MySQL | 目标审批/空业务保护/独占 fixture；精确主键清理、有限 TTL 子键；无全局命令 | 授权范围验证已验；未探查其他项目数据 |
| Authentication or certificate error | 配置/驱动无透明重连、不自动关闭 TLS/公钥校验；错误脱敏回归。当前操作者选择无 SSL | 无 SSL 已验；真实 TLS 证书故障未运行 |
| First start and subsequent restart | AdminApiIT 初始化/不重置；MySQL 重开；合成浏览器登录与设置持久化 | 本机已验 |
| Restricted runtime identity | ManagedMaterialsTest、PrivateStateFilesTest、独立 probe；不使用 777 | macOS 已验；Linux root/non-root 待镜像 |
| Redis fails but MySQL is available | 浏览器 RUNNING + DEGRADED；真实缓存故障 benchmark；管理诊断/内部健康边界 | 已验 |
| Stop with in-flight remote work | ManagedShutdownTest 统一预算；RuntimeManagerIT 不合作在途模型 <8s、迟到结果 UNKNOWN；浏览器整进程停止约0.82s | 本机已验；容器停止待验 |
| Restore a new deployment | 安全备份与回滚文档；未获覆盖目标授权，没有实际恢复 | **待验（8.5）** |
| Publish evidence for both target architectures | Docker 29.5.2 可用；正式 arm64 构建两次在 Docker Hub 认证连接重置失败；amd64 未运行 | **待验，发布仍阻断** |

## runtime-simplification（5）

| Scenario | 当前证据 | 状态 |
| --- | --- | --- |
| Attempt to use legacy configuration | FoundationContextTest、WorkflowContractTest，新依赖缺失失败，没有旧 profile 回退 | 已验 |
| Inspect the production artifact | 独立 ManagedContainerProbe 检查实际候选 JAR，拒绝旧 SQL/SQLite/授权入口/夹具/诊断 | JAR 已验；镜像待验 |
| Run CI or an operator diagnostic | 独立 diagnostics JAR、合成探针；受保护独立 CI 外部阶段与未验收发布门禁 | 本机已验，CI 外部/正式镜像未运行 |
| Replace SQLite-specific tests | 下方退役映射；MySQL 真实仓储、状态机、所有权与恢复覆盖 | 已验；2.3 实际破坏性布局另列 |
| Review cleanup and documentation | 活跃 MySQL/Redis 开发/运维文档、旧资料历史标记、当前覆盖/性能/未运行报告 | 已完成 |

## 退役、保留、迁移（当前真实文件）

- 已退役 SQLite/单用户实现专属测试：SqliteStoreTest、SchemaMigrationTest、ConversationRepositoryTest、DurableWorkflowTest、AssistantRuntimeTest、LoginCoordinatorTest、WechatOutboundTest、AssistantPropertiesTest、ManagedStoreTest、ManagedMigrationTest、ContainerStorageProbeTest。旧 SQL/profile/驱动不在候选 JAR。没有删除真实 SQLite、登录材料或旧 `target`。
- 对应有效保障迁入：ExternalSchemaIT/ExternalPaginationIT/ExternalTransactionsIT/ExternalHistoryIT、MysqlLayoutTest、MysqlTransactionsTest、ManagedMaterialsTest、ManagedRepositoryIT、RuntimeManagerIT、BindingCoordinatorIT、AdminApiIT。旧 ManagedRepositoryTest/RuntimeManagerTest/BindingCoordinatorTest/AdminApiTest 是改用真实引擎的 `*IT`，不是减少其隔离、安全和管理断言。
- 原样保留或扩展：ManagedModelPoolTest、FairUserSchedulerTest、CompatibleChatClientTest、TokenUsageTest、ConversationPolicyTest、PrivateStateFilesTest、HealthServerTest、SafeDiagnosticsTest、WechatApiClientTest、ScannerIdentityResolverTest、FoundationContextTest、WorkflowContractTest；Node 页面竞态 14 项。
- 探针只在独立 test/diagnostics classpath：ManagedContainerProbe、WechatConnectivityProbe、TwoAccountWechatProbe、ModelConnectivityProbe 及相应测试；无隐蔽生产入口。
- 新覆盖：ModelConfigurationTest、ConversationTimeContextTest、ConversationTimeConfigurationTest、ExternalServicesTest、ExternalIntegrationTargetTest、HistoryCacheFrameTest、HistoryServiceTest、RedisHistoryCacheTest、ExternalRedisIT、HistoryBenchmarkIT、ManagedShutdownTest。
- ManagedBrowserFixture、AdminUsageFixture、SyntheticHistory 只在测试 classpath。浏览器使用同一个授权 owner、空业务 preflight、随机 Redis 子前缀、新私有材料目录和固定回环端口；真实模型/发送被禁用，退出按精确主键清理本次合成数据。

共 **51** 个 scenario：缓存13 + 时间6 + MySQL17 + 部署10 + 精简5。旧 change `add-multi-user-wechat-admin` 的47/52及5项未完成验收不被本 change 代勾、归档或同步。

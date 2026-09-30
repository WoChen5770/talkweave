# 实施记录（2026-09-30）

## 暂停交接点（2026-09-30，用户要求下班前暂停并本地提交）

**状态：PAUSED / WIP。停止后续实施、测试和部署，仅记录交接并保存本地 Git 提交；不得把本提交作为已验收版本部署。** 以下交接点优先于后文按阶段保留的历史记录；后文“尚未替换 SQLite”“公共模板仍使用 TLS”等描述不代表当前源码。

任务清单目前勾选 **13/45**：1.1–1.4、2.1、2.2、2.7、5.1–5.4、6.1、6.3。其余 32 项包含本轮已推进但尚未完成全部验收的工作，暂停时不仓促补勾，不归档 change，也不同步主规格。

### 当前源码与运行边界

- 生产 `ManagedStore`、用户/会话/设置/用量仓储及 Spring 接线已改为外部 MySQL；短事务、运行 epoch、身份/容量锁、恢复和送达确认已接入。确认送达首次原子推进历史 revision/count/watermark，重复确认不重复推进。
- 历史查询先过滤作用域、成功模型结果、确认送达及序号上限，再按索引取最近 N 轮；真实 EXPLAIN 暴露的 filesort 已通过生产查询索引和连接顺序调整修复。
- `HistoryWindow`、`RedisHistoryCache`、`HistoryService` 已接入运行链路，包括 MySQL 读时授权、严格窗口校验、回源、合并回填、送达后增量更新、有限超时/熔断及管理端缓存状态。动态当前时间仍在每轮请求时生成，不缓存到历史窗口。
- 修复真实运行测试发现的中断问题：所有权连接检查由独立平台线程执行，调用方中断不关闭共享锁连接；确认仅中止借用事务连接的取消不误封禁整个实例；重新认证状态在持久化失效后发布。
- 已移除生产 SQLite 依赖、旧迁移资源和旧单用户运行路径；对应仓储、管理、绑定和运行测试迁为 MySQL IT，保留行为断言。连接诊断移至 test 源集并配置独立 diagnostics JAR；历史诊断参考保留在 `scripts/diagnostics/legacy/`，不是可运行的当前工具。
- Docker/Compose 仅提供应用并连接外部服务，不增加 MySQL/Redis 镜像。公共模板及已忽略的本地配置均按用户选择不启用 SSL；本地连接信息不提交。新材料目录与旧 SQLite/登录材料隔离，旧真实文件没有删除。
- 已有 NAS 布局为 20 表、V2 READY，保留安装标记、epoch 和自增高水位；没有停止共享服务、清空数据库或 Redis。测试仅清理独占合成业务行及随机项目测试键。
- 旧运行 JAR 未覆盖、旧进程未停止，新候选 JAR 尚未构建。已增加 `talkweave.artifact-name` 供后续另名打包，不能据此声称运行中的应用已升级。

### 暂停前验证证据（不是最终全量通过）

- 真实 MySQL：`ManagedRepositoryIT` 25/25、`AdminApiIT` 9/9、`BindingCoordinatorIT` 10/10、修复后的 `RuntimeManagerIT` 17/17 通过；`ExternalTransactionsIT` 在取消处理调整后通过。
- `ExternalHistoryIT` 在生产查询调整后通过：过滤先于 LIMIT、零历史轮数、64 位标记、重复送达、索引计划，以及 Redis 热命中不读正文、增量更新、随机作用域键/60 秒 TTL。该 IT 使用 1 秒命令/2 秒阶段预算，不能作为默认 100/250 毫秒在 NAS 负载下的验收证据。
- 定向 `HistoryServiceTest` 8 项与 `MysqlTransactionsTest` 17 项，共 25 项通过。
- 最后一次较广单测使用 `-Dtest=*,!*IT,!WorkflowContractTest`：218 项中 215 通过、1 failure、1 error、1 原有 native symlink 跳过。失败/错误均来自模型诊断探针依赖已移除的旧配置默认值；随后已调整为独立配置环境与显式默认值，**尚未复测**。
- `WorkflowContractTest` 尚未更新为新的 Compose、诊断产物和烟测约定；最新容器探针测试、CI 修改尚未复测。早期 Node 14/14、OpenSpec 严格校验及外部 schema/兼容性/分页通过记录不代表最终提交已完整回归。

### 恢复工作时的待办

1. 复测模型诊断配置修复，更新 `WorkflowContractTest`，执行最终单测和显式外部 IT，核对测试退役/迁移覆盖与任务清单。
2. 补完整体 8 秒关闭预算、缓存慢响应/故障/并发回填和恢复覆盖，以及 100/1000/10000 历史量 × 1/4 并发性能矩阵；尚未测得可宣称的加速比例。
3. 核对配置/开发/诊断/覆盖文档与当前实现，构建独立候选应用和 diagnostics 产物，补验 Linux 权限、双架构镜像和浏览器端到端。
4. CI 发布步骤目前明确 `exit 1` 阻断，等待授权的外部服务运行验收接入；网络隔离烟测显式记录 `externalServices=NOT_RUN`，不能代替任务 7.4。
5. 真实扫码、付费模型、覆盖恢复及共享服务级操作未执行；需要单独授权，不能以恢复实施为由默认执行。当前只保存本地提交，不推送、不发布、不部署。

## 历史阶段记录（以下内容按当时进度保留）

## 本次确认后的 V002 与事务基础实施

- 用户确认后，在 proposal、design、MySQL spec 和 tasks 中补充 V002 追加式布局升级；增加任务 2.7 和 3 个 scenario，保留先前已完成项及旧 change 状态。当前新增任务 2.7 已完成。
- V001 规范化 SHA-256 仍为 `b07695d51959e5955c6df96c7d9b9a50a944362e956c4a198d7e99c65f65bfc5`，增加回归断言防止改写。V002 只对 conversation 追加 BIGINT 自增序号、唯一索引、用户分页索引，不变更 UUID 主键及旧字段/外键。
- `MysqlLayout` 精确识别 READY V1/V2；V1 在独占锁下先持久记录 V2 INITIALIZING，再执行追加 DDL，保存版本链/布局摘要并置 READY 后才增加 epoch。失败不盲目重试、不删除表或伪称 DDL 已回滚；新库也沿不变 V001→V002 链初始化。
- 已在此前授权且无业务行的 NAS 项目库实际执行 **fromVersion=1 → toVersion=2**，安装 ID 保留；后续 **2→2 重开**通过，第二实例在升级前/后均拒绝。表数仍为 20，原外键/唯一约束和回滚合成分页验证通过。未停止共享服务、重启旧应用、改动 SSL 或清库。
- `ExternalPaginationIT` 在真实 MySQL 的连接私有临时表执行生产 V002，验证非空合成原文保留、用户游标、插入后旧序号稳定及超过 2^53 的精度。临时表随连接关闭消失；不把该测试当作已承载真实业务的完整 schema 升级或 NAS 崩溃恢复验收。实际破坏性布局故障仍在 2.3 待验。
- 继续提供 `MysqlTransactions` 与 JDBC `Sql.insertId` 基础：连接池短事务、不全局同步；运行 epoch 共享行锁在容量/用户锁之前；开始及提交前核对独占运行权；纯 SQL 死锁最多额外重试两次，提交不确定、超时、连接或回滚失败不重试；异常不保留原始 SQL/驱动原因。
- `ExternalTransactionsIT` 真实验证两个独立池连接可并行、合成行回滚、JDBC 大整数主键；只中止测试自己的非池化锁连接，确认当前事务回滚，原对象在新 owner/epoch 建立后仍不能执行回调。未终止其他连接，未保留合成业务行。
- 事务执行器尚未替换 `ManagedStore` 和现有 repositories，运行面/模型/发送前复核及迟到用量接线仍缺；**2.4–2.6 保持未完成**。SQLite 生产路径与旧测试未提前退役，也未部署新 JAR。
- 定向单测 `MysqlLayoutTest` 12 项、`MysqlTransactionsTest` 16 项通过；最终显式外部 profile **4/4 通过**（分页、V2 布局、MySQL/Redis 兼容性、事务）；Node 页面竞态 **14/14 通过**。OpenSpec 严格校验通过，apply 为 ready、13/45。
- 本轮全套 Java 命令 `mvn -o -s scripts/maven-public-settings.xml -Dmaven.repo.local=E:/.m2 '-Dtest=*,!ManagedContainerProbeTest,!*IT' test -q`：**334 项，333 通过、1 项原有 native symlink 跳过、0 失败/错误**。Windows POSIX 容器测试仍未验，不改弱断言。随后强化回滚失败时永久关闭运行权，并重跑上述两类及 `ExternalIntegrationTargetTest` 共 **43 项通过**；该执行器仍未接入现有业务路径。补丁检查通过。

## 本次续作：显式外部集成入口

- 完成 1.3：`external-services` profile 直接连接显式授权的已有服务；配置路径、库名、项目键前缀与实际精确版本逐一核对。默认单元测试不选择 IT、不加载本地 NAS 文件，缺少授权参数的 IT 明确失败。
- 原 `NasCompatibilityIT` / `NasSchemaIT` 分别迁为 `ExternalServicesIT` / `ExternalSchemaIT`，原引擎断言保留。新增无网络目标校验、随机合成范围和秘密脱敏单测；布局测试在独占锁下先拒绝任何已承载业务的库，不清理数据。Redis 键使用每次运行的 `:it:<uuid>:` 子前缀和 60 秒 TTL。
- 使用此前已授权 NAS 目标和显式无 SSL 配置执行两项 IT 通过；MySQL 合成行回滚，未重启服务/清库/发布。另执行缺少参数的 profile，2 项均在 CONFIG 阶段按预期失败、0 跳过，验证不能冒充集成通过。
- 以下镜像失败为早期历史记录，不再是实施或外部测试前提；按用户确认直接复用已有服务。无法安全执行的恢复/布局破坏场景继续待验，不阻断其他安全任务。

本次验证结果（Java 21.0.11 / Windows amd64）：

- `ExternalIntegrationTargetTest`：15 项通过；配合已有 `ExternalServicesTest` 33 项配置测试，定向回归 48 项通过。
- `mvn -o -s scripts/maven-public-settings.xml -Dmaven.repo.local=E:/.m2 '-Dtest=*,!ManagedContainerProbeTest,!*IT' test -q`：306 项，305 通过、1 项原有 native symlink 跳过、0 失败/错误。已知 Windows POSIX 容器测试仍显式排除，未改弱断言。
- 显式 `-Pexternal-services` 配齐授权参数：`ExternalSchemaIT`、`ExternalServicesIT` 2/2 通过；缺少参数的负向命令明确失败，随后完整参数复测 2/2 通过。没有真实微信、付费模型或服务级故障操作。
- Node 页面竞态 14/14 通过；沙箱首次 `spawn EPERM` 后经权限机制重跑，不修改测试。OpenSpec 严格校验与 `git diff --check` 通过。

### 上一轮仓储布局缺口（本轮已确认并完成 V002）

`ManagedUsage.conversations` 及现有管理 API/Node 分页测试依赖稳定的 64 位 `sequence`/`before` 游标，当前 SQLite 查询使用 `conversation.rowid`。已执行的 MySQL `V001__managed.sql` 中 `conversation` 只有 UUID 主键及时间/历史标记，没有对应分页序号。`MysqlLayout` 又只接受精确匹配的 V1 脚本与结构摘要，直接改写 V001 会使已授权 NAS 布局无法重新打开。

上一轮建议保留已执行 V001，补充 V002 的仅新增分页序号/索引升级，并明确独占锁、精确旧布局识别、升级中断标记和重开验证；不删表、不清库、不改现有管理接口。当时任务只定义空库初始化及匹配布局重开，因此按 `openspec-apply-change` 的设计问题暂停规则请求确认。用户本轮同意后已完成上述补充和 V002 实施，该设计阻碍已解除；生产业务接线仍未完成。

## 既有实现概况

- managed 每轮在历史读取后采样一次时间，顺序为固定 system、原历史、动态 system 时间、当前 user。默认 `Asia/Shanghai`，`CONVERSATION_TIME_ZONE` / `managed.conversation.time-zone` 可覆盖，修改后重启。
- 时间含日期/时分秒/星期/区域/偏移，接收时间另行标注；重试不重采样，新轮重采样。最低预算包含时间，超限不调用模型。
- 共享 `ModelConfiguration` 已脱离旧 `AssistantProperties.Model`，管理字段、验证、持久 JSON 与配置快照保持兼容。扫描调用方后删除无使用方的 `ManagedTurnWorker.compatibleModel()`，实际模型池保留。
- 五份新 spec 的全部 **51 个 scenario**（原 48 + V002 新增 3）及测试退役/保留/迁移计划见 [覆盖清单](coverage.md)。原 NAS 测试只迁移命名并保留断言，没有删除安全测试覆盖、SQLite 文件或登录材料，也未替换 SQLite 生产存储。
- 首轮实施没有连接 NAS；用户后续填写连接配置后的只读核验见下节。未扫码、调用真实/付费模型、升级外部服务、停止已有应用、提交、推送、发布、归档或同步主规格。

## 用户填写配置后的只读核验

用户确认已填写 `config/external-services.local.yml` 后，使用独立 `scripts/diagnostics/ExternalServicesCheck.java` 检查连接字段并尝试只读连接；本地配置始终由 Git 忽略，未打印或复制凭据。工具使用 Java 21、SnakeYAML 2.4 和 MySQL Connector/J 9.6.0；后两者与当前 Spring Boot BOM 一致，仅作为诊断 classpath 使用，没有加入生产依赖。

- 连接字段类型/必填值/端口/schema/TLS 校验通过；其他运行参数尚未完整校验，任务 1.2 未完成。
- 首次使用模板 TLS 设置核验：MySQL `VERIFY_IDENTITY` 在证书信任阶段失败（`CERTIFICATE_TRUST`，SQLState `08S01`）；Redis TCP 可连接，但 TLS 握手超时。首次检查未取得服务版本。
- 用户明确要求“不启用ssl”后，仅将被 Git 忽略的本地配置改为 MySQL `ssl-mode: 'DISABLED'`、Redis `tls: false`；公共模板仍保留 TLS 默认值。
- 重新只读核验成功：MySQL **8.0.44**，事务隔离级别 `REPEATABLE-READ`；Redis **7.2.12**，认证后 `PING` 返回 `PONG`。两条连接均未加密。
- MySQL 目标 schema 未发现可见表、视图、例程、触发器或事件；当前账号的直接授权包含全局权限，部署前应由操作者收敛为项目专用权限。检查未展开角色授权，也不能将元数据可见性等同于初始化授权。
- 上述只读检查未在服务端建表、读取业务数据或读写 Redis 键；后续用户明确回复“都允许”，授权所配置空库建表、合成数据及项目专属 Redis 测试键的读写，执行结果见下节。共享 NAS 服务的停止、重启、恢复及服务级故障注入不在本次操作范围。
- 只读核验结束时没有新增完成任务，进度为 **7/44**；不将连接成功等同于完整集成验收。

## 授权后的配置、兼容性及 schema 实施

本轮新增完成 1.1、1.2、2.1、2.2。没有修改本地真实凭据或 SSL 开关，仍为 MySQL `DISABLED`、Redis `tls: false`；没有提交、重启旧应用或切换运行存储。

- 固定实际基线：NAS **MySQL 8.0.44 / Redis 7.2.12**，项目使用 **Connector/J 9.6.0 / HikariCP 6.3.3 / Lettuce 6.6.0.RELEASE**。Maven 通过显式项目设置从公开 Central 补齐依赖，未修改全局仓库或 NAS 服务。
- `ExternalServices` 实现连接、专用 schema、缓存开关/前缀/TTL/字节和时间预算、队列/并发、材料目录与时区绑定校验。类型/范围/缺失值失败不保留含源值的异常链，配置对象字符串化脱敏。公共模板补全可调默认值；正式 Spring 启动接线属于 7.1，尚未切换。
- `MysqlConnections` 提供专用物理连接和有界 Hikari 池，显式关闭自动重连、多语句、本地文件加载与公钥自动获取，固定事务隔离及严格 SQL 模式。真实测试验证独立连接、事务回滚、Unicode 与 64 位整数。
- 发现 Connector/J `getLoginTimeout()` 固定返回 0，导致 Hikari 关闭创建连接执行器时零等待并告警；通过数据源显式报告配置对应的有限等待修正，复测无警告。尚未声称满足整个应用 8 秒关闭预算，7.2 仍未完成。
- 新 `db/mysql/V001__managed.sql` 的 **20 张 InnoDB 表**已在授权专用库建立；身份使用 `utf8mb4_0900_bin`，活动绑定/邀请/会话以独立关系表和唯一键表示。已验大小写与尾空格精确比较、用户/账号/机器人唯一性、跨范围外键、Unicode 正文及大于 2^53/最大有符号 64 位计数。
- `MysqlLayout`/`MysqlOwnership` 已实现只读预检、独立连接命名锁、初始化标记、脚本/结构摘要和运行 epoch；真实验证空库初始化、匹配重开、第二实例拒绝且不推进 epoch。**2.3–2.6 仍未完成**：未知/部分 DDL 等故障覆盖、业务仓储/恢复和外部操作前的 epoch 核验接线尚缺。
- Redis 原子写脚本按定宽十进制字符串比较版本，验证 `9007199254740992` 与 `9007199254740993`、`Long.MAX_VALUE`、同版本覆盖范围及 TTL。它只是缓存传输基础，尚无完整窗口、MySQL 读时授权、历史回源和送达后更新接线，4.1–4.7 均未完成。
- `NasCompatibilityIT`、`NasSchemaIT` 是显式开关/路径的手动操作者测试，**不是 CI**。兼容性测试只写连接私有临时表及 60 秒 TTL 随机项目键；schema 测试保留表/安装标记并推进运行 epoch，所有合成业务行事务回滚。自增高水位可能增长；没有清库、删表或读取其他应用资源。

本轮验证：`ExternalServicesTest` 33 项、`HistoryCacheFrameTest` 2 项通过；两项授权 NAS 测试最终复测通过。Java 回归命令 `mvn -o -s scripts/maven-public-settings.xml -Dmaven.repo.local=E:/.m2 '-Dtest=*,!ManagedContainerProbeTest,!Nas*IT' test -q` 共 **291 项，290 通过、1 个原有 native symlink 跳过，0 失败/错误**。已知 Windows POSIX 容器测试仍明确排除，没有删测试规避失败。

## 已执行验证

环境：Windows 11 amd64，Microsoft JDK **21.0.11**，Maven **3.9.8**，项目 Spring Boot **3.5.13** / Spring AI **1.1.8**。默认 PATH 上是 Java 17，因此每次 Maven 命令为当前进程指定 Java 21。

| 验证 | 结果与范围 |
| --- | --- |
| 修改前 `mvn test -q` | 238 项，0 assertion failures、2 errors、1 skipped；两项 error 都是 ManagedContainerProbeTest 在 Windows 读取 POSIX 权限失败 |
| 时间/管理/模型定向回归 | 109 项通过，覆盖跨午夜、DST、接收/生成时间、严格常规网关字段、重试、原文不变、预算无付费调用 |
| 抽取模型配置后的回归 | `mvn -o test '-Dtest=*,!ManagedContainerProbeTest' -q`：256 项，0 failures、0 errors、1 skipped，即 255 通过 |
| native symlink 测试 | PrivateStateFilesTest 中原有 native symlink 场景在本机跳过；必须在 Linux 补验，没有放宽生产权限验证 |
| 容器权限测试 | 整个 ManagedContainerProbeTest（3 项）从上述回归命令显式排除；两个 POSIX 用例已有失败证据，不声称 Linux/镜像验收通过，未修改测试绕过问题 |
| Node 页面竞态 | `node --test src/test/js/binding-ui.test.cjs src/test/js/admin-details-ui.test.cjs`：14/14 通过 |
| 配置序列化 | 新 ModelConfigurationTest 验证已有 JSON 的读取、全部 12 个字段、Duration、Unicode、往返与秘密脱敏；原管理 API/模型池回归通过 |
| 规划与补丁检查 | `openspec validate adopt-mysql-redis-conversation-runtime --strict` 和 `git diff --check` 通过；apply 状态 7/44，不等于整个 change 已验收 |

沙箱下临时私有目录权限测试出现 `INVALID_DIRECTORY`、Node 出现 `spawn EPERM`，已按权限机制在沙箱外用本机合成数据重跑通过，未修改安全逻辑。测试日志中的引导密码缺失与无效时区异常是预期失败用例。

`mvn clean test` 在删除旧 `target/talkweave-0.1.0-SNAPSHOT.jar` 时因文件被其他进程占用而停止。未杀进程或强制删除。该次清理已移除 `target/classes`；随后从源码重编译、运行上述 256 项测试成功，确认没有残留 `AssistantProperties$Model.class`，新 `ModelConfiguration.class` 存在。旧 JAR **未更新**，当前运行中的应用不会因此自动获得时间修复。

## 数据库/缓存阻碍与未执行项

Docker Desktop **4.92.0** 已启动，Engine **29.8.0**、Linux/amd64。没有现成测试镜像；尝试拉取候选 `mysql:8.4.6`、`redis:7.4.5-alpine` 均失败：Docker Hub 连接被解析到不可达回环地址，并报告未配置 HTTPS proxy。提权后仍失败，未改动系统 DNS、代理或镜像源。

这些 tag **只是早期候选，不是已核验兼容版本**；后续 NAS 检查与上述授权测试已确认实际基线。官方检索工具当前仍返回 provider gateway 404；版本证据来自服务实际响应、固定 BOM/依赖和真实测试，不声称查验了所有“最新版”。

早期再次尝试拉取 `mysql:8.0.44`、`redis:7.2.12-alpine` 均在 Docker Hub manifest 请求时连接超时；提权后仍失败，未完成 tag/架构或镜像内容核验，未启动测试容器、改动代理/DNS或停止任何服务。此记录已被外部服务方案取代，任务 1.3 不再依赖镜像且已完成。

因此尚未完成仓储换型、全链路所有权/恢复与 Redis 缓存、实际查询计划/并发/故障、生产旧路径退役、诊断产物分离、外部服务 CI/双架构镜像、浏览器端到端、8 秒整体关闭、性能基准。未测量缓存加速比例，未用 SQLite/H2 代替真实引擎。

后续直接通过显式配置复用已授权项目资源，故障使用测试自有连接中止、本机代理或合成回调控制。不能默认让 CI 读取本机 NAS 配置；真实扫码/付费模型及覆盖恢复仍须另外授权。不能安全执行的未知/部分布局与真实恢复保持待验，不要求数据库镜像或新实例来继续安全实现。覆盖通过后才能删除旧存储与专属测试，不把局部代码和 schema 验证标为整个 change 完成。

## 规格关系

旧 `add-multi-user-wechat-admin` 仍是 47/52，9.2、9.3、10.3–10.5 未完成项原样保留。本 change 后续接管其 SQLite/本地备份/部署前提；时间实现已将“上一轮完整请求前缀相等”调整为“固定 system 和相同原始历史不变”。尚未做跨 change 主规格同步。

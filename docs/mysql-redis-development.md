# MySQL / Redis 开发与验证

当前唯一生产路径为 managed + MySQL，可选 Redis 历史缓存。旧单用户/SQLite 已退役，不能用旧 profile 或 `assistant.enabled` 恢复。使用 Java 21；生产包、诊断包、真实引擎验证和容器验收分别记录。

## 无外部数据服务的单元回归

```sh
mvn verify
node --test src/test/js/binding-ui.test.cjs src/test/js/admin-details-ui.test.cjs
```

默认 Maven 不选择 `*IT`，不读取本地 NAS 配置。HTTP 协议测试只绑定回环合成服务，不访问真实微信或付费模型。Node 使用合成 DOM，不是浏览器 E2E。macOS 可在 JVM 启动前设置 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/private/tmp`，避免默认 `/var` 符号链接被私有文件防护拒绝；不放宽生产检查。

已有旧运行产物时使用独立构建目录，不执行 clean 或覆盖旧 JAR：

```sh
mvn -Dtalkweave.build-directory=.build-cache/mysql-runtime-verify \
  -Dtalkweave.artifact-name=talkweave-mysql-candidate verify
```

独立诊断构建/加载方式见 [diagnostics README](../scripts/diagnostics/README.md)。实际候选 JAR 要通过独立 `ManagedContainerProbe --artifact` 检查，不得夹带旧 SQL/SQLite/旧授权入口/测试夹具。更换输出目录后不要混入旧报告统计。

## 显式真实引擎集成

先按 [部署说明](mysql-redis-operations.md) 填写受保护连接文件，并取得项目专用 schema 与 Redis 前缀的合成读写授权。以下命令中的大写值必须替换为操作者确认的实际值，不在命令行填写密码：

```sh
mvn -Pexternal-services \
  -Dtalkweave.build-directory=.build-cache/mysql-runtime-it \
  -Dtalkweave.it.config=/ABSOLUTE/PRIVATE/external-services.local.yml \
  -Dtalkweave.it.mysql-schema=APPROVED_SCHEMA \
  -Dtalkweave.it.redis-prefix=APPROVED_PREFIX \
  -Dtalkweave.it.mysql-version=EXACT_MYSQL_VERSION \
  -Dtalkweave.it.redis-version=EXACT_REDIS_VERSION \
  -Dtalkweave.it.allow-schema-initialization=true \
  -Dtalkweave.it.allow-schema-upgrade=true '-Dtest=*IT,!HistoryBenchmarkIT' test
```

版本逐字核对，不自动升级服务；初建/升级开关也必须有授权。测试拒绝已承载业务的目标，独占合成夹具只按本次精确键清理自身记录，保留布局、安装 ID、epoch 和自增水位。多套测试不要同时使用同一 schema。Redis 使用随机 `:it:<uuid>:` 子前缀和有限 TTL。无配置、目标不匹配或未运行不算通过。

不拉取/运行 MySQL/Redis 镜像，不停止共享服务，不 KILL 其他会话，不更改全局配置。故障只中止测试自己的连接或控制合成回调。真实备份覆盖、未知/部分 DDL 破坏、微信扫码及付费调用须另行授权；模拟不冒充真实恢复。

当前已重验外部 MySQL 8.0.46 / Redis 7.4.9；固定客户端 Connector/J 9.6.0、HikariCP 6.3.3、Lettuce 6.6.0.RELEASE。历史 8.0.44 / 7.2.12 记录不能作为新服务版本证据。详细数量、失败修复与未完成项见 [当前实施记录](../openspec/changes/adopt-mysql-redis-conversation-runtime/implementation-status.md) 和 [51 场景覆盖映射](../openspec/changes/adopt-mysql-redis-conversation-runtime/coverage-current.md)。

性能测试单独选择 `-Dtest=HistoryBenchmarkIT`，沿用上述授权参数；调参对照再加 `-Dtalkweave.benchmark.tuned=true`。已完成 100/1000/10000 轮 × 并发 1/4 × 直读/冷/热/故障的 24 组及 2 组调参。热命中正文查询为零，但没有稳定整轮加速；完整方法、p50/p95、查询量和环境限制见 [性能报告](../openspec/changes/adopt-mysql-redis-conversation-runtime/performance.md)。不要把基准与其他独占夹具同时运行。

## CI 与镜像：当前发布被阻断

`.github/workflows/container.yml` 执行 Java/Node 单元、独立诊断构建、amd64/arm64 应用镜像构建。`scripts/ci/image-smoke.sh` 显式加载 diagnostics JAR，以无网络方式检查正式产物和两种 UID 材料权限；结果明确为 `externalServices=NOT_RUN`，不是管理员/数据库/缓存运行验收。

发布分支在镜像导出之前有显式失败门禁，不得删除门禁以让流水线变绿。已加入独立手动 `external-integration` job，只允许默认分支的显式 dispatch，并使用同名受保护 GitHub Environment。操作者须另行配置环境审批、秘密 `EXTERNAL_SERVICES_YAML` 及变量 `APPROVED_MYSQL_SCHEMA`、`APPROVED_REDIS_PREFIX`、`EXPECTED_MYSQL_VERSION`、`EXPECTED_REDIS_VERSION`；不能直接上传开发机配置。目标必须允许独占空业务合成测试及初建/已识别升级。缺少配置会失败，fork PR 不接触这些秘密。

`scripts/ci/external-services.sh` 仅供 GitHub-hosted 临时 runner：先执行无外部服务单元测试预热依赖，再将出站限制为明确的 MySQL/Redis IPv4 地址与端口及回环合成端点，离线执行 IT，最后只移除本次防火墙链和临时文件。当前隔离入口要求数字 IPv4；默认预算的 Redis 断连代理测试遵循本次无 SSL 配置，不代表 TLS 故障验证。**不要在开发机/NAS 执行此防火墙脚本。** 没有新增数据库镜像，未实际配置或运行 GitHub 环境；正式镜像外部运行阶段仍未闭合，7.4/7.5 保持未完成。未来发布只能使用同次验收的镜像，不另行构建替代品。

两架构必须分别记录运行成功/失败/未运行以及原生/QEMU；当前本机 JAR 和真实数据库测试不能代替正式镜像或 NAS 实机。镜像摘要只记录实际构建值，不预填。

## 开发预览与安全边界

正式 JAR 使用 `EXTERNAL_SERVICES_CONFIG` 指向新配置，业务 MySQL 与新 `materials` 均需专用；缺少外部服务时不会回退 SQLite。真实绑定或恢复已有活动绑定会发出真实微信请求，不能把直接启动正式 JAR 当成无网络 UI 预览。

`ManagedBrowserFixture` 仅在测试 classpath，使用 `synthetic-browser-only`，禁用真实微信/模型调用但仍依赖授权 MySQL/Redis。现在从空业务预检、种子到关闭清理共用一个独占 owner；不得对真实业务库执行，不能仅凭用户列表为空认定安全。材料使用新 `/private/tmp/talkweave-browser-*` 目录，缓存只写随机测试子键，关闭按精确主键清理本次合成记录。

在 macOS/Java 21 上先独立构建测试 classpath，再提供与 IT 相同的显式授权参数；不把密码写到命令行：

```sh
mvn -Dtalkweave.build-directory=.build-cache/browser-verify test-compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -Dmdep.outputFile=.build-cache/browser-classpath
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/private/tmp java \
  -Dtalkweave.it.enabled=true \
  -Dtalkweave.it.config=/ABSOLUTE/PRIVATE/external-services.local.yml \
  -Dtalkweave.it.mysql-schema=APPROVED_SCHEMA -Dtalkweave.it.redis-prefix=APPROVED_PREFIX \
  -Dtalkweave.it.mysql-version=EXACT_MYSQL_VERSION -Dtalkweave.it.redis-version=EXACT_REDIS_VERSION \
  -Dtalkweave.it.allow-schema-initialization=true -Dtalkweave.it.allow-schema-upgrade=true \
  -cp ".build-cache/browser-verify/test-classes:.build-cache/browser-verify/classes:$(cat .build-cache/browser-classpath)" \
  io.github.wochen5770.talkweave.managed.admin.ManagedBrowserFixture --synthetic.cache-fault=true
```

唯一可选程序参数是 `--synthetic.cache-fault=true`，不接受任意 Spring 覆盖；省略时使用授权 Redis 测试子键。固定入口 `http://127.0.0.1:18680`，健康端口 18081；端口占用时拒绝，不停止其他进程。合成账户 `synthetic-admin` / `synthetic-browser-only-password` 仅供这一夹具。Ctrl-C 关闭夹具，不关闭共享服务。

实际浏览器已通过登录、两用户用量隔离、配置保存/刷新、Key 不读回、退出和第二页面会话失效，以及 RUNNING + 缓存退化展示；标签按文本显示。截图和步骤见实施记录。此结果不是微信扫码或付费模型 E2E。

`ManagedShutdown` 在资源初始化前注册清理，先撤销本进程数据库运行权并停止新增调度，再共享 6 秒并行关闭预算，为 Web/框架保留 2 秒。无响应模型的在途测试小于 8 秒返回并报告未完整关闭，迟到结果不会发送；浏览器完整进程停止约 0.82 秒。无法确认组件停止时保留材料锁，报告固定组件标签；Linux 正式容器 SIGTERM 仍单独待验。

管理 API 保持正文不可见、模型 Key 不读回、认证/CSRF/来源校验；逐次模型用量按不可变原范围记录，Redis 命中不产生供应商缓存 token。时间上下文在原历史之后、当前问题之前，每轮新采样、同轮重试固定，不写进持久历史。

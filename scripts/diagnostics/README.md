# 独立诊断工具

诊断入口位于 test 源集，打包为单独的 `*-diagnostics.jar`，不包含测试类或浏览器夹具，也不进入正式业务 JAR/镜像。必须使用同一次构建的应用 JAR 和诊断 JAR；正常 `java -jar` 不加载诊断包。`scripts/diagnostics/legacy/` 仅保留历史参考，不能作为当前可运行工具。

## 构建与离线产物检查

使用 Java 21。可指定全新输出目录，避免本地旧 `target` 中残留已退役的 class/SQL，也不必清理可能仍在使用的旧运行 JAR：

```sh
mvn -Dtalkweave.build-directory=.build-cache/mysql-runtime-verify \
  -Dtalkweave.artifact-name=talkweave-mysql-candidate verify

java -Dloader.path=.build-cache/mysql-runtime-verify/talkweave-mysql-candidate-diagnostics.jar \
  -Dloader.main=io.github.wochen5770.talkweave.runtime.probe.ManagedContainerProbe \
  -cp .build-cache/mysql-runtime-verify/talkweave-mysql-candidate.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --artifact .build-cache/mysql-runtime-verify/talkweave-mysql-candidate.jar
```

没有覆盖输出参数时仍使用 `target/talkweave-0.1.0-SNAPSHOT{,-diagnostics}.jar`。本地依赖缓存可通过 `-Dmaven.repo.local=...` 指定；macOS 安全测试需要时，在 JVM 启动前设置 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/private/tmp`，不放宽生产路径的符号链接拒绝。

`ManagedContainerProbe` 只支持三个离线模式：`--artifact <正式JAR>` 检查当前 MySQL/健康组件、拒绝旧实现/迁移资源/诊断入口/夹具；`--materials <新子目录>` 验证私有材料初始化与重开；`--legacy <新子目录>` 创建合成旧文件并验证拒绝后无修改。后两者拒绝已存在的目标目录，不能指向真实数据。容器烟测 `scripts/ci/image-smoke.sh <image> <platform> <diagnostics.jar>` 在无网络、只读根环境中运行这些模式，分别检查 root/non-root，明确输出 `externalServices=NOT_RUN`；它不代表管理员、外部服务、在途停止或两架构运行验收通过。

## 微信与模型诊断（必须单独授权）

所有入口通过上述 `PropertiesLauncher` 与显式 `-Dloader.path` 加载。将 `-Dloader.main` 替换为对应的完整类名，并在 launcher 后传递参数：

| 入口（包名均为 `io.github.wochen5770.talkweave.runtime.probe`） | 参数和边界 |
| --- | --- |
| `WechatConnectivityProbe` | `--allow-live-wechat <全新私有目录>`；真实扫码和收发须操作者在场授权。 |
| `TwoAccountWechatProbe` | `--allow-live-wechat [--allow-relogin] <全新私有目录>`；重认证另需允许撤销旧凭据，不与同绑定的旧实例并行。 |
| `ModelConnectivityProbe` | `--check-config <私有YAML>` 仅校验 `assistant.model`，不联网；`--allow-paid-model-check <私有YAML> <全新私有目录>` 至多两次真实模型请求，须单独付费调用授权。 |

模型诊断 YAML 只由显式文件加载，不继承 Spring 启动配置、环境秘密或导入文件；不保存对话正文，不自动重试或恢复已开始的运行目录。探针不依赖 MySQL，也不能替代管理端全链路验收。协议帮助文本保留其观察工具身份；生产授权仍由当前身份解析器限制范围。

## 外部服务只读检查

`ExternalServicesCheck.java` 是独立 Java 21 源文件工具，不在 Maven 生产/测试源码目录中，不随正常业务 JAR 发布，也不会被应用自动调用。它只读取操作者指定的本地 YAML，不启动 Spring 应用。

运行需要 SnakeYAML 2.4 与 MySQL Connector/J 9.6.0 的本地 classpath，与当前固定依赖一致；连接检查不代表生产 MySQL 链路已完整验收。

```powershell
# 将两个占位路径替换为本机已有的 JAR 路径；密码只能留在 Git 忽略的配置中。
$diagnosticClasspath = 'C:/path/snakeyaml-2.4.jar;C:/path/mysql-connector-j-9.6.0.jar'
java --class-path $diagnosticClasspath scripts/diagnostics/ExternalServicesCheck.java --validate-only config/external-services.local.yml

# 只有获得操作者对该目标的连接授权后，才使用网络模式。
java --class-path $diagnosticClasspath scripts/diagnostics/ExternalServicesCheck.java --allow-read-only-network config/external-services.local.yml
```

- 校验模式只检查连接字段、YAML 类型/重复键、端口、schema 与 TLS 设置，不声称完成全部运行配置校验。
- 网络模式只执行 MySQL `SELECT`/`SHOW` 元数据检查（连接设为只读），不读取业务行；对象统计只反映当前账号可见范围，角色权限未展开，不足以单独证明 schema 可安全初始化。
- Redis 仅使用 `AUTH`、`SELECT`、`PING`、`INFO server`；不读取或写入任何键，不使用 `KEYS`、`SCAN`、`EVAL`、清库或全局配置命令。`SELECT` 仅选择本连接的逻辑库。
- 连接/读取超时均为 3 秒；该值只用于诊断，不是拟实现的 Redis 运行阶段预算。
- 不自动重连或降级 TLS，不开启 MySQL 公钥自动获取，不打印原始服务错误、YAML 出错行、连接串、用户名、密码或授权全文。
- 密码不作为命令行参数；不得把本地配置内容或原始错误粘贴进报告。
- 退出码 0 表示所选检查成功，1 表示网络/服务检查未全部成功，2 表示配置/用法错误。成功连通也不授权建表、故障注入、恢复或清理测试。

## 授权后的合成兼容性与 schema 验证

`external-services` Maven profile 是显式直连外部服务的合成集成入口；默认 `mvn test` 不选取 `*IT`，不读取 NAS 配置。profile 仅运行集成测试，单元回归须另跑。显式运行 IT 却缺少授权配置时会在连接前**失败**，不把跳过记为通过；没有 MySQL/Redis 镜像或容器前提。

```powershell
# Java 21。替换为独立核对的授权库名/项目前缀/服务版本；密码只放受保护文件。
mvn -Pexternal-services '-Dtalkweave.it.config=C:/private/approved-services.yml' `
  '-Dtalkweave.it.mysql-schema=APPROVED_SCHEMA' '-Dtalkweave.it.redis-prefix=APPROVED_PREFIX' `
  '-Dtalkweave.it.mysql-version=8.0.44' '-Dtalkweave.it.redis-version=7.2.12' `
  '-Dtalkweave.it.allow-schema-initialization=true' '-Dtalkweave.it.allow-schema-upgrade=true' test

# 只验证连接/事务/原子脚本时，不授权持久布局初始化：
mvn -Pexternal-services '-Dtest=ExternalServicesIT' '-Dtalkweave.it.config=C:/private/approved-services.yml' `
  '-Dtalkweave.it.mysql-schema=APPROVED_SCHEMA' '-Dtalkweave.it.redis-prefix=APPROVED_PREFIX' `
  '-Dtalkweave.it.mysql-version=8.0.44' '-Dtalkweave.it.redis-version=7.2.12' test
```

- `ExternalIntegrationTarget` 只加载显式路径，拒绝链接、过大/重复键/别名 YAML；不继承生产 Spring/环境连接参数。授权库名/前缀必须精确匹配文件，服务实际版本必须匹配显式基线。`ExternalIntegrationTargetTest` 无外网验证拒绝与脱敏边界。
- `ExternalServicesIT` 由原 `NasCompatibilityIT` 迁移并保留其引擎断言。本机已验 MySQL 8.0.44、Redis 7.2.12，使用 Connector/J 9.6.0、HikariCP 6.3.3、Lettuce 6.6.0.RELEASE；换版本需要重新验证，不以填写版本号当作兼容证明。
- MySQL 仅使用本次建立的连接、随机命名锁和连接私有临时表；中止自己的 JDBC 连接，不使用 `KILL`。表随物理连接结束而消失。
- Redis 使用配置前缀下的随机 `:it:<uuid>:history-v1` 键，`EVAL`/`GET`/`PTTL` 验证精确版本比较；键只有合成内容，每次成功写入设置 60 秒 TTL，不扫描或清空共享服务。
- `ExternalSchemaIT` 由原 `NasSchemaIT` 迁移；额外要求初始化和布局升级开关，取得独占锁后只读确认所有业务表为空、设置仍为初值，再打开布局/推进 epoch。已有业务行时拒绝，不清理或重置业务夹具。空库初始化后追加 V002，精确匹配 V1 只追加分页序号/索引，V2 直接重开；不创建数据库/用户，不使用 `DROP`/`TRUNCATE`。保留 20 张项目表、安装标记和 epoch；合成行在短事务中回滚，随机标签区分每次运行。自增高水位增长不等于业务行残留。报告区分实际 fromVersion=1 的升级与 fromVersion=2 的重开，禁止为重复测试降级或改写 V001。
- `ExternalPaginationIT` 在自有连接临时表上执行与正式 V002 相同的追加语句，验证非空合成行原文保留、稳定按用户游标和超过 2^53 的序号；这不是已承载业务的完整 schema 升级验收。实际项目表的外键/归属约束仍由 `ExternalSchemaIT` 验证，升级中断分类由无网络 `MysqlLayoutTest` 验证；不在共享 NAS 制造失败布局。
- `ExternalTransactionsIT` 同样要求明确的初始化/升级开关和空业务保护（并发测试需连接池至少 2 个连接）。测试池内并行短事务、合成行回滚、JDBC 64 位生成主键，以及仅中止测试自己的独占锁连接后原实例永久失效；替代 owner 重开会增加 epoch。不会终止其他连接或停止服务；事务基础通过不等于完整业务故障验收。
- 仓储、运行权和缓存已接入源码。按操作者选择，CI 必需构建/测试/双架构离线检查通过即可正式发布，NAS 验收在部署后继续；初始化或发布成功不表示现有应用已升级，也不代替未完成的集成验收。
- CI 外部集成必须由维护者独立配置授权的受保护文件和范围，不能默认使用本机 NAS 文件；它不作为正式发布前置条件。业务故障和恢复的当前证据见 change 的 `coverage-current.md`，未执行环境仍明确待验。不停止或重启共享服务，不向真实模型/微信发请求。
- `scripts/maven-public-settings.xml` 是显式可选的公开 Maven Central 镜像设置，不更改用户/全局设置；本机可用 `-Dmaven.repo.local=<既有缓存路径>` 指定缓存。

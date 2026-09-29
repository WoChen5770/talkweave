# 实施状态与验证记录

日期：2026-09-28。

## 进度

OpenSpec change：`add-personal-wechat-chat-assistant`，**31/38** 完成。本次确认后新增完成 7.1 的 GitHub Actions/GHCR 交付；此前已完成 4.1–4.4、5.1、5.3、6.4、7.2、7.3、8.1；未归档，也未标为整体完成。

已新增：
- 受保护的无交互登录、原子二维码/配对文件、挑战过期/一次性消费、凭据/节点/游标恢复、代次隔离与封顶网络退避。
- 随机身份核对挑战、候选身份私有诊断及人工配置绑定；不信任首次发言，不跨机器人/所有者复用历史或关联令牌。
- 独立接收和持久顺序消费者；延迟模型下 A-/new-B 顺序正确，B 不携带 A 历史。
- 受控发送、业务错误/读超时分类、持久发送 ID、防不确定重发；getconfig/输入状态和启动停止通知的短超时最佳努力处理。
- 模型单请求期限、总预算、最多尝试次数；429/明确未发送连接故障可重试，Retry-After 秒数/日期受总预算限制，普通 5xx/鉴权/坏响应/已发出的超时不重试。
- Loopback 存活/就绪检查、降级/积压/不确定状态，不做付费探测；停机停止领取、取消长轮询并持久标记未完成工作。
- Dockerfile/Compose、权限/登录/绑定/恢复文档及全部 46 个 Scenario 的测试或人工验收映射。

**主程序默认已改为常驻，并会主动连接微信。** 纯初始化必须使用 `assistant.enabled=false`，README 已更新。本轮未启动真实账号、未导入真实联调凭据、未修改真实配置、未发起付费模型请求。原有一次性真实联调结论保留如下，不扩大为完整系统/NAS验收。

已确认分工：GitHub Actions 构建/验证并发布到 GHCR；操作者自行提交仓库并完成 NAS 部署/真实验收。本机 Docker 状态不阻塞开发。

仍未完成：首次实际 GitHub CI 两平台构建/Java+SQLite/发布结果（2.6），操作者 NAS 运维和备份恢复演练（7.4/7.5）、最终组合故障/镜像隐私回归（8.2/8.3）、操作者 NAS 端到端验收（8.4）与最终交付报告（8.5）。

## CI/GHCR 本次交付

- `.github/workflows/container.yml`：PR/普通开发分支只读验证，默认分支/v* 版本标签在两架构检查后发布 GHCR；手动运行默认不发布。构建无 packages 写权限，发布使用短期 GITHUB_TOKEN，不传递真实模型/微信秘密。
- SHA 固定的外部 Actions：通过官方仓库 `git ls-remote` 核实版本标签对应提交；没有使用浮动 Action 引用。
- 每架构先解析基础镜像摘要，再构建/加载；arm64 明确 QEMU on amd64。`image-smoke.sh` 非 root、无网络、只读根目录，两个新容器复用临时卷核对 Java/SQLite 会话与历史。
- 发布仅加载已验证 tar（SHA-256 校验），不重新构建；两架构通过后发布多架构正式标签，保留测试/平台/manifest 证据。
- Compose 已改为只拉取 `ASSISTANT_IMAGE` 指定的 GHCR 镜像，提供 `.env.example`；不在 NAS 构建，不写入任何真实值。README、operations、github-actions 和 container-validation 文档同步。
- 这只是本地实现/验证完成，**没有提交到 GitHub、运行远程 Actions、构建/发布镜像或访问 NAS**。

## 本轮实际验证

- Windows amd64，Microsoft JDK 21.0.11，Maven 3.9.8；仅进程级 JAVA_HOME，不改系统 JDK。
- `mvn -o -s .build-cache/maven-settings.xml -gs .build-cache/maven-settings.xml -B -Dstyle.color=never verify`：**129 tests，0 failures，0 errors，1 skipped，BUILD SUCCESS**，产出 Spring Boot JAR。
- Windows 文件 ACL 测试在授权的沙箱外执行；原生 Linux/macOS 符号链接测试在 Windows 跳过，Mockito 文件类型/链接拒绝分支通过。当前账户不能创建原生符号链接；Jimfs 下载遇到 Maven Central 网络失败，因此移除新增依赖，不声称内存文件系统或 Windows 原生符号链接实测通过。
- `docker compose --env-file .env.example -f compose.yml config --quiet`：成功，仅结构校验，不运行服务。
- Docker CLI 29.8.0 可用，但 `desktop-linux` 引擎 named pipe 不存在（沙箱内外一致）。未启动 Docker Desktop、未上传镜像，amd64/arm64 实际镜像及运行均未验证。
- actionlint 1.7.7 静态检查成功；Go 模块代理不可达后，从官方 GitHub Release 下载 Windows 工具并核对官方 SHA-256：`7f12f1801bca3d480d67aaf7774f4c2a6359a3ca8eebe382c95c10c9704aa731`。工具/下载缓存仅位于忽略目录。
- 打包 JAR 的 PropertiesLauncher 冒烟入口在 Windows/amd64 使用缓存下的相对测试目录运行成功：两个独立 Java 进程分别 `--write` / `--verify`，均输出 CI_STORAGE_OK、schema=2、externalCalls=0。此项验证打包入口和进程间持久化，不代表容器/arm64/NAS 已执行。
- Git Bash `bash -n scripts/ci/image-smoke.sh` 成功；沙箱内信号管道被拒后，经授权仅在沙箱外做语法检查，没有执行 Docker 冒烟脚本。
- IDEA MCP 未打开本项目，构建工具无法执行；没有改动其打开的其他项目，使用 Maven 完成构建。
- 测试读取 classpath 默认配置，运行器装配测试注入合成微信/模型客户端，均不依赖真实配置文件或服务。

详细场景映射见 [scenario-coverage.md](scenario-coverage.md)，操作步骤见 [operations.md](operations.md)，双架构边界见 [container-validation.md](container-validation.md)。本机临时 Maven settings/报告/日志均在忽略目录中，不作为交付物。

## 测试分布

| 测试类 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| LoginCoordinatorTest | 5 | 0 | 0 | 0 |
| WechatApiClientTest | 14 | 0 | 0 | 0 |
| WechatOutboundTest | 7 | 0 | 0 | 0 |
| ConversationPolicyTest | 3 | 0 | 0 | 0 |
| CompatibleChatClientTest | 19 | 0 | 0 | 0 |
| ConversationRepositoryTest | 12 | 0 | 0 | 0 |
| DurableWorkflowTest | 10 | 0 | 0 | 0 |
| SchemaMigrationTest | 3 | 0 | 0 | 0 |
| SqliteStoreTest | 4 | 0 | 0 | 0 |
| AssistantPropertiesTest | 20 | 0 | 0 | 0 |
| AssistantRuntimeTest | 7 | 0 | 0 | 0 |
| FoundationContextTest | 3 | 0 | 0 | 0 |
| HealthServerTest | 2 | 0 | 0 | 0 |
| PrivateStateFilesTest | 3 | 0 | 0 | 1 |
| ContainerStorageProbeTest | 3 | 0 | 0 | 0 |
| ModelConnectivityProbeTest | 5 | 0 | 0 | 0 |
| WechatConnectivityProbeTest | 2 | 0 | 0 | 0 |
| SafeDiagnosticsTest | 2 | 0 | 0 | 0 |
| WorkflowContractTest | 5 | 0 | 0 | 0 |

## 本次真实微信联调记录（不含凭据或正文）

- 环境：上述本机 Windows/JDK；不是 NAS 或 Docker 验证。服务节点为 `https://ilinkai.weixin.qq.com`，未添加额外主机白名单。
- 操作者同意现场扫码后，二维码申请成功，扫码登录确认成功；本次未经过配对码、新节点或凭据失效分支，不能据此宣称这些真实分支已验证。
- 可用账号条件目前仅能确认本次操作者账号可扫码登录并向机器人发送私聊文字；不推断其他账号、地区或微信版本普遍可用。
- 身份核对依据：登录响应提供 bot ID 与扫码者 ID；入站用户通过只向操作者展示的随机挑战关联到 sender ID。三种 ID 不假定同值。原始身份只保留在受保护的本地证据中；正式绑定前还需人工核对，不能仅信任首次发言者。
- 收到的完整私聊用户消息具有非空 message ID、sender ID、context token，且随机挑战精确匹配。message ID 以字符串保留，避免 uint64 精度损失；本次单次往返不构成跨重启稳定性的实测。
- 单次回复直接使用同一入站消息的 context token，不跨用户或机器人复用；微信接口确认发送成功。仅验证本次关联令牌可用，未实测其有效时长。
- 该固定回复为 **65 UTF-8 字节**，接口确认可发送；这不是最大长度边界测试，不能证明配置中的 2000 字节上限已获真实验证。
- 程序正常结束，二维码和配对码文件均已清理；凭据和身份材料仍在受保护的本机 `data/` 下，未写入源码、报告或普通日志。未调用任何模型。
- 操作者在本任务中明确确认微信客户端收到固定回复；结合接口确认与受保护证据，任务 2.4 已完成。本次只验证单次文字往返，不把它视为完整身份授权、全部通道边界或常驻机器人交付。

## 本次真实模型联调记录（不含密钥或正文）

- 操作者已填写本地配置并明确允许两次短模型调用；未读取或发送微信聊天历史，也未使用私人系统提示词。
- 环境：Windows amd64、Microsoft JDK 21.0.11、Spring AI 1.1.8。完成时间 2026-09-28 14:18（Asia/Shanghai）。不是 NAS 或微信到模型的端到端验证。
- 请求模型标识：`gpt-6-luna`，按用户配置原值发送，不替换模型。该名称仅是配置/请求标识，不据此认证兼容服务背后的实际模型或供应商。
- 基础地址来自本地配置，完整服务端点仅保存在受保护的运行证据中。使用指定前缀后追加 `/v1/chat/completions`，不重定向到其他服务。
- 请求方法 POST、Bearer 鉴权、JSON 字段为 `model`、`messages`、`stream=false`、`max_tokens=256`。未发送工具、媒体、temperature 或厂商私有参数。mock 契约验证准确字段集；相同 Java 客户端的真实两次请求均成功。
- 首轮为 system/user，次轮为 system/user/assistant/user，assistant 消息来自实际首轮返回；第二轮新问题不包含校验词，模型准确从前文回忆该词。
- 两次响应均由现有 Spring AI Chat Completions 的 `choices[].message.content` 文本通路成功解析，返回非空文本，未标记长度终止；没有保存或打印原始响应/正文。记录的是适配器实际可解析性，不是对未知响应字段的完整结构承诺。
- 计数：发起 2 次、接收有效回复 2 次、自动重试 0 次；未额外探测模型列表、健康或配额，没有第三次请求。程序以 `COMPLETED` 正常退出。
- 本次输出预算为 256、请求超时 60 秒。服务接受 `max_tokens` 参数，不据此断言其最大输出值或具体收费；费用以该服务账单为准。
- 上下文容量配置为 8192；此次仅验证短对话的上下文传递，不验证 8192 是否是模型真实上限。正式使用前仍需按服务说明核实容量，不能由模型名字推断。
- 证据仅含配置元数据、预算、计数及结果布尔值，存于受保护的本地 `data/`；无密钥、随机校验词和对话正文。任务 2.5 已完成。

## 下一步：GitHub CI 与操作者验收

操作者提交仓库后，从 Actions 查看 verify、两个 build、publish-images 和 publish-manifest 的结果；下载架构证据，补充 2.6 的实际状态。不需要先启动开发机 Docker。

NAS 的目录/配置、扫码/绑定、真实模型对话、容器重建、断网/失效恢复、备份回滚由操作者自行检查（清单见 github-actions.md），完成后再确认 7.4/7.5/8.4。没有收到实际结果前，这些项保留未完成；不替操作者访问 NAS，也不把本地测试或工作流配置当作验收。

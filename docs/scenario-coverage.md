# Scenario 验证映射

> 历史单用户 SQLite 覆盖，不作为当前验收。当前替代关系见 [MySQL/Redis 覆盖清单](../openspec/changes/adopt-mysql-redis-conversation-runtime/coverage.md)。

日期：2026-09-28。覆盖本 change 四份规格的全部 46 个 Scenario。表格中的“离线通过”仅表示临时本地磁盘/合成协议测试，真实服务和 NAS 条件另行验收。没有把已建立映射的人工场景标为验收完成。

本轮命令：`mvn -o -s .build-cache/maven-settings.xml -gs .build-cache/maven-settings.xml -B -Dstyle.color=never verify`。结果 **129 tests，0 failures，0 errors，1 skipped**；输出 Spring Boot JAR。Windows ACL 测试经授权在沙箱外执行。原生符号链接测试因 Windows 平台跳过，Mockito 拒绝分支测试通过；Jimfs 依赖下载失败后未保留该依赖，也未把模拟称为原生实测。

另执行 `docker compose --env-file .env.example -f compose.yml config --quiet` 成功；它不构建或运行容器。双平台验证由 GitHub Actions 执行，是否通过以当前提交的运行结果为准；NAS 验收由操作者自行完成。本机 Docker 状态不阻塞开发。测试类位于 `src/test/java/io/github/wochen5770/talkweave/` 对应职责包；JUnit 明细在本机 `target/surefire-reports/`，不提交测试生成物。

## compatible-chat-model

| Scenario | 测试或人工验收映射 | 实际状态 |
|---|---|---|
| API prefix is configured explicitly | CompatibleChatClientTest | 离线通过；不代表真实服务/NAS验收 |
| Ambiguous or missing configuration | AssistantPropertiesTest / FoundationContextTest | 离线通过；不代表真实服务/NAS验收 |
| Compatible service returns a text answer | CompatibleChatClientTest | 离线通过；不代表真实服务/NAS验收 |
| Response contains no usable text | CompatibleChatClientTest | 离线通过；不代表真实服务/NAS验收 |
| Invalid budget configuration | AssistantPropertiesTest / FoundationContextTest | 离线通过；不代表真实服务/NAS验收 |
| Provider truncates generation | CompatibleChatClientTest | 离线通过；不代表真实服务/NAS验收 |
| Rate limiting | CompatibleChatClientTest | 离线通过；不代表真实服务/NAS验收 |
| Authentication failure | CompatibleChatClientTest | 离线通过；不代表真实服务/NAS验收 |
| Read timeout after dispatch | CompatibleChatClientTest | 离线通过；不代表真实服务/NAS验收 |

## nas-container-runtime

| Scenario | 测试或人工验收映射 | 实际状态 |
|---|---|---|
| Two target architectures have independent verification results | docs/github-actions.md / ContainerStorageProbeTest：CI 分别构建两平台，核对 Java/SQLite 读写、出网、UID/GID、镜像摘要及原生/仿真环境 | 待 GitHub CI 实际运行；NAS 验收由操作者执行 |
| Unattended NAS restart | docs/operations.md：重启 NAS，验证有效凭据/绑定恢复或明确等待状态 | 人工验收待执行；不使用 Windows 单元测试代替 |
| A second instance uses the same data directory | SqliteStoreTest / FoundationContextTest / ConversationRepositoryTest | 本地磁盘离线通过；NAS挂载待验收 |
| Container replacement | docs/container-validation.md：用同一持久目录重建容器，核对聊天、会话、游标与未完成状态 | 容器验收待执行 |
| Data mount is unavailable | SqliteStoreTest / FoundationContextTest / ConversationRepositoryTest | 本地磁盘离线通过；NAS挂载待验收 |
| Operator completes login without a terminal prompt | LoginCoordinatorTest / PrivateStateFilesTest | 合成登录/权限通过；原生符号链接 Linux/macOS 测试在 Windows 跳过，NAS 文件管理操作待演练 |
| Stale verification code is submitted | LoginCoordinatorTest / PrivateStateFilesTest | 合成登录/权限通过；原生符号链接 Linux/macOS 测试在 Windows 跳过，NAS 文件管理操作待演练 |
| Waiting for a user scan | AssistantRuntimeTest / HealthServerTest | 离线通过；Docker 健康策略静态配置检查通过，容器实测待执行 |
| Stop during a model call | AssistantRuntimeTest | 离线停机/恢复通过；Docker 实际停止信号待验证 |
| Restore a stopped-instance backup | docs/operations.md 第5节：停止实例完整备份，在新目录与兼容版本恢复并核对历史/未完成轮次 | 人工演练待执行；恢复逻辑有 SqliteStoreTest / DurableWorkflowTest 覆盖，但不替代备份演练 |

## personal-conversation

| Scenario | 测试或人工验收映射 | 实际状态 |
|---|---|---|
| Unbound or unauthorized sender | ConversationRepositoryTest / LoginCoordinatorTest / AssistantRuntimeTest / WechatOutboundTest | 离线通过；不代表真实服务/NAS验收 |
| Bot account changes after login | ConversationRepositoryTest / LoginCoordinatorTest / AssistantRuntimeTest / WechatOutboundTest | 离线通过；不代表真实服务/NAS验收 |
| Continue after restart | ConversationRepositoryTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Start fresh without deleting history | ConversationRepositoryTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Ask for available commands | ConversationRepositoryTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| New conversation arrives during a slow answer | AssistantRuntimeTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Duplicate across process restart | ConversationRepositoryTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| User repeats the same question intentionally | ConversationRepositoryTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| History exceeds the budget | ConversationPolicyTest | 离线通过；不代表真实服务/NAS验收 |
| Current input alone is too large | ConversationPolicyTest | 离线通过；不代表真实服务/NAS验收 |
| Crash during model execution | AssistantRuntimeTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Model rejects a request | AssistantRuntimeTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |

## wechat-channel

| Scenario | 测试或人工验收映射 | 实际状态 |
|---|---|---|
| First login requires a pairing code | LoginCoordinatorTest / WechatApiClientTest | 离线通过；不代表真实服务/NAS验收 |
| QR code expires or login fails | LoginCoordinatorTest / WechatApiClientTest | 离线通过；不代表真实服务/NAS验收 |
| Restart with usable credentials | LoginCoordinatorTest / WechatApiClientTest | 离线通过；不代表真实服务/NAS验收 |
| Service rejects the token | WechatApiClientTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Temporary network outage | AssistantRuntimeTest | 离线通过；不代表真实服务/NAS验收 |
| Crash before accepting a batch | ConversationRepositoryTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Model response is slow | AssistantRuntimeTest | 离线通过；不代表真实服务/NAS验收 |
| Storage or backlog prevents safe intake | ConversationRepositoryTest / DurableWorkflowTest / AssistantRuntimeTest | 离线通过；不代表真实服务/NAS验收 |
| Business failure in an HTTP success response | WechatOutboundTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Missing or expired reply context | WechatOutboundTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Delivery outcome is unknown | WechatOutboundTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| User sends an image | ConversationPolicyTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |
| Generated reply is too long | ConversationPolicyTest / DurableWorkflowTest | 离线通过；不代表真实服务/NAS验收 |

## 新增 CI 发布 Scenario

| Scenario | 测试或人工验收映射 | 实际状态 |
|---|---|---|
| Pull request verifies without publication credentials | WorkflowContractTest.pullRequestsAndBuildsHaveNoPublicationCredentials / actionlint | 本地策略/语法通过；GitHub PR 运行待触发 |
| Publish only the two verified image artifacts | WorkflowContractTest.publishDependsOnAllTestsAndTransfersOnlyTestedImages / bothArchitecturesAreLoadedAndCheckedBeforeExport；ContainerStorageProbeTest | 本地契约/合成存储通过；实际两平台镜像和 GHCR 发布待 CI |

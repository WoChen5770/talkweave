# 多用户 change 场景覆盖与验收记录

本表逐项对应六份 delta spec，不将组件测试等同于应用接线、真实微信协议或 NAS 发布验收。普通测试仅使用临时数据库、可控时钟、合成身份和本地 HTTP 服务。

## 实际执行范围

- 2026-09-29 Maven 全套回归及 package 成功：229 项测试，0 失败、0 错误、1 项平台跳过。日志：`.build-cache/multi-user-final-verification.log`。OpenSpec strict 与 git diff --check 通过。
- 前端 Node 回归：`node --test src/test/js/binding-ui.test.cjs src/test/js/admin-details-ui.test.cjs`，14 项通过；覆盖跨用户迟到图片/详情/用量、任务版本、过期、配对重输、引用清理、时间筛选、已知/未知/异常统计与审计分页竞态。
- 浏览器人工自动化：独立新测试目录，合成管理员登录、新增备注含 HTML 的用户、无用量展示、45 分钟超时保存/刷新、合成模型保存且 Key 清空、审计页面。新增本轮独立二维码弹窗、两个用户/标签页、陈旧创建冲突、刷新、取消、配对码提交、身份未验证拒绝授权、图片移除、退出以及活动弹窗中的会话失效恢复；二维码使用 test-only 无网络 Ports。截图：`.build-cache/admin-binding-preview.png`（内容为合成码）。
- **未执行**：真实扫码、真实模型调用、完整两个用户的浏览器绑定/对话、NAS/容器验收。
- 本轮运行集成：RuntimeManagerTest 17 项与 ManagedModelPoolTest 2 项，覆盖独立连接/历史/游标、退避、背压、低磁盘、凭据失效、ABA、重绑迟到轮询、辅助调用撤权、重启和不确定发送；所有身份/微信/模型均为合成。浏览器确认最新页面的全局运行状态、未绑定状态、历史任务提示、HTML 备注文本显示且无控制台错误。截图：`.build-cache/admin-runtime-preview.png`。
- Windows 上一个 POSIX 权限专属测试按平台条件跳过；其余权限测试需要正常 Windows ACL 权限。

- 本轮详情与用量：AdminUsageFixture 使用真实 repository/API 结构写入合成计数（含畸形缓存字段的标准化结果），生产 jar 不含该夹具。浏览器已验证 A/B 独立统计、会话和时间范围、未知/异常/显式零、部分统计标志、文本备注、用户审计和退出清理。截图：`.build-cache/admin-details-preview.png`、`.build-cache/admin-usage-preview.png`。预览已停止，日志未出现合成密码/Key/token/聊天正文。

## admin-console

| Requirement / Scenario | 状态 | 测试或人工步骤 |
|---|---|---|
| Single administrator authentication / Anonymous management access | 合成通过 | AdminApiTest.anonymousAndForgedRequestsCannotMutateOrReadSecrets；AdminApiTest.bindingApiProtectsImagesPairingAndTaskOwnership 覆盖实际图片/状态/配对/取消接口 |
| Single administrator authentication / Forged mutation and logout | 合成通过 | AdminApiTest.anonymousAndForgedRequestsCannotMutateOrReadSecrets / logoutAndExpiredSessionDenySubsequentAccess |
| Single administrator authentication / Login throttling | 合成通过 | AdminApiTest.loginThrottlesBeforeExpensiveAuthenticationAndDoesNotLogCredentials |
| Administrator controlled user management / Create and bind from the page | 部分 | 浏览器已验证新增、独立二维码及配对流程；真实扫码开通与双账号实际连接待验 |
| Administrator controlled user management / Disable and restore user | 部分 | AdminApiTest.userChangesAreIdempotentAndUsagePreservesUnknownValues；RuntimeManagerTest.disableResumeReservesWorkerUntilOldCallExitsAndRejectsAllOldReplies；真实凭据恢复待验 |
| Validated global settings / Invalid settings do not replace working settings | 合成通过 | AdminApiTest.settingsAreValidatedAndKeyNeverReadBack |
| Validated global settings / Save a new global model configuration | 组件通过 | AdminApiTest.settingsAreValidatedAndKeyNeverReadBack；ManagedRepositoryTest.persistedObserverRejectsConversationInjectionAndRechecksRevocationBetweenAttempts；ManagedModelPoolTest 验证在途旧版本租约；应用已接线，真实中转站待验 |
| Validated global settings / Configure an installation without a model | 合成通过 | AdminApiTest.independentStartupWithoutModelAndNoLegacyRuntime；浏览器已验证无模型登录 |
| Privacy preserving dashboard / Inspect a user without reading conversations | 合成通过 | AdminApiTest.metadataAndFilteredUsageAreScopedPrivateAndTruthful；ManagedRepositoryTest.administratorConversationMetadataKeepsHistoricalBindingsSeparateAndPagesTiedTimes；浏览器验证详情元数据、会话/时间范围、跨用户统计隔离与退出清理，无正文/凭据接口 |
| Privacy preserving dashboard / Poll binding status | 合成通过 | BindingCoordinatorTest、AdminApiTest.bindingApiProtectsImagesPairingAndTaskOwnership；状态/审计无 token、扫码身份、QR 内容或配对码；浏览器验证 no-store 图片与终态清除 |
| Privacy preserving dashboard / Untrusted display strings | 部分通过 | 浏览器新增含 img/onerror 的备注，DOM 中没有生成 img；错误文本和所有状态分支待完整端到端覆盖 |
| Administrative audit trail / Trace a rebind operation | 部分 | 二维码 API 已记录脱敏用户/任务 ID 与终态；仓储/协调器合成验证重绑，真实完整重绑待验 |

## wechat-user-provisioning

| Requirement / Scenario | 状态 | 测试或人工步骤 |
|---|---|---|
| Scoped one time binding invitation / Separate simultaneous invitations | 合成通过 | BindingCoordinatorTest 独立任务/材料/激活；浏览器两个用户与两个标签页，任务 ID/图片独立 |
| Scoped one time binding invitation / Refresh or cancel a QR code | 合成通过 | BindingCoordinatorTest.refreshedCancelledAndDisabledTasksDiscardLateNetworkResults / restartInvalidatesOldInvitationsAndCleansOnlyPrivateKnownFiles；浏览器刷新/取消；Node 过期与迟到图片测试 |
| Scoped one time binding invitation / Pairing is required by WeChat | 合成通过 | BindingCoordinatorTest.pairingIsScopedSingleUseAndBoundToCurrentTask / repeatedPairingChallengeAllowsACorrectedCodeWithoutReusingTheOldOne；管理员 API 与浏览器提交测试 |
| Verified scanner becomes the authorized owner / Automatically activate a verified scanner | 组件通过 / 真实页面待验 | ScannerIdentityResolverTest 与 BindingCoordinatorTest 验证限定范围生产 resolver 自动激活、无首消息认领；双账号协议及重扫观察见 multi-user-protocol.md |
| Verified scanner becomes the authorized owner / Identity mapping is missing or unsupported | 合成通过 | ScannerIdentityResolverTest、BindingCoordinatorTest、AdminApiTest 验证缺失身份/未知范围失败关闭 |
| Verified scanner becomes the authorized owner / Another person sends the first message | 合成通过 | ScannerIdentityResolverTest；解析输入不接收入站消息；ManagedRepositoryTest 拒绝非授权 sender |
| Unique and atomic user binding / Duplicate account across two user slots | 合成通过 | ManagedRepositoryTest.botAndGlobalAccountUniquenessAreBothEnforced / concurrentFinalizationCannotAssignTheSameAccountTwice |
| Unique and atomic user binding / User disabled before confirmation | 合成通过 | ManagedRepositoryTest.refreshExpirationAndDisableInvalidateAttempts |
| Reauthentication does not transfer ownership / Login credentials expire | 部分 | ManagedRepositoryTest.reauthenticationKeepsBindingButRejectsAnotherOwnerAndOldEpochWork；RuntimeManagerTest.invalidCredentialStopsOnlyItsOwnerAndReauthenticationKeepsIdentityAndCursor 验证原身份恢复及游标；真实凭据待验 |
| Reauthentication does not transfer ownership / Administrator confirms a different identity | 部分 | ManagedRepositoryTest.replacementNeverCarriesOldHistoryIntoNewBinding；BindingCoordinatorTest 验证明确确认、刷新不再次推进 epoch、旧权限立即失效；RuntimeManagerTest.latePollAfterExplicitReplacementCannotWriteCursorOrHistoryForNewIdentity；真实协议待验 |
| Real protocol evidence gates automatic binding acceptance / Validate two independent accounts | 真实待验 | TwoAccountWechatProbeTest 仅为合成观察工具测试；须经授权按 multi-user-protocol.md 用两个真实账号执行 |

## multi-user-runtime

| Requirement / Scenario | 状态 | 测试或人工步骤 |
|---|---|---|
| Independent channel state / One connection reconnects while another remains active | 合成通过 | RuntimeManagerTest.transientPollFailureRetriesOnlyThatConnection / invalidCredentialStopsOnlyItsOwnerAndReauthenticationKeepsIdentityAndCursor；真实微信连接并存待验 |
| Independent channel state / Restart with several active users | 合成通过 | RuntimeManagerTest.restartRecoversReadyReplyButNeverReplaysAnInterruptedModel / uncertainSendIsNotRepeatedEvenAfterRestartOrDuplicateInput；真实协议观察待验 |
| Authorization scoped conversation data / Interleaved user histories | 组件通过 | ManagedRepositoryTest.historiesScopesAndDuplicateMessageIdsAreIsolated / turnWorkerSendsOnlyOwnConfirmedHistoryAndNeverRepeatsModelForSend；RuntimeManagerTest.twoConnectionsKeepCursorHistoryAuxiliaryCallsAndDedupSeparate |
| Authorization scoped conversation data / Stale work after rebind | 组件通过 | ManagedRepositoryTest.reauthenticationKeepsBindingButRejectsAnotherOwnerAndOldEpochWork / replacementNeverCarriesOldHistoryIntoNewBinding |
| Bounded and fair processing / Slow model call for one user | 组件通过 | FairUserSchedulerTest.slowAAllowsBAndCToProgressWithinGlobalConcurrency / aBusyUserYieldsToOtherUsersAndDuplicateWakesAreCoalesced；RuntimeManagerTest.slowUserBackpressureDoesNotBlockOtherUserAndNeverCommitsRejectedBatch |
| Bounded and fair processing / Per-user backlog limit reached | 组件通过 | ManagedRepositoryTest.backlogRollsBackEventsConversationsAndCursorAsOneBatch；RuntimeManagerTest.oversizedPendingBatchRetainsCursorUntilEnoughCapacityIsAvailable / globalBacklogIsReportedWhileAlreadyAcceptedWorkCanDrain |
| Disable revokes runtime authorization / Disable during a model call | 组件通过 | ManagedRepositoryTest.turnWorkerDoesNotDispatchLateReplyAfterDisableAndPreservesAccounting；RuntimeManagerTest.authorizationIsRecheckedBetweenTypingTicketAndTypingAndModel / disableResumeReservesWorkerUntilOldCallExitsAndRejectsAllOldReplies |
| Durable deduplication and uncertain delivery / Duplicate and restart | 组件通过 | ManagedRepositoryTest.historiesScopesAndDuplicateMessageIdsAreIsolated / duplicateActivationIsIdempotentAndInterruptedProcessingIsNotReplayed / turnWorkerKeepsLocalCommandsFreeAndDoesNotRetryAmbiguousSends |

## conversation-lifecycle

| Requirement / Scenario | 状态 | 测试或人工步骤 |
|---|---|---|
| Sliding idle timeout / Sliding window remains active | 组件通过 | ManagedRepositoryTest.idleWindowSlidesAndExpiresAtExactBoundaryWithoutHelpOrDuplicatesRefreshingIt |
| Sliding idle timeout / Boundary and next conversation | 组件通过 | ManagedRepositoryTest.idleWindowSlidesAndExpiresAtExactBoundaryWithoutHelpOrDuplicatesRefreshingIt |
| Sliding idle timeout / Duplicate and unauthorized traffic | 组件通过 | ManagedRepositoryTest.idleWindowSlidesAndExpiresAtExactBoundaryWithoutHelpOrDuplicatesRefreshingIt / historiesScopesAndDuplicateMessageIdsAreIsolated |
| Runtime configurable timeout / Reduce timeout for an existing conversation | 组件通过 | ManagedRepositoryTest.timeoutUpdateIsImmediateAndSurvivesRestart；AdminApiTest 校验与持久化 |
| Runtime configurable timeout / Increase timeout does not resurrect history | 组件通过 | ManagedRepositoryTest.timeoutUpdateIsImmediateAndSurvivesRestart |
| Session boundary preserves history and identity / Explicit new conversation | 组件通过 | ManagedRepositoryTest.newCommandAndQueueDelayDoNotReassignAlreadyAcceptedMessages / turnWorkerKeepsLocalCommandsFreeAndDoesNotRetryAmbiguousSends |
| Session boundary preserves history and identity / Idle expiration is not a WeChat logout | 组件通过 | ManagedRepositoryTest.failedModelResponseDoesNotRefreshActivityAndExpiredHistoryIsNotLoaded；真实多连接不掉线待验 |
| Durable and race safe message assignment / Restart after a long idle period | 组件通过 | ManagedRepositoryTest.timeoutUpdateIsImmediateAndSurvivesRestart |
| Durable and race safe message assignment / Processing delay does not create artificial inactivity | 组件通过 | ManagedRepositoryTest.newCommandAndQueueDelayDoNotReassignAlreadyAcceptedMessages |
| Durable and race safe message assignment / Concurrent first messages after timeout | 组件通过 | ManagedRepositoryTest.simultaneousMessagesAfterTimeoutShareOneNewConversation |

## model-cache-accounting

| Requirement / Scenario | 状态 | 测试或人工步骤 |
|---|---|---|
| Provider neutral compatible requests / Gateway rejects unknown request fields | 本地 HTTP 测试 | CompatibleChatClientTest.strictRelayAcceptsStablePrefixAndFullHistoryWithoutSpecialCacheFields |
| Provider neutral compatible requests / Provider does not support cache discounts | 本地 HTTP 测试 | CompatibleChatClientTest.strictRelayAcceptsStablePrefixAndFullHistoryWithoutSpecialCacheFields / boundedRawParserDoesNotFabricateUsage |
| Stable prefix within bounded context / Consecutive turns share a prefix | 本地 HTTP 测试 | CompatibleChatClientTest.strictRelayAcceptsStablePrefixAndFullHistoryWithoutSpecialCacheFields |
| Stable prefix within bounded context / Context budget evicts old history | 本地 HTTP 测试 | CompatibleChatClientTest.boundedContextDropsOldTurnsEvenWhenThatChangesCachePrefix；ConversationPolicyTest |
| Cache state respects identity and model boundaries / A new session or model configuration | 组件通过 | ManagedRepositoryTest.replacementNeverCarriesOldHistoryIntoNewBinding / modelSettingsAreVersionedAndBootstrapCannotResetAnAdministrator；无显式缓存资源 |
| Cache state respects identity and model boundaries / Cross-user cache identifier | 组件通过 | ManagedRepositoryTest.persistedObserverRejectsConversationInjectionAndRechecksRevocationBetweenAttempts；ModelRequestContext 不进入请求体 |
| Truthful token accounting / Complete cache usage reported | 本地 HTTP 测试 | CompatibleChatClientTest.reportsActualCountsWithoutAddingAnyVendorOrIdentityFields / countsAreNotNarrowedToLibraryIntegers |
| Truthful token accounting / Usage details omitted or malformed | 本地 HTTP 测试 | TokenUsageTest；CompatibleChatClientTest.boundedRawParserDoesNotFabricateUsage / everyRetryIsObservedButEmptyAnswersRetainUsageAndNeverRetry |
| Truthful token accounting / Disable or switch configuration during a call | 组件通过 | ManagedRepositoryTest.persistedObserverRejectsConversationInjectionAndRechecksRevocationBetweenAttempts / turnWorkerDoesNotDispatchLateReplyAfterDisableAndPreservesAccounting |
| Honest aggregate reporting / Mixed known and unknown calls | 合成通过 | ManagedRepositoryTest.modelHttpAttemptsReachScopedDurableUsageEvenWhenDeliveryFails / partialAndUnreturnedUsageRemainVisibleAcrossRestart；AdminApiTest.metadataAndFilteredUsageAreScopedPrivateAndTruthful 与真实浏览器验证完整、未知、异常、显式零和部分统计，Node 覆盖迟到筛选响应；真实上游字段仍待验 |

## managed-container-deployment

| Requirement / Scenario | 状态 | 测试或人工步骤 |
|---|---|---|
| Single container with restricted management ingress / Deploy with the documented Compose file | 待实现 / NAS 待验 | 默认 Compose 尚未切换；后续执行受限端口、单镜像、内部健康 smoke |
| Single container with restricted management ingress / Access through a protected remote entry | 人工待验 | 仅回环预览验证通过；受信代理/来源校验/HTTPS/安全 cookie 配置需专门联调 |
| Explicit administrator bootstrap / First installation has no administrator secret | 合成通过 | AdminApiTest.absentBootstrapDoesNotOpenAccessAndResidualValuesCannotResetPassword |
| Explicit administrator bootstrap / Restart after initialization | 合成通过 | AdminApiTest.absentBootstrapDoesNotOpenAccessAndResidualValuesCannotResetPassword |
| Fresh data without legacy destruction / Existing single-user directory is mounted | 组件通过 | ManagedStoreTest 只读前置检查验证字节/权限/目录未修改；容器挂载烟测待验 |
| Fresh data without legacy destruction / Fresh independent installation | 部分 | ManagedStoreTest / AdminApiTest 独立新目录与无模型启动；容器首次启动待验 |
| Configurable runtime identity with private state / Root and non-root deployments | 容器待验 | 默认 root/non-root 与私有挂载权限矩阵尚未执行 |
| Shutdown matches the default container stop window / Stop with a model request in progress | 部分 | FairUserSchedulerTest 有界停止；CompatibleChatClientTest 超时不重试；RuntimeManager 已有保守中断/恢复；完整容器停止预算仍未实现 |
| Backup recovery and verified publication / Restore and resume | NAS 待验 | 停止后完整备份、恢复两账号与凭据重登观察；未执行，不以普通重启测试代替 |
| Backup recovery and verified publication / Protocol checks are still pending | 已明确限制 | production resolver 失败关闭；页面开发预览提示；未发布多用户镜像，change 未归档 |

## 发布与规格替换限制

现有 add-personal-wechat-chat-assistant 的单用户/手工绑定/无 Web 要求与本 change 属替换关系。本轮没有改动、同步或归档旧 change。管理端默认容器入口切换、真实协议身份依据与目标环境验收完成前，禁止标注全部交付，也不机械合并两套互相冲突的规格。

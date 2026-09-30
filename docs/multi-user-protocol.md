# 多用户扫码身份验证边界

## 已查阅的资料与结论

本地参考包 `@tencent-weixin/openclaw-weixin` 2.4.9 的 `src/auth/login-qr.ts` 将登录结果 `ilink_user_id` 描述为扫码者 ID；消息接口则使用 `from_user_id`。这证明了字段来源，但没有单独证明二者的命名空间、跨机器人稳定性及通用等价关系。既有单账号联调用一次性测试消息关联了发送者，也不能作为多人自动授权的通用协议证明。

`ScannerIdentityResolver` 定义确认登录证据、协议版本、带命名空间的账号身份、bot/sender 与证据版本。它不接受首条入站消息作为身份材料。生产 resolver 现仅支持下文已核对的 2.4.9 / bot_type=3 / ilinkai.weixin.qq.com 范围；没有可通过配置开启的“信任首消息”或测试身份绕过。

自动绑定实现与完整产品验收分开记录：协议观察和生产适配已有明确范围，完整真实管理页面/模型/NAS 验收仍未完成，不能用独立探针代替。

## 必须取得的证据

1. 可靠协议资料或可信映射能将确认登录的扫码身份关联到消息 sender；记录命名空间、稳定性和适用协议版本。两个字符串碰巧相同不构成充分依据。
2. 经操作者同意的两个不同微信账号分别扫码后，两套 token/bot 可同时轮询和收发，后登录者没有覆盖前者。
3. 同一账号再次扫码时是否复用 bot、返回已有绑定提示或撤销旧 token；此项可能使既有部署掉线，必须单独取得操作者同意。
4. 两个账号各自的 sender 与扫描身份关联正确；错误/陌生发送者不能自动获得权限。

原始二维码、token 与身份仅可保留在操作者本地受保护的测试目录。公开记录只能说明验证范围和结果，不能粘贴这些材料。测试消息仅用于开发联调，不是最终用户开通流程；最终产品不能要求再次发送绑定口令。

## 当前状态

- 已实现：已验证协议范围的身份解析、未知范围拒绝、自动激活和同身份换机器人重认证。
- 已观察：两个授权账号独立扫码及同时收发；A 重扫后机器人 ID 改变、扫码身份不变，旧 A token 失效，新 A 和原 B 均可收发。范围仅限下述实测。
- 已核对下文上游身份使用依据；此命名空间限定于该服务及 bot_type，不声称是通用微信 ID。
- 不得据此宣布：生产扫码自动授权已可用、所有账号都支持接入或两个账号永不互相使凭据失效。

## 2026-09-29 单账号实测补充

经操作者授权，独立 `WechatConnectivityProbe` 完成一次真实扫码与随机测试消息往返：

- 登录确认成功，随机测试消息匹配，入站 context token 存在。
- 本次登录的扫码身份与该测试消息发送者 ID 相等。
- 唯一一次测试回复获得接口成功确认，程序以 `COMPLETED` 正常退出；没有调用模型，也没有创建 managed 用户授权。
- 原始凭据与身份仅保留于操作者本机私有测试目录，不进入仓库。本记录不包含二维码、token、身份或测试消息内容。

此阶段两账号程序仅完成 A 登录，因操作者当时没有第二个账号而被主动停止。此阶段尚未验证两账号并存或重扫影响；后续结果见下一节。

重新核对锁定上游版本的协议文档后，文档将 `ilink_user_id` 描述为扫码用户 ID、`from_user_id` 描述为发送者 ID，同时明确客户端类型与行为不代表完整服务端契约；没有从该文档取得跨机器人全局身份稳定性的明确保证。

单账号结果不单独满足任务 1.4、1.5、4.4，不能将本次 ID 相等直接当作通用自动授权依据。

## 2026-09-29 双账号与同号重扫实测补充

操作者随后提供两个微信账号，并单独同意重扫可能撤销旧凭据的测试。独立 `TwoAccountWechatProbe` 完成基础并存测试及另一轮带 `--allow-relogin` 的测试，均正常退出：

- A、B 返回不同的机器人 ID 和扫码身份。两轮初始并存检查均各自接收随机测试消息并获得回复成功确认，操作者确认两边收到回复。
- 两个账号各自的扫码身份与各自测试消息发送者 ID 相等。
- A 再次扫码后，机器人 ID 改变，扫码身份保持不变。旧 A 凭据轮询返回 `-14`，被分类为 `REMOTE_STALE_TOKEN`，未对旧连接尝试发送。
- 新 A 与原 B 均再次接收各自测试消息并获得回复成功确认，操作者确认收到回复；两边扫码身份仍分别与消息发送者一致。
- 测试未调用模型、未激活 managed 授权、未修改 NAS 数据。仅保存本地受保护证据，本记录不包含真实身份或凭据。

该结果证明本次环境中的连接并存和重扫行为，不保证任意账号、版本、节点或未来服务端行为相同。配对码、其他服务节点、跨版本稳定性及陌生发送者的真实拒绝路径未实测。生产适配必须区分稳定账号身份与会变化的机器人 ID，并处理旧 token 撤销；协议身份语义审查与生产实现仍待完成，不仅凭实测相等启用自动授权。

## 锁定上游的身份使用依据与实现阻塞

已进一步核对腾讯上游 commit `24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c` 的完整调用关系，而不只依赖协议文档的字段名称：

- [`src/auth/login-qr.ts`](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/src/auth/login-qr.ts) 将确认响应的 `ilink_user_id` 返回为 `userId`，注释明确用于 `allowFrom`。
- [`src/channel.ts`](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/src/channel.ts) 保存该 `userId`；[`src/messaging/process-message.ts`](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/src/messaging/process-message.ts) 以它作为发送者授权列表的回退值，与入站 `from_user_id` 精确匹配。
- [`src/auth/accounts.ts`](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/src/auth/accounts.ts) 的 `clearStaleAccountsForUserId` 用同一扫码 `userId` 识别并清理新机器人之外的旧账号；这提供了上游跨机器人身份使用依据，与本次重扫观察相符。

据此，任务 1.4 的协议依据、双账号并存与重扫观察已完成。拟支持范围固定为客户端协议 2.4.9、bot_type=3、`https://ilinkai.weixin.qq.com`，命名空间绑定该服务范围，不声称这是微信号或其他协议的通用 ID。其他版本/节点继续未验证。

新增带历史的重认证回归曾暴露 V001 布局问题：历史事件外键直接引用可变的当前 binding，更新 bot_id 与旧事件冲突。经操作者授权，V002 增加连接身份历史并事务迁移事件表约束，保留全部旧记录和原 bot_id；重认证只新增连接身份、更新当前 bot 与 generation。迁移按 SQLite 表重建流程进行，提交前完整性检查，失败回滚，运行前恢复外键执行，不以删除历史或运行期关闭约束绕过。

## 两账号独立验证入口

`TwoAccountWechatProbe` 不启动 Spring、不接触正式数据库、不调用模型。必须由操作者明确传入允许真实微信访问的参数；重复扫码还需要额外的 opt-in。不要使用正在正式运行的账号做重复登录测试，或先停止其旧实例。

先完成打包，在装有 Java 21 的测试机上运行，最后一个参数必须是新的、尚不存在的私有目录：

```bash
java -Dloader.main=io.github.wochen5770.talkweave.runtime.probe.TwoAccountWechatProbe \
  -Dloader.path=target/talkweave-0.1.0-SNAPSHOT-diagnostics.jar \
  -cp target/talkweave-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --allow-live-wechat /private/probes/two-account-run-01
```

1. 在测试目录的 `a-login/qr.png` 扫码登录账号 A，再在 `b-login/qr.png` 扫码登录不同账号 B；如要求配对码，只在对应目录的 `verify-code.json` 修改 `code`，保留原 challengeId。
2. 两个账号都登录后，查看 `coexist-a/status.json` 和 `coexist-b/status.json`，分别在对应机器人私聊发送文件中的 `expectedMessage`。这只是开发验证，不是最终产品的绑定口令。
3. 测试同时轮询两套连接，各确认一次受保护的随机测试消息并仅尝试回复一次。单个发送超时记录为 DELIVERY_UNKNOWN，不重复发送。
4. 根目录 `evidence.json` 保存不含原始身份的观察摘要；各子目录的 `credentials.json` 和 `evidence.json` 含敏感原始材料，必须留在本地，不上传或提交仓库。

若明确同意观察同号重扫对旧凭据的影响，在另一个全新目录运行时加入 `--allow-relogin`：

```bash
java -Dloader.main=io.github.wochen5770.talkweave.runtime.probe.TwoAccountWechatProbe \
  -Dloader.path=target/talkweave-0.1.0-SNAPSHOT-diagnostics.jar \
  -cp target/talkweave-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --allow-live-wechat --allow-relogin /private/probes/two-account-run-02
```

完成初始 A/B 测试后，`a-relogin/qr.png` 必须仍由账号 A 扫描。随后按 `after-old-a`、`after-b`、`after-new-a` 的状态文件测试：旧 A token 可能已被服务端撤销，此时无须为旧 A 再发送测试消息；B 和新 A 的结果仍分别记录。测试不自动假定同号重扫行为一致。

退出码 0 仅表示观察记录已写入，不代表所有网络检查成功，更不代表身份关联已获协议证明。摘要中的 `automaticBindingApproved` 始终为 false；完成真实验证后还需检查协议语义、原始身份及结果，才能实施生产 resolver（任务 1.4/1.5）。没有使用 `--allow-relogin` 时该分支明确记为未验证。

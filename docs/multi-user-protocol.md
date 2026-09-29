# 多用户扫码身份验证边界

## 已查阅的资料与结论

本地参考包 `@tencent-weixin/openclaw-weixin` 2.4.9 的 `src/auth/login-qr.ts` 将登录结果 `ilink_user_id` 描述为扫码者 ID；消息接口则使用 `from_user_id`。这证明了字段来源，但没有单独证明二者的命名空间、跨机器人稳定性及通用等价关系。既有单账号联调用一次性测试消息关联了发送者，也不能作为多人自动授权的通用协议证明。

`ScannerIdentityResolver` 现在定义了确认登录证据、协议版本、带命名空间的全局账号身份、bot/sender 与证据版本。它不接受首条入站消息作为身份材料。生产 resolver 当前只返回未验证结果：没有可通过配置开启的“信任首消息”“两个 ID 相同就绑定”或测试身份绕过。

这不是已完成的扫码自动绑定功能。对应 change 的真实验证和生产协议适配任务保持未完成，旧单用户部署没有因此被授权逻辑替换。

## 必须取得的证据

1. 可靠协议资料或可信映射能将确认登录的扫码身份关联到消息 sender；记录命名空间、稳定性和适用协议版本。两个字符串碰巧相同不构成充分依据。
2. 经操作者同意的两个不同微信账号分别扫码后，两套 token/bot 可同时轮询和收发，后登录者没有覆盖前者。
3. 同一账号再次扫码时是否复用 bot、返回已有绑定提示或撤销旧 token；此项可能使既有部署掉线，必须单独取得操作者同意。
4. 两个账号各自的 sender 与扫描身份关联正确；错误/陌生发送者不能自动获得权限。

原始二维码、token 与身份仅可保留在操作者本地受保护的测试目录。公开记录只能说明验证范围和结果，不能粘贴这些材料。测试消息仅用于开发联调，不是最终用户开通流程；最终产品不能要求再次发送绑定口令。

## 当前状态

- 已实现：失败关闭的身份解析边界与合成拒绝测试。
- 未验证：两账号真实扫码、账号命名空间保证、并存和重扫行为。
- 不得据此宣布：生产扫码自动授权已可用、所有账号都支持接入或两个账号永不互相使凭据失效。
## 两账号独立验证入口

`TwoAccountWechatProbe` 不启动 Spring、不接触正式数据库、不调用模型。必须由操作者明确传入允许真实微信访问的参数；重复扫码还需要额外的 opt-in。不要使用正在正式运行的账号做重复登录测试，或先停止其旧实例。

先完成打包，在装有 Java 21 的测试机上运行，最后一个参数必须是新的、尚不存在的私有目录：

```bash
java -Dloader.main=io.github.wochen5770.talkweave.runtime.probe.TwoAccountWechatProbe \
  -Dorg.sqlite.tmpdir=/tmp -cp target/talkweave-0.1.0-SNAPSHOT.jar \
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
  -cp target/talkweave-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --allow-live-wechat --allow-relogin /private/probes/two-account-run-02
```

完成初始 A/B 测试后，`a-relogin/qr.png` 必须仍由账号 A 扫描。随后按 `after-old-a`、`after-b`、`after-new-a` 的状态文件测试：旧 A token 可能已被服务端撤销，此时无须为旧 A 再发送测试消息；B 和新 A 的结果仍分别记录。测试不自动假定同号重扫行为一致。

退出码 0 仅表示观察记录已写入，不代表所有网络检查成功，更不代表身份关联已获协议证明。摘要中的 `automaticBindingApproved` 始终为 false；完成真实验证后还需检查协议语义、原始身份及结果，才能实施生产 resolver（任务 1.4/1.5）。没有使用 `--allow-relogin` 时该分支明确记为未验证。
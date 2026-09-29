# 协议与依赖基线

记录日期：2026-09-28。分别记录已读取的资料、本地契约验证和有界的真实互通结果，不将单次成功推断为通用服务保证。

## 微信来源

1. 腾讯上游 `Tencent/openclaw-weixin`：commit `24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c`，package version **2.4.9**，MIT。
   - [协议文档](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/docs/protocol_zh_CN.md)
   - [类型](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/src/api/types.ts)
   - [登录行为](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/src/auth/login-qr.ts)
   - [许可证](https://github.com/Tencent/openclaw-weixin/blob/24de5c9eb0dd5e595d7e2d090ed8a3f82870d42c/LICENSE)
2. 用户提供的 Python 参考仓库：commit `22381fe994f4b0265195861ff5bb335a41cfa22d`，说明对齐上游 2.4.6。此前仓库元数据未声明许可证，本项目不复制该仓库的 Python 源码。当前实现优先依据腾讯上游的事实性协议描述，自行编写 Java。

本地参考副本只位于被忽略的 `.build-cache/references/`，不属于构建输入或发行物。

## 当前最小契约

| 操作 | 请求与判断 |
|---|---|
| 申请二维码 | 固定入口的 POST `/ilink/bot/get_bot_qrcode?bot_type=3`，`local_token_list` 最多 10 项，首次为空 |
| 扫码状态 | GET `/ilink/bot/get_qrcode_status`，URL 编码 `qrcode` 与可选数字 `verify_code`；不携带 bot Authorization/UIN |
| 状态类型 | wait、scaned、need_verifycode、verify_code_blocked、expired、scaned_but_redirect、binded_redirect、confirmed 分别返回类型化状态，不把绑定提示冒充新凭据 |
| 确认登录 | 读取 bot_token、ilink_bot_id、baseurl，单独保留 ilink_user_id；不假设扫码身份等于消息 sender id |
| 节点切换 | 仅接受显式信任的 HTTPS 微信域名 origin；默认只有 ilinkai.weixin.qq.com。其他实际返回节点须人工核实后加入调用方的 trustedHosts；拒绝任意域名和 HTTP 自动跳转 |
| 收消息 | POST `/ilink/bot/getupdates`，携带 `get_updates_buf` 和 `base_info`，返回游标只供后续持久事务提交，不由客户端提前保存 |
| 稳定消息标识 | 上游类型为 uint64 的 message_id；JSON 字符串和整数均保留十进制原值，不转换为浮点数 |
| 文本回复 | POST `/ilink/bot/sendmessage`，msg 中带接收者、原入站 context_token、调用方生成/保存的唯一 client_id、message_type=2、message_state=2、TEXT item |
| 成功判断 | HTTP 2xx、JSON object、缺省或为 0 的数值 ret/errcode；任一业务码为 -14 均分类为失效；错误 body 不记录 |

2.4.9 的应用版本头按 `0x00020409` 编码，即十进制 `132105`。`base_info.channel_version` 为 `2.4.9`，bot_agent 标记为本项目而不冒充 OpenClaw。

上述字段来自客户端协议说明，不是服务端保证。`getconfig`、输入状态、生命周期通知、自动重连/恢复和完整登录编排属于后续任务，目前没有假装实现。通道允许的长度、关联令牌时效、真实消息 ID 和节点列表仍需真实验证。

## 模型与 Java 依赖

锁定 Java 21、Spring Boot **3.5.13**、Spring AI **1.1.8**、SQLite JDBC **3.49.1.0**；已通过本机构建和本地测试验证该组合，不声明这些是所有依赖的最新版本。

模型适配使用 Spring AI 1.1.8 发行 source JAR 中的 OpenAiChatModel / OpenAiApi / OpenAiChatOptions 核实实际接口行为。官方 OpenAI Chat Completions 页面在本次网络访问中返回 403，未将未读取的官方网页当作互通证据。

模拟 HTTP 服务断言请求只有 model、messages、stream=false、max_tokens，不存在 temperature、tools、默认模型或隐式 fallback。显式禁止内部工具执行和框架自动重试。1.1.8 的结束原因 metadata 使用枚举名 `LENGTH`；同时抑制其在空候选时可能输出完整 Prompt 的框架日志。

测试使用纯合成的 fake- 前缀标识/密钥，无真实二维码、账号、token 或付费请求。任务 2.4、2.5 已在本机完成各自的真实互通验证，范围与限制见 `implementation-status.md`；任务 2.6 的 NAS 验证仍未执行。

## 自定义 API 实测补充

2026-09-28，经操作者明确同意，对本地配置的服务使用模型标识 `gpt-6-luna` 完成两次非流式请求。`max_tokens=256` 被接受，文本结果可解析，第二轮携带首轮历史并准确回忆随机校验词；无重试、无模型切换。上下文配置为 8192，但未测容量上限。详情及不含正文的证据范围见实施状态文档。此结果仅适用于本次配置的兼容服务，不代替官方 OpenAI 文档，也不证明底层模型身份。

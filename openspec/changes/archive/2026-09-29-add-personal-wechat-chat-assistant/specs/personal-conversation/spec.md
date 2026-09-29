## Purpose

为单个明确授权的个人微信使用者提供可持续的多轮对话，定义新建会话、历史恢复、上下文选择和消息处理的可观察行为，确保重复投递、连续输入及服务重启不会导致跨身份泄露、无序回答或无法解释的历史状态。

## ADDED Requirements

### Requirement: Explicit personal identity authorization
系统 MUST 根据可信的本地配置校验机器人身份和使用者身份，在绑定缺失或不匹配时默认拒绝模型调用及历史访问。系统 MUST 不将第一位发消息的人自动认定为所有者，也不得将未经证实等价的登录身份和消息发送者身份混用。

#### Scenario: Unbound or unauthorized sender
- **WHEN** 尚未配置所有者，或入站身份与绑定身份不匹配
- **THEN** 系统不调用模型、不返回任何聊天历史，也不把该内容加入个人会话

#### Scenario: Bot account changes after login
- **WHEN** 重新扫码获得的机器人身份不同于当前绑定身份
- **THEN** 系统要求本地重新确认绑定，不自动向新机器人身份开放原有个人会话

### Requirement: Persistent current conversation
系统 SHALL 为已绑定身份维护一个当前会话，持久记录用户输入、模型结果、实际回复和处理状态。正常重启后 SHALL 恢复相同当前会话及已成功交互的上下文；微信协议关联令牌 MUST 不充当业务会话标识。

#### Scenario: Continue after restart
- **WHEN** 一个对话轮次已成功回复，应用重启后用户继续发消息
- **THEN** 当前会话保持不变，后续模型请求能够使用此前预算范围内的成功对话

### Requirement: Explicit new conversation and help
系统 SHALL 将精确匹配的 `/new` 作为新建会话指令，保留旧会话记录但不再将其用于新会话上下文，并返回确认。系统 SHALL 支持 `/help` 显示当前支持的能力及指令。指令 MUST 不调用模型；未知斜杠指令 SHALL 返回帮助提示。

#### Scenario: Start fresh without deleting history
- **WHEN** 用户发送 `/new`
- **THEN** 系统持久创建新的当前会话并确认，下次模型调用不包含旧会话内容，旧记录不被删除

#### Scenario: Ask for available commands
- **WHEN** 用户发送 `/help` 或未知斜杠指令
- **THEN** 系统返回支持的指令和文字对话范围，不消耗模型调用

### Requirement: Ordered durable processing
系统 SHALL 按持久接收的顺序处理同一绑定身份的消息，一次仅执行一个对话轮次；普通文本、指令和本地能力提示 MUST 遵循同一顺序。消息归属哪个当前会话 SHALL 在轮到该消息处理时确定，防止排队中的 `/new` 失效。

#### Scenario: New conversation arrives during a slow answer
- **WHEN** 用户依次发送问题 A、`/new`、问题 B，且 A 的模型请求仍在运行
- **THEN** 系统先结束 A 的处理，再确认新会话，最后使用新会话回答 B；B 不携带 A 的上下文

### Requirement: Idempotent inbound acceptance
系统 MUST 使用经验证的通道稳定消息标识及机器人身份识别重复入站消息，确保同一消息重放不重复调用模型或重复执行 `/new`。内容相同但消息标识不同的两条输入 MUST 被视为独立消息；缺少可验证标识的异常消息 MUST 被隔离而非仅凭文本去重。

#### Scenario: Duplicate across process restart
- **WHEN** 同一入站消息在重启后再次出现，且此前已有持久处理记录
- **THEN** 系统复用既有处理状态，不新建第二个轮次、第二次模型调用或第二次新会话操作

#### Scenario: User repeats the same question intentionally
- **WHEN** 用户连续发送文本相同但消息标识不同的两条消息
- **THEN** 系统按两个独立输入顺序处理

### Requirement: Bounded and truthful context
系统 SHALL 组合系统提示词、当前输入及当前会话最近的完整成功轮次，并按配置的消息数量和上下文预算裁剪最旧历史。失败、取消、未确认送达的轮次及运维指令 MUST 不作为成功对话自动纳入上下文。发生回复截断时，后续上下文 SHALL 使用实际成功发送的文本，而不是用户未见的完整结果。

#### Scenario: History exceeds the budget
- **WHEN** 保存的历史超过配置的模型输入预算
- **THEN** 系统从最旧完整轮次开始移除上下文，保留系统提示词和当前输入，不删除持久聊天记录

#### Scenario: Current input alone is too large
- **WHEN** 系统提示词与当前输入即使移除全部历史仍超过配置预算
- **THEN** 系统不发送超预算请求，提示缩短输入或调整配置，且不静默截断用户本次问题

### Requirement: Recover without concealing uncertain work
系统 SHALL 在模型失败时记录失败轮次并尽力发送不泄露内部信息的提示，随后允许处理后续消息。重启后尚未执行的已接收消息 SHALL 可继续处理；已开始但结果未保存的模型调用或结果不确定的发送 MUST 不被当作成功，也不自动重新执行存在不确定性的外部操作。

#### Scenario: Crash during model execution
- **WHEN** 应用在模型请求已经开始、结果尚未持久保存时退出
- **THEN** 重启后该轮次标记为中断或结果不确定，不自动重新调用模型；状态可诊断，用户再次发起请求时可正常继续

#### Scenario: Model rejects a request
- **WHEN** 模型接口返回明确不可重试的错误
- **THEN** 本轮记录失败，在微信通道允许时发送简洁提示，不将原始异常或密钥发送给用户，也不阻塞后续轮次

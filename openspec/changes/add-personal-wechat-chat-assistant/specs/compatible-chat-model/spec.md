## Purpose

使个人助手通过用户指定的服务地址、凭据和模型名称使用 Chat Completions 兼容接口，提供可验证的非流式文字生成行为、配置检查、资源预算和错误边界，避免将普通对话能力绑定到某个模型厂商或误认为接口名称代表完整兼容性。

## ADDED Requirements

### Requirement: Explicit compatible endpoint configuration
系统 SHALL 支持配置模型服务基础地址、API Key、模型名、系统提示词、请求超时、上下文容量和输出预算。第一版 SHALL 使用 `/v1/chat/completions` 协议，不自动改用其他协议或其他提供商。基础地址及路径 MUST 按明确规则组合，避免重复版本路径；缺少必填配置时 SHALL 在开始消费对话前报告脱敏错误。

#### Scenario: API prefix is configured explicitly
- **WHEN** 用户配置带可选网关前缀但不含末尾 `/v1` 的服务基础地址
- **THEN** 系统在该前缀后追加一次 `/v1/chat/completions`，保留指定模型和鉴权，不请求默认提供商

#### Scenario: Ambiguous or missing configuration
- **WHEN** 基础地址已包含末尾 `/v1`、完整接口路径、URL 凭据或查询参数，或缺少密钥与模型名
- **THEN** 系统给出明确的配置修正提示，不重复拼接接口路径、不悄悄回退配置，也不输出凭据

### Requirement: Minimal non-streaming text exchange
系统 SHALL 发送按顺序排列的系统、用户和助手消息，使用配置的模型和非流式请求，并携带经兼容性验证的输出预算参数。第一版 MUST 不发送工具定义、图片、音频或厂商私有必需参数。系统 SHALL 将首个有效候选的文本内容作为模型结果；空候选、空文本或仅有工具调用的响应 SHALL 被视为不支持或无有效回答。

#### Scenario: Compatible service returns a text answer
- **WHEN** 配置的接口接受请求并返回有效文本候选
- **THEN** 系统提取回答文字供微信发送，不把响应 JSON 直接发送给用户

#### Scenario: Response contains no usable text
- **WHEN** 接口返回空候选、空白文本、无效 JSON 或仅工具调用
- **THEN** 系统记录可诊断的兼容性错误，不执行工具，也不将空回复标记为成功

### Requirement: Configurable context and output budgets
系统 MUST 在调用前为系统提示词、历史、当前输入、消息封装及输出预留空间，并根据配置容量检查预算；未知模型 SHALL 使用有说明的保守估算，不假设可以仅凭模型名称得到准确容量。接口声明达到输出长度上限时 SHALL 保留已有文本并标明可能不完整。

#### Scenario: Invalid budget configuration
- **WHEN** 配置的输出预留与安全余量已占满或超过上下文容量
- **THEN** 系统拒绝该配置并提供可修正提示，而不是提交无法容纳输入的请求

#### Scenario: Provider truncates generation
- **WHEN** 响应表明生成因长度限制而终止，且包含可用文字
- **THEN** 系统返回已有文字并加不完整提示，不把它伪装成完整回答

### Requirement: Bounded failures without hidden provider fallback
系统 SHALL 对网络和服务错误分类，采用请求期限、最多重试次数及总耗时限制。仅尚未发出请求的可判定连接失败以及明确允许重试的限流或暂时不可用响应 SHALL 自动重试；鉴权、参数错误及请求可能已经执行的读超时 MUST 不盲目重试。系统 MUST 不切换到未经配置的服务或泄露原始错误响应中的敏感内容。

#### Scenario: Rate limiting
- **WHEN** 服务明确返回可重试限流响应
- **THEN** 系统在总时间及次数预算内退避重试，在可用时遵守服务端等待提示，耗尽预算后结束本轮并给出简洁失败提示

#### Scenario: Authentication failure
- **WHEN** 服务返回鉴权失败
- **THEN** 系统不反复重试、不更换服务，输出脱敏的配置诊断并结束本轮

#### Scenario: Read timeout after dispatch
- **WHEN** 请求已经发出但模型响应读超时
- **THEN** 系统将本轮记为结果不确定或失败，不自动发起可能重复计费的第二次模型请求

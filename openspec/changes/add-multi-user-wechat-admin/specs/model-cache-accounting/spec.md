## Purpose

在不锁定模型厂商或中转站的条件下保持对话请求兼容，通过稳定输入前缀利用上游已有的缓存能力，并向管理员如实报告模型返回的用量。缓存属于可选性能和费用优化，不能替代完整上下文、跨用户授权隔离或正确的会话边界。

## ADDED Requirements

### Requirement: Provider neutral compatible requests
系统 SHALL 允许配置兼容 API 地址、Key 与模型名，默认采用兼容缓存模式，不向未确认支持的接口发送专用缓存字段、创建缓存资源或假设服务端保存聊天状态。第一版 MUST 正常发送所需系统提示词、授权历史与当前问题；不以只发送最新一句话或返回旧答案代替模型输入缓存。

#### Scenario: Gateway rejects unknown request fields
- **WHEN** 所配置中转站只接受常规兼容请求字段
- **THEN** 默认请求不包含未经支持验证的缓存参数，仍可进行有上下文的正常对话

#### Scenario: Provider does not support cache discounts
- **WHEN** 服务不支持输入缓存或其保留时间已经结束
- **THEN** 对话仍携带正确历史并正常请求，不宣称缓存命中或费用优惠，不为获取命中而额外重复请求

### Requirement: Stable prefix within bounded context
系统 SHALL 使用稳定的系统提示词、确定的消息顺序和一致的内容拼接，避免在固定前缀插入每次变化的时间、随机数或管理展示数据。系统 MUST 继续遵守配置的历史轮数与上下文预算，不为缓存命中无限增长历史。截断、模型变化或中转路由变化可能降低命中，系统 MUST 不承诺固定命中率。

#### Scenario: Consecutive turns share a prefix
- **WHEN** 同一会话连续请求且系统提示词、模型配置及已选历史未发生截断变化
- **THEN** 两次请求的相同历史部分保持相同内容与顺序，新增问答追加在其后，缓存是否命中以提供商返回的信息为准

#### Scenario: Context budget evicts old history
- **WHEN** 历史轮数或上下文预算要求移除较早问答
- **THEN** 系统优先满足上下文限制，即使命中率可能下降，也不突破预算或重用不匹配历史

### Requirement: Cache state respects identity and model boundaries
应用维护的任何缓存标识或未来显式缓存句柄 MUST 归属于用户、绑定版本、会话及模型配置版本，不能承担授权依据或跨范围复用。提供商自动复用相同公共前缀不构成会话延续；缓存 TTL MUST 独立于本地会话空闲超时。特定厂商显式缓存适配不属于第一版必须实现的能力。

#### Scenario: A new session or model configuration
- **WHEN** 用户开启新会话、身份重绑或管理员切换模型服务配置
- **THEN** 新请求不引用不匹配范围的私有缓存资源；旧会话历史不因上游缓存尚存在而加入新请求

#### Scenario: Cross-user cache identifier
- **WHEN** 请求带有归属于另一个用户或绑定版本的应用侧缓存标识
- **THEN** 系统拒绝该关联，不读取其历史或提交该缓存资源给模型服务

### Requirement: Truthful token accounting
系统 SHALL 按实际模型调用尝试保存用户、绑定、会话、模型配置版本、时间、结果及接口明确返回的输入 token、输出 token 和缓存命中输入 token。缺失、无法解析或无法确认语义的字段 MUST 保持未知；不得把缺失当作零、按本地字节估算伪造真实用量，或仅凭字段缺失判定没有命中。

#### Scenario: Complete cache usage reported
- **WHEN** 服务返回输入 2000、其中缓存命中 1500、输出 100，且字段语义已被适配确认
- **THEN** 系统记录原值，展示缓存输入占比 75%，不把缓存输入再额外加到输入总量中

#### Scenario: Usage details omitted or malformed
- **WHEN** 响应未返回缓存明细，或返回负数、非数字、缓存量大于同口径输入量等不可确认数据
- **THEN** 缓存量和相关比例显示未知或数据异常，不伪造 0% 或 100%，有效回答仍按正常流程处理

#### Scenario: Disable or switch configuration during a call
- **WHEN** 请求发出后用户被停用或模型配置改变，随后服务返回已知用量
- **THEN** 用量归属原用户、原会话和原配置，不因禁止发送回复而丢弃，也不计入新身份或新配置

### Requirement: Honest aggregate reporting
管理端 SHALL 按用户、会话和时间范围汇总已知用量，并显示未知用量的调用数量或部分统计标记。一次尝试的用量 MUST 不因重复消息、响应重处理或重启重复累计；没有明确价格与计费口径时 MUST 不显示确定的节省金额。

#### Scenario: Mixed known and unknown calls
- **WHEN** 统计范围内部分请求返回用量，部分超时或未提供用量
- **THEN** 页面标记统计不完整，仅对已知数据给出明确口径的数值，不把已知部分当作全部实际费用或整体命中率

# 多用户管理端交付检查（2026-09-29）

## 后续修复：默认端口与 CI 测试卷

按操作者要求，默认管理端口统一改为 8680（应用、镜像、Compose 与烟测），健康端口仍为 8081。
运行 36584271008 的两架构镜像构建成功，但 root 存储烟测失败，未发布新镜像。
已在本机 Docker 复现空命名卷 copy-up 将准备好的 `0:0` 所有权恢复为镜像中的 `10001:10001`，导致 cap-drop ALL 的 root 无法写入。管理端测试挂载增加 `volume-nocopy` 后，同样的复现通过；不增加 capabilities、不扩大权限。正式 Compose 使用 bind mount，不受这个命名卷初始化行为影响。
本地完整镜像构建遇到 Docker Hub token 服务连接重置，修复后的完整双架构结果仍以新提交 CI 为准。

## 本轮完成

- 默认生产 main 固定启用 managed profile；旧单用户文件授权不会因省略 profile 被启用。
- Dockerfile 保持单镜像、非 root 镜像用户、可选运维配置；Compose 默认 `0:0`、独立 `data-multi-user`、仅回环管理端口，无额外服务或旧配置强制挂载。
- 缺少首次管理员密码时明确拒绝启动；`ADMIN_INIT_*` 为正式引导变量，兼容早期 `ADMIN_INITIAL_*`。移除引导秘密后持久账号不变。
- NAS 初始化、受保护入口、扫码信任、root 风险、完整停机备份、兼容恢复、原独立部署回滚已文档化。旧操作文档保留为 `legacy-operations.md`，不再作为新部署入口。
- CI 脚本新增两种 UID 的多用户数据库读写/权限、旧目录无写入拒绝、Web 登录、无模型设置页、内部健康、空闲停止及去除初始秘密后重建登录。脚本仍禁用外网，合成探针不创建微信绑定。

## 实际执行结果

| 检查 | 结果与范围 |
| --- | --- |
| Java 21 Maven verify | 232 项，0 失败、0 错误、0 跳过；仅本地合成服务 |
| Node 管理页逻辑测试 | 14 项通过；不是完整浏览器端到端验收 |
| actionlint v1.7.7 / Bash 语法 / Compose config | 通过；未触发 GitHub CI 或发布 |
| 默认 JAR 空目录启动 | 无外部 application.yml、无模型配置，测试管理员可登录、设置页可读；未启用微信绑定 |
| 浏览器 | 登录、备注 HTML 作为文本、30→17 分钟保存、退出返回登录页通过；未测试真实扫码，完整 10.3 不勾选 |
| 去除初始秘密后重启 | 固定 JAR 副本启动，原测试账号登录通过 |
| 本地空闲 SIGTERM | 正常 graceful shutdown，进程退出 143；不等同于 Docker 模型在途停止验收 |
| OpenSpec strict / git diff --check | 通过 |

首次 Maven 运行因 macOS `/var` 临时路径符号链接失败；使用 `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/private/tmp` 重跑通过，没有放宽安全路径检查。预览 JAR 曾被并行打包替换而导致退出类加载错误，随后使用固定副本重新验证正常退出；前一次异常不算通过。

范围审查：历史先 authorize + event 范围校验，再按 user/binding/conversation/sequence 查询；claim 同时约束 generation/epoch。仅按 sequence 的更新在同一事务中先校验对应 scoped event。用量开始检查范围，结束按内部 attempt ID 幂等归属原请求，管理汇总按 user 和 conversation 所属校验；HTTP 不开放任意 attempt 写入或聊天正文读取。认证、CSRF、来源、任务所属、跨范围 ID、ABA 与迟到结果由现有安全/隔离回归覆盖，未删除旧存储安全测试。

## 仍未完成

- 1.4 / 1.5 / 4.4 / 10.5：按操作者要求留到 NAS 后进行真实协议验证及其依赖适配。生产解析器继续失败关闭，不能直接承诺 NAS 扫码后就能自动授权。
- 9.2 / 10.4：root/non-root 与 amd64/arm64 镜像脚本已补齐，但本机 Docker daemon 未运行，未执行镜像测试或 GitHub CI。
- 9.3：移除了 stop_grace_period 和 20 秒收尾命令，设置了较短默认等待；完整应用（Web、二维码、所有连接、数据库）共享 8 秒预算及模型在途容器停止仍未完成，参数相加不能当作验收证据。
- 10.3：本轮只补充登录、设置、文本注入、退出浏览器检查；双用户独立绑定任务、用量混合数据和失效会话完整组合仍需端到端执行，已有 Node/API 合成结果不能替代。

当前不归档、不同步主规格、不发布镜像。旧 change 已保存在 `archive/2026-09-29-add-personal-wechat-chat-assistant`，当前不存在主规格目录；规划文档中“旧 change 尚未归档”的描述是原编写时背景。将来同步/归档须明确替换旧版手工绑定、单连接、无 Web、旧目录和 20 秒停止前提，不能机械合并互斥要求。既有去重、私有权限、不确定投递和确认投递历史等安全要求继续保留。

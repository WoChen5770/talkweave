# Talkweave：GitHub Actions / GHCR 使用说明

## 分工与当前状态

- 项目交付 Dockerfile、Actions、合成测试和部署说明；你负责提交到 GitHub、NAS 部署与真实验收。
- 当前工作流经过本地 actionlint 与契约测试；是否构建、发布成功仍以当前提交的 GitHub Actions 运行结果为准。本地测试未启动微信或调用实际模型。
- 不需要在开发机安装或启动 Docker，不需要提供 NAS 账号给开发代理。

## 1. 提交仓库

将源码提交到 GitHub，保留 `.github/workflows/container.yml`、`scripts/ci/`、Dockerfile 和 `pom.xml`。不要使用强制添加命令提交被忽略的 `config/`、`data/`、`.env`、本机缓存、数据库或真实联调材料。

工作流自动使用小写仓库名生成镜像路径；如果仓库名末尾有 `-`，会去掉以符合 Docker 镜像命名规则：

```text
ghcr.io/wochen5770/talkweave
```

发布仅使用 GitHub 自动提供的短期 `GITHUB_TOKEN`，不需要 Docker Hub 账号、GHCR PAT 或任何模型/微信 Secret。组织策略须允许工作流发布 Packages；构建 job 只有 `contents: read`，只有发布 job 获得 `packages: write`。所有外部 Actions 都固定到提交 SHA，版本号写在行尾注释。首次创建包时会写入源仓库 OCI label，便于关联权限。

## 2. 触发方式与标签

| 触发 | 行为 |
|---|---|
| Pull request（包括 fork PR） | 测试 + 两架构构建/无网络冒烟；不登录 GHCR、不发布 |
| 普通开发分支 push | 同上，只验证 |
| 仓库默认分支 push | 全部检查通过后发布 `latest` 和 `sha-<完整commit>` |
| `v*` 版本标签 push，例如 `v0.1.0` | 全部检查通过后发布相同版本标签及 `sha-<完整commit>`，不覆盖 `latest` |
| Actions 页面手动运行 | 默认只验证；勾选 `publish` 且选择默认分支或 `v*` 标签才发布 |

版本标签必须是合法镜像标签（字母、数字、点、下划线、连字符，长度最多 128）。`latest` 是默认分支最新通过的构建，不代表你已在 NAS 验收。稳定部署建议使用确定版本或 `ghcr.io/...@sha256:<多架构manifest摘要>`；摘要比可变标签更适合回滚。

## 3. 流水线如何避免“构建成功但没验证”

1. **verify**：拒绝已跟踪的私有运行目录/数据库，执行 actionlint、Bash 语法检查和 Maven 单元/契约测试；无真实 API 调用。
2. **build**：两个矩阵任务分别构建 amd64、arm64。基础镜像标签在每次 CI 中先解析到摘要，并以摘要作为 Docker build 参数；记录基础镜像及执行环境。
3. **目标镜像冒烟**：amd64 为原生 CI，arm64 在 amd64 runner 上通过 QEMU 执行。保留旧存储回归，并在独立临时卷中分别以 `0:0`、`10001:10001` 检查多用户数据库/用户/设置持久化及私有权限。容器使用只读根文件系统与 `--network none`，验证真实 Web 启动、合成管理员登录、无模型设置页、内部健康、默认停止时间及移除初始化秘密后的重建登录。不会挂载 NAS 数据或真实配置；这一烟测不覆盖真实账号或模型在途停机验收。
4. **publish-images**：必须等两个 build 任务都成功。下载已测试镜像 tar、校验 SHA-256、加载并推送到唯一运行标签；不会另行从源码重建一个“未经冒烟检查”的镜像。
5. **publish-manifest**：使用两份已推送镜像的精确摘要创建多架构正式标签，校验包含且仅包含 `linux/amd64` 与 `linux/arm64`，输出摘要和验证方式到 Actions Summary。

临时镜像标签形如 `run-<run-id>-<attempt>-<arch>`，可在保留正式版本后按自己的保留策略清理，不在本工作流自动删除 Packages。不会发布 32 位 ARM/x86 镜像。

## 4. 如何核对首轮 CI

在仓库 **Actions → Verify and publish container** 查看：

- `verify` 及两个架构的 `build` 均成功；发布运行还需两个 `publish-images` 和 `publish-manifest` 成功。
- 下载 `unit-test-reports`、`container-evidence-amd64`、`container-evidence-arm64` 与 `published-manifest`。
- 每架构证据包含基础镜像摘要、native/QEMU 标记、镜像配置、非 root UID/GID、`storage-write.txt` / `storage-verify.txt` 中的 `CI_STORAGE_OK`。
- 发布镜像 tar 只保留 2 天，测试/架构证据 14 天，manifest 证据 30 天；需要长期留档请自行下载保存摘要和报告。
- Linux CI 应执行本地 Windows 跳过的原生符号链接测试。若存在失败或跳过，按实际结果记录，不用本地 Windows 通过替代。

PR 构建不会上传供发布的镜像 tar，也不会产生 GHCR 发布 job。某个架构失败时不产生本次正式多架构标签；已有的旧标签不会因此变成新构建。

## 5. NAS 拉取部署

首次 GHCR 包可能是私有的：公开仓库也不要假定镜像自动公开。你可自行将包设为 Public，或在 NAS 使用仅具 `read:packages` 权限的凭据登录 GHCR；不要使用模型 API Key 登录。CI 发布无需你创建该凭据。

把同一已验证提交的 `compose.yml`、`.env.example` 下载到新的 NAS 部署目录：

1. 按 [operations.md](operations.md) 准备独立 `data-multi-user` 本地磁盘目录，完成 UID/GID 授权，不挂载原旧数据。
2. 将 `.env.example` 复制为 `.env`；`compose.yml` 已固定为 `ghcr.io/wochen5770/talkweave:latest`，如需固定版本或摘要直接修改 `image`。这里不存放模型密钥。
3. 在私有 `.env` 中设置初始管理员凭据。无需外部模型配置文件；首次登录后在页面保存模型设置，再移除初始秘密并重建容器。
4. 执行 `docker compose pull`，再执行 `docker compose up -d`。Compose 不在 NAS 构建镜像，默认仅发布回环 8680；Docker 从多架构 manifest 选择匹配镜像。
5. 按 [operations.md](operations.md) 经 SSH/HTTPS/VPN 受保护入口登录。当前生产身份解析失败关闭，真实两账号验证与后续适配仍待完成；不要把扫码页面当作已通过的自动开通能力。

**安全边界：** 活动数据目录必须是 NAS 本地磁盘，不使用 SMB/NFS。Compose 默认 root，可覆盖为目录所有者；镜像自身仍默认非 root。`/tmp` 允许 SQLite 原生库映射，仍限制 nosuid/nodev 和大小。示例不设置 stop_grace_period；8 秒完整收尾目标必须通过实际在途测试确认，不能用空闲启动/停止冒烟替代。

## 6. 由你完成的 NAS 验收清单

请记录镜像标签/manifest 摘要、NAS 架构、日期和结论，不需要提交 API Key、token、配对码、身份 ID 或聊天正文。

- [ ] NAS 正确选择 amd64/arm64，实际 Java/SQLite 启动正常。
- [ ] 独立目录授权、管理员初始化与秘密移除、受保护页面登录完成。
- [ ] 两账号协议依据、独立收发及明确授权的重扫验证通过，再实现生产身份适配。
- [ ] 两轮真实模型对话上下文正确；`/new` 清除后续上下文但保留旧记录。
- [ ] 非文字消息只给能力提示，不调用媒体能力或工具。
- [ ] 重新创建容器保留当前会话/聊天/游标；不会重复模型调用或重复发送。
- [ ] 断网后有退避并恢复；明确 token 失效后重新扫码，换机器人不沿用旧绑定。
- [ ] 等待扫码/模型故障只影响就绪，不导致健康策略不断重启。
- [ ] 停止中的未完成请求有可解释的中断/不确定状态，不自动重放。
- [ ] 停止实例的一致性备份、兼容版本恢复、迁移失败回滚和旧备份未完成轮次核对已演练。

CI 通过后记录当前多用户 change 的 10.4 结果；真实协议验证对应 1.4/1.5/4.4，NAS 验收对应 10.5。未执行项保持未完成，不以旧单用户 change 的任务编号代替当前验收。

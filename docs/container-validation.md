# 双架构容器交付与验证记录

## 已确认的执行方式

镜像由 **GitHub Actions 构建并发布到 GHCR**；NAS 部署、真实出网、运维/备份演练及端到端验收由操作者自行执行。开发机 Docker 是否启动不再是前置条件。详见 [github-actions.md](github-actions.md)。

## 当前结果（2026-09-28）

| 目标 | Docker/Actions 配置 | 实际 GitHub 构建 | 镜像内 Java/SQLite | NAS 实机/出网 |
|---|---|---|---|---|
| linux/amd64 | 已提供并静态检查 | 等待提交后触发 | 待 CI；计划原生 amd64 | 待操作者 |
| linux/arm64 | 已提供并静态检查 | 等待提交后触发 | 待 CI；计划 QEMU/amd64 | 待操作者 |

本轮本地 actionlint 1.7.7、工作流契约测试与 Java 合成持久化测试通过；没有在 GitHub 触发运行，没有构建/上传真实镜像，没有使用真实微信或模型配置。**本地测试不能代替表中的 CI/平台/NAS 实测**。本机 Docker 引擎未运行是已有环境事实，不是后续开发阻塞理由。

## 基础镜像与发布证据

Dockerfile 的构建标签默认 `maven:3.9.8-eclipse-temurin-21`，运行标签默认 `eclipse-temurin:21-jre-jammy`。CI 会在构建前解析两个标签为 SHA-256 摘要并通过 BUILDER_IMAGE/RUNTIME_IMAGE 使用摘要；每个平台分别保存解析记录、最终镜像配置、Java/SQLite 冒烟和容器重建结果。当前尚无实际 CI 解析/构建结果，不预填摘要。

构建阶段在 BUILDPLATFORM 生成 Java JAR并跑测试；每个 TARGETPLATFORM 的运行镜像还要单独运行 SQLite。arm64 使用 QEMU 时明确标注仿真，不能称为 NAS 原生测试。发布任务加载已通过检查的 tar 制品并校验校验和，不从源码另建镜像；正式多架构标签必须等两份镜像成功后才产生。

## Compose 和可选本地构建

NAS 使用 `.env` 中的 `ASSISTANT_IMAGE=ghcr.io/<owner>/<repo>:<tag>` 或 `@sha256:<digest>`，执行 `docker compose pull` / `docker compose up -d`。Compose 不含 build 项，不会在 NAS 下载 Maven 构建源码；缺少镜像变量会明确报错。启动是真实外部连接操作，请先准备私有配置与身份核对。

若未来自行在开发机测试，仍可使用同一 Dockerfile（不是前置要求）：

```sh
docker buildx build --platform linux/amd64 --load -t ci-assistant:amd64 .
bash scripts/ci/image-smoke.sh ci-assistant:amd64 linux/amd64
docker buildx build --platform linux/arm64 --load -t ci-assistant:arm64 .
bash scripts/ci/image-smoke.sh ci-assistant:arm64 linux/arm64
```

冒烟脚本创建唯一临时 Docker 卷，只写合成数据、禁用容器网络、以非 root 运行，并在结束时删除该临时卷。它不挂载当前真实 data/config，也不启动常驻微信循环。

## 构建上下文隐私

`.dockerignore` 是允许列表：仅 pom.xml、src、Dockerfile、Compose、该工作流及 CI 脚本供构建/契约测试使用。不会把真实 config、data、`.env`、联调凭据、IDE 文件或 `.build-cache` 发给构建器。运行层只复制构建出的 JAR，不包含工作流、脚本或测试目录。

GitHub verify job 另检查已跟踪的运行目录、配置和数据库；这不是任意位置的秘密扫描，提交者仍需检查未误提交密钥。真实运行层扫描、CI 两平台结果和 NAS 数据保留验收尚待执行，2.6 / 8.3 / 8.4 不因静态检查提前完成。

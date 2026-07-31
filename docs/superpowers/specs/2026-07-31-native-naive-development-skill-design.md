# v2rayNG 原生 Naive 修改、编译与测试 Skill 设计

日期：2026-07-31

状态：已确认并实施

## 1. 目标

创建本机 Codex Skill `v2rayng-native-naive-dev-runner`，把原生 NaiveProxy 后续修改所需的三仓库协作、精准测试、Jenkins/Docker Android 构建、APK 原生库门禁和真机验证流程固化下来。

该 Skill 面向真实维护任务，不重新实现现有流水线，也不把当前提交号写成不可变的唯一输入。它提供当前项目的安全默认值，同时允许调用者显式覆盖三个仓库的 Git ref。

## 2. 适用范围

默认只处理以下三个 fork：

| 层级 | 默认仓库 | 职责 |
|---|---|---|
| Core | `sola97/Xray-core` | 原生 Naive 协议、Cronet、HTTPS/QUIC、UoT、TLS/ECH 与 Header 行为 |
| Android AAR | `sola97/AndroidLibXrayLite` | 固定 Xray 版本、链接 Android Cronet 平台库、导出 Android 调用接口 |
| Android App | `sola97/v2rayNG` | 手机配置、导入导出、Xray JSON、VPN/HEV TUN、Jenkins/Docker 和 E2E 入口 |

默认只触发 Jenkins Job `v2rayng-naive-android-ci`。Skill 不默认接受任意仓库 URL、任意 Jenkins Job 或任意 Jenkinsfile；如需扩大这些边界，必须由用户明确授权并先修改设计。

允许显式覆盖：

- `XRAY_REF`
- `ANDROID_LIB_REF`
- `V2RAYNG_REF`
- 本地三仓库根目录
- Jenkins URL、凭据文件和产物下载目录

覆盖 Git ref 只改变构建输入，不改变批准的仓库身份和流水线脚本路径。

## 3. Skill 安装位置与结构

Skill 安装到：

```text
C:\Users\H\.codex\skills\v2rayng-native-naive-dev-runner\
├── SKILL.md
├── agents\
│   └── openai.yaml
├── references\
│   ├── environment.md
│   ├── workflow.md
│   └── android-smoke.md
└── scripts\
    └── inspect-native-naive-workspace.ps1
```

不增加 README、更新日志或复制的 Jenkinsfile。详细资料按需放在 `references`，主 `SKILL.md` 只保留触发条件、决策顺序和关键入口。

### 3.1 `SKILL.md`

主流程按以下顺序执行：

1. 确认用户要诊断、修改、构建、测试还是打包。
2. 检查三个工作树的分支、提交、远端、未提交改动和 v2rayNG HEV 子模块状态。
3. 根据改动归属选择 Core、AAR、App 或跨三层路径。
4. 修改前读取对应代码和仓库内现有测试，不做无关重构。
5. 运行与风险匹配的精准测试。
6. 完成功能后，在受影响的每个仓库分别提交并推送；依赖固定顺序必须是 Core、AndroidLib、v2rayNG。
7. 需要 Android 完整产物时，调用 v2rayNG 仓库中的专用 Jenkins runner。
8. 检查 Jenkins JUnit、commit manifest、AAR、APK SHA-256 和逐 ABI 原生库门禁。
9. 有已授权 ADB 设备时执行真机烟测；没有设备时明确列出未验证边界，不把编译通过写成真机通过。

### 3.2 `references/environment.md`

记录可复用但不应塞进主流程的环境信息：

- 三个本地仓库的默认目录和远端身份。
- Jenkins URL、专用 Job、凭据文件格式和默认路径。
- NUC Docker 节点与空间门槛。
- Docker 内固定 JDK、Go、Android SDK、Build Tools、NDK、gomobile 版本。
- 本地 `E:\CodexBuildCache\native-naive` 缓存和产物目录约定。
- 凭据、GitHub token、Jenkins token 不进入 Skill、日志、提交或命令输出的约束。
- 只清理本 Job 工作区和可确认的重复缓存，禁止默认执行全局 Docker prune。

固定工具链仍由仓库中的 `ci/jenkins/Dockerfile.android` 和 `Jenkinsfile.naive` 定义；参考文档只说明如何查证，避免出现两套权威配置。

### 3.3 `references/workflow.md`

包含改动归属和验证矩阵：

| 改动类型 | 修改仓库 | 最低验证 | 完整验证触发条件 |
|---|---|---|---|
| Naive 协议、Cronet、UoT、TLS/ECH | Xray-core | 对应 Go package 精准测试 | 接口或运行行为变化时构建 AAR、App 并跑 E2E/Jenkins |
| Xray 版本、Cronet 平台链接、gomobile API | AndroidLibXrayLite | `go.mod` 一致性和 AAR 构建 | ABI、导出 API 或 native 链接变化时跑完整 Jenkins |
| 配置页、导入导出、JSON 生成 | v2rayNG | 对应 Kotlin 单测和编译 | 用户可见主路径变化时跑 F-Droid APK 构建 |
| HEV TUN、JNI、ABI、Gradle 打包 | v2rayNG | 原生文件和 Gradle 精准检查 | 必须跑完整 Jenkins 五 APK 门禁，并优先做真机启动烟测 |
| 跨层字段或默认值 | 三仓库按依赖方向 | 各层精准测试 | 必须用固定 commit manifest 完成完整 Jenkins 构建 |

文档还会说明：

- 如何先提交并推送 Core，再更新 AndroidLib 的 Xray 固定版本，最后更新 v2rayNG 构建输入。
- 如何避免用分支漂移掩盖依赖不一致，完整构建优先传 commit SHA。
- 如何调用 `ci/jenkins/invoke-v2rayng-naive-ci.ps1`、恢复监控、读取失败阶段和下载产物。
- 如何调用 `ci/e2e/invoke-naive-e2e.ps1` 验证 TCP 与默认 UoT v2。
- 如何区分精准验证、完整 Jenkins 验证和 Android 真机验证。
- 如何处理 Gradle 网络抖动、磁盘不足、HEV 子模块缺失、AAR 版本不一致与 APK 缺库等已知失败类型。

### 3.4 `references/android-smoke.md`

真机验证覆盖主要用户路径：

1. 安装与启动，首先确认默认 HEV TUN 不再触发 `UnsatisfiedLinkError`。
2. 新建、保存、重开和编辑 Naive 节点。
3. 验证 HTTPS 默认、QUIC 可选、UoT 默认开启且默认 v2，并可在手机中切换 v1/v2/关闭。
4. 验证 URI、剪贴板、JSON 和二维码导入导出。
5. 验证 VPN 模式的 TCP、UDP/DNS、断开与重连。
6. 验证 Wi-Fi/蜂窝切换后的 Cronet 重建。
7. 收集过滤后的 logcat，检查崩溃、native linker、HEV、Cronet 和敏感信息边界。

Skill 不会在没有设备或没有用户授权时假装完成真机测试。

### 3.5 `scripts/inspect-native-naive-workspace.ps1`

提供只读环境检查，输出结构化 JSON，至少包含：

- 三个仓库目录是否存在。
- 当前分支和 HEAD。
- `origin` 与 `upstream` URL。
- 工作树是否干净以及变更文件数量。
- v2rayNG 的 HEV gitlink、实际子模块提交和递归子模块状态。
- v2rayNG 是否包含 Jenkins runner、Jenkinsfile、Dockerfile、APK 验证脚本和 E2E runner。
- AndroidLib `go.mod` 固定的 Xray 伪版本是否与本地 Xray HEAD 对应。

脚本发现不一致时以非零退出码失败并给出具体字段；不会自动切分支、清理工作树、修改依赖、触发 Jenkins 或读取明文凭据。

## 4. Jenkins 与测试调用方式

Skill 复用仓库权威入口：

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 `
  -XrayRef <commit-or-ref> `
  -AndroidLibRef <commit-or-ref> `
  -V2rayNGRef <commit-or-ref>
```

中断后仅恢复监控：

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 -ResumeLastBuild
```

独立互通验证：

```powershell
& .\ci\e2e\invoke-naive-e2e.ps1 `
  -WorkRoot E:\CodexBuildCache\native-naive
```

Skill 不复制这些脚本的认证、轮询、Docker 构建和门禁实现。若仓库入口发生变化，应先更新仓库流水线及其测试，再同步 Skill 的参考说明。

## 5. 安全和状态约束

- 操作前检查 dirty worktree；不覆盖、不重置、不暂存与当前功能无关的用户改动。
- 不在 Skill 中保存 GitHub PAT、Jenkins token、SSH 私钥内容或解密后的凭据。
- 不把 token 放在命令行、控制台摘要、构建参数或提交记录中。
- 创建或更新 Jenkins Job 只在用户明确要求时传 `-EnsureJob` 或 `-UpdateJob`。
- 不修改 UAMS、Old DC 或其他 Jenkins Job。
- 不默认操作 NUC 上的其他容器、镜像、卷和工作区。
- APK/AAR 构建成功不等于协议活体、真机 VPN 或网络切换已经通过；交付说明必须列出实际验证证据和剩余风险。

## 6. 验证与验收

Skill 实现完成后执行以下验证：

1. 使用 `skill-creator` 的 `quick_validate.py` 校验目录、frontmatter 和元数据。
2. 在当前三仓库上运行只读 workspace inspector，核对其 JSON 与实际 Git 状态一致。
3. 用临时无效目录验证 inspector 能明确失败，且不修改任何仓库。
4. 检查 `agents/openai.yaml` 能让 Skill 以清楚的名称和描述被发现。
5. 手工走读 SKILL 中的分层决策，确认 Core-only、App-only、HEV 打包和跨三层修改都有明确路径。

本次创建 Skill 不重复触发一次耗时约十二分钟的 Jenkins Android 构建；现有 #21 已证明仓库流水线可运行。未来 Skill 首次用于实际代码修改时，应根据改动风险调用完整 Jenkins 构建，而不是依赖旧产物。

## 7. 提交策略

设计文档作为 v2rayNG 仓库的一次独立提交并推送。Skill 本体位于本机 Codex Skill 目录，不属于当前 Git 仓库；实现完成后再把可复用的 Skill 源文件镜像到 v2rayNG 仓库的 `docs/codex-skills/v2rayng-native-naive-dev-runner/`，作为可审阅、可提交和可恢复的版本，然后提交并推送。

本机安装目录仍是实际运行入口；仓库镜像是版本化来源。两处内容在交付前做文件哈希比对，避免本机版本与提交版本不一致。

# v2rayNG 原生 NaiveProxy 实施与验证记录

日期：2026-07-31

分支：`feature/native-naiveproxy`

结论：原生客户端主路径已经实现，不依赖 `naiveplugin`；Jenkins Android 构建、Xray 精准测试、v2rayNG 单元测试以及 sing-box TCP/UoT v2 真实互通均已通过。Jenkins #17 暴露的 HEV TUN 缺库问题已经在 #21 修复，五个 APK 均通过逐 ABI 原生库门禁。新 APK 尚未完成 Android 真机复测，HTTP/3/QUIC 和 ECH 活体互通仍按本文件的未验证边界处理。

## 1. 交付范围

本实现把 NaiveProxy 做成 Xray-core 的一等出站协议，并沿用 v2rayNG 原有的节点、路由、VPN、DNS 和链式代理流程：

```text
手机 Naive 配置页
  -> ProfileItem / 导入导出
  -> CoreOutboundBuilder
  -> Xray protocol: "naive"
  -> AAR 内的 Xray + Cronet
  -> Naive HTTPS 或 QUIC 服务端
  -> TCP / UoT UDP 目标
```

明确没有采用以下方案：

- 不安装或调用 `naiveplugin`。
- 不在 APK 内再嵌一套 sing-box 客户端核心。
- 不启动本地 SOCKS/HTTP 中转进程把流量转交给插件。
- 不让 UDP 在 Naive 服务端不支持时静默直连。

Cronet Android 平台库在构建 AAR 时静态进入各 ABI 的 `libgojni.so`，运行时不下载额外插件或原生库。

## 2. 源码仓库与固定提交

三个公开 fork 都使用 `feature/native-naiveproxy` 分支：

| 仓库 | 已验证提交 | 主要内容 |
|---|---|---|
| [sola97/Xray-core](https://github.com/sola97/Xray-core/tree/feature/native-naiveproxy) | [`3ac438417f44`](https://github.com/sola97/Xray-core/commit/3ac438417f44ad853477a3f317f27ae18620f6b0) | 原生 `protocol: "naive"`、Cronet 生命周期、TCP、UoT v1/v2、HTTPS/QUIC、CA、ECH、Header、DNS 和保留 Header 校验 |
| [sola97/AndroidLibXrayLite](https://github.com/sola97/AndroidLibXrayLite/tree/feature/native-naiveproxy) | [`95569b7be65c`](https://github.com/sola97/AndroidLibXrayLite/commit/95569b7be65c1b3bf706041994cb9ace9699cee8) | 固定上述 Xray 提交、只链接四个 Android Cronet 平台模块、导出 `notifyNetworkChanged()` |
| [sola97/v2rayNG](https://github.com/sola97/v2rayNG/tree/feature/native-naiveproxy) | [`2d834c5c254e`](https://github.com/sola97/v2rayNG/commit/2d834c5c254e46f5f41203218bdaa17f10753473) | 手机配置、导入导出、Xray JSON、网络切换回调、HEV TUN 完整打包、Jenkins/Docker 和可重复 sing-box E2E runner |

Xray 的两个功能提交为：

- `d4997a5 feat: add native naive outbound`
- `3ac4384 fix: reserve transport-managed Naive headers`

AndroidLibXrayLite 的功能链为：

- `26bf762 feat: link native naive cronet runtime`
- `0390278 build: link only Android cronet libraries`
- `8da7ae6 fix: retry transient asset downloads`
- `95569b7 build: update native Naive Xray revision`

v2rayNG 的主要功能提交为：

- `44d01be feat: add native naive profile and outbound`
- `3c8eaea feat: import native naive JSON configs`
- `5aa6f47 feat: add native naive phone editor`
- `2aa5f9a feat: refresh native naive on network changes`
- `d7ddbcb ci: build and verify native naive APK`
- `ab9e42b fix: align Naive header validation with core`
- `4bff243 test: add native Naive interoperability runner`
- `2a035c0 fix: package HEV TUN native libraries`
- `2d834c5 fix: pass pinned NDK to HEV build`

其余同分支提交是为了修复真实 Jenkins 构建过程中暴露的 SDK/Gradle 网络重试、磁盘门槛、Compose 编译、APK 导出和归档边界问题。

## 3. 手机端配置方式

在主界面的添加菜单选择“添加 [NaiveProxy]”，进入独立的 NaiveProxy 配置页。配置会随节点保存，重新打开时由 `ServerUiState` 恢复。

### 3.1 基础字段

- 别名。
- 服务器地址和端口，支持域名、IPv4 和带方括号语义的 IPv6。
- 用户名和密码；密码输入框使用密码遮罩。
- SNI；留空时使用服务器地址。

### 3.2 传输

- `HTTPS（HTTP/2）`：默认选项。
- `QUIC（HTTP/3）`：可选择 `bbr`、`bbr2`、`cubic`、`reno` 或核心默认拥塞控制。
- HTTPS 可以配置并发隧道数，必须大于等于 1。
- QUIC 的并发隧道数固定为 1，界面会自动收敛到该值。

### 3.3 UDP over TCP

- 默认开启。
- 默认版本为 v2。
- 手机端可以切换 v2/v1，以兼容旧服务端。
- 可以关闭；关闭后界面明确提示 UDP 无法转发，不会把 UDP 自动放行到直连。

UoT 需要服务端支持。已验证的 sing-box Naive 入站支持该路径；标准 Caddy/forwardproxy Naive 服务通常只代理 TCP，不能把“TCP 可用”理解成“UDP 也可用”。

### 3.4 TLS、CA 与 ECH

- 自定义根 CA 使用 PEM 内容，不使用文件路径。
- ECH 可填写固定 `ECH CONFIGS` PEM。
- 未提供固定 ECH Config 时，可以填写查询服务器名和显式 DNS over HTTPS 地址。
- 没有提供“跳过证书验证”，因为当前底层实现不支持可接受的等价安全语义。

### 3.5 Extra Headers

页面支持逐项添加、删除和修改 Header。Header 名称按大小写不敏感去重，并拒绝空名称、CR/LF 注入及传输层管理的保留字段。

当前保留字段包括 Naive/Cronet 内部 Header 以及标准 hop-by-hop/消息分帧 Header，例如：

- `connection`
- `content-length`
- `host`
- `keep-alive`
- `proxy-authenticate`
- `proxy-authorization`
- `proxy-connection`
- `te`
- `trailer`
- `transfer-encoding`
- `upgrade`

这样可以避免手机配置覆盖 Cronet 必须自行维护的连接、认证和消息边界字段。

## 4. 导入、导出与分享

### 4.1 URI

支持：

- `naive+https://...`
- `naive+quic://...`

主要查询参数为：

- `sni`
- `insecure-concurrency`
- `extra-headers`，值为 URL 编码后的 JSON 对象
- `uot=2`，开启 UoT v2
- `uot=1`，开启 UoT v1
- `uot=0`，关闭 UoT
- `quic-congestion-control`
- `trusted-root-cert`
- `ech=1`
- `ech-config`
- `ech-query-server-name`
- `ech-dns-server`

URI 未写 `uot` 时按 v2 导入；导出时会明确写出 `uot`，避免接收方依赖不一致的隐式默认值。

### 4.2 JSON

支持从以下 JSON 结构导入 Naive 出站：

- Xray：`protocol: "naive"`
- sing-box：`type: "naive"`
- 顶层单个出站、出站数组或包含 `outbounds` 数组的配置

对于 sing-box JSON，`certificate_path` 和 ECH `config_path` 不会被当作 Android 本地文件直接接受；需要粘贴证书/ECH 内容，防止导入一个手机上不存在的服务端文件路径。

## 5. 核心运行行为

v2rayNG 为 Naive 节点生成 Xray `protocol: "naive"` 出站，Xray Mux 保持关闭，由 Naive/Cronet 自己管理连接与并发。

Xray-core 负责：

- 一个出站实例对应一个 Cronet Engine 生命周期。
- HTTPS 使用 HTTP/2 CONNECT；QUIC 使用 HTTP/3。
- TCP 使用 Naive 的 early connection 路径。
- UDP 使用 `sing/common/uot` v1/v2；配置版本为 0 或缺省时规范化为 v2。
- 域名解析和 ECH 查询经过 Xray dialer/DNS 边界，不另开一个不受路由控制的 Android 网络客户端。
- 网络切换时，Android 回调调用 `CoreController.notifyNetworkChanged()`，最终对活动 Naive Engine 执行 `CloseAllConnections()`，让后续请求在新网络上重建连接。

网络切换绑定已经在 AAR API 中验证；Wi-Fi/蜂窝网络真实切换后的恢复行为仍需要 Android 真机测试。

## 6. Jenkins 与 Docker 构建环境

专用 Job：[`v2rayng-naive-android-ci`](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/)

最终构建：[#21 SUCCESS](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/)

耗时：743445 ms，约 12 分 23 秒

Job 只从批准的三个公开 fork 检出受限 ref，不接受任意仓库 URL 或任意脚本。它没有修改 `uams-nuc-ci`、Old DC Job、现有容器或现有服务。

固定工具链包括：

- `eclipse-temurin:17-jdk-jammy` 固定镜像摘要
- Go 1.26.0 固定下载 SHA-256
- Android command-line tools `14742923` 固定 SHA-1
- Android platform 37.0
- Android build-tools 37.0.0
- Android NDK 29.0.14206865
- gomobile `v0.0.0-20260709172247-6129f5bee9d5`
- Gradle 单 worker、Kotlin in-process、JVM 1.5 GiB

构建前要求至少 8 GiB 可用空间，Gradle 每次尝试前要求至少 4 GiB。Go module、Go build 和 Gradle 缓存都使用本 Job 的专用 BuildKit cache ID。为使 #21 能越过空间门槛，只删除了本 Job 的旧工作区和两份已确认重复的 Gradle cache record；没有执行全局 `docker system prune`，也没有触碰其他 Job、镜像、容器或卷。

### 6.1 #17 缺陷与修复边界

#17 虽然构建成功，但其所有 APK 都没有 `libhev-socks5-tunnel.so` 和
`libhevsockstun.so`。默认启用 HEV TUN 的 VPN 模式会在
`TProxyService` 初始化时抛出 `UnsatisfiedLinkError`，因此 #17 APK 已判定为无效，
不能继续作为交付包使用。

修复保持运行时默认值不变，恢复上游构建链：

- 按 v2rayNG gitlink 递归检出 `hev-socks5-tunnel` 及其嵌套子模块。
- 使用固定 NDK 29 执行 `compile-hevtun.sh`，构建四个 ABI 的 JNI 库和 Root 模式程序。
- 在 Gradle 前把 HEV 产物复制到 `V2rayNG/app/libs`。
- 对每个 APK 的实际 ABI 硬校验 `libgojni.so`、`libhev-socks5-tunnel.so` 和
  `libhevsockstun.so`；缺任一文件就使流水线失败。

#18 首次执行修复时发现 Docker 中变量名是 `ANDROID_NDK_HOME`，上游脚本读取
`NDK_HOME`；提交 `2d834c5` 显式传入固定 NDK 路径。#19 和 #20 随后因 Google Maven
TLS 握手中断失败；同一基础镜像内 Java 17 连续读取 Google Maven 10 次成功后，#21
利用专用 Gradle 缓存完成了构建。

### 6.2 #21 实际固定提交

```json
{
  "xray": "3ac438417f44ad853477a3f317f27ae18620f6b0",
  "androidLib": "95569b7be65c1b3bf706041994cb9ace9699cee8",
  "v2rayNG": "2d834c5c254e46f5f41203218bdaa17f10753473",
  "hevTun": "ad7600497931205105b08367bd1b450048157e40"
}
```

### 6.3 #21 测试结果

Docker 构建中通过：

- `go test -tags with_purego ./proxy/naive`
- `go test -tags with_purego ./infra/conf -run '^TestNaive'`
- `go test -tags with_purego ./common/singbridge ./proxy/shadowsocks_2022`
- `testFdroidDebugUnitTest`
- `assembleFdroidDebug`

Jenkins 归档的 6 个 JUnit suite 合计 33 个测试，失败 0、错误 0、跳过 0：

| Suite | 测试数 |
|---|---:|
| `CoreOutboundBuilderNaiveTest` | 2 |
| `NaiveFmtTest` | 10 |
| `ServerUiStateNaiveTest` | 2 |
| `ShadowsocksFmtTest` | 15 |
| `HttpUtilTest` | 1 |
| `UtilsTest` | 3 |

归档清单经过检查，只包含构建产物和验证证据，没有再次归档 `.dockerenv`、`/etc` 或其他容器根文件系统内容。五个 F-Droid APK 都通过 ZIP 完整性检查和逐 ABI 三类原生文件门禁。

## 7. Android 产物

以下是 F-Droid debug 产物；下载通常需要 Jenkins 登录。

| 产物 | 大小 | SHA-256 |
|---|---:|---|
| [arm64-v8a APK](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/apk/v2rayNG_2.2.6-fdroid_arm64-v8a.apk) | 43M | `5d5792f033d36180ee29b1d7b24457e53ecb476f029b9065a97e7e584e759904` |
| [armeabi-v7a APK](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/apk/v2rayNG_2.2.6-fdroid_armeabi-v7a.apk) | 43M | `94bf8db0e65ba09cd3e5b5e7549f9a81486de440adf04e86a8278da04b254f56` |
| [Universal APK](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/apk/v2rayNG_2.2.6-fdroid_universal.apk) | 100M | `9e4c004d707c313e3a4156c2ec1e3770f36142fdc052778b6e98e03c73942029` |
| [x86 APK](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/apk/v2rayNG_2.2.6-fdroid_x86.apk) | 45M | `e8b44b52697e4892c9cd373061cdf1e7643c8cd5c4875cffb8586e068294346e` |
| [x86_64 APK](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/apk/v2rayNG_2.2.6-fdroid_x86_64.apk) | 45M | `fc0f80540bec3975521912e605fbd7b242ebfee3d2d3c72c31fc13156a3a3cd6` |
| [libv2ray.aar](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/libv2ray.aar) | 75M | `7bc3359673b79afd3484eff93b3b6874a079fa22e36516c91abd2c22960451d6` |

AAR 中四个 `libgojni.so` 的未压缩大小为：

- `armeabi-v7a`：40,885,372 bytes
- `arm64-v8a`：46,064,640 bytes
- `x86`：45,533,400 bytes
- `x86_64`：48,668,864 bytes

Universal APK 已检查同时包含以上四个 ABI。每个 ABI 还包含以下 HEV 文件：

| ABI | `libhev-socks5-tunnel.so` | `libhevsockstun.so` |
|---|---:|---:|
| `armeabi-v7a` | 235,652 bytes | 163,504 bytes |
| `arm64-v8a` | 342,696 bytes | 240,464 bytes |
| `x86` | 327,168 bytes | 235,364 bytes |
| `x86_64` | 341,536 bytes | 238,944 bytes |

独立下载的 arm64、armeabi-v7a 和 Universal APK 均再次运行
`ci/jenkins/verify-native-apk.sh`，结果通过；本地 SHA-256 与 Jenkins 清单一致。AAR 的
`CoreController` 已通过 `javap` 确认包含：

```java
public native void notifyNetworkChanged();
```

其他证据：

- [commit manifest](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/commit-manifest.json)
- [APK SHA-256](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/apk.sha256)
- [AAR SHA-256](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/artifact/artifacts/libv2ray.aar.sha256)
- [JUnit 报告](https://jenkins-nuc.sora.vip/job/v2rayng-naive-android-ci/21/testReport/)

## 8. sing-box 真实互通验证

仓库中的 [`ci/e2e/invoke-naive-e2e.ps1`](../ci/e2e/invoke-naive-e2e.ps1) 会：

1. 固定或验证 Xray 与 sing-box 源码提交。
2. 构建 Windows Xray，并把固定 Windows Cronet DLL 放到同一目录。
3. 构建固定提交的 sing-box。
4. 构建并单测 E2E 程序。
5. 生成临时 CA 和服务端证书。
6. 启动 sing-box Naive HTTPS 入站、TCP echo 和 UDP echo。
7. 启动带两个 `dokodemo-door` 入站的 Xray。
8. 分别完成 TCP 和 UoT UDP 往返。
9. 输出 JSON 结果和包含二进制 SHA-256 的 manifest。

执行示例：

```powershell
.\ci\e2e\invoke-naive-e2e.ps1 `
  -WorkRoot E:\CodexBuildCache\native-naive
```

本次结果：

```json
{
  "passed": true,
  "tcp": "passed",
  "udpOverTcp": "passed",
  "udpOverTcpVersion": 2,
  "udpVersionSource": "defaulted by Xray because udpOverTcp.version was omitted",
  "xrayRevision": "3ac438417f44ad853477a3f317f27ae18620f6b0",
  "singBoxRevision": "4f7f89463ccfa506f90c46c715cf9798159d2c44"
}
```

E2E 二进制证据：

| 文件 | SHA-256 |
|---|---|
| `xray.exe` | `37C0605D20AF34D2952BB6809D1DD3F391FD7175F09DDA9BAFDE9CB1D57A8D42` |
| `sing-box.exe` | `BC0927BF302F2CACE9F02B67E4DA4F59CAC0BFE2390E25051565F7B70BF8918E` |
| `libcronet.dll` | `C7434CFA93C3041321DD19111C4DE6C52B8A9531A65661BA45425D3C51EC69E2` |

Xray E2E 配置故意只包含：

```json
"udpOverTcp": {
  "enabled": true
}
```

没有写 `version`。E2E runner 的配置单测确认该字段保持缺省，Xray-core 的配置测试确认缺省/0 规范化为 v2，最终 UDP echo 通过固定 sing-box 服务端完成真实往返。

## 9. 已验证边界

| 能力 | 状态 | 证据 |
|---|---|---|
| 无插件原生出站 | 已验证 | Xray `protocol: "naive"`、AAR/APK 构建、E2E 直接运行 Xray |
| 手机 Naive 配置模型和页面 | 已验证到编译/单测 | Compose 编译、`ServerUiStateNaiveTest`、Jenkins APK |
| URI 与 Xray/sing-box JSON 导入 | 已验证 | `NaiveFmtTest` 10 项 |
| 默认 UoT v2 | 已验证 | 核心配置测试、手机默认值测试、sing-box UDP echo |
| UoT v1 代码路径 | 已验证到单元/编译 | 核心 UoT 配置与实现测试；未做独立服务端活体兼容 |
| HTTPS/HTTP2 TCP | 已验证 | sing-box TCP echo |
| QUIC/HTTP3 | 已验证到代码、配置与构建 | 未做独立服务端活体互通 |
| 自定义 CA | 已验证 | E2E 临时 CA 完成 TLS 连接 |
| ECH | 已验证到配置、校验和构建 | 未做 ECH 服务端正反例 |
| Extra Headers | 已验证到校验和配置生成 | 未做要求特定 Header 的前置代理活体测试 |
| 网络切换通知 | 已验证到 AAR API 和调用路径 | 未做 Android Wi-Fi/蜂窝真实切换 |
| 四 ABI AAR/APK | 已验证 | Jenkins #21 AAR/APK 内容检查、逐 ABI Go/HEV 文件门禁和独立下载复核 |
| 默认 HEV TUN 原生库 | 已验证到打包 | #21 五个 APK 均包含 `libhev-socks5-tunnel.so` 和 `libhevsockstun.so`；尚待真机启动复测 |

## 10. 尚需 Android 真机验证

用户提供的真机日志已经确认 #17 在默认 HEV TUN 路径因缺少
`libhev-socks5-tunnel.so` 崩溃；该 APK 已废弃。当前没有授权的 ADB 设备安装 #21，
因此以下事项仍不能写成已完成：

- 在手机上新建、保存、重开和编辑 Naive 节点。
- 扫码导入、剪贴板导入和分享二维码的完整交互。
- VPN 模式下通过真实 Naive 服务端浏览 TCP 流量。
- VPN 模式下的 UDP DNS/echo。
- Wi-Fi 与移动网络切换后的 Cronet 连接恢复。
- 长时间运行、Doze、后台限制和进程重启后的稳定性。
- Android 日志中敏感字段的运行期检查。

有设备后建议使用 `arm64-v8a` debug APK，按以上顺序执行；测试结果应单独记录设备型号、Android 版本、服务端类型、传输方式、UoT 版本和网络切换结果。

## 11. 仍需补充的协议级集成验证

以下不阻塞当前“原生 Naive + 默认 UoT v2”主路径交付，但在面向更广泛用户发布前应补齐：

- sing-box HTTP/3/QUIC Naive 入站的 TCP 活体往返。
- ECH 固定 Config 成功、错误 Config 明确失败。
- UoT v1 与旧服务端的真实兼容测试。
- UoT v2 DNS UDP 查询。
- 需要指定 Extra Header 才允许 CONNECT 的服务端验证。
- 标准 Caddy/forwardproxy 下 TCP 成功、UDP 失败且抓包确认没有直连的负例。
- Play Store debug/release 变体构建和相应发布合规检查。

Jenkins 的 `RUN_E2E` 与 `RUN_ANDROID_SMOKE` 当前仍 fail closed；只有对应 Stage 真正实现后才能开启，避免参数显示为 true 但实际没有执行。

## 12. 发布与许可证注意事项

当前实现涉及 v2rayNG、AndroidLibXrayLite、Xray-core、cronet-go 平台库和各自传递依赖。发布 APK/AAR 前应：

- 保留对应许可证和版权声明。
- 提供三个公开 fork、分支和固定提交链接。
- 归档最终构建使用的 Go module 与 Android 依赖清单。
- 区分 debug 测试产物和正式签名 release 产物。
- 对最终第三方 notices 做一次独立合规审阅；本记录不是法律意见。

## 13. 复现入口

Android Jenkins 构建：

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1
```

中断后只恢复监控，不重复触发：

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 -ResumeLastBuild
```

独立 sing-box 互通：

```powershell
& .\ci\e2e\invoke-naive-e2e.ps1 `
  -WorkRoot E:\CodexBuildCache\native-naive
```

详细设计依据见 [v2rayNG 原生 NaiveProxy 设计](superpowers/specs/2026-07-30-native-naiveproxy-design.md)。

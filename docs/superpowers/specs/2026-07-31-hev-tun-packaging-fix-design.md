# v2rayNG Jenkins HEV TUN 完整打包修复设计

日期：2026-07-31

状态：已批准，待实施

目标分支：`feature/native-naiveproxy`

## 1. 问题与根因

Jenkins #17 生成的所有 APK 都缺少：

- `lib/<abi>/libhev-socks5-tunnel.so`
- `lib/<abi>/libhevsockstun.so`

VPN 模式默认启用 HEV TUN，`TProxyService` 初始化时调用
`System.loadLibrary("hev-socks5-tunnel")`，因此 APK 在启动 VPN 时抛出
`UnsatisfiedLinkError`，尚未进入任何 Naive 出站连接。

根因是专用 Jenkins/Docker 流水线只检出了 v2rayNG 主仓库，没有初始化
`hev-socks5-tunnel` 子模块，也没有执行上游 `compile-hevtun.sh`。现有 APK
门禁只检查 `libgojni.so`，所以不完整 APK 仍被归档为成功产物。

## 2. 已比较方案

### 方案 A：恢复上游 HEV 构建链并加强门禁（采用）

固定检出仓库记录的 HEV 子模块提交，在已经固定 NDK 29 的 Docker builder 中执行
`compile-hevtun.sh`，将四 ABI 结果复制到 `V2rayNG/app/libs`，并对每个 APK
硬校验 HEV JNI 库、Root 模式可执行文件和 `libgojni.so`。

优点是与当前 v2rayNG 默认行为和上游构建方式一致，修改集中在 CI/打包边界，
不会改变运行时 TUN 架构。

### 方案 B：默认关闭或删除 HEV

技术上可以改用 Xray-core TUN，但会改变默认设置、旧用户配置和 Root 模式，且需要
额外真机回归。它不是本次漏打包问题的窄修复，因此不采用。

### 方案 C：下载或提交预编译 HEV 二进制

可以缩短构建时间，但会引入额外二进制来源、版本同步和可追溯性问题。仓库已经提供
固定子模块和构建脚本，因此不采用。

## 3. 构建设计

### 3.1 Jenkins 检出

在 v2rayNG 主仓库切到指定 ref 后执行：

```bash
git -C "$BUILD_ROOT/v2rayng" submodule update \
  --init --recursive --depth=1 hev-socks5-tunnel
```

流水线记录并归档实际 HEV 子模块提交，确保 APK 可追溯。只初始化本次需要的
`hev-socks5-tunnel`，不使用仓库中的 `AndroidLibXrayLite` 子模块，因为 AAR 仍由
批准的独立 AndroidLib fork/ref 构建。

### 3.2 Docker 上下文

`.dockerignore.android` 显式允许：

- `v2rayng/compile-hevtun.sh`
- `v2rayng/hev-socks5-tunnel/**`

继续忽略 v2rayNG 原有 `app/libs`，防止本机旧 AAR 或旧 native 文件污染可复现构建。

### 3.3 NDK 构建与复制

Docker builder 已固定 `ANDROID_NDK_HOME=/opt/android-sdk/ndk/29.0.14206865`。
复制 v2rayNG 源码后，在 Gradle 之前执行仓库脚本：

```bash
bash compile-hevtun.sh
cp -R libs/. V2rayNG/app/libs/
```

脚本一次生成四个 ABI：

- `armeabi-v7a`
- `arm64-v8a`
- `x86`
- `x86_64`

每个 ABI 必须同时生成：

- `libhev-socks5-tunnel.so`：VpnService/`TProxyService` JNI 库
- `libhevsockstun.so`：Root 模式独立程序，使用 `.so` 文件名进入 nativeLibraryDir

随后再把本次生成的 `libv2ray.aar` 放入同一个 `V2rayNG/app/libs`，继续执行现有单测和
APK 构建。

## 4. 失败语义与门禁

不在 Kotlin 中捕获并吞掉 `UnsatisfiedLinkError`。构建缺库必须在 Jenkins 阶段失败，
不能推迟到手机运行时。

新增两层门禁：

1. Docker 中 `compile-hevtun.sh` 完成后，逐 ABI 检查两个输出文件非空。
2. Jenkins 解包每个 APK，根据 APK 实际包含的 ABI 检查：
   - `lib/<abi>/libgojni.so`
   - `lib/<abi>/libhev-socks5-tunnel.so`
   - `lib/<abi>/libhevsockstun.so`

Universal APK 必须包含四个 ABI 的三类文件。任一文件缺失，构建立即失败且不作为可用
APK 交付。

## 5. 验证计划

实施前的确定性失败信号是对 Jenkins #17 `armeabi-v7a` APK 解包检查；结果为 HEV
文件数量 0。

修复后必须完成：

- PowerShell/Jenkinsfile/Dockerfile 静态语法与 `git diff --check`。
- Jenkins Docker 内的 Xray 精准测试。
- v2rayNG 33 项 JVM 单元测试或更多，不允许回退。
- 四 ABI AAR 和五个 F-Droid APK 构建。
- 每个 ABI APK 的三类 native 文件门禁。
- Universal APK 四 ABI 门禁。
- 下载新 `armeabi-v7a` APK，用与 #17 相同的解包命令看到
  `libhev-socks5-tunnel.so` 和 `libhevsockstun.so`。
- 保留 APK/AAR SHA-256、提交清单、HEV 子模块提交和 JUnit 报告。

没有授权 ADB 设备时，不能声称手机 VPN 已运行通过；但新 APK 必须先消除本次日志中
可确定复现的缺库条件。用户可在设备上用默认 HEV TUN 再做启动验证。

## 6. 修改范围

计划只修改：

- `ci/jenkins/Jenkinsfile.naive`
- `ci/jenkins/Dockerfile.android`
- `ci/jenkins/.dockerignore.android`
- Jenkins/实施文档

不修改 Naive 协议实现、手机配置模型、TUN 默认值、`TProxyService` 或其他协议代码。

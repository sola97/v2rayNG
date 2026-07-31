# Android Device Smoke Test

Read this file before installing APKs, using ADB, changing VPN state, or interpreting Android logs.

## Authorization and Preconditions

Device work mutates an external device. Confirm the user has authorized the connected device and the requested install/test actions. Do not clear logs, uninstall an app, overwrite app data, or change network/VPN state without that authorization.

Record:

- device manufacturer/model;
- Android version and API level;
- ABI;
- APK filename, Jenkins build number, SHA-256, and resolved commit manifest;
- server implementation and version;
- transport (HTTPS or QUIC);
- UoT setting (v2, v1, or off);
- network type and switching sequence.

Start read-only:

```powershell
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
```

Use the F-Droid package ID `com.v2ray.ang.fdroid`; confirm it from the built APK before relying on it.

## APK Integrity Before Install

Verify the local SHA-256 against Jenkins evidence:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '<apk-path>'
```

Run the repository APK gate from a Bash-capable environment when possible:

```bash
./ci/jenkins/verify-native-apk.sh '<apk-path>'
```

For the device ABI, the APK must include:

- `libgojni.so`
- `libhev-socks5-tunnel.so`
- `libhevsockstun.so`

Do not install #17 artifacts; they are known to lack HEV libraries.

## Install and Launch

Installing with `-r` retains compatible app data and overwrites the installed package. State that effect before running it.

```powershell
adb install -r '<apk-path>'
adb shell monkey -p com.v2ray.ang.fdroid -c android.intent.category.LAUNCHER 1
```

Do not clear application data unless a clean-state test is explicitly required and the user approves losing that app data.

## HEV TUN Startup Gate

The first runtime gate is that the default VPN/HEV path starts without native-link failure. Capture logs around launch and VPN start. Clearing logcat discards device logs, so do it only when approved:

```powershell
adb logcat -c
# Launch the app and start the VPN path on the device.
adb logcat -d -v threadtime | Select-String -Pattern 'AndroidRuntime|UnsatisfiedLinkError|dlopen|hev-socks5-tunnel|hevsockstun|TProxyService|v2rayNG|GoLog'
```

Fail the smoke test on:

- `UnsatisfiedLinkError` for `hev-socks5-tunnel`;
- `dlopen` or linker errors for any required native library;
- fatal `AndroidRuntime` crash on VPN start;
- immediate HEV process exit that prevents the tunnel from starting.

Library presence in the APK is necessary but not sufficient; this startup check proves the Android loader and runtime path.

## Phone Configuration Path

Exercise the real UI:

1. Open the add menu and select NaiveProxy.
2. Enter alias, server, port, user, password, and SNI.
3. Verify HTTPS/HTTP2 is the default.
4. Select QUIC/HTTP3 and verify congestion-control options and concurrency behavior.
5. Verify UoT is enabled by default and version v2 is selected.
6. Switch to v1, save, reopen, and confirm it persists.
7. Disable UoT, save, reopen, and confirm the UI warns that UDP cannot be forwarded.
8. Exercise CA content, ECH values, and Extra Header add/edit/delete validation when relevant to the change.

Passwords must remain masked. Logs and screenshots must not reveal passwords, proxy credentials, ECH private material, tokens, or authentication Headers.

## Import and Export

When the change affects formats, test the requested surfaces:

- `naive+https://` URI;
- `naive+quic://` URI;
- clipboard import;
- QR-code import/share;
- Xray `protocol: "naive"` JSON;
- sing-box `type: "naive"` JSON.

Verify omitted `uot` imports as v2 and exports include an explicit `uot` value. Confirm server-local paths such as certificate/ECH config paths are not treated as valid Android files.

## Network and Traffic Matrix

Use a server that is known to support the tested feature. Standard Caddy/forwardproxy Naive usually proves TCP only; use sing-box or another confirmed implementation for UoT.

| Case | Expected result |
|---|---|
| HTTPS + TCP | request succeeds through Naive |
| HTTPS + UoT v2 UDP/DNS | UDP round trip succeeds when server supports it |
| HTTPS + UoT off | UDP fails without direct bypass |
| QUIC + TCP | succeeds only when server and build support HTTP/3 |
| UoT v1 | succeeds only against a compatible server |
| wrong CA/ECH | explicit connection failure, no insecure fallback |
| required Extra Header missing | expected proxy rejection |
| required Extra Header present | connection succeeds |

When validating no-direct-bypass behavior, use a destination or capture method that distinguishes proxy traffic from direct traffic. A generic timeout alone is weak evidence.

## Network Switching

With an active connection:

1. Start on Wi-Fi and prove traffic works.
2. Switch to cellular and prove subsequent requests recover.
3. Switch back to Wi-Fi and repeat.
4. Inspect logs for the Android network callback, `notifyNetworkChanged()`, connection closure, and successful reconstruction.

Do not claim success if the app only remained open; prove traffic after each switch.

## Stability Checks

For release-oriented validation, add:

- repeated connect/disconnect cycles;
- background/foreground transition;
- Doze or battery restriction behavior when in scope;
- process restart and configuration restoration;
- a longer TCP/UDP run;
- log inspection for recurring native, Cronet, HEV, or memory failures.

## Evidence and Reporting

Save only redacted evidence. The report must distinguish:

- APK static content gate;
- install/launch result;
- HEV runtime startup;
- configuration persistence;
- TCP result;
- UDP/UoT result and version;
- network-switch result;
- crash/logcat result;
- cases not run and the reason.

If no authorized device is available, report Android smoke as not run. Jenkins APK success and desktop E2E do not substitute for this section.

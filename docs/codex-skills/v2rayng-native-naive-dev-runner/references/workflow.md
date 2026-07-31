# Development, Build, and Test Workflow

Read this file before changing code, dependency pins, tests, Jenkins, or release artifacts.

## 1. Inspect State and Preserve User Work

Run the workspace inspector, then independently inspect relevant diffs before editing:

```powershell
git -C '<repo>' status --short
git -C '<repo>' branch --show-current
git -C '<repo>' rev-parse HEAD
git -C '<repo>' remote -v
```

If a working tree is dirty, identify which files belong to the user and keep them untouched. Never use `git reset --hard`, `git checkout --`, `git clean`, broad staging, or force-push as a convenience.

Before modifying a repository, locate and read its applicable `AGENTS.md` files and examine the existing call path and tests.

## 2. Choose the Owning Repository

| Requirement | Primary repository | Common locations |
|---|---|---|
| Naive config schema and validation | Xray-core | `proxy/naive`, `infra/conf` |
| Cronet engine lifecycle and requests | Xray-core | `proxy/naive` |
| HTTPS/HTTP2, QUIC/HTTP3 | Xray-core | `proxy/naive` |
| TCP, early connection, UoT v1/v2 | Xray-core | `proxy/naive`, `common/singbridge` |
| Android Cronet platform linking | AndroidLibXrayLite | `go.mod`, `libv2ray_cronet_android_*.go` |
| gomobile network-change API | AndroidLibXrayLite | `libv2ray_naive.go` |
| Profile model and Xray JSON | v2rayNG | `ProfileItem.kt`, `V2rayConfig.kt`, `CoreOutboundBuilder.kt` |
| Naive URI/JSON import-export | v2rayNG | `NaiveFmt.kt`, `NaiveConfigValidator.kt`, `AngConfigManager.kt` |
| Phone configuration UI | v2rayNG | `ServerNaiveActivity.kt`, `ServerUiState.kt`, strings |
| Network change callback | v2rayNG | `DefaultNetworkMonitor.kt`, Core service/config classes |
| HEV TUN runtime and root mode | v2rayNG | `TProxyService.kt`, root package, `compile-hevtun.sh` |
| ABI/APK/AAR/Jenkins | v2rayNG | `ci/jenkins`, Gradle files, HEV submodule |
| Live sing-box interoperability | v2rayNG | `ci/e2e` |

Do not implement a cross-layer field as disconnected constants. Trace the value from phone input/import through `ProfileItem`, outbound JSON, Xray config parsing, and runtime behavior.

## 3. Preserve Product Semantics

Unless the user approves a product change, retain:

- no `naiveplugin` and no embedded second client core;
- no local SOCKS/HTTP sidecar used to fake native support;
- UoT enabled by default and normalized to version 2 when missing or zero;
- phone control for UoT v2, v1, and off;
- UDP failure instead of silent direct bypass when UoT is off/unsupported;
- HTTPS as default and QUIC as optional;
- QUIC concurrency fixed to one where required by the Core contract;
- CA/ECH values as content/config rather than server-local file paths;
- case-insensitive Header uniqueness and rejection of transport-managed/hop-by-hop fields;
- Xray Mux disabled for Naive because Cronet/Naive owns connection management;
- network-change notification closes active Cronet connections so later requests rebuild;
- HEV TUN native libraries packaged for every APK ABI.

## 4. Focused Validation

Choose commands from the changed layer. Adjust selectors when tests are renamed; do not run unrelated suites only for appearance.

### Xray-core

Known focused commands:

```powershell
go test -tags with_purego ./proxy/naive
go test -tags with_purego ./infra/conf -run '^TestNaive'
go test -tags with_purego ./common/singbridge ./proxy/shadowsocks_2022
```

Run the first for protocol/Cronet behavior, the second for config parsing/defaults, and the third when UoT or sing bridge behavior changes. Add a regression test that fails before a bug fix when practical.

### AndroidLibXrayLite

At minimum:

- inspect `go.mod`/`go.sum` diff;
- verify only the four Android Cronet platform modules are linked;
- run applicable Go tests or compile checks;
- require Jenkins AAR construction for Xray pin, Cronet, gomobile API, or native-link changes;
- inspect the resulting AAR API with `javap` when an exported controller method changes.

Update the Xray replacement through the Go tool rather than hand-writing a pseudo-version:

```powershell
go mod edit -replace=github.com/xtls/xray-core=github.com/sola97/Xray-core@'<pushed-xray-sha>'
go mod tidy
```

Review the complete module diff. Reject non-Android Cronet platform modules.

### v2rayNG App

Relevant focused tests currently include:

- `CoreOutboundBuilderNaiveTest`
- `NaiveFmtTest`
- `ServerUiStateNaiveTest`

Use the existing Gradle wrapper and exact module paths. For a user-visible configuration change, verify compilation, state restoration, validation, import/export, and outbound JSON rather than testing only one layer.

### HEV TUN and Packaging

Any HEV, JNI, ABI, submodule, `jniLibs`, packaging, Docker, NDK, or Gradle-native change requires a full Jenkins build. The build must recursively initialize the HEV submodule and its nested submodules, build with the pinned NDK, copy outputs into the app inputs, and fail if any APK ABI lacks:

- `libgojni.so`
- `libhev-socks5-tunnel.so`
- `libhevsockstun.so`

Do not accept Gradle success alone. The original #17 APKs compiled but crashed on the default HEV path because the libraries were absent.

## 5. Cross-Repository Update Order

For Core/API/schema changes:

1. Implement and test Xray-core.
2. Commit and push Xray-core.
3. Update AndroidLibXrayLite to the pushed Core SHA using Go module tooling.
4. Validate Android-only Cronet dependencies and AAR-facing APIs.
5. Commit and push AndroidLibXrayLite.
6. Implement/update v2rayNG app behavior and tests.
7. Commit and push v2rayNG.
8. Trigger Jenkins with all three immutable pushed SHAs.

For App-only changes, do not manufacture Core/AAR commits. Still pass known-good immutable Core and AAR SHAs to the final Jenkins build.

For Jenkinsfile/Docker changes, remember that the Job loads the Jenkinsfile from v2rayNG SCM. Push the Pipeline change before triggering the evidence build.

## 6. Commit Discipline

For each completed functional slice in each affected repository:

```powershell
git status --short
git diff --check
git diff -- '<task-files>'
git add -- '<task-files>'
git diff --cached --check
git commit -m '<clear message>'
git push origin '<branch>'
git rev-parse HEAD
git rev-parse 'origin/<branch>'
```

Stage explicit paths only. Confirm local HEAD equals the remote branch after the push. Keep documentation-only follow-ups separate when they are independently reviewable.

## 7. Jenkins Build

Use the repository runner from the v2rayNG root:

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 `
  -XrayRef '<xray-sha>' `
  -AndroidLibRef '<androidlib-sha>' `
  -V2rayNGRef '<v2rayng-sha>'
```

Optional Play Store debug build:

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 `
  -XrayRef '<xray-sha>' `
  -AndroidLibRef '<androidlib-sha>' `
  -V2rayNGRef '<v2rayng-sha>' `
  -BuildPlayStore
```

Resume monitoring without triggering a duplicate build:

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 -ResumeLastBuild
```

The final runner JSON, not a partial console line, decides the Jenkins result. Record Job, build number, URL, result, duration, and artifact URLs.

### Evidence Checklist

- `commit-manifest.json` matches the pushed SHAs and expected HEV gitlink.
- JUnit report has the expected suites, test counts, zero failures/errors, and explained skips.
- `libv2ray.aar` exists and has its SHA-256 record.
- Expected F-Droid APK variants exist; Play Store variants exist when requested.
- `apk.sha256` covers every delivered APK.
- Every APK passes ZIP integrity and the native ABI gate.
- The Universal APK includes all intended ABIs.
- Archives contain only intended artifacts/evidence, not container filesystem contents.

## 8. sing-box Interoperability

Run separately from Jenkins while the Pipeline flags remain fail-closed:

```powershell
& .\ci\e2e\invoke-naive-e2e.ps1 `
  -WorkRoot 'E:\CodexBuildCache\native-naive'
```

Require:

- a JSON result with `passed: true`;
- TCP echo success;
- UoT UDP echo success;
- expected UoT version/source, especially default v2 when version is omitted;
- manifest revisions and SHA-256 values for Xray, sing-box, Cronet, and the test harness.

Extend this runner when modifying QUIC, ECH, UoT v1, required Extra Headers, or negative no-direct-bypass behavior. Do not call a config/unit test a live interoperability test.

## 9. CI Failure Diagnosis

Classify before retrying:

| Symptom | Likely boundary | Action |
|---|---|---|
| dependency consistency stage fails | AndroidLib/Core pin | compare manifest, `go.mod`, and selected SHA |
| HEV gitlink/submodule failure | v2rayNG checkout | inspect recursive submodule state and gitlink |
| `NDK_HOME`/native build failure | Docker/HEV build | verify the pinned NDK path passed to `compile-hevtun.sh` |
| missing `.so` in APK | Gradle/packaging | inspect copied HEV outputs and per-ABI APK contents |
| Google Maven TLS/HTTP failure | external network/cache | prove transport failure; one controlled retry may be appropriate |
| disk preflight failure | NUC Job storage | inspect task-specific workspace/cache; do not globally prune |
| Go/Kotlin/JUnit failure | code/test | fix the regression; do not retry as infrastructure |
| archive contains unrelated files | artifact glob/path | fix archive paths before accepting the build |

## 10. Completion Standard

A change is complete only when:

- the requested behavior is fully implemented across every affected layer;
- proportionate focused tests pass;
- each completed repository change is committed and pushed;
- a full Jenkins build passes when required by the matrix;
- generated artifacts have integrity and native-library evidence;
- required E2E/device checks pass, or their absence is explicitly reported;
- no unrelated user changes, repositories, Jenkins Jobs, NUC services, or caches were modified.

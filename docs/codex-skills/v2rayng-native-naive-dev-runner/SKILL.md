---
name: v2rayng-native-naive-dev-runner
description: Maintain, diagnose, build, test, and package the native NaiveProxy integration across sola97/Xray-core, sola97/AndroidLibXrayLite, and sola97/v2rayNG. Use when Codex must modify native Naive protocol behavior, Cronet, UoT v1/v2, HTTPS or QUIC, TLS/ECH, Android Naive configuration and import/export, HEV TUN packaging, the dedicated v2rayng-naive-android-ci Jenkins/Docker build, APK/AAR native-library gates, sing-box interoperability, or Android device smoke tests. Do not use for plugin-based naiveplugin work or unrelated v2rayNG protocols.
---

# v2rayNG Native Naive Development Runner

## Purpose

Use this skill for the native, plugin-free NaiveProxy implementation only. Coordinate the three approved forks, preserve their dependency direction, choose risk-based tests, build through the dedicated Jenkins/Docker pipeline, and report exactly which desktop, CI, artifact, interoperability, and Android-device checks passed.

Do not replace the repository CI scripts with logic copied into this skill. The checked-in `Jenkinsfile.naive`, Dockerfile, Jenkins runner, APK gate, and E2E runner remain authoritative.

## Read References by Task

- Read [references/environment.md](references/environment.md) before the first repository, Jenkins, NUC, credential, cache, or artifact operation in a turn.
- Read [references/workflow.md](references/workflow.md) before modifying code, updating cross-repository pins, choosing tests, committing, pushing, triggering Jenkins, or diagnosing a CI failure.
- Read [references/android-smoke.md](references/android-smoke.md) before installing an APK, using ADB, interpreting Android logs, or claiming a device result.

## Fixed Scope

Approved repositories:

- Core: `sola97/Xray-core`
- Android AAR: `sola97/AndroidLibXrayLite`
- Android app: `sola97/v2rayNG`

Approved Jenkins target:

- URL: `https://jenkins-nuc.sora.vip/`
- Job: `v2rayng-naive-android-ci`
- Pipeline path: `ci/jenkins/Jenkinsfile.naive`

Git refs, local checkout paths, Jenkins URL, credential file, and artifact directory may be explicitly overridden. Repository identities, Job identity, and Pipeline path may not be widened to arbitrary values without fresh user authorization.

Never install, invoke, or reintroduce `naiveplugin`. Never add a local plugin or sidecar process to impersonate a native implementation.

## Workflow

### 1. Establish the Requested Outcome

Classify the turn as one or more of:

- diagnosis or impact analysis;
- Core protocol change;
- Android AAR or gomobile change;
- Android application/configuration change;
- HEV TUN, JNI, ABI, or packaging change;
- Jenkins/Docker environment change;
- artifact inspection;
- sing-box interoperability test;
- Android device smoke test.

For diagnosis-only requests, inspect and explain first. Do not mutate code, Jenkins, a device, or the NUC unless the user also asks for a fix or execution.

### 2. Inspect Before Editing

Run the bundled read-only inspector from PowerShell:

```powershell
& 'C:\Users\H\.codex\skills\v2rayng-native-naive-dev-runner\scripts\inspect-native-naive-workspace.ps1'
```

Override checkout locations when the work moves:

```powershell
& 'C:\Users\H\.codex\skills\v2rayng-native-naive-dev-runner\scripts\inspect-native-naive-workspace.ps1' `
  -XrayRoot 'D:\src\Xray-core' `
  -AndroidLibRoot 'D:\src\AndroidLibXrayLite' `
  -V2rayNGRoot 'D:\src\v2rayNG'
```

Read the emitted JSON. Stop before editing if a repository is missing, `origin` is not the approved fork, a required CI file is absent, or AndroidLib is unexpectedly pinned to a different Xray commit. Dirty worktrees are evidence to preserve, not permission to reset or overwrite them.

Use `-RequireClean` only when a clean state is genuinely required. Use `-RequireInitializedSubmodules` before a local HEV build; Jenkins initializes HEV recursively itself.

Also read every applicable repository `AGENTS.md` before changing files.

### 3. Select the Owning Layer

Use the narrowest complete path:

- Modify Xray-core for protocol semantics, Cronet lifecycle, HTTP/2 or HTTP/3 transport, UoT, TLS, CA, ECH, DNS, and transport-managed Header validation.
- Modify AndroidLibXrayLite for the Xray revision, Android-only Cronet linking, gomobile exports, or AAR construction.
- Modify v2rayNG for the phone editor, profile model, URI/JSON import and export, outbound JSON, network callbacks, VPN integration, HEV TUN, Gradle packaging, Jenkins, and E2E orchestration.
- Treat a field/default/API change that crosses layers as a three-repository change. Do not patch only the UI when the Core schema or AAR API must also change.

Follow [references/workflow.md](references/workflow.md) for code locations and the validation matrix.

### 4. Implement and Validate by Risk

Preserve these current product requirements unless the user explicitly changes them:

- native implementation with no plugin;
- UoT enabled by default;
- default UoT version v2, with v1/v2/off configurable on the phone;
- HTTPS/HTTP2 default, QUIC/HTTP3 optional;
- UDP must fail closed when disabled or unsupported, never silently bypass to direct;
- HEV TUN remains available and its native libraries must be packaged for every APK ABI;
- no insecure TLS switch that the Core cannot implement with acceptable semantics.

Start with focused tests close to the changed code. Expand to AAR, APK, E2E, Jenkins, or device tests when the change crosses a boundary or affects user-visible/runtime behavior. A compile pass is not proof of live protocol or device behavior.

### 5. Commit and Push Completed Functional Slices

After a functional slice is complete and its proportionate checks pass:

1. Recheck `git status` and diff in every affected repository.
2. Stage only task-related files.
3. Commit and push the affected repository immediately.
4. For cross-repository changes, use this order: Xray-core, AndroidLibXrayLite, v2rayNG.
5. Pin downstream dependencies to the pushed upstream commit, not an unpushed local revision.
6. Prefer immutable commit SHAs for the final Jenkins build, even when development used branch names.

Do not amend, reset, force-push, clean, or discard user changes unless the user explicitly authorizes that exact action.

### 6. Build Through the Dedicated Jenkins Runner

From the v2rayNG repository root:

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 `
  -XrayRef '<pushed-xray-sha>' `
  -AndroidLibRef '<pushed-androidlib-sha>' `
  -V2rayNGRef '<pushed-v2rayng-sha>'
```

Use `-ResumeLastBuild` only to resume monitoring an already-triggered build. Use `-EnsureJob` or `-UpdateJob` only when the user explicitly asks to create or update this dedicated Job. Do not modify UAMS, Old DC, or other Jenkins Jobs.

Do not enable `-RunE2E` or `-RunAndroidSmoke` while those Jenkins stages remain fail-closed placeholders. Run the checked-in E2E runner separately and run Android smoke tests only with an authorized device.

Treat Jenkins success as incomplete until the requested evidence is checked:

- resolved commit manifest;
- relevant JUnit totals and failures;
- `libv2ray.aar` and APK SHA-256 manifests;
- the expected APK variants;
- per-ABI `libgojni.so`, `libhev-socks5-tunnel.so`, and `libhevsockstun.so` gates;
- archive contents limited to intended artifacts and evidence.

### 7. Run Interoperability or Device Checks When Required

For real TCP and default UoT v2 exchange against sing-box:

```powershell
& .\ci\e2e\invoke-naive-e2e.ps1 `
  -WorkRoot 'E:\CodexBuildCache\native-naive'
```

Read the generated result and manifest; do not infer success from process exit alone.

For Android work, follow [references/android-smoke.md](references/android-smoke.md). Installing an APK, clearing logcat, starting/stopping the app, or changing VPN state requires an authorized device and must be reported as a device mutation.

## Failure Rules

- Diagnose before retrying a failed Jenkins build. Report the build URL, failed stage, and redacted relevant evidence.
- A transient Google Maven or network error may justify one controlled retry only after the logs show a transport failure rather than a code/test failure.
- A missing HEV submodule or native library is a build defect, not an optional warning.
- An AndroidLib/Xray pin mismatch must be corrected before a release-evidence build.
- Insufficient NUC disk space permits only task-specific inspection and cleanup. Never run global `docker system prune` or remove other Jobs' data by default.
- Never expose the Jenkins token, GitHub token, Authorization header, crumb, passwords, access tokens, or SSH private-key material.

## Completion Report

Lead with the delivered outcome. Include:

- repositories, branches, and pushed commit SHAs;
- focused tests and exact outcomes;
- Jenkins Job/build number/result/duration and manifest SHAs when run;
- artifact names and local/Jenkins locations when produced;
- E2E and Android device results when run;
- unverified boundaries and why they remain unverified.

Do not describe old build #21 or any previous artifact as proof that a new modification passed. Use historical results only as a baseline.

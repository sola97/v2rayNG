# Environment and Fixed Boundaries

Read this file before repository, Jenkins, NUC, credential, cache, or artifact operations.

## Default Local Layout

```text
C:\Users\H\Documents\Codex\2026-07-30\new-chat-2\work\
├── xray-core\
├── AndroidLibXrayLite-src\
└── v2rayNG\
```

Default working branch for all three repositories is `feature/native-naiveproxy`. Do not assume the branch or commits are unchanged; run the inspector and Git checks at the start of each task.

Approved Git identities:

| Layer | `origin` | `upstream` |
|---|---|---|
| Core | `sola97/Xray-core` | `XTLS/Xray-core` |
| AAR | `sola97/AndroidLibXrayLite` | `2dust/AndroidLibXrayLite` |
| App | `sola97/v2rayNG` | `2dust/v2rayNG` |

SSH and HTTPS forms of the same GitHub owner/repository identity are equivalent. A different owner or repository is outside the default scope.

## Known Verified Baseline

Jenkins build #21 previously resolved:

```json
{
  "xray": "3ac438417f44ad853477a3f317f27ae18620f6b0",
  "androidLib": "95569b7be65c1b3bf706041994cb9ace9699cee8",
  "v2rayNG": "2d834c5c254e46f5f41203218bdaa17f10753473",
  "hevTun": "ad7600497931205105b08367bd1b450048157e40"
}
```

This is historical evidence, not a default answer for future builds. The v2rayNG branch contains later documentation commits, and future feature commits must be built using their own resolved manifest.

## Jenkins

- URL: `https://jenkins-nuc.sora.vip/`
- Job: `v2rayng-naive-android-ci`
- Jenkins agent label: `nuc-docker`
- Pipeline: `ci/jenkins/Jenkinsfile.naive`
- Local runner: `ci/jenkins/invoke-v2rayng-naive-ci.ps1`
- Credential file default: `C:\Users\H\.codex\secrets\old-dc-jenkins-ci.json`

The credential file contains a user name and a DPAPI-protected token. Let the checked-in runner decrypt it in process. Never print, rewrite, commit, copy into the Skill, place in Jenkins parameters, or expose the decrypted value.

`-EnsureJob` may create the dedicated Job only when explicitly authorized. Updating it requires the separate `-UpdateJob` opt-in. Neither switch authorizes changes to any other Job.

## NUC and Docker

- Host: `ubuntu@192.168.2.2`
- SSH key: `C:\Users\H\.ssh\id_rsa`
- Jenkins build requirement: at least 8 GiB free before the build workspace starts
- Gradle retry requirement: at least 4 GiB free before an attempt

Use NUC SSH only when Jenkins evidence is insufficient and the user has asked for environment diagnosis or repair. Start with read-only checks: disk use, Docker version/info, Job workspace size, and task-specific cache size.

Allowed cleanup is limited to confirmed stale data owned by `v2rayng-naive-android-ci`. Do not default to global Docker pruning, deleting shared images/volumes, or touching other Jenkins workspaces, containers, or services.

## Authoritative Toolchain

The current toolchain is defined by these repository files:

- `ci/jenkins/Dockerfile.android`
- `ci/jenkins/Jenkinsfile.naive`
- `ci/jenkins/run-gradle-android.sh`

The last verified build used:

- Eclipse Temurin JDK 17 Jammy image pinned by digest;
- Go 1.26.0 with a fixed download SHA-256;
- Android command-line tools 14742923 with a fixed SHA-1;
- Android platform 37;
- Android build-tools 37.0.0;
- Android NDK 29.0.14206865;
- gomobile `v0.0.0-20260709172247-6129f5bee9d5`;
- single-worker Gradle, Kotlin in-process, and a 1.5 GiB JVM limit.

Before reporting exact versions in a future task, verify the checked-in Dockerfile/Jenkinsfile. Do not silently update the Skill as a second source of truth.

## Local Cache and Artifacts

Default reusable cache root:

```text
E:\CodexBuildCache\native-naive
```

Recommended downloaded artifact layout:

```text
E:\CodexBuildCache\native-naive\jenkins-<build-number>\
├── commit-manifest.json
├── libv2ray.aar
├── apk.sha256
└── apk\
```

Do not overwrite artifacts from another build number. Keep the Jenkins build number and resolved commit manifest beside downloaded APKs.

## Repository-Owned Entrypoints

| Purpose | Entrypoint |
|---|---|
| Jenkins trigger/monitor | `ci/jenkins/invoke-v2rayng-naive-ci.ps1` |
| Jenkins Pipeline | `ci/jenkins/Jenkinsfile.naive` |
| Android image | `ci/jenkins/Dockerfile.android` |
| Gradle retry/build | `ci/jenkins/run-gradle-android.sh` |
| APK native gate | `ci/jenkins/verify-native-apk.sh` |
| sing-box interoperability | `ci/e2e/invoke-naive-e2e.ps1` |
| HEV native build | `compile-hevtun.sh` |

If an entrypoint changes, update and test the repository implementation first. Then update this reference and the versioned Skill copy in the same v2rayNG commit.

## Secret Handling

- Never use a token pasted in chat as file content or a committed constant.
- Never echo secrets to prove authentication.
- Prefer the existing DPAPI Jenkins credential file and configured SSH keys.
- Redact URLs that contain credentials, request headers, cookies, tokens, passwords, and generated proxy credentials from final reports.
- Do not include Jenkins/GitHub credentials in Android configs, E2E fixtures, Gradle properties, manifests, or archived evidence.

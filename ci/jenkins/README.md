# Native NaiveProxy Android CI

This directory owns the isolated Jenkins/Docker build for the native NaiveProxy work. It does not modify or reuse the UAMS and Old DC jobs.

## Reproducible toolchain

`Dockerfile.android` pins the Linux/amd64 JDK base image digest, Go archive checksum, Android command-line tools checksum, Android platform package 37.0, build-tools 37.0.0, NDK 29.0.14206865, and gomobile commit. The pipeline also downloads a pinned Docker Buildx release and verifies its checksum, because the NUC Docker client does not ship the plugin. A verified copy is retained in this Job's dedicated Jenkins-user cache and installed into each disposable workspace. The Docker build:

1. runs the focused Xray Naive, config, singbridge, and Shadowsocks 2022 tests;
2. checks out the exact `hev-socks5-tunnel` gitlink from v2rayNG and builds its four Android ABIs with the pinned NDK;
3. builds `libv2ray.aar` from the approved AndroidLibXrayLite fork;
4. places the HEV libraries and that exact AAR in v2rayNG, then runs the F-Droid debug unit tests;
5. builds the requested debug APK variants and exports the AAR, APKs, JUnit XML, and HTML test report.

The Jenkins pipeline verifies all four AAR ABIs, the `CoreController.notifyNetworkChanged()` binding, every produced APK as a ZIP, and each APK ABI's `libgojni.so`, `libhev-socks5-tunnel.so`, and `libhevsockstun.so`. The universal APK must contain all three files for all four ABIs before the pipeline archives checksums, API listings, source commits, the HEV submodule commit, JUnit results, and size reports.

## Jenkins job

The dedicated job is `v2rayng-naive-android-ci` at `https://jenkins-nuc.sora.vip/`. Create it once and trigger a build from Windows PowerShell:

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 -EnsureJob
```

After the job exists, the normal command is:

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1
```

If the local process is interrupted after Jenkins accepted a build, resume monitoring without triggering a duplicate build:

```powershell
& .\ci\jenkins\invoke-v2rayng-naive-ci.ps1 -ResumeLastBuild
```

Use `-UpdateJob` only when the checked-in Pipeline-from-SCM job definition changes. The runner reads the existing DPAPI-protected Jenkins credential file and never prints its token or authorization header.

## Resource policy

- Builds are serialized and limited to one Go package worker, one Go runtime thread, and one Gradle worker.
- A build fails when the workspace filesystem has less than 8 GiB free. Gradle additionally checks for at least 4 GiB immediately before each attempt.
- BuildKit Go module and build caches use names scoped to this pipeline; the workspace-local Buildx plugin does not modify the Jenkins container image.
- The Gradle user home is a locked BuildKit cache scoped to this pipeline; the Kotlin compiler runs in-process with a 1.5 GiB JVM heap.
- The Docker build removes only non-Android Cronet platform modules from this pipeline's named Go module cache; it keeps the four Android libraries and never prunes Docker globally.
- The pipeline does not run `docker system prune` or remove unrelated images, volumes, containers, jobs, or workspaces.
- Jenkins E2E and Android smoke switches fail closed until their service/device stages are checked in. The independent Windows sing-box interoperability runner is documented under `ci/e2e/`; it does not make the Jenkins switch report a test that Jenkins did not run.

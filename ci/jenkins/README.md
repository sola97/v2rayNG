# Native NaiveProxy Android CI

This directory owns the isolated Jenkins/Docker build for the native NaiveProxy work. It does not modify or reuse the UAMS and Old DC jobs.

## Reproducible toolchain

`Dockerfile.android` pins the Linux/amd64 JDK base image digest, Go archive checksum, Android command-line tools checksum, Android platform package 37.0, build-tools 37.0.0, NDK 29.0.14206865, and gomobile commit. The pipeline also downloads a pinned Docker Buildx release and verifies its checksum, because the NUC Docker client does not ship the plugin. A verified copy is retained in this Job's dedicated Jenkins-user cache and installed into each disposable workspace. The Docker build:

1. runs the focused Xray Naive, config, singbridge, and Shadowsocks 2022 tests;
2. builds `libv2ray.aar` from the approved AndroidLibXrayLite fork;
3. exports only the AAR artifact.

The Jenkins pipeline then verifies all four Android ABIs and the `CoreController.notifyNetworkChanged()` binding before archiving the artifact, its checksum, API listing, source commit manifest, and size report.

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

- Builds are serialized and limited to one Go package worker and one Go runtime thread.
- A build fails when the workspace filesystem has less than 12 GiB free.
- BuildKit Go module and build caches use names scoped to this pipeline; the workspace-local Buildx plugin does not modify the Jenkins container image.
- The pipeline does not run `docker system prune` or remove unrelated images, volumes, containers, jobs, or workspaces.
- E2E and Android smoke switches fail closed until their isolated service/device stages are checked in.

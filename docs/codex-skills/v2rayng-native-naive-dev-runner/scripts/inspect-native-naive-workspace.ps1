[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'C:\Users\H\Documents\Codex\2026-07-30\new-chat-2\work',
    [string]$XrayRoot,
    [string]$AndroidLibRoot,
    [string]$V2rayNGRoot,
    [switch]$RequireClean,
    [switch]$RequireInitializedSubmodules
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-GitText {
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $output = & git -C $RepositoryRoot @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and !$AllowFailure) {
        $message = ($output | Out-String).Trim()
        throw "git $($Arguments -join ' ') failed in '$RepositoryRoot' with exit code $exitCode`: $message"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Text = ($output | Out-String).TrimEnd()
        Lines = @($output | ForEach-Object { [string]$_ })
    }
}

function ConvertTo-GitHubIdentity {
    param([string]$RemoteUrl)

    if ([string]::IsNullOrWhiteSpace($RemoteUrl)) {
        return $null
    }

    $normalized = $RemoteUrl.Trim() -replace '\\', '/'
    $match = [regex]::Match(
        $normalized,
        '(?i)(?:github\.com)[/:](?<owner>[A-Za-z0-9_.-]+)/(?<repository>[A-Za-z0-9_.-]+?)(?:\.git)?$'
    )
    if (!$match.Success) {
        return $null
    }

    return ($match.Groups['owner'].Value + '/' + $match.Groups['repository'].Value).ToLowerInvariant()
}

function Resolve-RepositoryRoot {
    param(
        [string]$ExplicitRoot,
        [string]$ParentRoot,
        [string]$DefaultChild
    )

    $candidate = if ([string]::IsNullOrWhiteSpace($ExplicitRoot)) {
        Join-Path $ParentRoot $DefaultChild
    }
    else {
        $ExplicitRoot
    }

    return [IO.Path]::GetFullPath($candidate)
}

function New-RepositorySnapshot {
    param(
        [string]$Name,
        [string]$RepositoryRoot,
        [string]$ExpectedOrigin,
        [string]$ExpectedUpstream
    )

    return [ordered]@{
        name = $Name
        path = $RepositoryRoot
        exists = $false
        isGitWorkTree = $false
        branch = $null
        head = $null
        detachedHead = $false
        clean = $false
        changeCount = 0
        changes = @()
        origin = [ordered]@{
            url = $null
            identity = $null
            expectedIdentity = $ExpectedOrigin.ToLowerInvariant()
            matches = $false
        }
        upstream = [ordered]@{
            url = $null
            identity = $null
            expectedIdentity = if ($ExpectedUpstream) { $ExpectedUpstream.ToLowerInvariant() } else { $null }
            matches = $false
        }
    }
}

function Get-RepositorySnapshot {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [Parameter(Mandatory)][string]$ExpectedOrigin,
        [string]$ExpectedUpstream,
        [Parameter(Mandatory)][AllowEmptyCollection()][System.Collections.Generic.List[string]]$Errors,
        [Parameter(Mandatory)][AllowEmptyCollection()][System.Collections.Generic.List[string]]$Warnings
    )

    $snapshot = New-RepositorySnapshot `
        -Name $Name `
        -RepositoryRoot $RepositoryRoot `
        -ExpectedOrigin $ExpectedOrigin `
        -ExpectedUpstream $ExpectedUpstream

    if (!(Test-Path -LiteralPath $RepositoryRoot -PathType Container)) {
        $Errors.Add("$Name repository directory is missing: $RepositoryRoot")
        return $snapshot
    }
    $snapshot.exists = $true

    $inside = Invoke-GitText -RepositoryRoot $RepositoryRoot -Arguments @('rev-parse', '--is-inside-work-tree') -AllowFailure
    if ($inside.ExitCode -ne 0 -or $inside.Text.Trim() -ne 'true') {
        $Errors.Add("$Name is not a Git working tree: $RepositoryRoot")
        return $snapshot
    }
    $snapshot.isGitWorkTree = $true

    $branch = (Invoke-GitText -RepositoryRoot $RepositoryRoot -Arguments @('branch', '--show-current')).Text.Trim()
    $head = (Invoke-GitText -RepositoryRoot $RepositoryRoot -Arguments @('rev-parse', 'HEAD')).Text.Trim()
    $statusLines = (Invoke-GitText -RepositoryRoot $RepositoryRoot -Arguments @('status', '--porcelain=v1')).Lines
    $originResult = Invoke-GitText -RepositoryRoot $RepositoryRoot -Arguments @('remote', 'get-url', 'origin') -AllowFailure
    $upstreamResult = Invoke-GitText -RepositoryRoot $RepositoryRoot -Arguments @('remote', 'get-url', 'upstream') -AllowFailure
    $originUrl = if ($originResult.ExitCode -eq 0) { $originResult.Text.Trim() } else { $null }
    $upstreamUrl = if ($upstreamResult.ExitCode -eq 0) { $upstreamResult.Text.Trim() } else { $null }
    $originIdentity = ConvertTo-GitHubIdentity -RemoteUrl $originUrl
    $upstreamIdentity = ConvertTo-GitHubIdentity -RemoteUrl $upstreamUrl

    $snapshot.branch = $branch
    $snapshot.head = $head
    $snapshot.detachedHead = [string]::IsNullOrWhiteSpace($branch)
    $snapshot.clean = $statusLines.Count -eq 0
    $snapshot.changeCount = $statusLines.Count
    $snapshot.changes = @($statusLines)
    $snapshot.origin.url = $originUrl
    $snapshot.origin.identity = $originIdentity
    $snapshot.origin.matches = $originIdentity -eq $ExpectedOrigin.ToLowerInvariant()
    $snapshot.upstream.url = $upstreamUrl
    $snapshot.upstream.identity = $upstreamIdentity
    $snapshot.upstream.matches = if ($ExpectedUpstream) {
        $upstreamIdentity -eq $ExpectedUpstream.ToLowerInvariant()
    }
    else {
        $null
    }

    if (!$snapshot.origin.matches) {
        $Errors.Add("$Name origin must identify '$ExpectedOrigin'; actual URL: '$originUrl'")
    }
    if ($ExpectedUpstream -and !$snapshot.upstream.matches) {
        $Warnings.Add("$Name upstream is not '$ExpectedUpstream'; actual URL: '$upstreamUrl'")
    }
    if ($RequireClean -and !$snapshot.clean) {
        $Errors.Add("$Name working tree is not clean and -RequireClean was specified")
    }

    return $snapshot
}

if (!(Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'git is required but was not found on PATH'
}

$resolvedWorkspaceRoot = [IO.Path]::GetFullPath($WorkspaceRoot)
$resolvedXrayRoot = Resolve-RepositoryRoot -ExplicitRoot $XrayRoot -ParentRoot $resolvedWorkspaceRoot -DefaultChild 'xray-core'
$resolvedAndroidLibRoot = Resolve-RepositoryRoot -ExplicitRoot $AndroidLibRoot -ParentRoot $resolvedWorkspaceRoot -DefaultChild 'AndroidLibXrayLite-src'
$resolvedV2rayNGRoot = Resolve-RepositoryRoot -ExplicitRoot $V2rayNGRoot -ParentRoot $resolvedWorkspaceRoot -DefaultChild 'v2rayNG'

$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

$xray = Get-RepositorySnapshot `
    -Name 'xray' `
    -RepositoryRoot $resolvedXrayRoot `
    -ExpectedOrigin 'sola97/Xray-core' `
    -ExpectedUpstream 'XTLS/Xray-core' `
    -Errors $errors `
    -Warnings $warnings
$androidLib = Get-RepositorySnapshot `
    -Name 'androidLib' `
    -RepositoryRoot $resolvedAndroidLibRoot `
    -ExpectedOrigin 'sola97/AndroidLibXrayLite' `
    -ExpectedUpstream '2dust/AndroidLibXrayLite' `
    -Errors $errors `
    -Warnings $warnings
$v2rayNG = Get-RepositorySnapshot `
    -Name 'v2rayNG' `
    -RepositoryRoot $resolvedV2rayNGRoot `
    -ExpectedOrigin 'sola97/v2rayNG' `
    -ExpectedUpstream '2dust/v2rayNG' `
    -Errors $errors `
    -Warnings $warnings

$requiredFiles = @(
    'ci/jenkins/invoke-v2rayng-naive-ci.ps1',
    'ci/jenkins/Jenkinsfile.naive',
    'ci/jenkins/Dockerfile.android',
    'ci/jenkins/.dockerignore.android',
    'ci/jenkins/run-gradle-android.sh',
    'ci/jenkins/verify-native-apk.sh',
    'ci/e2e/invoke-naive-e2e.ps1',
    'compile-hevtun.sh',
    '.gitmodules'
)
$requiredFileState = foreach ($relativePath in $requiredFiles) {
    $platformPath = $relativePath -replace '/', [IO.Path]::DirectorySeparatorChar
    $fullPath = Join-Path $resolvedV2rayNGRoot $platformPath
    $exists = Test-Path -LiteralPath $fullPath -PathType Leaf
    if (!$exists) {
        $errors.Add("Required v2rayNG file is missing: $relativePath")
    }
    [ordered]@{
        path = $relativePath
        exists = $exists
    }
}

$dependencyState = [ordered]@{
    goModPath = Join-Path $resolvedAndroidLibRoot 'go.mod'
    xrayHead = $xray.head
    expectedShortRevision = if ($xray.head) { $xray.head.Substring(0, 12) } else { $null }
    pinnedShortRevision = $null
    replaceLine = $null
    matchesLocalXrayHead = $false
}
if ($androidLib.isGitWorkTree -and $xray.isGitWorkTree) {
    if (!(Test-Path -LiteralPath $dependencyState.goModPath -PathType Leaf)) {
        $errors.Add("AndroidLib go.mod is missing: $($dependencyState.goModPath)")
    }
    else {
        $replaceLine = Get-Content -LiteralPath $dependencyState.goModPath | Where-Object {
            $_ -match '^replace\s+github\.com/xtls/xray-core\s+=>\s+github\.com/sola97/Xray-core\s+'
        } | Select-Object -First 1
        $dependencyState.replaceLine = $replaceLine
        if (!$replaceLine) {
            $errors.Add('AndroidLib go.mod does not contain the approved sola97/Xray-core replacement')
        }
        else {
            $revisionMatch = [regex]::Match($replaceLine, '-(?<revision>[0-9a-fA-F]{12})(?:\s|$)')
            if (!$revisionMatch.Success) {
                $errors.Add('AndroidLib Xray replacement does not end in a 12-character Git revision')
            }
            else {
                $pinnedShortRevision = $revisionMatch.Groups['revision'].Value.ToLowerInvariant()
                $dependencyState.pinnedShortRevision = $pinnedShortRevision
                $dependencyState.matchesLocalXrayHead = $pinnedShortRevision -eq $dependencyState.expectedShortRevision
                if (!$dependencyState.matchesLocalXrayHead) {
                    $errors.Add("AndroidLib pins Xray '$pinnedShortRevision' but local Xray HEAD is '$($dependencyState.expectedShortRevision)'")
                }
            }
        }
    }
}

$hevState = [ordered]@{
    path = Join-Path $resolvedV2rayNGRoot 'hev-socks5-tunnel'
    gitlink = $null
    initialized = $false
    actualHead = $null
    matchesGitlink = $false
    recursiveStatus = @()
    recursiveStateClean = $false
}
if ($v2rayNG.isGitWorkTree) {
    $gitlinkResult = Invoke-GitText -RepositoryRoot $resolvedV2rayNGRoot -Arguments @('ls-tree', 'HEAD', '--', 'hev-socks5-tunnel') -AllowFailure
    $gitlinkMatch = [regex]::Match($gitlinkResult.Text, '^160000\s+commit\s+(?<revision>[0-9a-f]{40})\s+hev-socks5-tunnel$')
    if (!$gitlinkMatch.Success) {
        $errors.Add('v2rayNG HEAD does not contain the expected hev-socks5-tunnel gitlink')
    }
    else {
        $hevState.gitlink = $gitlinkMatch.Groups['revision'].Value
    }

    $submoduleMarker = Join-Path $hevState.path '.git'
    if (Test-Path -LiteralPath $submoduleMarker) {
        $actualResult = Invoke-GitText -RepositoryRoot $hevState.path -Arguments @('rev-parse', 'HEAD') -AllowFailure
        if ($actualResult.ExitCode -eq 0 -and $actualResult.Text -match '^[0-9a-f]{40}$') {
            $hevState.initialized = $true
            $hevState.actualHead = $actualResult.Text.Trim()
            $hevState.matchesGitlink = $hevState.actualHead -eq $hevState.gitlink
            if (!$hevState.matchesGitlink) {
                $warnings.Add("HEV submodule HEAD '$($hevState.actualHead)' differs from gitlink '$($hevState.gitlink)'")
            }
        }
    }

    $submoduleResult = Invoke-GitText -RepositoryRoot $resolvedV2rayNGRoot -Arguments @('submodule', 'status', '--recursive') -AllowFailure
    $hevState.recursiveStatus = @($submoduleResult.Lines)
    $outOfSyncSubmodules = @($submoduleResult.Lines | Where-Object { $_ -match '^[+-U]' })
    $hevState.recursiveStateClean = $submoduleResult.ExitCode -eq 0 -and $outOfSyncSubmodules.Count -eq 0

    if (!$hevState.initialized) {
        $warnings.Add('HEV submodule is not initialized in the local v2rayNG checkout')
    }
    if ($RequireInitializedSubmodules -and (!$hevState.initialized -or !$hevState.matchesGitlink -or !$hevState.recursiveStateClean)) {
        $errors.Add('HEV recursive submodules are not initialized at the committed revisions')
    }
}

$result = [ordered]@{
    passed = $errors.Count -eq 0
    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    workspaceRoot = $resolvedWorkspaceRoot
    requirements = [ordered]@{
        cleanRequired = $RequireClean.IsPresent
        initializedSubmodulesRequired = $RequireInitializedSubmodules.IsPresent
    }
    repositories = [ordered]@{
        xray = $xray
        androidLib = $androidLib
        v2rayNG = $v2rayNG
    }
    dependency = $dependencyState
    hevTun = $hevState
    requiredFiles = @($requiredFileState)
    warnings = @($warnings)
    errors = @($errors)
}

$result | ConvertTo-Json -Depth 10
if ($errors.Count -gt 0) {
    exit 20
}

[CmdletBinding()]
param(
    [string]$WorkRoot = (Join-Path ([IO.Path]::GetTempPath()) 'v2rayng-native-naive-e2e'),
    [string]$OutputDirectory,
    [string]$XrayRef = '3ac438417f44ad853477a3f317f27ae18620f6b0',
    [string]$SingBoxRef = '4f7f89463ccfa506f90c46c715cf9798159d2c44',
    [string]$XraySource,
    [string]$SingBoxSource
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$xrayRepository = 'https://github.com/sola97/Xray-core.git'
$singBoxRepository = 'https://github.com/SagerNet/sing-box.git'
$cronetWindowsModule = 'github.com/sagernet/cronet-go/lib/windows_amd64@v0.0.0-20260712142643-1e5048bd5587'

function Assert-NativeCommand {
    param([string]$Name)

    if (!(Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Assert-SafeGitRef {
    param([string]$Name, [string]$Value)

    if ($Value.Length -gt 160 -or $Value -notmatch '^[A-Za-z0-9._/-]+$' -or $Value.Contains('..') -or $Value.StartsWith('/') -or $Value.EndsWith('/')) {
        throw "$Name is not a safe Git ref"
    }
}

function Invoke-NativeCommand {
    param(
        [string]$FilePath,
        [string[]]$Arguments
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

function Resolve-PinnedCheckout {
    param(
        [string]$Name,
        [string]$Repository,
        [string]$Ref,
        [string]$RequestedSource,
        [string]$DefaultSource
    )

    if ($RequestedSource) {
        $source = (Resolve-Path -LiteralPath $RequestedSource).Path
        if (!(Test-Path -LiteralPath (Join-Path $source '.git'))) {
            throw "$Name source is not a Git checkout: $source"
        }
        $status = (& git -C $source status --short)
        if ($LASTEXITCODE -ne 0) { throw "Unable to inspect $Name checkout" }
        if ($status) { throw "$Name checkout must be clean: $source" }
        $revision = (& git -C $source rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0) { throw "Unable to read $Name revision" }
        if ($revision -ne $Ref) {
            throw "$Name checkout is at $revision, expected $Ref"
        }
        return $source
    }

    $source = $DefaultSource
    if (!(Test-Path -LiteralPath (Join-Path $source '.git'))) {
        New-Item -ItemType Directory -Force -Path (Split-Path $source -Parent) | Out-Null
        Invoke-NativeCommand -FilePath 'git' -Arguments @('clone', '--filter=blob:none', '--no-checkout', $Repository, $source)
    }
    $origin = (& git -C $source remote get-url origin).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Unable to read $Name origin" }
    if ($origin -ne $Repository) {
        throw "$Name checkout has unexpected origin '$origin'"
    }
    $status = (& git -C $source status --short)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect $Name checkout" }
    if ($status) { throw "$Name checkout must be clean: $source" }

    Invoke-NativeCommand -FilePath 'git' -Arguments @('-C', $source, 'fetch', '--depth=1', 'origin', $Ref)
    Invoke-NativeCommand -FilePath 'git' -Arguments @('-C', $source, 'checkout', '--detach', 'FETCH_HEAD')
    $revision = (& git -C $source rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $revision -ne $Ref) {
        throw "$Name resolved to '$revision', expected '$Ref'"
    }
    return $source
}

function Set-GoWorkspace {
    param([string]$Root)

    New-Item -ItemType Directory -Force -Path $Root,(Join-Path $Root 'tmp'),(Join-Path $Root 'gocache'),(Join-Path $Root 'gopath'),(Join-Path $Root 'gomodcache') | Out-Null
    $env:GOTMPDIR = Join-Path $Root 'tmp'
    $env:GOCACHE = Join-Path $Root 'gocache'
    $env:GOPATH = Join-Path $Root 'gopath'
    $env:GOMODCACHE = Join-Path $Root 'gomodcache'
    $env:GOTOOLCHAIN = 'auto'
    $env:CGO_ENABLED = '0'
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This runner currently targets Windows amd64 because it validates the Windows Cronet runtime used by the local E2E process.'
}

Assert-NativeCommand -Name 'git'
Assert-NativeCommand -Name 'go'
Assert-SafeGitRef -Name 'XrayRef' -Value $XrayRef
Assert-SafeGitRef -Name 'SingBoxRef' -Value $SingBoxRef

$WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
if (!$OutputDirectory) {
    $OutputDirectory = Join-Path $WorkRoot 'e2e-result'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $WorkRoot,$OutputDirectory | Out-Null

$xraySourcePath = Resolve-PinnedCheckout -Name 'Xray' -Repository $xrayRepository -Ref $XrayRef -RequestedSource $XraySource -DefaultSource (Join-Path $WorkRoot 'sources\xray-core')
$singBoxSourcePath = Resolve-PinnedCheckout -Name 'sing-box' -Repository $singBoxRepository -Ref $SingBoxRef -RequestedSource $SingBoxSource -DefaultSource (Join-Path $WorkRoot 'sources\sing-box')

$binDirectory = Join-Path $WorkRoot 'e2e-bin'
New-Item -ItemType Directory -Force -Path $binDirectory | Out-Null
$xrayBinary = Join-Path $binDirectory 'xray.exe'
$singBoxBinary = Join-Path $binDirectory 'sing-box.exe'
$harnessBinary = Join-Path $binDirectory 'naive-e2e.exe'
$cronetLibrary = Join-Path $binDirectory 'libcronet.dll'

Set-GoWorkspace -Root (Join-Path $WorkRoot 'e2e-xray')
Push-Location $xraySourcePath
try {
    Invoke-NativeCommand -FilePath 'go' -Arguments @('build', '-tags', 'with_purego', '-trimpath', '-o', $xrayBinary, '.\main')
    $cronetModuleJson = (& go mod download -json $cronetWindowsModule) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw 'Unable to download the pinned Windows Cronet runtime' }
    $cronetModule = $cronetModuleJson | ConvertFrom-Json
    Copy-Item -LiteralPath (Join-Path $cronetModule.Dir 'libcronet.dll') -Destination $cronetLibrary -Force
}
finally {
    Pop-Location
}

Set-GoWorkspace -Root (Join-Path $WorkRoot 'e2e-singbox')
Push-Location $singBoxSourcePath
try {
    Invoke-NativeCommand -FilePath 'go' -Arguments @('build', '-trimpath', '-o', $singBoxBinary, '.\cmd\sing-box')
}
finally {
    Pop-Location
}

Set-GoWorkspace -Root (Join-Path $WorkRoot 'e2e-harness')
$harnessSource = Join-Path $PSScriptRoot 'naive_e2e.go'
Invoke-NativeCommand -FilePath 'go' -Arguments @('test', $harnessSource, (Join-Path $PSScriptRoot 'naive_e2e_test.go'))
Invoke-NativeCommand -FilePath 'go' -Arguments @('build', '-trimpath', '-o', $harnessBinary, $harnessSource)

$resultPath = Join-Path $OutputDirectory 'naive-e2e-result.json'
Invoke-NativeCommand -FilePath $harnessBinary -Arguments @(
    '-xray', $xrayBinary,
    '-sing-box', $singBoxBinary,
    '-output', $resultPath,
    '-xray-revision', $XrayRef,
    '-sing-box-revision', $SingBoxRef
)

$manifest = [ordered]@{
    xrayRevision = $XrayRef
    singBoxRevision = $SingBoxRef
    cronetWindowsModule = $cronetWindowsModule
    xraySha256 = (Get-FileHash -LiteralPath $xrayBinary -Algorithm SHA256).Hash
    singBoxSha256 = (Get-FileHash -LiteralPath $singBoxBinary -Algorithm SHA256).Hash
    cronetSha256 = (Get-FileHash -LiteralPath $cronetLibrary -Algorithm SHA256).Hash
    harnessSha256 = (Get-FileHash -LiteralPath $harnessBinary -Algorithm SHA256).Hash
    result = (Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json)
}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'naive-e2e-manifest.json') -Encoding utf8

Write-Host "Native Naive E2E passed. Evidence: $OutputDirectory"

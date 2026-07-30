[CmdletBinding()]
param(
    [string]$JenkinsUrl = 'https://jenkins-nuc.sora.vip/',
    [string]$JobName = 'v2rayng-naive-android-ci',
    [string]$CredentialFile = 'C:\Users\H\.codex\secrets\old-dc-jenkins-ci.json',
    [string]$XrayRef = 'd4997a5569da8da76fc96d37fce0411e39076908',
    [string]$AndroidLibRef = 'feature/native-naiveproxy',
    [string]$V2rayNGRef = 'feature/native-naiveproxy',
    [switch]$BuildPlayStore,
    [switch]$RunE2E,
    [switch]$RunAndroidSmoke,
    [switch]$EnsureJob,
    [switch]$UpdateJob,
    [switch]$ResumeLastBuild,
    [int]$PollSeconds = 8,
    [int]$TimeoutMinutes = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-SafeGitRef {
    param([string]$Name, [string]$Value)

    if ($Value.Length -gt 160 -or $Value -notmatch '^[A-Za-z0-9._/-]+$' -or $Value.Contains('..') -or $Value.StartsWith('/') -or $Value.EndsWith('/')) {
        throw "$Name is not a safe Git ref"
    }
}

function Get-JenkinsHeaders {
    param([pscustomobject]$Credential)

    $secureToken = ConvertTo-SecureString $Credential.encryptedToken
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    try {
        $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
        $basicBytes = [Text.Encoding]::UTF8.GetBytes("$($Credential.userName):$plainToken")
        try {
            $authorization = 'Basic ' + [Convert]::ToBase64String($basicBytes)
        }
        finally {
            [Array]::Clear($basicBytes, 0, $basicBytes.Length)
            $plainToken = $null
        }
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
    }

    $headers = @{ Authorization = $authorization }
    try {
        $crumb = Invoke-RestMethod -Uri "${JenkinsUrl}crumbIssuer/api/json" -Headers $headers -Method Get
        $headers[$crumb.crumbRequestField] = $crumb.crumb
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 404) {
            throw
        }
    }
    return $headers
}

function Get-JobConfigXml {
    param([string]$RepositoryUrl, [string]$BranchName, [string]$ScriptPath)

    $escapedRepository = [Security.SecurityElement]::Escape($RepositoryUrl)
    $escapedBranch = [Security.SecurityElement]::Escape($BranchName)
    $escapedScript = [Security.SecurityElement]::Escape($ScriptPath)
    return @"
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <actions/>
  <description>Native NaiveProxy Android CI. Builds pinned Xray-core, AndroidLibXrayLite, and v2rayNG forks in Docker without modifying other Jenkins jobs.</description>
  <keepDependencies>false</keepDependencies>
  <properties>
    <hudson.model.ParametersDefinitionProperty>
      <parameterDefinitions>
        <hudson.model.StringParameterDefinition>
          <name>XRAY_REF</name>
          <description>Ref in sola97/Xray-core</description>
          <defaultValue>d4997a5569da8da76fc96d37fce0411e39076908</defaultValue>
          <trim>true</trim>
        </hudson.model.StringParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>ANDROID_LIB_REF</name>
          <description>Ref in sola97/AndroidLibXrayLite</description>
          <defaultValue>feature/native-naiveproxy</defaultValue>
          <trim>true</trim>
        </hudson.model.StringParameterDefinition>
        <hudson.model.StringParameterDefinition>
          <name>V2RAYNG_REF</name>
          <description>Ref in sola97/v2rayNG</description>
          <defaultValue>feature/native-naiveproxy</defaultValue>
          <trim>true</trim>
        </hudson.model.StringParameterDefinition>
        <hudson.model.BooleanParameterDefinition>
          <name>BUILD_FDROID</name>
          <description>Build F-Droid debug APK when app stages are enabled</description>
          <defaultValue>true</defaultValue>
        </hudson.model.BooleanParameterDefinition>
        <hudson.model.BooleanParameterDefinition>
          <name>BUILD_PLAYSTORE</name>
          <description>Build Play Store debug APK when app stages are enabled</description>
          <defaultValue>false</defaultValue>
        </hudson.model.BooleanParameterDefinition>
        <hudson.model.BooleanParameterDefinition>
          <name>RUN_E2E</name>
          <description>Run isolated sing-box Naive end-to-end tests</description>
          <defaultValue>false</defaultValue>
        </hudson.model.BooleanParameterDefinition>
        <hudson.model.BooleanParameterDefinition>
          <name>RUN_ANDROID_SMOKE</name>
          <description>Run smoke tests only with an authorized ADB device</description>
          <defaultValue>false</defaultValue>
        </hudson.model.BooleanParameterDefinition>
      </parameterDefinitions>
    </hudson.model.ParametersDefinitionProperty>
  </properties>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
    <scm class="hudson.plugins.git.GitSCM" plugin="git">
      <configVersion>2</configVersion>
      <userRemoteConfigs>
        <hudson.plugins.git.UserRemoteConfig>
          <url>$escapedRepository</url>
        </hudson.plugins.git.UserRemoteConfig>
      </userRemoteConfigs>
      <branches>
        <hudson.plugins.git.BranchSpec>
          <name>$escapedBranch</name>
        </hudson.plugins.git.BranchSpec>
      </branches>
      <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>
      <submoduleCfg class="empty-list"/>
      <extensions>
        <hudson.plugins.git.extensions.impl.CloneOption>
          <shallow>true</shallow>
          <noTags>false</noTags>
          <reference></reference>
          <depth>1</depth>
          <honorRefspec>true</honorRefspec>
        </hudson.plugins.git.extensions.impl.CloneOption>
      </extensions>
    </scm>
    <scriptPath>$escapedScript</scriptPath>
    <lightweight>true</lightweight>
  </definition>
  <triggers/>
  <disabled>false</disabled>
</flow-definition>
"@
}

function Invoke-JenkinsRequest {
    param(
        [string]$Uri,
        [hashtable]$Headers,
        [string]$Method = 'Get',
        [string]$ContentType,
        [object]$Body
    )

    $arguments = @{ Uri = $Uri; Headers = $Headers; Method = $Method }
    if ($ContentType) { $arguments.ContentType = $ContentType }
    if ($null -ne $Body) { $arguments.Body = $Body }
    return Invoke-WebRequest @arguments
}

Assert-SafeGitRef -Name 'XrayRef' -Value $XrayRef
Assert-SafeGitRef -Name 'AndroidLibRef' -Value $AndroidLibRef
Assert-SafeGitRef -Name 'V2rayNGRef' -Value $V2rayNGRef

if ($PollSeconds -lt 2 -or $PollSeconds -gt 60) { throw 'PollSeconds must be between 2 and 60' }
if ($TimeoutMinutes -lt 5 -or $TimeoutMinutes -gt 360) { throw 'TimeoutMinutes must be between 5 and 360' }
if (!(Test-Path -LiteralPath $CredentialFile -PathType Leaf)) { throw "Jenkins credential file not found: $CredentialFile" }

$JenkinsUrl = $JenkinsUrl.TrimEnd('/') + '/'
$credential = Get-Content -Raw -LiteralPath $CredentialFile | ConvertFrom-Json
if (!$credential.userName -or !$credential.encryptedToken) { throw 'Jenkins credential file is incomplete' }
$headers = Get-JenkinsHeaders -Credential $credential
$credential = $null

$encodedJobName = [Uri]::EscapeDataString($JobName)
$jobUrl = "${JenkinsUrl}job/${encodedJobName}/"
$jobExists = $true
try {
    Invoke-JenkinsRequest -Uri "${jobUrl}api/json" -Headers $headers | Out-Null
}
catch {
    if ($_.Exception.Response.StatusCode.value__ -eq 404) {
        $jobExists = $false
    }
    else {
        throw
    }
}

$jobConfigArguments = @{
    RepositoryUrl = 'https://github.com/sola97/v2rayNG.git'
    BranchName = '*/feature/native-naiveproxy'
    ScriptPath = 'ci/jenkins/Jenkinsfile.naive'
}
$jobXml = Get-JobConfigXml @jobConfigArguments

if (!$jobExists) {
    if (!$EnsureJob) { throw "Jenkins job '$JobName' does not exist. Re-run with -EnsureJob to create this dedicated job." }
    $createJobArguments = @{
        Uri = "${JenkinsUrl}createItem?name=$encodedJobName"
        Headers = $headers
        Method = 'Post'
        ContentType = 'application/xml; charset=utf-8'
        Body = [Text.Encoding]::UTF8.GetBytes($jobXml)
    }
    Invoke-JenkinsRequest @createJobArguments | Out-Null
}
elseif ($UpdateJob) {
    $updateJobArguments = @{
        Uri = "${jobUrl}config.xml"
        Headers = $headers
        Method = 'Post'
        ContentType = 'application/xml; charset=utf-8'
        Body = [Text.Encoding]::UTF8.GetBytes($jobXml)
    }
    Invoke-JenkinsRequest @updateJobArguments | Out-Null
}

$deadline = [DateTimeOffset]::UtcNow.AddMinutes($TimeoutMinutes)
$buildNumber = $null
if ($ResumeLastBuild) {
    $jobState = Invoke-RestMethod -Uri "${jobUrl}api/json?tree=lastBuild[number]" -Headers $headers
    if (!$jobState.lastBuild.number) { throw "Jenkins job '$JobName' has no build to resume" }
    $buildNumber = [int]$jobState.lastBuild.number
}
else {
    $buildParameters = @{
        XRAY_REF = $XrayRef
        ANDROID_LIB_REF = $AndroidLibRef
        V2RAYNG_REF = $V2rayNGRef
        BUILD_FDROID = 'true'
        BUILD_PLAYSTORE = $BuildPlayStore.IsPresent.ToString().ToLowerInvariant()
        RUN_E2E = $RunE2E.IsPresent.ToString().ToLowerInvariant()
        RUN_ANDROID_SMOKE = $RunAndroidSmoke.IsPresent.ToString().ToLowerInvariant()
    }

    $triggerArguments = @{
        Uri = "${jobUrl}buildWithParameters"
        Headers = $headers
        Method = 'Post'
        ContentType = 'application/x-www-form-urlencoded'
        Body = $buildParameters
    }
    $triggerResponse = Invoke-JenkinsRequest @triggerArguments

    $queueLocation = [string]$triggerResponse.Headers.Location
    if (!$queueLocation) { throw 'Jenkins did not return a queue item URL' }
    $queueUrl = [Uri]$queueLocation
    if (!$queueUrl.IsAbsoluteUri) { $queueUrl = [Uri]::new([Uri]$JenkinsUrl, $queueUrl) }

    while (!$buildNumber) {
        if ([DateTimeOffset]::UtcNow -gt $deadline) { throw 'Timed out waiting for Jenkins to start the build' }
        Start-Sleep -Seconds $PollSeconds
        $queueItem = Invoke-RestMethod -Uri ($queueUrl.AbsoluteUri.TrimEnd('/') + '/api/json') -Headers $headers
        if ($queueItem.cancelled) { throw 'Jenkins cancelled the queued build' }
        if ($queueItem.executable.number) { $buildNumber = [int]$queueItem.executable.number }
    }
}

$buildUrl = "${jobUrl}${buildNumber}/"
do {
    if ([DateTimeOffset]::UtcNow -gt $deadline) { throw "Timed out waiting for Jenkins build #$buildNumber" }
    Start-Sleep -Seconds $PollSeconds
    $build = Invoke-RestMethod -Uri "${buildUrl}api/json?tree=number,url,building,result,duration,artifacts[fileName,relativePath]" -Headers $headers
} while ($build.building)

$result = [ordered]@{
    job = $JobName
    buildNumber = $build.number
    result = $build.result
    url = $build.url
    durationMilliseconds = $build.duration
    artifacts = @($build.artifacts | ForEach-Object {
        [ordered]@{
            fileName = $_.fileName
            url = "${buildUrl}artifact/$($_.relativePath)"
        }
    })
}
$result | ConvertTo-Json -Depth 5

if ($build.result -ne 'SUCCESS') { exit 30 }

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsRoot,
    [ValidateSet("pilot", "stable")]
    [string]$Channel = "stable",
    [string]$SignToolPath,
    [string]$ExpectedLatestVersion,
    [string]$ExpectedMinimumSupportedVersion,
    [switch]$RequireSignature
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$artifacts = [System.IO.Path]::GetFullPath($ArtifactsRoot)
$channelDirectory = Join-Path $artifacts $Channel
$releaseJsonPath = Join-Path $channelDirectory "release.json"
$appInstallerPath = Join-Path $channelDirectory "LearnBotLocalAgent.appinstaller"

if (-not (Test-Path -LiteralPath $releaseJsonPath -PathType Leaf)) {
    throw "Missing release metadata: $releaseJsonPath"
}
if (-not (Test-Path -LiteralPath $appInstallerPath -PathType Leaf)) {
    throw "Missing App Installer file: $appInstallerPath"
}

$release = Get-Content -Raw -LiteralPath $releaseJsonPath | ConvertFrom-Json
if ($release.schema -ne "learnbot.local-agent.release.v1" -or $release.channel -ne $Channel) {
    throw "Release metadata schema or channel is invalid."
}
if ($Channel -eq "stable" -and
    (-not ($release.PSObject.Properties.Name -contains "productionTrusted") -or -not [bool]$release.productionTrusted)) {
    throw "Stable release metadata is not marked as production-trusted."
}
if ([string]$release.version -notmatch '^\d+\.\d+\.\d+\.\d+$' -or
    [string]$release.minimumSupportedVersion -notmatch '^\d+\.\d+\.\d+\.\d+$' -or
    [Version]$release.minimumSupportedVersion -gt [Version]$release.version) {
    throw "Release version metadata is invalid."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedLatestVersion) -and $release.version -ne $ExpectedLatestVersion) {
    throw "Release version does not match the backend latest-version deployment setting."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedMinimumSupportedVersion) -and
    $release.minimumSupportedVersion -ne $ExpectedMinimumSupportedVersion) {
    throw "Release minimum version does not match the backend minimum-version deployment setting."
}
if ($release.packageUrl -notmatch '/downloads/local-agent/releases/([^/]+)/([^/]+\.msix)$') {
    throw "Release metadata has an invalid immutable package URL: $($release.packageUrl)"
}

$version = $Matches[1]
$packageFileName = $Matches[2]
if ($version -ne [string]$release.version) {
    throw "Immutable package URL version does not match release.json."
}
$packagePath = Join-Path $artifacts ("releases\$version\$packageFileName")
if (-not (Test-Path -LiteralPath $packagePath -PathType Leaf)) {
    throw "Missing immutable package: $packagePath"
}

$actualHash = (Get-FileHash -LiteralPath $packagePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne ([string]$release.sha256).ToLowerInvariant()) {
    throw "Package SHA-256 does not match release.json."
}
if ((Get-Item -LiteralPath $packagePath).Length -ne [long]$release.sizeBytes) {
    throw "Package size does not match release.json."
}

$appInstaller = [xml](Get-Content -Raw -LiteralPath $appInstallerPath)
if (-not $appInstaller.OuterXml.Contains([string]$release.packageUrl) -or
    -not $appInstaller.OuterXml.Contains([string]$release.version)) {
    throw "App Installer does not match release.json."
}
$appInstallerRoot = $appInstaller.DocumentElement
$mainPackage = $appInstallerRoot.SelectSingleNode("*[local-name()='MainPackage']")
if ($appInstallerRoot.GetAttribute("Uri") -ne [string]$release.appInstallerUrl -or
    $appInstallerRoot.GetAttribute("Version") -ne [string]$release.version -or
    $mainPackage.GetAttribute("Name") -ne "LearnBot.LocalAgent" -or
    $mainPackage.GetAttribute("Publisher") -ne [string]$release.publisher -or
    $mainPackage.GetAttribute("Version") -ne [string]$release.version -or
    $mainPackage.GetAttribute("ProcessorArchitecture") -ne "x64" -or
    $mainPackage.GetAttribute("Uri") -ne [string]$release.packageUrl) {
    throw "App Installer identity or URI fields do not exactly match release.json."
}

$unpackRoot = Join-Path ([IO.Path]::GetTempPath()) ("learnbot-release-verify-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $unpackRoot | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($packagePath, $unpackRoot)
    $manifestPath = Join-Path $unpackRoot "AppxManifest.xml"
    $manifest = [xml](Get-Content -Raw -LiteralPath $manifestPath)
    $identity = $manifest.DocumentElement.SelectSingleNode("*[local-name()='Identity']")
    if ($identity.GetAttribute("Name") -ne "LearnBot.LocalAgent" -or
        $identity.GetAttribute("Publisher") -ne [string]$release.publisher -or
        $identity.GetAttribute("Version") -ne [string]$release.version -or
        $identity.GetAttribute("ProcessorArchitecture") -ne "x64") {
        throw "MSIX manifest identity does not match release metadata."
    }
    foreach ($requiredPath in @(
        "app\learnbot.exe",
        "setup\LearnBotSetup.exe",
        "host\LearnBotAgentHost.exe",
        "Assets\StoreLogo.png",
        "Assets\Square44x44Logo.png",
        "Assets\Square150x150Logo.png"
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $unpackRoot $requiredPath) -PathType Leaf)) {
            throw "MSIX is missing required package entry: $requiredPath"
        }
    }
    Add-Type -AssemblyName System.Drawing
    $expectedAssets = @{
        "Assets\StoreLogo.png" = 50
        "Assets\Square44x44Logo.png" = 44
        "Assets\Square150x150Logo.png" = 150
    }
    foreach ($asset in $expectedAssets.GetEnumerator()) {
        $image = [Drawing.Image]::FromFile((Join-Path $unpackRoot $asset.Key))
        try {
            if ($image.Width -ne $asset.Value -or $image.Height -ne $asset.Value) {
                throw "MSIX visual asset has invalid dimensions: $($asset.Key)"
            }
        } finally {
            $image.Dispose()
        }
    }
} finally {
    Remove-Item -LiteralPath $unpackRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if ($RequireSignature) {
    if (-not [bool]$release.signed) {
        throw "The release is marked unsigned."
    }
    if ([string]::IsNullOrWhiteSpace($SignToolPath)) {
        $signTool = Get-Command "signtool.exe" -ErrorAction SilentlyContinue
        if ($null -eq $signTool) {
            throw "signtool.exe is required to verify the signed release."
        }
        $SignToolPath = $signTool.Source
    }
    & $SignToolPath verify /pa /all /v $packagePath
    if ($LASTEXITCODE -ne 0) {
        throw "Authenticode verification failed for $packagePath"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $packagePath
    if ($null -eq $signature.SignerCertificate -or
        -not [string]::Equals($signature.SignerCertificate.Subject, [string]$release.publisher, [StringComparison]::Ordinal)) {
        throw "Package signer subject does not exactly match the release publisher."
    }
}

Write-Host "Local Agent release verified: $Channel $($release.version)"
Write-Host "SHA-256: $actualHash"

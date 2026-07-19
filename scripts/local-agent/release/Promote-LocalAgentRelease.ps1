[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ArtifactsRoot,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')]
    [string]$Version,
    [ValidateSet("pilot")]
    [string]$From = "pilot",
    [ValidateSet("stable")]
    [string]$To = "stable",
    [string]$SignToolPath,
    [switch]$AllowRollback
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Expand-Template([string]$Source, [string]$Destination, [hashtable]$Values) {
    $content = Get-Content -Raw -LiteralPath $Source
    foreach ($entry in $Values.GetEnumerator()) {
        $content = $content.Replace("{{" + $entry.Key + "}}", [string]$entry.Value)
    }
    if ($content -match '\{\{[A-Z0-9_]+\}\}') {
        throw "Unresolved release template placeholder: $($Matches[0])"
    }
    Set-Content -LiteralPath $Destination -Value $content -Encoding UTF8
}

function Publish-AtomicFile([string]$Source, [string]$Destination) {
    $temporary = Join-Path (Split-Path -Parent $Destination) ("." + [IO.Path]::GetFileName($Destination) + "." + [Guid]::NewGuid().ToString("N") + ".tmp")
    $backup = $temporary + ".bak"
    try {
        Copy-Item -LiteralPath $Source -Destination $temporary
        $stream = [IO.File]::Open($temporary, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        try { $stream.Flush($true) } finally { $stream.Dispose() }
        if (Test-Path -LiteralPath $Destination -PathType Leaf) {
            [IO.File]::Replace($temporary, $Destination, $backup, $true)
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        } else {
            [IO.File]::Move($temporary, $Destination)
        }
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    }
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$artifacts = [IO.Path]::GetFullPath((if ([IO.Path]::IsPathRooted($ArtifactsRoot)) { $ArtifactsRoot } else { Join-Path $repoRoot $ArtifactsRoot }))
$sourceDirectory = Join-Path $artifacts $From
$sourceMetadataPath = Join-Path $sourceDirectory "release.json"
$sourceAppInstallerPath = Join-Path $sourceDirectory "LearnBotLocalAgent.appinstaller"
$testScript = Join-Path $PSScriptRoot "Test-LocalAgentRelease.ps1"

& $testScript -ArtifactsRoot $artifacts -Channel $From -SignToolPath $SignToolPath -RequireSignature
$source = Get-Content -Raw -LiteralPath $sourceMetadataPath | ConvertFrom-Json
if ($source.version -ne $Version -or $source.channel -ne $From -or -not [bool]$source.signed) {
    throw "Only the requested signed pilot release can be promoted."
}
$sourceInsecurePrivateNetwork = ($source.PSObject.Properties.Name -contains "insecurePrivateNetwork") -and [bool]$source.insecurePrivateNetwork
$sourceEnterpriseManagedTrust = ($source.PSObject.Properties.Name -contains "enterpriseManagedTrust") -and [bool]$source.enterpriseManagedTrust
if ($sourceInsecurePrivateNetwork) {
    throw "A private-network HTTP pilot cannot be promoted to stable. Publish a new HTTPS package version first."
}
if (-not ($source.PSObject.Properties.Name -contains "productionTrusted") -or -not [bool]$source.productionTrusted) {
    throw "Pilot was not signed with an asserted production-trusted identity and cannot be promoted to stable."
}
$packageUri = $null
if (-not [Uri]::TryCreate([string]$source.packageUrl, [UriKind]::Absolute, [ref]$packageUri) -or
    $packageUri.Scheme -ne [Uri]::UriSchemeHttps -or
    -not [string]::IsNullOrEmpty($packageUri.UserInfo) -or
    -not [string]::IsNullOrEmpty($packageUri.Query) -or
    -not [string]::IsNullOrEmpty($packageUri.Fragment) -or
    $packageUri.AbsolutePath -notmatch '^/downloads/local-agent/releases/([^/]+)/([^/]+\.msix)$') {
    throw "Pilot packageUrl is not a trusted immutable LearnBot release URL."
}
$origin = $packageUri.GetLeftPart([UriPartial]::Authority)
$urlVersion = $Matches[1]
$packageFileName = $Matches[2]
if ($urlVersion -ne $Version) { throw "Pilot package URL version does not match the requested version." }
$packagePath = Join-Path $artifacts "releases\$Version\$packageFileName"
if ((Get-FileHash -LiteralPath $packagePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne ([string]$source.sha256).ToLowerInvariant()) {
    throw "The immutable pilot package hash changed; promotion is blocked."
}

$targetDirectory = Join-Path $artifacts $To
New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
$rollback = $false
$replacedVersion = $null
$currentStablePath = Join-Path $targetDirectory "release.json"
if (Test-Path -LiteralPath $currentStablePath -PathType Leaf) {
    $currentStable = Get-Content -Raw -LiteralPath $currentStablePath | ConvertFrom-Json
    $replacedVersion = [string]$currentStable.version
    if ([Version]$Version -eq [Version]$replacedVersion) {
        if (-not [string]::Equals([string]$currentStable.sha256, [string]$source.sha256, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Stable already references this version with a different hash; immutable identity violation."
        }
    }
    if ([Version]$Version -lt [Version]$replacedVersion) {
        if (-not $AllowRollback) {
            throw "Promotion would downgrade stable from $replacedVersion to $Version. Pass -AllowRollback only for an explicitly approved rollback."
        }
        $rollback = $true
    }
}
$staging = Join-Path $targetDirectory (".promotion-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $staging | Out-Null
try {
    $targetAppInstallerUri = "$origin/downloads/local-agent/$To/LearnBotLocalAgent.appinstaller"
    $stagedAppInstaller = Join-Path $staging "LearnBotLocalAgent.appinstaller"
    Expand-Template (Join-Path $repoRoot "local-agent\Packaging\LearnBotLocalAgent.appinstaller.template.xml") $stagedAppInstaller @{
        VERSION = $Version
        PUBLISHER = [Security.SecurityElement]::Escape([string]$source.publisher)
        PUBLIC_BASE_URL = [Security.SecurityElement]::Escape($origin)
        PACKAGE_URI = [Security.SecurityElement]::Escape([string]$source.packageUrl)
        APPINSTALLER_URI = [Security.SecurityElement]::Escape($targetAppInstallerUri)
    }
    [xml](Get-Content -Raw -LiteralPath $stagedAppInstaller) | Out-Null

    $targetSigningCertificate = $null
    if ($source.PSObject.Properties.Name -contains "signingCertificate" -and $null -ne $source.signingCertificate) {
        $targetSigningCertificate = [ordered]@{
            required = [bool]$source.signingCertificate.required
            subject = [string]$source.signingCertificate.subject
            thumbprint = [string]$source.signingCertificate.thumbprint
            sha256 = [string]$source.signingCertificate.sha256
            path = [string]$source.signingCertificate.path
            url = "$origin/downloads/local-agent/$([string]$source.signingCertificate.path)"
            targetStore = [string]$source.signingCertificate.targetStore
        }
    }

    $targetMetadata = [ordered]@{
        schema = [string]$source.schema
        channel = $To
        version = $Version
        minimumSupportedVersion = [string]$source.minimumSupportedVersion
        platform = [string]$source.platform
        architecture = [string]$source.architecture
        packageUrl = [string]$source.packageUrl
        appInstallerUrl = $targetAppInstallerUri
        sha256 = [string]$source.sha256
        sizeBytes = [long]$source.sizeBytes
        publisher = [string]$source.publisher
        signed = $true
        signingMode = [string]$source.signingMode
        enterpriseManagedTrust = $sourceEnterpriseManagedTrust
        insecurePrivateNetwork = $false
        transportSecurity = "https"
        productionTrusted = $true
        signingCertificate = $targetSigningCertificate
        publishedAt = [string]$source.publishedAt
        promotedAt = [DateTimeOffset]::UtcNow.ToString("o")
        promotionMode = $(if ($rollback) { "rollback" } else { "forward" })
        replacedVersion = $replacedVersion
        promotedBy = [Environment]::UserName
    }
    $stagedMetadata = Join-Path $staging "release.json"
    $targetMetadataJson = $targetMetadata | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($stagedMetadata, $targetMetadataJson, [Text.UTF8Encoding]::new($false))

    $lock = $null
    try {
        $lock = [IO.File]::Open((Join-Path $targetDirectory ".publish.lock"), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        # Re-evaluate the target while holding the channel lock so concurrent promotions
        # cannot bypass forward-only or immutable-version guards.
        if (Test-Path -LiteralPath $currentStablePath -PathType Leaf) {
            $lockedStable = Get-Content -Raw -LiteralPath $currentStablePath | ConvertFrom-Json
            $replacedVersion = [string]$lockedStable.version
            if ([Version]$Version -eq [Version]$replacedVersion) {
                if (-not [string]::Equals([string]$lockedStable.sha256, [string]$source.sha256, [StringComparison]::OrdinalIgnoreCase)) {
                    throw "Stable already references this version with a different hash; immutable identity violation."
                }
                Write-Host "Stable already references Local Agent $Version with the verified hash."
                return
            }
            $rollback = [Version]$Version -lt [Version]$replacedVersion
            if ($rollback -and -not $AllowRollback) {
                throw "Promotion would downgrade stable from $replacedVersion to $Version. Pass -AllowRollback only for an explicitly approved rollback."
            }
        }
        $targetMetadata.replacedVersion = $replacedVersion
        $targetMetadata.promotionMode = $(if ($rollback) { "rollback" } else { "forward" })
        $targetMetadata.promotedAt = [DateTimeOffset]::UtcNow.ToString("o")
        $targetMetadataJson = $targetMetadata | ConvertTo-Json -Depth 4
        [IO.File]::WriteAllText($stagedMetadata, $targetMetadataJson, [Text.UTF8Encoding]::new($false))
        Publish-AtomicFile $stagedAppInstaller (Join-Path $targetDirectory "LearnBotLocalAgent.appinstaller")
        Publish-AtomicFile $stagedMetadata (Join-Path $targetDirectory "release.json")
    } catch [IO.IOException] {
        throw "Another release is publishing the '$To' channel, or atomic replacement is unavailable."
    } finally {
        if ($null -ne $lock) { $lock.Dispose() }
    }
} finally {
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Promoted the immutable Local Agent package $Version from $From to $To."
Write-Host "SHA-256: $($source.sha256)"

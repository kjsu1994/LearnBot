[CmdletBinding()]
param(
    [string]$ServerLanIp,
    [ValidateRange(1, 65535)]
    [int]$Port = 8083,
    [string]$Version,
    [string]$MinimumVersion,
    [switch]$NoBuild,
    [switch]$ConfigureOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-LatestPortableRelease([string]$ReleaseRoot) {
    $candidates = @(
        Get-ChildItem -LiteralPath $ReleaseRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
            ForEach-Object {
                $metadataPath = Join-Path $_.FullName "release.json"
                if (Test-Path -LiteralPath $metadataPath -PathType Leaf) {
                    try {
                        $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
                        if ([bool]$metadata.signed -and [bool]$metadata.portableServerPackage -and
                            [string]$metadata.version -eq $_.Name) {
                            [pscustomobject]@{
                                version = $_.Name
                                parsedVersion = [Version]$_.Name
                                metadata = $metadata
                            }
                        }
                    } catch {
                        Write-Verbose "Ignoring invalid Local Agent release metadata: $metadataPath"
                    }
                }
            }
    )
    return $candidates | Sort-Object parsedVersion -Descending | Select-Object -First 1
}

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose failed with exit code $LASTEXITCODE."
    }
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$artifactsRoot = Join-Path $repoRoot "artifacts\local-agent"
$releaseRoot = Join-Path $artifactsRoot "releases"
$selectedRelease = Resolve-LatestPortableRelease $releaseRoot

if ([string]::IsNullOrWhiteSpace($Version)) {
    if ($null -eq $selectedRelease) {
        throw "No signed portable Local Agent release exists under $releaseRoot. Copy the centrally signed release and public CER to this server first."
    }
    $Version = $selectedRelease.version
} elseif ($Version -notmatch '^\d+\.\d+\.\d+\.\d+$') {
    throw "Version must use the four-part MSIX format, for example 0.2.0.0."
}

$immutableMetadataPath = Join-Path $releaseRoot "$Version\release.json"
if (-not (Test-Path -LiteralPath $immutableMetadataPath -PathType Leaf)) {
    throw "Local Agent release $Version is missing from this server: $immutableMetadataPath"
}
$immutable = Get-Content -Raw -LiteralPath $immutableMetadataPath | ConvertFrom-Json
if (-not [bool]$immutable.signed -or -not [bool]$immutable.portableServerPackage) {
    throw "Local Agent release $Version is not a signed portable-server package."
}
if ([string]::IsNullOrWhiteSpace($MinimumVersion)) {
    $MinimumVersion = [string]$immutable.minimumSupportedVersion
}
if ($MinimumVersion -notmatch '^\d+\.\d+\.\d+\.\d+$' -or [Version]$MinimumVersion -gt [Version]$Version) {
    throw "MinimumVersion must be a four-part version that is not newer than Version."
}

$initializeScript = Join-Path $PSScriptRoot "Initialize-LocalAgentLanHttp.ps1"
$initializeArguments = @{
    Port = $Port
    LatestVersion = $Version
    MinimumVersion = $MinimumVersion
}
if (-not [string]::IsNullOrWhiteSpace($ServerLanIp)) {
    $initializeArguments.ServerLanIp = $ServerLanIp
}
$initializationJson = & $initializeScript @initializeArguments
$deployment = $initializationJson | ConvertFrom-Json

$setReleaseScript = Join-Path $repoRoot "scripts\local-agent\release\Set-LocalAgentServerRelease.ps1"
& $setReleaseScript `
    -Version $Version `
    -PublicBaseUrl $deployment.publicBaseUrl `
    -ArtifactsRoot $artifactsRoot `
    -AllowInsecurePrivateNetwork

$channelMetadataPath = Join-Path $artifactsRoot "pilot\release.json"
$channelRelease = Get-Content -Raw -LiteralPath $channelMetadataPath | ConvertFrom-Json
$composeFiles = @(
    (Join-Path $repoRoot "docker-compose.yml"),
    (Join-Path $repoRoot "docker-compose.local-agent-release.yml"),
    (Join-Path $repoRoot "docker-compose.local-agent-lan-http.yml")
)
$started = $false
if (-not $ConfigureOnly) {
    if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker is not installed or is not available in PATH. Install Docker Desktop and retry."
    }
    $composeArguments = @("compose", "--env-file", [string]$deployment.environmentFile)
    foreach ($composeFile in $composeFiles) {
        if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
            throw "Required Compose file is missing: $composeFile"
        }
        $composeArguments += @("-f", $composeFile)
    }
    Push-Location $repoRoot
    try {
        Invoke-Docker ($composeArguments + @("config"))
        $upArguments = $composeArguments + @("up", "-d")
        if (-not $NoBuild) { $upArguments += "--build" }
        Invoke-Docker $upArguments
    } finally {
        Pop-Location
    }
    $started = $true
}

[ordered]@{
    schema = "learnbot.department-server.start-result.v1"
    configured = $true
    started = $started
    serverOrigin = [string]$deployment.publicBaseUrl
    userPortalUrl = "$($deployment.publicBaseUrl)/settings/local-agent"
    installerUrl = [string]$channelRelease.appInstallerUrl
    signingCertificateUrl = [string]$channelRelease.signingCertificate.url
    signingCertificateThumbprint = [string]$channelRelease.signingCertificate.thumbprint
    managedPcTrustStore = [string]$channelRelease.signingCertificate.targetStore
    version = $Version
    minimumVersion = $MinimumVersion
    environmentFile = [string]$deployment.environmentFile
    composeFiles = $composeFiles
    portablePackage = [bool]$channelRelease.portableServerPackage
    embeddedServerOrigin = $channelRelease.embeddedServerOrigin
} | ConvertTo-Json -Depth 4

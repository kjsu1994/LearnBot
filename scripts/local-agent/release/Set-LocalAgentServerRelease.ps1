[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$PublicBaseUrl,
    [ValidateSet("pilot")]
    [string]$Channel = "pilot",
    [string]$ArtifactsRoot,
    [string]$SigningCertificatePath,
    [switch]$AllowInsecurePrivateNetwork
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-Rfc1918Ipv4Literal([string]$HostName) {
    $address = $null
    if (-not [Net.IPAddress]::TryParse($HostName, [ref]$address)) { return $false }
    $bytes = $address.GetAddressBytes()
    return $bytes.Length -eq 4 -and (
        $bytes[0] -eq 10 -or
        ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
        ($bytes[0] -eq 192 -and $bytes[1] -eq 168)
    )
}

function Resolve-PublicOrigin([string]$Value, [bool]$AllowPrivateHttp) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        ($uri.Scheme -ne [Uri]::UriSchemeHttps -and $uri.Scheme -ne [Uri]::UriSchemeHttp) -or
        [string]::IsNullOrWhiteSpace($uri.Host) -or
        -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        $uri.AbsolutePath -ne "/" -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw "PublicBaseUrl must be an HTTP(S) origin without a path, query, user info, or fragment."
    }
    if ($uri.Scheme -eq [Uri]::UriSchemeHttp -and
        (-not $AllowPrivateHttp -or -not (Test-Rfc1918Ipv4Literal $uri.Host))) {
        throw "HTTP release origins require -AllowInsecurePrivateNetwork and an RFC1918 IPv4 literal."
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

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
    $directory = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $temporary = Join-Path $directory ("." + [IO.Path]::GetFileName($Destination) + "." + [Guid]::NewGuid().ToString("N") + ".tmp")
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

$origin = Resolve-PublicOrigin $PublicBaseUrl ([bool]$AllowInsecurePrivateNetwork)
$insecurePrivateNetwork = ([Uri]$origin).Scheme -eq [Uri]::UriSchemeHttp
if ($AllowInsecurePrivateNetwork -and -not $insecurePrivateNetwork) {
    throw "-AllowInsecurePrivateNetwork is valid only for an RFC1918 HTTP origin."
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
if ([string]::IsNullOrWhiteSpace($ArtifactsRoot)) {
    $ArtifactsRoot = Join-Path $repoRoot "artifacts\local-agent"
} elseif (-not [IO.Path]::IsPathRooted($ArtifactsRoot)) {
    $ArtifactsRoot = Join-Path $repoRoot $ArtifactsRoot
}
$artifacts = [IO.Path]::GetFullPath($ArtifactsRoot)
$releaseDirectory = Join-Path $artifacts ("releases\" + $Version)
$immutableMetadataPath = Join-Path $releaseDirectory "release.json"
if (-not (Test-Path -LiteralPath $immutableMetadataPath -PathType Leaf)) {
    throw "The signed portable release metadata does not exist: $immutableMetadataPath"
}
$immutable = Get-Content -Raw -LiteralPath $immutableMetadataPath | ConvertFrom-Json
if ($immutable.schema -ne "learnbot.local-agent.release.v1" -or
    [string]$immutable.version -ne $Version -or
    -not [bool]$immutable.signed -or
    -not ($immutable.PSObject.Properties.Name -contains "portableServerPackage") -or
    -not [bool]$immutable.portableServerPackage) {
    throw "Release $Version is not a signed portable-server package."
}

$packageFileName = "LearnBotLocalAgent_${Version}_x64.msix"
$packagePath = Join-Path $releaseDirectory $packageFileName
if (-not (Test-Path -LiteralPath $packagePath -PathType Leaf)) {
    throw "The immutable signed package does not exist: $packagePath"
}
$hash = (Get-FileHash -LiteralPath $packagePath -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $packagePath).Length
if ($hash -ne ([string]$immutable.sha256).ToLowerInvariant() -or $size -ne [long]$immutable.sizeBytes) {
    throw "The immutable package hash or size does not match its release metadata."
}
$signature = Get-AuthenticodeSignature -LiteralPath $packagePath
if ($null -eq $signature.SignerCertificate -or
    -not [string]::Equals($signature.SignerCertificate.Subject, [string]$immutable.publisher, [StringComparison]::Ordinal)) {
    throw "The immutable package signature does not match its publisher metadata."
}
$signatureStatusAccepted = $signature.Status.ToString() -eq "Valid" -or (
    $signature.Status.ToString() -eq "UnknownError" -and
    $signature.StatusMessage -match '(?i)root certificate.*not trusted by the trust provider'
)
if (-not $signatureStatusAccepted) {
    throw "The immutable package signature is missing, damaged, or has a hash mismatch: $($signature.Status)"
}
if ($null -eq $signature.TimeStamperCertificate) {
    throw "The immutable package does not contain a trusted release timestamp."
}

$signerCertificate = $signature.SignerCertificate
$trustDirectory = Join-Path $artifacts "trust"
if ([string]::IsNullOrWhiteSpace($SigningCertificatePath)) {
    $certificateSourcePath = Get-ChildItem -LiteralPath $trustDirectory -Filter "*.cer" -File -ErrorAction SilentlyContinue |
        Where-Object {
            try {
                $candidate = New-Object Security.Cryptography.X509Certificates.X509Certificate2($_.FullName)
                [string]::Equals($candidate.Thumbprint, $signerCertificate.Thumbprint, [StringComparison]::OrdinalIgnoreCase)
            } catch {
                $false
            }
        } |
        Select-Object -First 1 -ExpandProperty FullName
    if ([string]::IsNullOrWhiteSpace($certificateSourcePath)) {
        throw "The public certificate matching the MSIX signer is missing from $trustDirectory. Copy the release-runner CER with the signed package."
    }
} else {
    $certificateSourcePath = if ([IO.Path]::IsPathRooted($SigningCertificatePath)) {
        [IO.Path]::GetFullPath($SigningCertificatePath)
    } else {
        [IO.Path]::GetFullPath((Join-Path $repoRoot $SigningCertificatePath))
    }
    if (-not (Test-Path -LiteralPath $certificateSourcePath -PathType Leaf)) {
        throw "The public signing certificate does not exist: $certificateSourcePath"
    }
}
$publicCertificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2($certificateSourcePath)
if (-not [string]::Equals($publicCertificate.Thumbprint, $signerCertificate.Thumbprint, [StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals($publicCertificate.Subject, $signerCertificate.Subject, [StringComparison]::Ordinal)) {
    throw "The public CER does not match the certificate that signed the immutable MSIX."
}
$codeSigningEku = $publicCertificate.Extensions |
    Where-Object { $_.Oid.Value -eq "2.5.29.37" } |
    Select-Object -First 1
if ($null -eq $codeSigningEku -or
    @($codeSigningEku.EnhancedKeyUsages | ForEach-Object { $_.Value }) -notcontains "1.3.6.1.5.5.7.3.3") {
    throw "The public certificate matching the MSIX does not contain the Code Signing EKU."
}
$certificateFileName = "LearnBotLocalAgentSigning_$($publicCertificate.Thumbprint.ToUpperInvariant()).cer"
$certificateRelativePath = "trust/$certificateFileName"
$certificateDestinationPath = Join-Path $trustDirectory $certificateFileName
$certificateUri = "$origin/downloads/local-agent/$certificateRelativePath"

$channelDirectory = Join-Path $artifacts $Channel
$stagingDirectory = Join-Path $env:TEMP ("learnbot-server-release-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Force -Path $stagingDirectory | Out-Null
    $stagedCertificate = Join-Path $stagingDirectory $certificateFileName
    Copy-Item -LiteralPath $certificateSourcePath -Destination $stagedCertificate
    $certificateHash = (Get-FileHash -LiteralPath $stagedCertificate -Algorithm SHA256).Hash.ToLowerInvariant()
    $packageUri = "$origin/downloads/local-agent/releases/$Version/$packageFileName"
    $appInstallerUri = "$origin/downloads/local-agent/$Channel/LearnBotLocalAgent.appinstaller"
    $stagedAppInstaller = Join-Path $stagingDirectory "LearnBotLocalAgent.appinstaller"
    $template = Join-Path $repoRoot "local-agent\Packaging\LearnBotLocalAgent.appinstaller.template.xml"
    Expand-Template $template $stagedAppInstaller @{
        VERSION = $Version
        PUBLISHER = [Security.SecurityElement]::Escape([string]$immutable.publisher)
        PUBLIC_BASE_URL = [Security.SecurityElement]::Escape($origin)
        PACKAGE_URI = [Security.SecurityElement]::Escape($packageUri)
        APPINSTALLER_URI = [Security.SecurityElement]::Escape($appInstallerUri)
    }
    [xml](Get-Content -Raw -LiteralPath $stagedAppInstaller) | Out-Null

    $metadata = [ordered]@{
        schema = "learnbot.local-agent.release.v1"
        channel = $Channel
        version = $Version
        minimumSupportedVersion = [string]$immutable.minimumSupportedVersion
        platform = "windows"
        architecture = "x64"
        packageUrl = $packageUri
        appInstallerUrl = $appInstallerUri
        packagePath = "releases/$Version/$packageFileName"
        appInstallerPath = "$Channel/LearnBotLocalAgent.appinstaller"
        sha256 = $hash
        sizeBytes = $size
        publisher = [string]$immutable.publisher
        signed = $true
        signingMode = [string]$immutable.signingMode
        enterpriseManagedTrust = [bool]$immutable.enterpriseManagedTrust
        portableServerPackage = $true
        embeddedServerOrigin = $null
        insecurePrivateNetwork = $insecurePrivateNetwork
        transportSecurity = $(if ($insecurePrivateNetwork) { "http-private-network" } else { "https" })
        productionTrusted = -not $insecurePrivateNetwork -and [bool]$immutable.productionTrusted
        signingCertificate = [ordered]@{
            required = [bool]$immutable.enterpriseManagedTrust
            subject = $publicCertificate.Subject
            thumbprint = $publicCertificate.Thumbprint.ToUpperInvariant()
            sha256 = $certificateHash
            path = $certificateRelativePath
            url = $certificateUri
            targetStore = "Cert:\LocalMachine\TrustedPeople"
        }
        publishedAt = [DateTimeOffset]::UtcNow.ToString("o")
    }
    $stagedMetadata = Join-Path $stagingDirectory "release.json"
    $metadataJson = $metadata | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($stagedMetadata, $metadataJson, [Text.UTF8Encoding]::new($false))

    New-Item -ItemType Directory -Force -Path $trustDirectory | Out-Null
    Publish-AtomicFile $stagedCertificate $certificateDestinationPath
    New-Item -ItemType Directory -Force -Path $channelDirectory | Out-Null
    $lockPath = Join-Path $channelDirectory ".publish.lock"
    $lock = $null
    try {
        $lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        Publish-AtomicFile $stagedAppInstaller (Join-Path $channelDirectory "LearnBotLocalAgent.appinstaller")
        Publish-AtomicFile $stagedMetadata (Join-Path $channelDirectory "release.json")
    } finally {
        if ($null -ne $lock) { $lock.Dispose() }
    }
} finally {
    Remove-Item -LiteralPath $stagingDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Configured Local Agent $Version for server $origin without rebuilding or re-signing the MSIX."

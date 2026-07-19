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
$insecurePrivateNetwork = ($release.PSObject.Properties.Name -contains "insecurePrivateNetwork") -and [bool]$release.insecurePrivateNetwork
$enterpriseManagedTrust = ($release.PSObject.Properties.Name -contains "enterpriseManagedTrust") -and [bool]$release.enterpriseManagedTrust
$packageUri = $null
$appInstallerUri = $null
if (-not [Uri]::TryCreate([string]$release.packageUrl, [UriKind]::Absolute, [ref]$packageUri) -or
    -not [Uri]::TryCreate([string]$release.appInstallerUrl, [UriKind]::Absolute, [ref]$appInstallerUri) -or
    $packageUri.GetLeftPart([UriPartial]::Authority) -ne $appInstallerUri.GetLeftPart([UriPartial]::Authority)) {
    throw "Release package and App Installer URLs must be absolute and same-origin."
}
if ($insecurePrivateNetwork) {
    if ($Channel -ne "pilot" -or
        $packageUri.Scheme -ne [Uri]::UriSchemeHttp -or
        -not (Test-Rfc1918Ipv4Literal $packageUri.Host) -or
        [string]$release.transportSecurity -ne "http-private-network") {
        throw "Insecure private-network metadata is valid only for an RFC1918 HTTP pilot release."
    }
    if ([bool]$release.signed -and -not $enterpriseManagedTrust) {
        throw "A signed private-network release must assert enterprise-managed signing trust."
    }
} elseif ($packageUri.Scheme -ne [Uri]::UriSchemeHttps -or $appInstallerUri.Scheme -ne [Uri]::UriSchemeHttps) {
    throw "Non-private release URLs must use HTTPS."
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

$packageSignature = if ([bool]$release.signed) { Get-AuthenticodeSignature -LiteralPath $packagePath } else { $null }
$hasSigningCertificateMetadata = $release.PSObject.Properties.Name -contains "signingCertificate" -and
    $null -ne $release.signingCertificate
if ($enterpriseManagedTrust -and -not $hasSigningCertificateMetadata) {
    throw "Enterprise-managed releases must publish the exact public certificate required by managed PCs."
}
if ($hasSigningCertificateMetadata) {
    $certificateMetadata = $release.signingCertificate
    $certificatePath = [string]$certificateMetadata.path
    if ($certificatePath -notmatch '^trust/[A-Za-z0-9_.-]+\.cer$') {
        throw "Signing certificate metadata has an unsafe artifact path."
    }
    $certificateUri = $null
    if (-not [Uri]::TryCreate([string]$certificateMetadata.url, [UriKind]::Absolute, [ref]$certificateUri) -or
        $certificateUri.GetLeftPart([UriPartial]::Authority) -ne $packageUri.GetLeftPart([UriPartial]::Authority) -or
        $certificateUri.AbsolutePath -ne "/downloads/local-agent/$certificatePath") {
        throw "Signing certificate URL must be same-origin and match its artifact path."
    }
    $certificateLocalPath = Join-Path $artifacts ($certificatePath.Replace('/', [IO.Path]::DirectorySeparatorChar))
    if (-not (Test-Path -LiteralPath $certificateLocalPath -PathType Leaf)) {
        throw "Published signing certificate is missing: $certificateLocalPath"
    }
    $certificateHash = (Get-FileHash -LiteralPath $certificateLocalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($certificateHash -ne ([string]$certificateMetadata.sha256).ToLowerInvariant()) {
        throw "Published signing certificate hash does not match release.json."
    }
    $publicCertificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2($certificateLocalPath)
    if (-not [string]::Equals($publicCertificate.Subject, [string]$release.publisher, [StringComparison]::Ordinal) -or
        -not [string]::Equals($publicCertificate.Subject, [string]$certificateMetadata.subject, [StringComparison]::Ordinal) -or
        -not [string]::Equals($publicCertificate.Thumbprint, [string]$certificateMetadata.thumbprint, [StringComparison]::OrdinalIgnoreCase) -or
        $null -eq $packageSignature -or $null -eq $packageSignature.SignerCertificate -or
        -not [string]::Equals($publicCertificate.Thumbprint, $packageSignature.SignerCertificate.Thumbprint, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Published CER, MSIX signer, and release certificate metadata do not match exactly."
    }
    if ([bool]$certificateMetadata.required -and [string]$certificateMetadata.targetStore -ne 'Cert:\LocalMachine\TrustedPeople') {
        throw "Managed PC trust must target the Local Computer Trusted People store."
    }
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
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $verifyOutput = & $SignToolPath verify /pa /all /v $packagePath 2>&1
        $verifyExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Write-Host ($verifyOutput -join [Environment]::NewLine)
    $signature = $packageSignature
    $managedTrustPending = $enterpriseManagedTrust -and
        $verifyExitCode -ne 0 -and
        $signature.Status.ToString() -eq "UnknownError" -and
        $signature.StatusMessage -match '(?i)root certificate.*not trusted by the trust provider' -and
        ($verifyOutput -join [Environment]::NewLine) -notmatch '(?i)hash mismatch|not digitally signed|invalid digest'
    if ($verifyExitCode -ne 0 -and -not $managedTrustPending) {
        throw "Authenticode verification failed for $packagePath"
    }
    if ($null -eq $signature.SignerCertificate -or
        -not [string]::Equals($signature.SignerCertificate.Subject, [string]$release.publisher, [StringComparison]::Ordinal)) {
        throw "Package signer subject does not exactly match the release publisher."
    }
}

Write-Host "Local Agent release verified: $Channel $($release.version)"
Write-Host "SHA-256: $actualHash"

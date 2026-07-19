[CmdletBinding(DefaultParameterSetName = "CertificateStore")]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')]
    [string]$Version,
    [Parameter(Mandatory = $true)]
    [string]$PublicBaseUrl,
    [Parameter(Mandatory = $true)]
    [string]$Publisher,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')]
    [string]$MinimumSupportedVersion,
    [ValidateSet("pilot", "stable")]
    [string]$Channel = "pilot",
    [string]$ArtifactsRoot,
    [string]$WindowsSdkBin,
    [string]$DotNetPath = "dotnet",
    [string]$TimestampUrl = "http://timestamp.acs.microsoft.com",
    [Parameter(Mandatory = $true, ParameterSetName = "CertificateStore")]
    [string]$CertificateThumbprint,
    [Parameter(Mandatory = $true, ParameterSetName = "ArtifactSigning")]
    [string]$ArtifactSigningDlibPath,
    [Parameter(Mandatory = $true, ParameterSetName = "ArtifactSigning")]
    [string]$ArtifactSigningMetadataPath,
    [Parameter(Mandatory = $true, ParameterSetName = "UnsignedTest")]
    [switch]$UnsignedTest,
    [switch]$AssertPublicTrustCertificate,
    [switch]$EnterpriseManagedTrust,
    [switch]$AllowInsecurePrivateNetwork,
    [switch]$PortableServerPackage,
    [switch]$SkipTimestamp,
    [switch]$ValidateConfigurationOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Find-SdkTool([string]$Name) {
    if (-not [string]::IsNullOrWhiteSpace($WindowsSdkBin)) {
        $candidate = Join-Path $WindowsSdkBin $Name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
        throw "$Name was not found under WindowsSdkBin: $WindowsSdkBin"
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $sdkRoot = Join-Path ([Environment]::GetFolderPath("ProgramFilesX86")) "Windows Kits\10\bin"
    $candidate = Get-ChildItem -LiteralPath $sdkRoot -Filter $Name -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.DirectoryName -match '[\\/]x64$' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -ne $candidate) { return $candidate.FullName }
    throw "$Name was not found. Install the Windows SDK packaging tools or pass -WindowsSdkBin."
}

function Expand-Template([string]$Source, [string]$Destination, [hashtable]$Values) {
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Required release template does not exist: $Source"
    }
    $content = Get-Content -Raw -LiteralPath $Source
    foreach ($entry in $Values.GetEnumerator()) {
        $content = $content.Replace("{{" + $entry.Key + "}}", [string]$entry.Value)
    }
    if ($content -match '\{\{[A-Z0-9_]+\}\}') {
        throw "Unresolved release template placeholder in ${Source}: $($Matches[0])"
    }
    Set-Content -LiteralPath $Destination -Value $content -Encoding UTF8
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code ${LASTEXITCODE}: $Executable $($Arguments -join ' ')"
    }
}

function Invoke-Sign([string]$SignTool, [string]$Path) {
    if ($UnsignedTest) { return }
    if ($PSCmdlet.ParameterSetName -eq "ArtifactSigning") {
        $arguments = @("sign", "/v", "/fd", "SHA256")
        if (-not $SkipTimestamp) { $arguments += @("/td", "SHA256", "/tr", $TimestampUrl) }
        $arguments += @("/dlib", $ArtifactSigningDlibPath, "/dmdf", $ArtifactSigningMetadataPath, $Path)
        Invoke-Checked $SignTool $arguments
        return
    }
    $thumbprint = ($CertificateThumbprint -replace '\s', '').ToUpperInvariant()
    $arguments = @("sign", "/v", "/fd", "SHA256", "/sha1", $thumbprint)
    if (-not $SkipTimestamp) { $arguments += @("/tr", $TimestampUrl, "/td", "SHA256") }
    $arguments += $Path
    Invoke-Checked $SignTool $arguments
}

function Invoke-PublishProject(
    [string]$Project,
    [string]$Output,
    [string]$Origin,
    [bool]$InsecurePrivateNetwork
) {
    New-Item -ItemType Directory -Force -Path $Output | Out-Null
    Invoke-Checked $DotNetPath @(
        "publish", $Project, "--configuration", "Release", "--runtime", "win-x64",
        "--self-contained", "true", "--output", $Output,
        "-p:PublishSingleFile=true", "-p:IncludeNativeLibrariesForSelfExtract=true",
        "-p:DebugType=embedded", "-p:Version=$Version", "-p:FileVersion=$Version",
        "-p:AssemblyVersion=$Version", "-p:ContinuousIntegrationBuild=true",
        "-p:LearnBotPublicBaseUrl=$Origin",
        "-p:LearnBotAllowInsecurePrivateNetwork=$($InsecurePrivateNetwork.ToString().ToLowerInvariant())"
    )
}

function Publish-AtomicFile([string]$Source, [string]$Destination) {
    $directory = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $temporary = Join-Path $directory ("." + [System.IO.Path]::GetFileName($Destination) + "." + [Guid]::NewGuid().ToString("N") + ".tmp")
    $backup = $temporary + ".bak"
    try {
        Copy-Item -LiteralPath $Source -Destination $temporary
        $stream = [System.IO.File]::Open($temporary, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        try { $stream.Flush($true) } finally { $stream.Dispose() }
        if (Test-Path -LiteralPath $Destination -PathType Leaf) {
            [System.IO.File]::Replace($temporary, $Destination, $backup, $true)
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        } else {
            [System.IO.File]::Move($temporary, $Destination)
        }
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    }
}

function Export-ScaledPng([string]$Source, [string]$Destination, [int]$Size) {
    Add-Type -AssemblyName System.Drawing
    $sourceImage = [System.Drawing.Image]::FromFile($Source)
    $bitmap = $null
    try {
        $bitmap = [System.Drawing.Bitmap]::new($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.Clear([System.Drawing.Color]::Transparent)
            $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
            $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.DrawImage($sourceImage, 0, 0, $Size, $Size)
        } finally {
            $graphics.Dispose()
        }
        $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        if ($null -ne $bitmap) { $bitmap.Dispose() }
        $sourceImage.Dispose()
    }
}

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
        ($uri.AbsolutePath -ne "/") -or
        -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw "PublicBaseUrl must be an HTTP(S) origin without a path, query, user info, or fragment."
    }
    if ($uri.Scheme -eq [Uri]::UriSchemeHttp -and
        (-not $AllowPrivateHttp -or -not (Test-Rfc1918Ipv4Literal $uri.Host))) {
        throw "HTTP release origins require -AllowInsecurePrivateNetwork and an RFC1918 IPv4 literal. Host names and public IP addresses are blocked."
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

$origin = Resolve-PublicOrigin $PublicBaseUrl ([bool]$AllowInsecurePrivateNetwork)
$insecurePrivateNetwork = ([Uri]$origin).Scheme -eq [Uri]::UriSchemeHttp
if ($AllowInsecurePrivateNetwork -and -not $insecurePrivateNetwork) {
    throw "-AllowInsecurePrivateNetwork is valid only for an RFC1918 HTTP origin. Omit it for HTTPS releases."
}
if ($insecurePrivateNetwork -and $Channel -ne "pilot") {
    throw "Enterprise private-network HTTP packages are restricted to the pilot channel and cannot be published as stable."
}
if ($PortableServerPackage -and $Channel -ne "pilot") {
    throw "Portable server packages are restricted to the pilot channel."
}
if ($PortableServerPackage -and -not $UnsignedTest -and -not $EnterpriseManagedTrust) {
    throw "Signed portable server packages require -EnterpriseManagedTrust."
}
if ($EnterpriseManagedTrust -and $AssertPublicTrustCertificate) {
    throw "Choose either -EnterpriseManagedTrust or -AssertPublicTrustCertificate, not both."
}
if ($EnterpriseManagedTrust -and $PSCmdlet.ParameterSetName -ne "CertificateStore") {
    throw "-EnterpriseManagedTrust requires a code-signing certificate from the Windows certificate store."
}
if ($insecurePrivateNetwork -and -not $UnsignedTest -and -not $EnterpriseManagedTrust) {
    throw "Signed private-network HTTP packages require -EnterpriseManagedTrust to assert that IT deploys the signing trust chain to managed PCs."
}
if ([Version]$MinimumSupportedVersion -gt [Version]$Version) {
    throw "MinimumSupportedVersion cannot be newer than Version."
}
if ($ValidateConfigurationOnly) {
    [ordered]@{
        valid = $true
        version = $Version
        minimumSupportedVersion = $MinimumSupportedVersion
        channel = $Channel
        publicBaseUrl = $origin
        portableServerPackage = [bool]$PortableServerPackage
        insecurePrivateNetwork = $insecurePrivateNetwork
        enterpriseManagedTrust = [bool]$EnterpriseManagedTrust
        signingMode = $PSCmdlet.ParameterSetName
    } | ConvertTo-Json
    return
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$packagingRoot = Join-Path $repoRoot "local-agent\Packaging"
$projectPath = Join-Path $repoRoot "local-agent\LearnBot.LocalAgent.csproj"
$setupProjectPath = Join-Path $repoRoot "local-agent\Setup\LearnBot.LocalAgent.Setup.csproj"
$hostProjectPath = Join-Path $repoRoot "local-agent\StartupHost\LearnBot.LocalAgent.StartupHost.csproj"
$manifestTemplate = Join-Path $packagingRoot "Package.appxmanifest.template.xml"
$appInstallerTemplate = Join-Path $packagingRoot "LearnBotLocalAgent.appinstaller.template.xml"
if ([string]::IsNullOrWhiteSpace($ArtifactsRoot)) {
    $ArtifactsRoot = Join-Path $repoRoot "artifacts\local-agent"
} elseif (-not [System.IO.Path]::IsPathRooted($ArtifactsRoot)) {
    $ArtifactsRoot = Join-Path $repoRoot $ArtifactsRoot
}
$artifacts = [System.IO.Path]::GetFullPath($ArtifactsRoot)
$releaseDirectory = Join-Path $artifacts ("releases\" + $Version)
$channelDirectory = Join-Path $artifacts $Channel
$stagingBoundary = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".tmp\local-agent-release"))
$stagingRoot = Join-Path $stagingBoundary $Version
$packageRoot = Join-Path $stagingRoot "package"

if ($UnsignedTest -and $Channel -eq "stable") {
    throw "Unsigned test packages cannot be published to the stable channel."
}
if (Test-Path -LiteralPath $releaseDirectory) {
    throw "Release $Version already exists and is immutable. Publish a new four-part version or promote the verified pilot package."
}
if (-not ([System.IO.Path]::GetFullPath($stagingRoot)).StartsWith($stagingBoundary, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe release staging path: $stagingRoot"
}
if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $packageRoot | Out-Null

$makeAppx = Find-SdkTool "makeappx.exe"
$signTool = if ($UnsignedTest) { "" } else { Find-SdkTool "signtool.exe" }
if ($PSCmdlet.ParameterSetName -eq "ArtifactSigning") {
    if (-not (Test-Path -LiteralPath $ArtifactSigningDlibPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $ArtifactSigningMetadataPath -PathType Leaf)) {
        throw "Artifact Signing dlib and metadata files are required."
    }
}
if ($PSCmdlet.ParameterSetName -eq "CertificateStore") {
    $normalizedThumbprint = ($CertificateThumbprint -replace '\s', '').ToUpperInvariant()
    $certificate = @(
        Get-ChildItem -LiteralPath "Cert:\CurrentUser\My\$normalizedThumbprint" -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath "Cert:\LocalMachine\My\$normalizedThumbprint" -ErrorAction SilentlyContinue
    ) | Select-Object -First 1
    if ($null -eq $certificate) {
        throw "The signing certificate was not found by thumbprint in CurrentUser or LocalMachine certificate stores."
    }
    if (-not [string]::Equals($certificate.Subject, $Publisher, [StringComparison]::Ordinal)) {
        throw "Publisher must exactly match the signing certificate subject. Certificate='$($certificate.Subject)' Publisher='$Publisher'."
    }
    if (-not $certificate.HasPrivateKey) {
        throw "The selected signing certificate does not have an accessible private key."
    }
    $enhancedKeyUsage = $certificate.Extensions |
        Where-Object { $_.Oid.Value -eq "2.5.29.37" } |
        Select-Object -First 1
    $codeSigningAllowed = $null -ne $enhancedKeyUsage -and @($enhancedKeyUsage.EnhancedKeyUsages | ForEach-Object { $_.Value }) -contains "1.3.6.1.5.5.7.3.3"
    if (-not $codeSigningAllowed) {
        throw "The selected certificate does not contain the Code Signing EKU."
    }
    if ($Channel -eq "stable" -and -not $AssertPublicTrustCertificate -and -not $EnterpriseManagedTrust) {
        throw "Stable CertificateStore publishing requires either -AssertPublicTrustCertificate or -EnterpriseManagedTrust."
    }
    if ($AssertPublicTrustCertificate) {
        $chain = [Security.Cryptography.X509Certificates.X509Chain]::new()
        try {
            $chain.ChainPolicy.RevocationMode = [Security.Cryptography.X509Certificates.X509RevocationMode]::Online
            $chain.ChainPolicy.RevocationFlag = [Security.Cryptography.X509Certificates.X509RevocationFlag]::EntireChain
            $chain.ChainPolicy.VerificationFlags = [Security.Cryptography.X509Certificates.X509VerificationFlags]::NoFlag
            $chain.ChainPolicy.UrlRetrievalTimeout = [TimeSpan]::FromSeconds(30)
            if (-not $chain.Build($certificate)) {
                $chainErrors = ($chain.ChainStatus | ForEach-Object { $_.StatusInformation.Trim() }) -join "; "
                throw "The asserted production certificate chain or revocation check failed: $chainErrors"
            }
            $rootCertificate = $chain.ChainElements[$chain.ChainElements.Count - 1].Certificate
            if ([string]::Equals($rootCertificate.Thumbprint, $certificate.Thumbprint, [StringComparison]::OrdinalIgnoreCase)) {
                throw "A self-signed certificate cannot be asserted as public trust for stable distribution."
            }
        } finally {
            $chain.Dispose()
        }
    }
}

$binaryOrigin = if ($PortableServerPackage) { "https://learnbot.portable.invalid" } else { $origin }
$binaryAllowsInsecurePrivateNetwork = $insecurePrivateNetwork -or [bool]$PortableServerPackage
Invoke-PublishProject $projectPath (Join-Path $packageRoot "app") $binaryOrigin $binaryAllowsInsecurePrivateNetwork
Invoke-PublishProject $setupProjectPath (Join-Path $packageRoot "setup") $binaryOrigin $binaryAllowsInsecurePrivateNetwork
Invoke-PublishProject $hostProjectPath (Join-Path $packageRoot "host") $binaryOrigin $binaryAllowsInsecurePrivateNetwork

$packageFileName = "LearnBotLocalAgent_${Version}_x64.msix"
$packageUri = "$origin/downloads/local-agent/releases/$Version/$packageFileName"
$appInstallerUri = "$origin/downloads/local-agent/$Channel/LearnBotLocalAgent.appinstaller"
$xmlPublisher = [System.Security.SecurityElement]::Escape($Publisher)
Expand-Template $manifestTemplate (Join-Path $packageRoot "AppxManifest.xml") @{
    VERSION = $Version
    PUBLISHER = $xmlPublisher
    PUBLIC_BASE_URL = [System.Security.SecurityElement]::Escape($origin)
}
$assetsDestination = Join-Path $packageRoot "Assets"
$assetsSource = Join-Path $packagingRoot "Assets"
if (Test-Path -LiteralPath $assetsSource -PathType Container) {
    Copy-Item -LiteralPath $assetsSource -Destination $assetsDestination -Recurse -Force
} else {
    $fallbackLogo = Join-Path $repoRoot "frontend\public\LearnBot_Logo_mini.png"
    if (-not (Test-Path -LiteralPath $fallbackLogo -PathType Leaf)) {
        throw "MSIX visual assets are missing and no LearnBot fallback logo exists."
    }
    New-Item -ItemType Directory -Force -Path $assetsDestination | Out-Null
    $assetSizes = @{
        "StoreLogo.png" = 50
        "Square44x44Logo.png" = 44
        "Square150x150Logo.png" = 150
    }
    foreach ($asset in $assetSizes.GetEnumerator()) {
        Export-ScaledPng $fallbackLogo (Join-Path $assetsDestination $asset.Key) $asset.Value
    }
}

Get-ChildItem -LiteralPath $packageRoot -Recurse -File |
    Where-Object { $_.Extension -in @(".exe", ".dll") } |
    ForEach-Object { Invoke-Sign $signTool $_.FullName }

$stagedPackage = Join-Path $stagingRoot $packageFileName
Invoke-Checked $makeAppx @("pack", "/d", $packageRoot, "/p", $stagedPackage, "/o")
Invoke-Sign $signTool $stagedPackage
if (-not $UnsignedTest) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $verifyOutput = & $signTool verify /pa /all /v $stagedPackage 2>&1
        $verifyExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    Write-Host ($verifyOutput -join [Environment]::NewLine)
    $signature = Get-AuthenticodeSignature -LiteralPath $stagedPackage
    $managedTrustPending = $EnterpriseManagedTrust -and
        $verifyExitCode -ne 0 -and
        $signature.Status.ToString() -eq "UnknownError" -and
        $signature.StatusMessage -match '(?i)root certificate.*not trusted by the trust provider' -and
        ($verifyOutput -join [Environment]::NewLine) -notmatch '(?i)hash mismatch|not digitally signed|invalid digest'
    if ($verifyExitCode -ne 0 -and -not $managedTrustPending) {
        throw "Authenticode verification failed for $stagedPackage"
    }
    if ($null -eq $signature.SignerCertificate -or
        -not [string]::Equals($signature.SignerCertificate.Subject, $Publisher, [StringComparison]::Ordinal)) {
        throw "The signed package certificate subject does not exactly match Publisher."
    }
    if (-not $SkipTimestamp -and $null -eq $signature.TimeStamperCertificate) {
        throw "The signed package does not contain the required RFC 3161 timestamp."
    }
}

$stagedPublicCertificate = $null
$signingCertificateMetadata = $null
if (-not $UnsignedTest) {
    $publicCertificate = $signature.SignerCertificate
    $certificateFileName = "LearnBotLocalAgentSigning_$($publicCertificate.Thumbprint.ToUpperInvariant()).cer"
    $certificateRelativePath = "trust/$certificateFileName"
    $certificateUri = "$origin/downloads/local-agent/$certificateRelativePath"
    $stagedPublicCertificate = Join-Path $stagingRoot $certificateFileName
    Export-Certificate -Cert $publicCertificate -FilePath $stagedPublicCertificate -Force | Out-Null
    $certificateHash = (Get-FileHash -LiteralPath $stagedPublicCertificate -Algorithm SHA256).Hash.ToLowerInvariant()
    $signingCertificateMetadata = [ordered]@{
        required = [bool]$EnterpriseManagedTrust
        subject = $publicCertificate.Subject
        thumbprint = $publicCertificate.Thumbprint.ToUpperInvariant()
        sha256 = $certificateHash
        path = $certificateRelativePath
        url = $certificateUri
        targetStore = $(if ($EnterpriseManagedTrust) { "Cert:\LocalMachine\TrustedPeople" } else { $null })
    }
}

$stagedAppInstaller = Join-Path $stagingRoot "LearnBotLocalAgent.appinstaller"
Expand-Template $appInstallerTemplate $stagedAppInstaller @{
    VERSION = $Version
    PUBLISHER = $xmlPublisher
    PUBLIC_BASE_URL = [System.Security.SecurityElement]::Escape($origin)
    PACKAGE_URI = [System.Security.SecurityElement]::Escape($packageUri)
    APPINSTALLER_URI = [System.Security.SecurityElement]::Escape($appInstallerUri)
}
[xml](Get-Content -Raw -LiteralPath $stagedAppInstaller) | Out-Null

$hash = (Get-FileHash -LiteralPath $stagedPackage -Algorithm SHA256).Hash.ToLowerInvariant()
$size = (Get-Item -LiteralPath $stagedPackage).Length
$metadata = [ordered]@{
    schema = "learnbot.local-agent.release.v1"
    channel = $Channel
    version = $Version
    minimumSupportedVersion = $MinimumSupportedVersion
    platform = "windows"
    architecture = "x64"
    packageUrl = $packageUri
    appInstallerUrl = $appInstallerUri
    packagePath = "releases/$Version/$packageFileName"
    appInstallerPath = "$Channel/LearnBotLocalAgent.appinstaller"
    sha256 = $hash
    sizeBytes = $size
    publisher = $Publisher
    signed = -not [bool]$UnsignedTest
    signingMode = $PSCmdlet.ParameterSetName
    enterpriseManagedTrust = -not [bool]$UnsignedTest -and [bool]$EnterpriseManagedTrust
    portableServerPackage = [bool]$PortableServerPackage
    embeddedServerOrigin = $(if ($PortableServerPackage) { $null } else { $origin })
    insecurePrivateNetwork = $insecurePrivateNetwork
    transportSecurity = $(if ($insecurePrivateNetwork) { "http-private-network" } else { "https" })
    productionTrusted = -not $insecurePrivateNetwork -and -not [bool]$UnsignedTest -and (
        $PSCmdlet.ParameterSetName -eq "ArtifactSigning" -or
        [bool]$AssertPublicTrustCertificate -or
        [bool]$EnterpriseManagedTrust
    )
    signingCertificate = $signingCertificateMetadata
    publishedAt = [DateTimeOffset]::UtcNow.ToString("o")
}
$stagedReleaseJson = Join-Path $stagingRoot "release.json"
$metadataJson = $metadata | ConvertTo-Json -Depth 4
[IO.File]::WriteAllText($stagedReleaseJson, $metadataJson, [Text.UTF8Encoding]::new($false))

New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
Copy-Item -LiteralPath $stagedPackage -Destination (Join-Path $releaseDirectory $packageFileName)
Copy-Item -LiteralPath $stagedReleaseJson -Destination (Join-Path $releaseDirectory "release.json")

# The immutable package is available before the channel metadata starts pointing to it.
if ($null -ne $stagedPublicCertificate) {
    $trustDirectory = Join-Path $artifacts "trust"
    New-Item -ItemType Directory -Force -Path $trustDirectory | Out-Null
    Publish-AtomicFile $stagedPublicCertificate (Join-Path $trustDirectory ([IO.Path]::GetFileName($stagedPublicCertificate)))
}
New-Item -ItemType Directory -Force -Path $channelDirectory | Out-Null
$channelLockPath = Join-Path $channelDirectory ".publish.lock"
$channelLock = $null
try {
    $channelLock = [System.IO.File]::Open(
        $channelLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None
    )
    Publish-AtomicFile $stagedAppInstaller (Join-Path $channelDirectory "LearnBotLocalAgent.appinstaller")
    # release.json is the commit pointer and is published last.
    Publish-AtomicFile $stagedReleaseJson (Join-Path $channelDirectory "release.json")
} catch [System.IO.IOException] {
    throw "Another release is already publishing channel '$Channel', or the channel filesystem does not support atomic replacement."
} finally {
    if ($null -ne $channelLock) { $channelLock.Dispose() }
}

Write-Host "Published Local Agent $Version to $Channel"
Write-Host "Package: $releaseDirectory\$packageFileName"
Write-Host "SHA-256: $hash"

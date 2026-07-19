[CmdletBinding()]
param(
    [string]$Subject = "CN=LearnBot Enterprise Pilot Code Signing",
    [ValidateRange(30, 1825)]
    [int]$ValidityDays = 730,
    [string]$PublicCertificatePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($Subject -notmatch '^CN=[^,]+$') {
    throw "Subject must be a simple CN distinguished name, for example CN=LearnBot Enterprise Pilot Code Signing."
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
if ([string]::IsNullOrWhiteSpace($PublicCertificatePath)) {
    $PublicCertificatePath = Join-Path $repoRoot "artifacts\local-agent\trust\LearnBotLocalAgentPilot.cer"
} elseif (-not [IO.Path]::IsPathRooted($PublicCertificatePath)) {
    $PublicCertificatePath = Join-Path $repoRoot $PublicCertificatePath
}
$publicPath = [IO.Path]::GetFullPath($PublicCertificatePath)
$workspaceBoundary = $repoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $publicPath.StartsWith($workspaceBoundary, [StringComparison]::OrdinalIgnoreCase)) {
    throw "The exported public certificate must stay inside the LearnBot repository workspace."
}

function Test-CodeSigningCertificate($Certificate) {
    if ($null -eq $Certificate -or -not $Certificate.HasPrivateKey -or $Certificate.NotAfter -le [DateTime]::UtcNow.AddDays(30)) {
        return $false
    }
    $ekuExtension = $Certificate.Extensions |
        Where-Object { $_.Oid.Value -eq "2.5.29.37" } |
        Select-Object -First 1
    return $null -ne $ekuExtension -and
        @($ekuExtension.EnhancedKeyUsages | ForEach-Object { $_.Value }) -contains "1.3.6.1.5.5.7.3.3"
}

$certificate = Get-ChildItem -LiteralPath Cert:\CurrentUser\My |
    Where-Object { [string]::Equals($_.Subject, $Subject, [StringComparison]::Ordinal) -and (Test-CodeSigningCertificate $_) } |
    Sort-Object NotAfter -Descending |
    Select-Object -First 1
$created = $false
if ($null -eq $certificate) {
    $certificate = New-SelfSignedCertificate `
        -Type Custom `
        -Subject $Subject `
        -FriendlyName "LearnBot Local Agent pilot signing" `
        -CertStoreLocation "Cert:\CurrentUser\My" `
        -KeyAlgorithm RSA `
        -KeyLength 3072 `
        -HashAlgorithm SHA256 `
        -KeyUsage DigitalSignature `
        -KeyExportPolicy NonExportable `
        -NotAfter ([DateTime]::UtcNow.AddDays($ValidityDays)) `
        -TextExtension @(
            "2.5.29.37={text}1.3.6.1.5.5.7.3.3",
            "2.5.29.19={critical}{text}ca=false"
        )
    $created = $true
}
if (-not (Test-CodeSigningCertificate $certificate)) {
    throw "The release-runner certificate does not have an accessible private key and Code Signing EKU."
}

$publicDirectory = Split-Path -Parent $publicPath
New-Item -ItemType Directory -Force -Path $publicDirectory | Out-Null
Export-Certificate -Cert $certificate -FilePath $publicPath -Force | Out-Null

$runnerTrustStore = "Cert:\LocalMachine\TrustedPeople"
$machineTrustInstalled = $false
try {
    $trusted = Get-ChildItem -LiteralPath Cert:\LocalMachine\TrustedPeople |
        Where-Object { $_.Thumbprint -eq $certificate.Thumbprint } |
        Select-Object -First 1
    if ($null -eq $trusted) {
        Import-Certificate -FilePath $publicPath -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
    }
    $machineTrustInstalled = $true
} catch [UnauthorizedAccessException] {
    $runnerTrustStore = "Cert:\CurrentUser\TrustedPeople"
    $trusted = Get-ChildItem -LiteralPath Cert:\CurrentUser\TrustedPeople |
        Where-Object { $_.Thumbprint -eq $certificate.Thumbprint } |
        Select-Object -First 1
    if ($null -eq $trusted) {
        Import-Certificate -FilePath $publicPath -CertStoreLocation Cert:\CurrentUser\TrustedPeople | Out-Null
    }
    Write-Verbose "LocalMachine TrustedPeople requires an elevated administrator session. The public CER remains ready for GPO/MDM deployment."
}

[ordered]@{
    schema = "learnbot.local-agent.pilot-signing-certificate.v1"
    created = $created
    subject = $certificate.Subject
    thumbprint = $certificate.Thumbprint
    notAfter = ([DateTimeOffset]$certificate.NotAfter).ToString("o")
    privateKeyExportable = $false
    privateKeyStore = "Cert:\CurrentUser\My"
    runnerTrustStore = $runnerTrustStore
    machineTrustInstalled = $machineTrustInstalled
    publicCertificatePath = $publicPath
    managedPcTrustStore = "Cert:\LocalMachine\TrustedPeople"
} | ConvertTo-Json

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
# Native stderr from expected-negative child PowerShell cases must be captured
# and asserted below instead of becoming a terminating NativeCommandError.
$ErrorActionPreference = "Continue"

$publishScript = Join-Path $PSScriptRoot "Publish-LocalAgentRelease.ps1"
$powershell = Join-Path $PSHOME "powershell.exe"

function Invoke-PolicyValidation([string[]]$Arguments) {
    $output = & $powershell -NoProfile -ExecutionPolicy Bypass -File $publishScript @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Expected release policy validation to pass: $($output -join [Environment]::NewLine)"
    }
    return ($output -join [Environment]::NewLine) | ConvertFrom-Json
}

function Assert-PolicyFailure([string]$Name, [string]$Expected, [string[]]$Arguments) {
    $output = & $powershell -NoProfile -ExecutionPolicy Bypass -File $publishScript @Arguments 2>&1
    if ($LASTEXITCODE -eq 0) { throw "Policy case unexpectedly passed: $Name" }
    $message = $output -join [Environment]::NewLine
    if ($message -notlike "*$Expected*") { throw "Unexpected $Name failure: $message" }
}

$common = @(
    "-Version", "9.9.9.9",
    "-Publisher", "CN=LearnBot Enterprise Code Signing",
    "-MinimumSupportedVersion", "0.1.0.0",
    "-CertificateThumbprint", "0000000000000000000000000000000000000000",
    "-EnterpriseManagedTrust",
    "-ValidateConfigurationOnly"
)

$privatePilot = Invoke-PolicyValidation (@(
    "-PublicBaseUrl", "http://192.168.1.72:8083",
    "-Channel", "pilot",
    "-AllowInsecurePrivateNetwork",
    "-PortableServerPackage"
) + $common)
if (-not $privatePilot.valid -or
    -not $privatePilot.insecurePrivateNetwork -or
    -not $privatePilot.enterpriseManagedTrust -or
    -not $privatePilot.portableServerPackage -or
    $privatePilot.publicBaseUrl -ne "http://192.168.1.72:8083") {
    throw "The expected enterprise LAN HTTP pilot policy was not resolved."
}

$internalHttps = Invoke-PolicyValidation (@(
    "-PublicBaseUrl", "https://learnbot.corp.example",
    "-Channel", "stable"
) + $common)
if (-not $internalHttps.valid -or $internalHttps.insecurePrivateNetwork) {
    throw "The enterprise-managed HTTPS stable policy was not resolved."
}

Assert-PolicyFailure "HTTP without opt-in" "require -AllowInsecurePrivateNetwork" @(
    "-Version", "9.9.9.1", "-PublicBaseUrl", "http://192.168.1.72:8083",
    "-Publisher", "CN=Test", "-MinimumSupportedVersion", "0.1.0.0",
    "-Channel", "pilot", "-UnsignedTest", "-ValidateConfigurationOnly"
)
Assert-PolicyFailure "public HTTP" "RFC1918" @(
    "-Version", "9.9.9.2", "-PublicBaseUrl", "http://203.0.113.10:8083",
    "-Publisher", "CN=Test", "-MinimumSupportedVersion", "0.1.0.0",
    "-Channel", "pilot", "-UnsignedTest", "-AllowInsecurePrivateNetwork", "-ValidateConfigurationOnly"
)
Assert-PolicyFailure "HTTP stable" "restricted to the pilot" @(
    "-Version", "9.9.9.3", "-PublicBaseUrl", "http://192.168.1.72:8083",
    "-Publisher", "CN=Test", "-MinimumSupportedVersion", "0.1.0.0",
    "-Channel", "stable", "-UnsignedTest", "-AllowInsecurePrivateNetwork", "-ValidateConfigurationOnly"
)
Assert-PolicyFailure "HTTP hostname" "RFC1918" @(
    "-Version", "9.9.9.4", "-PublicBaseUrl", "http://learnbot.internal:8083",
    "-Publisher", "CN=Test", "-MinimumSupportedVersion", "0.1.0.0",
    "-Channel", "pilot", "-UnsignedTest", "-AllowInsecurePrivateNetwork", "-ValidateConfigurationOnly"
)

Write-Host "local-agent-release-policy-ok"

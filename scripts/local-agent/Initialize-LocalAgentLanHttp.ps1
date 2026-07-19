[CmdletBinding()]
param(
    [string]$ServerLanIp,
    [ValidateRange(1, 65535)]
    [int]$Port = 8083,
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')]
    [string]$LatestVersion = "0.2.0.0",
    [ValidatePattern('^\d+\.\d+\.\d+\.\d+$')]
    [string]$MinimumVersion = "0.1.0.0",
    [string]$EnvironmentFile,
    [string]$NginxPolicyFile,
    [switch]$SkipLocalAddressCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-Rfc1918Ipv4Literal([string]$Value) {
    if ($Value -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { return $false }
    $address = $null
    if (-not [Net.IPAddress]::TryParse($Value, [ref]$address) -or
        $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $address.ToString() -ne $Value) {
        return $false
    }
    $bytes = $address.GetAddressBytes()
    return $bytes[0] -eq 10 -or
        ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) -or
        ($bytes[0] -eq 192 -and $bytes[1] -eq 168)
}

function Get-LocalPrivateIpv4Addresses {
    $candidates = @(
        [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object { $_.OperationalStatus -eq [Net.NetworkInformation.OperationalStatus]::Up } |
            ForEach-Object {
                $properties = $_.GetIPProperties()
                $hasIpv4Gateway = @($properties.GatewayAddresses | Where-Object {
                    $_.Address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and
                    $_.Address.ToString() -ne "0.0.0.0"
                }).Count -gt 0
                foreach ($unicast in $properties.UnicastAddresses) {
                    $address = $unicast.Address
                    if ($address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and
                        (Test-Rfc1918Ipv4Literal $address.ToString())) {
                        [pscustomobject]@{
                            address = $address.ToString()
                            hasDefaultGateway = $hasIpv4Gateway
                        }
                    }
                }
            }
    )
    $preferred = @($candidates | Where-Object hasDefaultGateway)
    $selected = if ($preferred.Count -gt 0) { $preferred } else { $candidates }
    return @($selected | ForEach-Object address | Sort-Object -Unique)
}

function Resolve-WorkspacePath([string]$Value, [string]$Default, [string]$RepositoryRoot) {
    $candidate = if ([string]::IsNullOrWhiteSpace($Value)) {
        Join-Path $RepositoryRoot $Default
    } elseif ([IO.Path]::IsPathRooted($Value)) {
        $Value
    } else {
        Join-Path $RepositoryRoot $Value
    }
    $resolved = [IO.Path]::GetFullPath($candidate)
    $boundary = $RepositoryRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Generated deployment files must stay inside the LearnBot repository: $resolved"
    }
    return $resolved
}

function Write-AtomicUtf8([string]$Path, [string]$Content) {
    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $temporary = Join-Path $directory ("." + [IO.Path]::GetFileName($Path) + "." + [Guid]::NewGuid().ToString("N") + ".tmp")
    $backup = $temporary + ".bak"
    try {
        [IO.File]::WriteAllText($temporary, $Content, [Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            [IO.File]::Replace($temporary, $Path, $backup, $true)
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        } else {
            [IO.File]::Move($temporary, $Path)
        }
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
    }
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$localAddresses = @(Get-LocalPrivateIpv4Addresses)
if ([string]::IsNullOrWhiteSpace($ServerLanIp)) {
    if ($localAddresses.Count -eq 1) {
        $ServerLanIp = $localAddresses[0]
    } elseif ($localAddresses.Count -eq 0) {
        throw "No RFC1918 IPv4 address was detected. Pass -ServerLanIp with the server PC's LAN address."
    } else {
        throw "Multiple private IPv4 addresses were detected ($($localAddresses -join ', ')). Pass -ServerLanIp to select the company LAN interface."
    }
}
$ServerLanIp = $ServerLanIp.Trim()
if (-not (Test-Rfc1918Ipv4Literal $ServerLanIp)) {
    throw "ServerLanIp must be a canonical RFC1918 IPv4 address (10/8, 172.16/12, or 192.168/16)."
}
if (-not $SkipLocalAddressCheck -and $localAddresses -notcontains $ServerLanIp) {
    throw "ServerLanIp $ServerLanIp is not assigned to this PC. Run this initializer on the server or pass -SkipLocalAddressCheck only when preparing files for that server."
}
if ([Version]$MinimumVersion -gt [Version]$LatestVersion) {
    throw "MinimumVersion cannot be newer than LatestVersion."
}

$environmentPath = Resolve-WorkspacePath $EnvironmentFile ".env.local-agent-lan-http" $repoRoot
$policyPath = Resolve-WorkspacePath $NginxPolicyFile "deploy\local-agent-lan-http.generated.inc" $repoRoot
$templatePath = Join-Path $repoRoot "nginx\client-ip.direct-lan.template.inc"
if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
    throw "Missing Nginx LAN policy template: $templatePath"
}

$publicBaseUrl = "http://${ServerLanIp}:$Port"
$environmentContent = @"
# Generated by Initialize-LocalAgentLanHttp.ps1. Do not commit this server-specific file.
LEARNBOT_NGINX_BIND_ADDRESS=$ServerLanIp
LEARNBOT_NGINX_PORT=$Port
LEARNBOT_LOCAL_AGENT_PUBLIC_BASE_URL=$publicBaseUrl
LEARNBOT_LOCAL_AGENT_LATEST_VERSION=$LatestVersion
LEARNBOT_LOCAL_AGENT_MINIMUM_VERSION=$MinimumVersion
"@
$policyContent = (Get-Content -Raw -LiteralPath $templatePath).Replace("{{SERVER_LAN_IP}}", $ServerLanIp)
if ($policyContent.Contains("{{SERVER_LAN_IP}}")) {
    throw "The generated Nginx policy still contains an unresolved server IP placeholder."
}

Write-AtomicUtf8 $environmentPath ($environmentContent.Trim() + [Environment]::NewLine)
Write-AtomicUtf8 $policyPath ($policyContent.Trim() + [Environment]::NewLine)

[ordered]@{
    schema = "learnbot.local-agent.lan-http-initialization.v1"
    serverLanIp = $ServerLanIp
    port = $Port
    publicBaseUrl = $publicBaseUrl
    environmentFile = $environmentPath
    nginxPolicyFile = $policyPath
    composeFiles = @(
        "docker-compose.yml",
        "docker-compose.local-agent-release.yml",
        "docker-compose.local-agent-lan-http.yml"
    )
} | ConvertTo-Json -Depth 3

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$temporary = Join-Path $root ('.tmp/quality/lan-http-' + [Guid]::NewGuid().ToString('N'))
$initializer = Join-Path $PSScriptRoot 'Initialize-LanHttp.ps1'
$environment = Join-Path $temporary 'deployment.env'
$policy = Join-Path $temporary 'policy.inc'
try {
    foreach ($address in @('10.23.40.5', '172.16.40.2', '192.168.50.20')) {
        $result = & $initializer -ServerLanIp $address -Port 9083 -SkipLocalAddressCheck -EnvironmentFile $environment -NginxPolicyFile $policy | ConvertFrom-Json
        if ($result.publicBaseUrl -ne "http://${address}:9083") { throw 'Origin mismatch' }
        $envText = Get-Content -Raw -LiteralPath $environment
        $policyText = Get-Content -Raw -LiteralPath $policy
        if ($envText -notmatch "LEARNBOT_NGINX_BIND_ADDRESS=$([regex]::Escape($address))" -or
            $envText -notmatch 'LEARNBOT_NGINX_PORT=9083' -or
            $envText -match 'LOCAL_AGENT|PACKAGE|SIGNATURE' -or
            -not $policyText.Contains($address) -or $policyText.Contains('{{SERVER_LAN_IP}}')) {
            throw 'Generated deployment contract failed'
        }
    }
    foreach ($address in @('127.0.0.1', '8.8.8.8', '192.168.001.2', '::1', 'new server IP')) {
        $rejected = $false
        try { & $initializer -ServerLanIp $address -SkipLocalAddressCheck -EnvironmentFile $environment -NginxPolicyFile $policy | Out-Null }
        catch { $rejected = $true }
        if (-not $rejected) { throw "Invalid address was accepted: $address" }
    }
    $rejected = $false
    try { & $initializer -ServerLanIp '10.23.40.5' -SkipLocalAddressCheck -EnvironmentFile '../outside.env' -NginxPolicyFile $policy | Out-Null }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Workspace boundary was not enforced' }
    Write-Output 'Generic LAN deployment contracts passed.'
} finally {
    $boundary = $root.TrimEnd('\') + '\.tmp\quality\'
    $resolved = [IO.Path]::GetFullPath($temporary)
    if (-not $resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup target' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}

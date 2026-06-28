param(
    [string]$Server = "http://localhost:8083",
    [string]$LoginId = $env:LEARNBOT_SMOKE_LOGIN_ID,
    [string]$Password = $env:LEARNBOT_SMOKE_PASSWORD,
    [string]$WorkspacePath = (Get-Location).Path,
    [string]$Path = "README.md"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($LoginId)) {
    $LoginId = "jinsu.kim"
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    $Password = "admin1234"
}

$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $root ".tmp\local-agent-revoke-smoke.json"
$agentOutPath = Join-Path $root ".tmp\local-agent-revoke-smoke.out.log"
$agentErrPath = Join-Path $root ".tmp\local-agent-revoke-smoke.err.log"
$agentLogPath = Join-Path $root ".tmp\agent.log"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$agentProcess = $null

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null
    )

    $params = @{
        Method = $Method
        Uri = $Uri
        WebSession = $session
        ContentType = "application/json"
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 20)
    }
    Invoke-RestMethod @params
}

function Wait-ForLog {
    param(
        [string]$Pattern,
        [int]$Seconds = 20
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($Seconds)
    do {
        Start-Sleep -Milliseconds 500
        if ($null -ne $agentProcess -and $agentProcess.HasExited) {
            break
        }
        $log = if (Test-Path $agentLogPath) { Get-Content -Raw $agentLogPath } else { "" }
        if ($log -match $Pattern) {
            return $true
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    return $false
}

try {
    if (Test-Path $configPath) {
        Remove-Item -LiteralPath $configPath -Force
    }
    Remove-Item -LiteralPath $agentOutPath, $agentErrPath, $agentLogPath -Force -ErrorAction SilentlyContinue

    Invoke-Json -Method POST -Uri "$Server/api/auth/login" -Body @{
        loginId = $LoginId
        password = $Password
        rememberLogin = $false
    } | Out-Null

    $pairing = Invoke-Json -Method POST -Uri "$Server/api/local-agents/pairing-token" -Body @{
        label = "local-agent-revoke-smoke"
    }

    $env:LEARNBOT_AGENT_CONFIG = $configPath
    dotnet run --project (Join-Path $root "local-agent") -- pair --server $Server --agent-id $pairing.agentId --token $pairing.token --transport websocket | Out-Host
    dotnet run --project (Join-Path $root "local-agent") -- workspace add $WorkspacePath | Out-Host

    $agentProcess = Start-Process -FilePath "dotnet" `
        -ArgumentList @("run", "--project", (Join-Path $root "local-agent"), "--", "agent", "start", "--interval-seconds", "5", "--transport", "websocket") `
        -RedirectStandardOutput $agentOutPath `
        -RedirectStandardError $agentErrPath `
        -WindowStyle Hidden `
        -PassThru

    if (-not (Wait-ForLog -Pattern "websocket hello acknowledged" -Seconds 20)) {
        throw "Local Agent WebSocket did not become ready before revoke. Check $agentOutPath and $agentErrPath."
    }

    $connected = Invoke-Json -Method GET -Uri "$Server/api/local-agents/status"
    if ($connected.state -ne "CONNECTED") {
        throw "Expected Local Agent status CONNECTED before revoke, got $($connected.state)."
    }

    Invoke-Json -Method DELETE -Uri "$Server/api/local-agents/tokens/$($pairing.tokenId)" | Out-Null

    $disconnected = $null
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        $disconnected = Invoke-Json -Method GET -Uri "$Server/api/local-agents/status"
        if ($disconnected.state -ne "CONNECTED") {
            break
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    if ($disconnected.state -eq "CONNECTED") {
        throw "Local Agent status remained CONNECTED after token revoke."
    }

    $oldTokenRejected = $false
    try {
        Invoke-RestMethod -Method GET -Uri "$Server/api/local-agents/tools/next" -Headers @{
            "X-Local-Agent-Token" = $pairing.token
        } | Out-Null
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) {
            $oldTokenRejected = $true
        }
    }
    if (-not $oldTokenRejected) {
        throw "Revoked Local Agent token was not rejected with 401."
    }

    if ($null -ne $agentProcess -and -not $agentProcess.HasExited) {
        Stop-Process -Id $agentProcess.Id -Force -ErrorAction SilentlyContinue
        $agentProcess.WaitForExit(3000) | Out-Null
    }
    $agentProcess = $null
    Remove-Item Env:\LEARNBOT_AGENT_CONFIG -ErrorAction SilentlyContinue

    & (Join-Path $root "scripts\local-agent-smoke.ps1") -Server $Server -LoginId $LoginId -Password $Password -WorkspacePath $WorkspacePath -ToolName file.read -Path $Path -Transport polling | Out-Host

    [pscustomobject]@{
        revokedTokenId = $pairing.tokenId
        revokedAgentId = $pairing.agentId
        stateAfterRevoke = $disconnected.state
        oldTokenRejected = $oldTokenRejected
        freshPollingSmoke = "SUCCEEDED"
    } | ConvertTo-Json -Depth 10
} finally {
    if ($null -ne $agentProcess -and -not $agentProcess.HasExited) {
        Stop-Process -Id $agentProcess.Id -Force -ErrorAction SilentlyContinue
        $agentProcess.WaitForExit(3000) | Out-Null
    }
    Remove-Item Env:\LEARNBOT_AGENT_CONFIG -ErrorAction SilentlyContinue
    if (Test-Path $configPath) {
        Remove-Item -LiteralPath $configPath -Force
    }
}

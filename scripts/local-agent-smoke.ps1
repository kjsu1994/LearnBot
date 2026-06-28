param(
    [string]$Server = "http://localhost:8083",
    [string]$LoginId = $env:LEARNBOT_SMOKE_LOGIN_ID,
    [string]$Password = $env:LEARNBOT_SMOKE_PASSWORD,
    [string]$WorkspacePath = (Get-Location).Path,
    [ValidateSet("file.read", "git.status", "git.diff")]
    [string]$ToolName = "file.read",
    [string]$Path = "README.md",
    [ValidateSet("polling", "websocket")]
    [string]$Transport = "polling"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($LoginId)) {
    $LoginId = "jinsu.kim"
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    $Password = "admin1234"
}

$root = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $root ".tmp\local-agent-smoke.json"
$agentOutPath = Join-Path $root ".tmp\local-agent-smoke.out.log"
$agentErrPath = Join-Path $root ".tmp\local-agent-smoke.err.log"
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
        label = "local-agent-smoke"
    }

    $env:LEARNBOT_AGENT_CONFIG = $configPath
    dotnet run --project (Join-Path $root "local-agent") -- pair --server $Server --agent-id $pairing.agentId --token $pairing.token --transport $Transport | Out-Host
    dotnet run --project (Join-Path $root "local-agent") -- workspace add $WorkspacePath | Out-Host

    $config = Get-Content -Raw $configPath | ConvertFrom-Json
    $workspaceId = $config.workspaces[0].workspaceId
    $input = @{}
    if ($ToolName -eq "file.read" -or $ToolName -eq "git.diff") {
        $input.path = $Path
    }

    if ($Transport -eq "websocket") {
        $agentProcess = Start-Process -FilePath "dotnet" `
            -ArgumentList @("run", "--project", (Join-Path $root "local-agent"), "--", "agent", "start", "--interval-seconds", "5", "--transport", "websocket") `
            -RedirectStandardOutput $agentOutPath `
            -RedirectStandardError $agentErrPath `
            -WindowStyle Hidden `
            -PassThru
        $readyDeadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
        $ready = $false
        do {
            Start-Sleep -Milliseconds 500
            if ($agentProcess.HasExited) {
                break
            }
            $log = if (Test-Path $agentLogPath) { Get-Content -Raw $agentLogPath } else { "" }
            if ($log -match "websocket hello acknowledged") {
                $ready = $true
                break
            }
            if ($log -match "websocket connect failed|falling back to polling") {
                break
            }
        } while ([DateTimeOffset]::UtcNow -lt $readyDeadline)
        if (-not $ready) {
            throw "Local Agent WebSocket did not become ready before enqueue. Check $agentOutPath and $agentErrPath."
        }
    }

    $queued = Invoke-Json -Method POST -Uri "$Server/api/local-agents/tools/read-only" -Body @{
        agentId = $pairing.agentId
        workspaceId = $workspaceId
        toolName = $ToolName
        input = $input
    }

    if ($Transport -eq "polling") {
        dotnet run --project (Join-Path $root "local-agent") -- agent start --once --transport polling | Out-Host
    }

    $result = $null
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        $result = Invoke-Json -Method GET -Uri "$Server/api/local-agents/tools/$($queued.requestId)"
        if ($result.status -in @("SUCCEEDED", "FAILED", "REJECTED", "TIMED_OUT", "CANCELLED", "DISCONNECTED")) {
            break
        }
    } while ([DateTimeOffset]::UtcNow -lt $deadline)

    if ($result.status -ne "SUCCEEDED") {
        throw "Local Agent smoke failed. status=$($result.status) error=$($result.error)"
    }
    if ($Transport -eq "websocket") {
        $log = if (Test-Path $agentLogPath) { Get-Content -Raw $agentLogPath } else { "" }
        if ($log -notmatch "websocket tool .* requestId=$($queued.requestId)") {
            throw "Local Agent WebSocket smoke did not complete through the WebSocket tool path. Check $agentOutPath and $agentErrPath."
        }
    }

    [pscustomobject]@{
        status = $result.status
        toolName = $result.toolName
        transport = $Transport
        requestId = $result.requestId
        workspaceId = $workspaceId
        outputKeys = @($result.output.PSObject.Properties.Name)
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

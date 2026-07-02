param(
    [ValidateSet("setup-plan", "setup", "start", "background-start", "background-stop", "status", "token", "logs", "doctor", "open")]
    [string]$Action = "status",
    [string]$Server = "http://localhost:8083",
    [string]$LoginId = $env:LEARNBOT_AGENT_LOGIN_ID,
    [string]$Password = $env:LEARNBOT_AGENT_PASSWORD,
    [string]$WorkspacePath = (Get-Location).Path,
    [int]$IntervalSeconds = 15,
    [ValidateSet("polling", "websocket", "auto")]
    [string]$Transport = "polling",
    [int]$Tail = 80,
    [switch]$Once,
    [string]$ConfigPath = $env:LEARNBOT_AGENT_CONFIG,
    [string]$AgentExe = $env:LEARNBOT_AGENT_EXE
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localAgentProject = Join-Path $repoRoot "local-agent"
$defaultAgentExe = Join-Path $env:USERPROFILE ".learnbot\bin\learnbot.exe"
. (Join-Path $PSScriptRoot "local-agent\setup\LocalAgentSetupPlan.ps1")

function Resolve-AgentExecutable {
    if (-not [string]::IsNullOrWhiteSpace($AgentExe)) {
        return [System.IO.Path]::GetFullPath($AgentExe)
    }
    if (Test-Path -LiteralPath $defaultAgentExe -PathType Leaf) {
        return [System.IO.Path]::GetFullPath($defaultAgentExe)
    }
    $command = Get-Command "learnbot" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    return $null
}

function Set-AgentConfigOverride {
    if (-not [string]::IsNullOrWhiteSpace($ConfigPath)) {
        $env:LEARNBOT_AGENT_CONFIG = (Resolve-Path -LiteralPath (Split-Path -Parent $ConfigPath) -ErrorAction SilentlyContinue)
        if ($null -eq $env:LEARNBOT_AGENT_CONFIG) {
            New-Item -ItemType Directory -Path (Split-Path -Parent $ConfigPath) -Force | Out-Null
        }
        $env:LEARNBOT_AGENT_CONFIG = [System.IO.Path]::GetFullPath($ConfigPath)
    }
}

function Invoke-Agent {
    param([string[]]$Arguments)
    Set-AgentConfigOverride
    $resolvedExe = Resolve-AgentExecutable
    if (-not [string]::IsNullOrWhiteSpace($resolvedExe)) {
        & $resolvedExe @Arguments
    } elseif (Test-Path -LiteralPath $defaultAgentExe -PathType Leaf) {
        & $defaultAgentExe @Arguments
    } else {
        & dotnet run --project $localAgentProject -- @Arguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw "learnbot local-agent command failed with exit code $LASTEXITCODE"
    }
}

function Get-AgentStatus {
    $text = Invoke-AgentCapture -Arguments @("agent", "status")
    $text | ConvertFrom-Json
}

function Invoke-AgentCapture {
    param([string[]]$Arguments)
    Set-AgentConfigOverride
    $resolvedExe = Resolve-AgentExecutable
    if (-not [string]::IsNullOrWhiteSpace($resolvedExe)) {
        $output = & $resolvedExe @Arguments
    } else {
        $output = & dotnet run --project $localAgentProject -- @Arguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw "learnbot local-agent command failed with exit code $LASTEXITCODE"
    }
    $output -join [Environment]::NewLine
}

function Start-AgentBackground {
    $resolvedExe = Resolve-AgentExecutable
    if ([string]::IsNullOrWhiteSpace($resolvedExe)) {
        throw "No learnbot executable found. Run .\scripts\local-agent-install.ps1 -Action install first, or pass -AgentExe."
    }

    $status = Get-AgentStatus
    if ($status.running -eq $true) {
        Write-Host "Local Agent is already running. pid=$($status.state.processId)"
        return
    }

    Set-AgentConfigOverride
    $arguments = @("agent", "start", "--interval-seconds", "$IntervalSeconds", "--transport", $Transport)
    Start-Process -FilePath $resolvedExe -ArgumentList $arguments -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Agent -Arguments @("agent", "status")
}

function Stop-AgentBackground {
    $status = Get-AgentStatus
    if ($null -eq $status.state -or $null -eq $status.state.processId) {
        Write-Host "Local Agent is not running."
        return
    }

    $processId = [int]$status.state.processId
    try {
        $process = Get-Process -Id $processId -ErrorAction Stop
    } catch {
        Write-Host "Local Agent process is no longer running. pid=$processId"
        Write-StoppedRunState -Status $status -ProcessId $processId
        return
    }

    $allowedNames = @("learnbot", "learnbot.exe")
    if ($allowedNames -notcontains $process.ProcessName -and $process.ProcessName -ne "learnbot") {
        throw "Refusing to stop unexpected process '$($process.ProcessName)' with pid=$processId."
    }

    Stop-Process -Id $processId -ErrorAction Stop
    Start-Sleep -Seconds 1
    Write-StoppedRunState -Status $status -ProcessId $processId
    Invoke-Agent -Arguments @("agent", "status")
}

function Write-StoppedRunState {
    param(
        [object]$Status,
        [int]$ProcessId
    )
    if ($null -eq $Status.statePath -or [string]::IsNullOrWhiteSpace([string]$Status.statePath)) {
        return
    }
    $startedAt = if ($null -ne $Status.state -and $null -ne $Status.state.startedAt) { [string]$Status.state.startedAt } else { [DateTimeOffset]::UtcNow.ToString("O") }
    $state = [pscustomobject]@{
        status = "stopped"
        processId = $ProcessId
        startedAt = $startedAt
        updatedAt = [DateTimeOffset]::UtcNow.ToString("O")
        lastEvent = "stopped by helper"
        logPath = $Status.logPath
    }
    $state | ConvertTo-Json -Depth 5 | Set-Content -Path $Status.statePath -Encoding UTF8
}

function Invoke-Json {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null
    )

    $params = @{
        Method = $Method
        Uri = $Uri
        WebSession = $Session
        ContentType = "application/json"
    }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 20)
    }
    Invoke-RestMethod @params
}

function Setup-Agent {
    if ([string]::IsNullOrWhiteSpace($LoginId)) {
        $LoginId = Read-Host "LearnBot login id"
    }
    if ([string]::IsNullOrWhiteSpace($Password)) {
        $secure = Read-Host "LearnBot password" -AsSecureString
        $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    }

    if (-not (Test-Path -LiteralPath $WorkspacePath -PathType Container)) {
        throw "Workspace path does not exist: $WorkspacePath"
    }

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-Json -Session $session -Method POST -Uri "$Server/api/auth/login" -Body @{
        loginId = $LoginId
        password = $Password
        rememberLogin = $false
    } | Out-Null

    $pairing = Invoke-Json -Session $session -Method POST -Uri "$Server/api/local-agents/pairing-token" -Body @{
        label = "local-agent-helper"
    }

    Invoke-Agent -Arguments @("pair", "--server", $Server, "--agent-id", $pairing.agentId, "--token", $pairing.token, "--transport", $Transport)
    Invoke-Agent -Arguments @("workspace", "add", $WorkspacePath)
    Invoke-Agent -Arguments @("agent", "status")

    Write-Host ""
    Write-Host "Local Agent setup completed."
    Write-Host "Run foreground agent:"
    Write-Host "  .\scripts\local-agent.ps1 -Action start"
    Write-Host ""
    Write-Host "This helper is for the internal foreground pilot. Windows Service/MSI packaging is still future work."
}

switch ($Action) {
    "setup-plan" {
        Get-LearnBotLocalAgentSetupPlan `
            -Server $Server `
            -WorkspacePath $WorkspacePath `
            -LoginId $LoginId `
            -Transport $Transport `
            -AgentExe $AgentExe `
            -ConfigPath $ConfigPath | ConvertTo-Json -Depth 10
    }
    "setup" {
        Setup-Agent
    }
    "start" {
        $agentArgs = @("agent", "start", "--interval-seconds", "$IntervalSeconds", "--transport", $Transport)
        if ($Once) {
            $agentArgs += "--once"
        }
        Invoke-Agent -Arguments $agentArgs
    }
    "background-start" {
        Start-AgentBackground
    }
    "background-stop" {
        Stop-AgentBackground
    }
    "status" {
        Invoke-Agent -Arguments @("agent", "status")
    }
    "token" {
        Invoke-Agent -Arguments @("agent", "token")
    }
    "logs" {
        Invoke-Agent -Arguments @("agent", "logs", "--tail", "$Tail")
    }
    "doctor" {
        Invoke-Agent -Arguments @("doctor")
    }
    "open" {
        Invoke-Agent -Arguments @("open")
    }
}

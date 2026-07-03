param(
    [ValidateSet("setup-plan", "setup-run-plan", "browser-pairing-plan", "pair-from-web-token-plan", "pair-from-web-token", "setup", "start", "background-start", "background-stop", "lifecycle-command", "lifecycle-status", "service-plan", "service-command-plan", "m8-status", "m8-doctor", "m8-lifecycle-run", "status", "token", "logs", "doctor", "open")]
    [string]$Action = "status",
    [string]$Server = "http://localhost:8083",
    [string]$LoginId = $env:LEARNBOT_AGENT_LOGIN_ID,
    [string]$Password = $env:LEARNBOT_AGENT_PASSWORD,
    [string]$WorkspacePath = (Get-Location).Path,
    [int]$IntervalSeconds = 15,
    [ValidateSet("polling", "websocket", "auto")]
    [string]$Transport = "polling",
    [string]$PairingAgentId = $env:LEARNBOT_PAIRING_AGENT_ID,
    [string]$PairingToken = $env:LEARNBOT_PAIRING_TOKEN,
    [int]$Tail = 80,
    [switch]$Once,
    [ValidateSet("background-start", "background-stop", "status", "logs", "doctor")]
    [string]$LifecycleAction = "status",
    [ValidateSet("install", "start", "stop", "uninstall")]
    [string]$ServiceAction = "install",
    [string]$ConfigPath = $env:LEARNBOT_AGENT_CONFIG,
    [string]$AgentExe = $env:LEARNBOT_AGENT_EXE
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localAgentProject = Join-Path $repoRoot "local-agent"
$defaultAgentExe = Join-Path $env:USERPROFILE ".learnbot\bin\learnbot.exe"
. (Join-Path $PSScriptRoot "local-agent\setup\LocalAgentSetupPlan.ps1")
. (Join-Path $PSScriptRoot "local-agent\setup\LocalAgentSetupRunPlan.ps1")
. (Join-Path $PSScriptRoot "local-agent\setup\LocalAgentBrowserPairingPlan.ps1")
. (Join-Path $PSScriptRoot "local-agent\setup\LocalAgentPairFromWebTokenPlan.ps1")
. (Join-Path $PSScriptRoot "local-agent\setup\LocalAgentPairFromWebTokenResult.ps1")
. (Join-Path $PSScriptRoot "local-agent\lifecycle\LocalAgentLifecycleStatus.ps1")
. (Join-Path $PSScriptRoot "local-agent\lifecycle\LocalAgentLifecycleCommandResult.ps1")
. (Join-Path $PSScriptRoot "local-agent\lifecycle\LocalAgentM8LifecycleRunResult.ps1")
. (Join-Path $PSScriptRoot "local-agent\lifecycle\LocalAgentServicePlan.ps1")
. (Join-Path $PSScriptRoot "local-agent\lifecycle\LocalAgentServiceCommandPlan.ps1")

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

function Invoke-AgentResult {
    param([string[]]$Arguments)
    Set-AgentConfigOverride
    $resolvedExe = Resolve-AgentExecutable
    try {
        if (-not [string]::IsNullOrWhiteSpace($resolvedExe)) {
            $output = & $resolvedExe @Arguments 2>&1
        } else {
            $output = & dotnet run --project $localAgentProject -- @Arguments 2>&1
        }
        $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
        [pscustomobject]@{
            exitCode = $exitCode
            output = ($output | Out-String).Trim()
        }
    } catch {
        [pscustomobject]@{
            exitCode = 1
            output = $_.Exception.Message
        }
    }
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

    $setupPlan = Get-LearnBotLocalAgentSetupPlan `
        -Server $Server `
        -WorkspacePath $WorkspacePath `
        -LoginId $LoginId `
        -Transport $Transport `
        -AgentExe $AgentExe `
        -ConfigPath $ConfigPath
    $setupRunPlan = Get-LearnBotLocalAgentSetupRunPlan -SetupPlan $setupPlan
    Assert-LearnBotLocalAgentSetupRunReady -SetupRunPlan $setupRunPlan

    if ([string]::IsNullOrWhiteSpace($Password)) {
        $secure = Read-Host "LearnBot password" -AsSecureString
        $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
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

function Pair-FromWebToken {
    $pairingPlan = Get-LearnBotLocalAgentPairFromWebTokenPlan `
        -Server $Server `
        -WorkspacePath $WorkspacePath `
        -AgentId $PairingAgentId `
        -PairingToken $PairingToken `
        -Transport $Transport `
        -AgentExe $AgentExe `
        -ConfigPath $ConfigPath

    Invoke-LearnBotLocalAgentPairFromWebTokenResult `
        -PairFromWebTokenPlan $pairingPlan `
        -PairingToken $PairingToken `
        -InvokeAgent { param([string[]]$Arguments) Invoke-AgentResult -Arguments $Arguments } | ConvertTo-Json -Depth 10
}

function Invoke-LifecycleCommand {
    Invoke-LearnBotLocalAgentLifecycleCommandResult `
        -LifecycleAction $LifecycleAction `
        -InvokeCommand {
            param([string]$LifecycleAction)
            try {
                switch ($LifecycleAction) {
                    "background-start" {
                        $output = & { Start-AgentBackground } 2>&1 | Out-String
                        [pscustomobject]@{ exitCode = 0; output = $output.Trim() }
                    }
                    "background-stop" {
                        $output = & { Stop-AgentBackground } 2>&1 | Out-String
                        [pscustomobject]@{ exitCode = 0; output = $output.Trim() }
                    }
                    "status" {
                        Invoke-AgentResult -Arguments @("agent", "status")
                    }
                    "logs" {
                        Invoke-AgentResult -Arguments @("agent", "logs", "--tail", "$Tail")
                    }
                    "doctor" {
                        Invoke-AgentResult -Arguments @("doctor")
                    }
                }
            } catch {
                [pscustomobject]@{
                    exitCode = 1
                    output = $_.Exception.Message
                }
            }
        } | ConvertTo-Json -Depth 10
}

function Invoke-M8LifecycleRun {
    $initialStatus = Get-AgentStatus
    Invoke-LearnBotLocalAgentM8LifecycleRunResult `
        -InitialStatus $initialStatus `
        -InvokeLifecycleCommand {
            param([string]$LifecycleAction)
            try {
                switch ($LifecycleAction) {
                    "background-start" {
                        $output = & { Start-AgentBackground } 2>&1 | Out-String
                        [pscustomobject]@{ exitCode = 0; status = "SUCCEEDED"; output = $output.Trim() }
                    }
                    "status" {
                        $result = Invoke-AgentResult -Arguments @("agent", "status")
                        [pscustomobject]@{ exitCode = $result.exitCode; status = if ($result.exitCode -eq 0) { "SUCCEEDED" } else { "FAILED" }; output = $result.output }
                    }
                    "logs" {
                        $result = Invoke-AgentResult -Arguments @("agent", "logs", "--tail", "$Tail")
                        [pscustomobject]@{ exitCode = $result.exitCode; status = if ($result.exitCode -eq 0) { "SUCCEEDED" } else { "FAILED" }; output = $result.output }
                    }
                }
            } catch {
                [pscustomobject]@{
                    exitCode = 1
                    status = "FAILED"
                    output = $_.Exception.Message
                }
            }
        } | ConvertTo-Json -Depth 10
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
    "setup-run-plan" {
        $plan = Get-LearnBotLocalAgentSetupPlan `
            -Server $Server `
            -WorkspacePath $WorkspacePath `
            -LoginId $LoginId `
            -Transport $Transport `
            -AgentExe $AgentExe `
            -ConfigPath $ConfigPath
        Get-LearnBotLocalAgentSetupRunPlan -SetupPlan $plan | ConvertTo-Json -Depth 10
    }
    "browser-pairing-plan" {
        $plan = Get-LearnBotLocalAgentSetupPlan `
            -Server $Server `
            -WorkspacePath $WorkspacePath `
            -LoginId "browser-login" `
            -Transport $Transport `
            -AgentExe $AgentExe `
            -ConfigPath $ConfigPath
        Get-LearnBotLocalAgentBrowserPairingPlan -SetupPlan $plan | ConvertTo-Json -Depth 10
    }
    "pair-from-web-token-plan" {
        Get-LearnBotLocalAgentPairFromWebTokenPlan `
            -Server $Server `
            -WorkspacePath $WorkspacePath `
            -AgentId $PairingAgentId `
            -PairingToken $PairingToken `
            -Transport $Transport `
            -AgentExe $AgentExe `
            -ConfigPath $ConfigPath | ConvertTo-Json -Depth 10
    }
    "pair-from-web-token" {
        Pair-FromWebToken
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
    "lifecycle-command" {
        Invoke-LifecycleCommand
    }
    "lifecycle-status" {
        $agentStatus = Get-AgentStatus
        Get-LearnBotLocalAgentLifecycleStatus `
            -ConfigPath $agentStatus.configPath `
            -StatePath $agentStatus.statePath `
            -LogPath $agentStatus.logPath `
            -AgentExe (Resolve-AgentExecutable) | ConvertTo-Json -Depth 10
    }
    "service-plan" {
        $agentStatus = Get-AgentStatus
        $resolvedExe = Resolve-AgentExecutable
        $installDir = if ([string]::IsNullOrWhiteSpace($resolvedExe)) { Split-Path -Parent $defaultAgentExe } else { Split-Path -Parent $resolvedExe }
        $executable = if ([string]::IsNullOrWhiteSpace($resolvedExe)) { $defaultAgentExe } else { $resolvedExe }
        Get-LearnBotLocalAgentServicePlan `
            -InstallDir $installDir `
            -Executable $executable `
            -ConfigPath $agentStatus.configPath `
            -Transport $Transport `
            -IntervalSeconds $IntervalSeconds | ConvertTo-Json -Depth 10
    }
    "service-command-plan" {
        $agentStatus = Get-AgentStatus
        $resolvedExe = Resolve-AgentExecutable
        $installDir = if ([string]::IsNullOrWhiteSpace($resolvedExe)) { Split-Path -Parent $defaultAgentExe } else { Split-Path -Parent $resolvedExe }
        $executable = if ([string]::IsNullOrWhiteSpace($resolvedExe)) { $defaultAgentExe } else { $resolvedExe }
        $plan = Get-LearnBotLocalAgentServicePlan `
            -InstallDir $installDir `
            -Executable $executable `
            -ConfigPath $agentStatus.configPath `
            -Transport $Transport `
            -IntervalSeconds $IntervalSeconds
        Get-LearnBotLocalAgentServiceCommandPlan -ServiceAction $ServiceAction -ServicePlan $plan | ConvertTo-Json -Depth 10
    }
    "m8-status" {
        Invoke-Agent -Arguments @("m8", "status")
    }
    "m8-doctor" {
        Invoke-Agent -Arguments @("m8", "doctor")
    }
    "m8-lifecycle-run" {
        Invoke-M8LifecycleRun
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

param(
    [string]$Server = "http://localhost:8083",
    [Alias("WorkspacePath")]
    [string]$Workspace = (Get-Location).Path,
    [string]$LoginId = $env:LEARNBOT_AGENT_LOGIN_ID,
    [ValidateSet("polling", "websocket", "auto")]
    [string]$Transport = "polling",
    [int]$IntervalSeconds = 15,
    [string]$InstallDir = (Join-Path $env:USERPROFILE ".learnbot\bin"),
    [switch]$NoElevate,
    [switch]$Plan
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$installScript = Join-Path $PSScriptRoot "local-agent-install.ps1"
$agentScript = Join-Path $PSScriptRoot "local-agent.ps1"
$serviceName = "LearnBotLocalAgent"
$exe = Join-Path $InstallDir "learnbot.exe"
$workspaceFullPath = [System.IO.Path]::GetFullPath($Workspace)

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Quote-ProcessArgument {
    param([string]$Value)
    '"' + ($Value -replace '"', '\"') + '"'
}

function Start-ElevatedSelf {
    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Quote-ProcessArgument $PSCommandPath),
        "-Server", (Quote-ProcessArgument $Server),
        "-Workspace", (Quote-ProcessArgument $workspaceFullPath),
        "-Transport", $Transport,
        "-IntervalSeconds", "$IntervalSeconds",
        "-InstallDir", (Quote-ProcessArgument $InstallDir)
    )
    if (-not [string]::IsNullOrWhiteSpace($LoginId)) {
        $arguments += @("-LoginId", (Quote-ProcessArgument $LoginId))
    }
    Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList ($arguments -join " ") | Out-Null
}

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    Write-Host ""
    Write-Host "==> $Name"
    & $Action
}

function Stop-AgentServiceIfRunning {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($null -eq $service) {
        Write-Host "Service is not installed yet."
        return
    }
    if ($service.Status -eq "Stopped") {
        Write-Host "Service is already stopped."
        return
    }
    Stop-Service -Name $serviceName -Force -ErrorAction Stop
    $service.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(30))
    Write-Host "Service stopped."
}

function Get-AgentStatusOrNull {
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
        return $null
    }
    try {
        $output = & $exe status 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $output) {
            return $null
        }
        return (($output | Out-String).Trim() | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Test-WorkspaceConfigured {
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
        return $false
    }
    try {
        $output = & $exe workspace list 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $output) {
            return $false
        }
        $workspaces = (($output | Out-String).Trim() | ConvertFrom-Json)
        foreach ($item in @($workspaces)) {
            if ($null -eq $item.path) {
                continue
            }
            $itemPath = [System.IO.Path]::GetFullPath([string]$item.path)
            $samePath = [string]::Equals($itemPath, $workspaceFullPath, [StringComparison]::OrdinalIgnoreCase)
            if ($samePath -and $item.approved -eq $true) {
                return $true
            }
        }
        return $false
    } catch {
        return $false
    }
}

function Ensure-AgentConfigured {
    $status = Get-AgentStatusOrNull
    if ($null -eq $status -or $status.configured -ne $true) {
        $setupArgs = @(
            "-Action", "setup",
            "-Server", $Server,
            "-WorkspacePath", $workspaceFullPath,
            "-Transport", $Transport
        )
        if (-not [string]::IsNullOrWhiteSpace($LoginId)) {
            $setupArgs += @("-LoginId", $LoginId)
        }
        & $agentScript @setupArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Local Agent setup failed with exit code $LASTEXITCODE."
        }
        return
    }

    if (Test-WorkspaceConfigured) {
        Write-Host "Agent is already configured and this workspace is approved."
        return
    }

    & $exe workspace add $workspaceFullPath
    if ($LASTEXITCODE -ne 0) {
        throw "Workspace registration failed with exit code $LASTEXITCODE."
    }
}

function Ensure-ServiceInstalled {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($null -ne $service) {
        Write-Host "Service is already installed."
        return
    }
    & $agentScript -Action service-command -ServiceAction install -Transport $Transport -IntervalSeconds $IntervalSeconds
    if ($LASTEXITCODE -ne 0) {
        throw "Service install command failed with exit code $LASTEXITCODE."
    }
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($null -eq $service) {
        throw "Service install did not create $serviceName."
    }
}

function Ensure-ServiceRunning {
    $service = Get-Service -Name $serviceName -ErrorAction Stop
    if ($service.Status -eq "Running") {
        Write-Host "Service is already running."
        return
    }
    Start-Service -Name $serviceName -ErrorAction Stop
    $service.WaitForStatus("Running", [TimeSpan]::FromSeconds(30))
    Write-Host "Service started."
}

if ($Plan) {
    [pscustomobject]@{
        schema = "learnbot.local-agent.bootstrap-plan.v1"
        repoRoot = $repoRoot
        server = $Server
        workspace = $workspaceFullPath
        installDir = [System.IO.Path]::GetFullPath($InstallDir)
        requiresAdministrator = $true
        commands = @(
            "stop service if running",
            "install local-agent executable and add user PATH",
            "setup pairing only when not configured",
            "add workspace only when missing",
            "install Windows service only when missing",
            "start Windows service when stopped",
            "print learnbot status"
        )
    } | ConvertTo-Json -Depth 5
    return
}

if (-not (Test-Administrator)) {
    if ($NoElevate) {
        throw "Administrator PowerShell is required. Re-run without -NoElevate to open the UAC prompt automatically."
    }
    Write-Host "Administrator permission is required. Opening elevated PowerShell..."
    Start-ElevatedSelf
    return
}

if (-not (Test-Path -LiteralPath $installScript -PathType Leaf)) {
    throw "Install script was not found: $installScript"
}
if (-not (Test-Path -LiteralPath $agentScript -PathType Leaf)) {
    throw "Local Agent helper script was not found: $agentScript"
}
if (-not (Test-Path -LiteralPath $workspaceFullPath -PathType Container)) {
    throw "Workspace path does not exist: $workspaceFullPath"
}

Invoke-Step "Stop existing service if needed" {
    Stop-AgentServiceIfRunning
}

Invoke-Step "Install LearnBot CLI" {
    & $installScript -Action install -InstallDir $InstallDir -AddToUserPath
    if ($LASTEXITCODE -ne 0) {
        throw "Local Agent install failed with exit code $LASTEXITCODE."
    }
}

Invoke-Step "Configure pairing and workspace" {
    Ensure-AgentConfigured
}

Invoke-Step "Install Windows Service if needed" {
    Ensure-ServiceInstalled
}

Invoke-Step "Start Windows Service" {
    Ensure-ServiceRunning
}

Invoke-Step "Final status" {
    & $exe status
}

Write-Host ""
Write-Host "LearnBot bootstrap completed."
Write-Host "Use from any approved workspace:"
Write-Host '  learnbot fix "README typo fix"'

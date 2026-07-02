function Get-LearnBotLocalAgentLifecycleStatus {
    param(
        [string]$ConfigPath = (Join-Path $env:USERPROFILE ".learnbot\agent.json"),
        [string]$StatePath = (Join-Path $env:USERPROFILE ".learnbot\agent-state.json"),
        [string]$LogPath = (Join-Path $env:USERPROFILE ".learnbot\agent.log"),
        [string]$AgentExe = $env:LEARNBOT_AGENT_EXE,
        [string]$ServiceName = "LearnBotLocalAgent"
    )

    $config = $null
    $state = $null
    $configExists = Test-Path -LiteralPath $ConfigPath -PathType Leaf
    $stateExists = Test-Path -LiteralPath $StatePath -PathType Leaf
    $logExists = Test-Path -LiteralPath $LogPath -PathType Leaf

    if ($configExists) {
        try {
            $config = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
        } catch {
            $config = $null
        }
    }

    if ($stateExists) {
        try {
            $state = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
        } catch {
            $state = $null
        }
    }

    $processId = if ($null -ne $state -and $null -ne $state.processId) { [int]$state.processId } else { $null }
    $processRunning = $false
    $processName = $null
    if ($null -ne $processId) {
        try {
            $process = Get-Process -Id $processId -ErrorAction Stop
            $processRunning = $true
            $processName = $process.ProcessName
        } catch {
            $processRunning = $false
        }
    }

    $service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    $serviceInstalled = $null -ne $service
    $serviceRunning = $serviceInstalled -and $service.Status -eq "Running"
    $serviceStartType = $null
    if ($serviceInstalled) {
        try {
            $serviceStartType = $service.StartType.ToString()
        } catch {
            $serviceStartType = $null
        }
    }

    $stateStatus = if ($null -ne $state -and $null -ne $state.status) { [string]$state.status } else { "unknown" }
    $running = $stateStatus -eq "running" -and $processRunning
    $staleState = $stateStatus -eq "running" -and -not $processRunning
    $configured = $null -ne $config -and -not [string]::IsNullOrWhiteSpace([string]$config.token) -and -not [string]::IsNullOrWhiteSpace([string]$config.agentId)
    $workspaceCount = if ($null -ne $config -and $null -ne $config.workspaces) { @($config.workspaces).Count } else { 0 }
    $approvedWorkspaceCount = if ($null -ne $config -and $null -ne $config.workspaces) { @($config.workspaces | Where-Object { $_.approved -eq $true }).Count } else { 0 }
    $logBytes = if ($logExists) { (Get-Item -LiteralPath $LogPath).Length } else { 0 }
    $resolvedAgentExe = if ([string]::IsNullOrWhiteSpace($AgentExe)) { $null } else { [System.IO.Path]::GetFullPath($AgentExe) }

    [pscustomobject]@{
        schema = "learnbot.local-agent.lifecycle-status.v1"
        mode = "internal-pilot"
        configured = $configured
        configPath = [System.IO.Path]::GetFullPath($ConfigPath)
        configExists = $configExists
        serverUrl = if ($null -ne $config) { $config.serverUrl } else { $null }
        agentId = if ($null -ne $config) { $config.agentId } else { $null }
        tokenSecretVisible = $false
        workspaceCount = $workspaceCount
        approvedWorkspaceCount = $approvedWorkspaceCount
        statePath = [System.IO.Path]::GetFullPath($StatePath)
        stateExists = $stateExists
        stateStatus = $stateStatus
        running = $running
        processId = $processId
        processRunning = $processRunning
        processName = $processName
        staleState = $staleState
        lastEvent = if ($null -ne $state) { $state.lastEvent } else { $null }
        startedAt = if ($null -ne $state) { $state.startedAt } else { $null }
        updatedAt = if ($null -ne $state) { $state.updatedAt } else { $null }
        logPath = [System.IO.Path]::GetFullPath($LogPath)
        logExists = $logExists
        logBytes = $logBytes
        agentExe = $resolvedAgentExe
        service = [pscustomobject]@{
            name = $ServiceName
            installed = $serviceInstalled
            running = $serviceRunning
            startType = $serviceStartType
            registrationEnabled = $false
        }
        commands = [pscustomobject]@{
            status = ".\scripts\local-agent.ps1 -Action lifecycle-status"
            start = ".\scripts\local-agent.ps1 -Action background-start"
            stop = ".\scripts\local-agent.ps1 -Action background-stop"
            logs = ".\scripts\local-agent.ps1 -Action logs -Tail 80"
            doctor = ".\scripts\local-agent.ps1 -Action doctor"
        }
        safety = [pscustomobject]@{
            typedToolsOnly = $true
            arbitraryShellExecution = $false
            tokenSecretPrinted = $false
            serverLocalMutationEnabled = $false
            serviceInstallEnabled = $false
            hardenedRestartPolicy = $false
        }
    }
}

function Get-LearnBotLocalAgentServicePlan {
    param(
        [string]$InstallDir = (Join-Path $env:USERPROFILE ".learnbot\bin"),
        [string]$Executable = (Join-Path $InstallDir "learnbot.exe"),
        [string]$ConfigPath = (Join-Path $env:USERPROFILE ".learnbot\agent.json"),
        [string]$ServiceName = "LearnBotLocalAgent",
        [ValidateSet("polling", "websocket", "auto")]
        [string]$Transport = "auto",
        [int]$IntervalSeconds = 15
    )

    $installDirFullPath = [System.IO.Path]::GetFullPath($InstallDir)
    $executableFullPath = [System.IO.Path]::GetFullPath($Executable)
    $configFullPath = [System.IO.Path]::GetFullPath($ConfigPath)
    $installed = Test-Path -LiteralPath $executableFullPath -PathType Leaf
    $configured = $false
    $workspaceCount = 0
    $approvedWorkspaceCount = 0

    if (Test-Path -LiteralPath $configFullPath -PathType Leaf) {
        try {
            $config = Get-Content -Raw -LiteralPath $configFullPath | ConvertFrom-Json
            $configured = -not [string]::IsNullOrWhiteSpace([string]$config.token) -and -not [string]::IsNullOrWhiteSpace([string]$config.agentId)
            $workspaceCount = if ($null -ne $config.workspaces) { @($config.workspaces).Count } else { 0 }
            $approvedWorkspaceCount = if ($null -ne $config.workspaces) { @($config.workspaces | Where-Object { $_.approved -eq $true }).Count } else { 0 }
        } catch {
            $configured = $false
        }
    }

    $service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    $serviceInstalled = $null -ne $service
    $missingPrerequisites = @()
    if (-not $installed) {
        $missingPrerequisites += "installedExecutable"
    }
    if (-not $configured) {
        $missingPrerequisites += "pairedConfig"
    }
    if ($approvedWorkspaceCount -lt 1) {
        $missingPrerequisites += "approvedWorkspace"
    }
    if ($serviceInstalled) {
        $missingPrerequisites += "serviceAlreadyInstalled"
    }

    $binPath = "`"$executableFullPath`" agent start --interval-seconds $IntervalSeconds --transport $Transport"

    [pscustomobject]@{
        schema = "learnbot.local-agent.service-plan.v1"
        mode = "windows-service-preview"
        previewOnly = $true
        readyToInstall = $missingPrerequisites.Count -eq 0
        installDir = $installDirFullPath
        executable = $executableFullPath
        installedExecutable = $installed
        configPath = $configFullPath
        configured = $configured
        workspaceCount = $workspaceCount
        approvedWorkspaceCount = $approvedWorkspaceCount
        tokenSecretVisible = $false
        service = [pscustomobject]@{
            name = $ServiceName
            installed = $serviceInstalled
            installEnabled = $false
            startEnabled = $false
            stopEnabled = $false
            uninstallEnabled = $false
            account = "LocalSystem or a dedicated least-privilege account, to be decided before enabling real install"
            startType = "Automatic"
            restartPolicy = "planned"
        }
        transport = $Transport
        intervalSeconds = $IntervalSeconds
        missingPrerequisites = $missingPrerequisites
        plannedCommands = [pscustomobject]@{
            install = "New-Service -Name $ServiceName -BinaryPathName '$binPath' -StartupType Automatic"
            start = "Start-Service -Name $ServiceName"
            stop = "Stop-Service -Name $ServiceName"
            uninstall = "sc.exe delete $ServiceName"
        }
        safety = [pscustomobject]@{
            typedToolsOnly = $true
            arbitraryShellExecution = $false
            tokenSecretPrinted = $false
            serverLocalMutationEnabled = $false
            requiresAdminApproval = $true
            executesServiceCommand = $false
        }
    }
}

function Test-LearnBotLocalAgentAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Invoke-LearnBotLocalAgentServiceCommandResult {
    param(
        [ValidateSet("install", "start", "stop", "uninstall")]
        [string]$ServiceAction = "install",
        [Parameter(Mandatory = $true)]
        [object]$ServicePlan,
        [Parameter(Mandatory = $true)]
        [scriptblock]$InvokeServiceCommand
    )

    $serviceName = [string]$ServicePlan.service.name
    $blockedReasons = @()
    if (-not (Test-LearnBotLocalAgentAdministrator)) {
        $blockedReasons += "administratorRequired"
    }
    if ($ServiceAction -eq "install" -and $ServicePlan.readyToInstall -ne $true) {
        $blockedReasons += @($ServicePlan.missingPrerequisites)
    }
    if ($ServiceAction -in @("start", "stop", "uninstall") -and $ServicePlan.service.installed -ne $true) {
        $blockedReasons += "serviceNotInstalled"
    }

    $command = switch ($ServiceAction) {
        "install" { $ServicePlan.plannedCommands.install }
        "start" { $ServicePlan.plannedCommands.start }
        "stop" { $ServicePlan.plannedCommands.stop }
        "uninstall" { $ServicePlan.plannedCommands.uninstall }
    }

    if ($blockedReasons.Count -gt 0) {
        return [pscustomobject]@{
            schema = "learnbot.local-agent.service-command-result.v1"
            mode = "windows-service-command"
            serviceAction = $ServiceAction
            attempted = $false
            succeeded = $false
            blocked = $true
            blockedReasons = @($blockedReasons | Select-Object -Unique)
            service = [pscustomobject]@{
                name = $serviceName
                installed = $ServicePlan.service.installed
            }
            command = $command
            exitCode = $null
            output = $null
            error = $null
            plan = $ServicePlan
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

    try {
        $execution = & $InvokeServiceCommand $ServiceAction $ServicePlan
        $exitCode = if ($null -ne $execution -and $null -ne $execution.exitCode) { [int]$execution.exitCode } else { 0 }
        return [pscustomobject]@{
            schema = "learnbot.local-agent.service-command-result.v1"
            mode = "windows-service-command"
            serviceAction = $ServiceAction
            attempted = $true
            succeeded = $exitCode -eq 0
            blocked = $false
            blockedReasons = @()
            service = [pscustomobject]@{
                name = $serviceName
                installed = (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) -ne $null
            }
            command = $command
            exitCode = $exitCode
            output = if ($null -ne $execution) { [string]$execution.output } else { "" }
            error = if ($exitCode -eq 0 -or $null -eq $execution) { $null } else { [string]$execution.output }
            plan = $ServicePlan
            safety = [pscustomobject]@{
                typedToolsOnly = $true
                arbitraryShellExecution = $false
                tokenSecretPrinted = $false
                serverLocalMutationEnabled = $false
                requiresAdminApproval = $true
                executesServiceCommand = $true
            }
        }
    } catch {
        [pscustomobject]@{
            schema = "learnbot.local-agent.service-command-result.v1"
            mode = "windows-service-command"
            serviceAction = $ServiceAction
            attempted = $true
            succeeded = $false
            blocked = $false
            blockedReasons = @()
            service = [pscustomobject]@{
                name = $serviceName
                installed = (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) -ne $null
            }
            command = $command
            exitCode = 1
            output = $_.Exception.Message
            error = $_.Exception.Message
            plan = $ServicePlan
            safety = [pscustomobject]@{
                typedToolsOnly = $true
                arbitraryShellExecution = $false
                tokenSecretPrinted = $false
                serverLocalMutationEnabled = $false
                requiresAdminApproval = $true
                executesServiceCommand = $true
            }
        }
    }
}

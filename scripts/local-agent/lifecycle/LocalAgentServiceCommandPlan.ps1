function Get-LearnBotLocalAgentServiceCommandPlan {
    param(
        [ValidateSet("install", "start", "stop", "uninstall")]
        [string]$ServiceAction = "install",
        [Parameter(Mandatory = $true)]
        [object]$ServicePlan
    )

    $command = switch ($ServiceAction) {
        "install" { $ServicePlan.plannedCommands.install }
        "start" { $ServicePlan.plannedCommands.start }
        "stop" { $ServicePlan.plannedCommands.stop }
        "uninstall" { $ServicePlan.plannedCommands.uninstall }
    }

    $missingPrerequisites = @($ServicePlan.missingPrerequisites)
    $blockedReasons = @("serviceCommandExecutionDisabled")
    if ($ServiceAction -eq "install" -and $ServicePlan.readyToInstall -ne $true) {
        $blockedReasons += $missingPrerequisites
    }
    if ($ServiceAction -in @("start", "stop", "uninstall") -and $ServicePlan.service.installed -ne $true) {
        $blockedReasons += "serviceNotInstalled"
    }

    [pscustomobject]@{
        schema = "learnbot.local-agent.service-command-plan.v1"
        mode = "windows-service-command-preview"
        serviceAction = $ServiceAction
        previewOnly = $true
        executionEnabled = $false
        command = $command
        blocked = $true
        blockedReasons = $blockedReasons
        service = [pscustomobject]@{
            name = $ServicePlan.service.name
            installed = $ServicePlan.service.installed
            installEnabled = $false
            startEnabled = $false
            stopEnabled = $false
            uninstallEnabled = $false
        }
        prerequisites = [pscustomobject]@{
            readyToInstall = $ServicePlan.readyToInstall
            installedExecutable = $ServicePlan.installedExecutable
            configured = $ServicePlan.configured
            approvedWorkspaceCount = $ServicePlan.approvedWorkspaceCount
            missing = $missingPrerequisites
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

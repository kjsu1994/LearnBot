function Get-LearnBotLocalAgentSetupRunPlan {
    param(
        [Parameter(Mandatory = $true)]
        [object]$SetupPlan,
        [switch]$Execute
    )

    $blockedReasons = @()
    if ($SetupPlan.readyToRun -ne $true) {
        $blockedReasons += @($SetupPlan.missingInputs)
    }
    if ($Execute) {
        $blockedReasons += "executionNotEnabled"
    }

    [pscustomobject]@{
        schema = "learnbot.local-agent.setup-run-plan.v1"
        mode = "guided-setup-run-preview"
        previewOnly = $true
        executionRequested = [bool]$Execute
        executionEnabled = $false
        readyToRun = $SetupPlan.readyToRun -eq $true
        blocked = $blockedReasons.Count -gt 0
        blockedReasons = $blockedReasons
        server = $SetupPlan.server
        workspacePath = $SetupPlan.workspacePath
        workspaceExists = $SetupPlan.workspaceExists
        transport = $SetupPlan.transport
        loginIdProvided = $SetupPlan.loginIdProvided
        passwordProvided = $SetupPlan.passwordProvided
        tokenSecretVisible = $false
        networkCallsEnabled = $false
        localCommandsEnabled = $false
        steps = @($SetupPlan.steps | ForEach-Object {
            [pscustomobject]@{
                order = $_.order
                name = $_.name
                ready = $SetupPlan.readyToRun -eq $true
                executionEnabled = $false
                endpoint = $_.endpoint
                command = $_.command
                secretInputRequired = $_.secretInputRequired
                tokenPrinted = $false
            }
        })
        safety = [pscustomobject]@{
            typedToolsOnly = $true
            arbitraryShellExecution = $false
            tokenSecretPrinted = $false
            serverLocalMutationEnabled = $false
            networkMutationEnabled = $false
            localConfigWriteEnabled = $false
            workspaceRegistrationEnabled = $false
        }
    }
}

function Assert-LearnBotLocalAgentSetupRunReady {
    param(
        [Parameter(Mandatory = $true)]
        [object]$SetupRunPlan
    )

    if ($SetupRunPlan.readyToRun -ne $true -or $SetupRunPlan.blocked -eq $true) {
        $reasons = @($SetupRunPlan.blockedReasons) -join ", "
        if ([string]::IsNullOrWhiteSpace($reasons)) {
            $reasons = "setup is not ready"
        }
        throw "Local Agent setup readiness check failed: $reasons"
    }
}

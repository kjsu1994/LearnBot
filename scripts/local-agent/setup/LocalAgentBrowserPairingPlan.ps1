function Get-LearnBotLocalAgentBrowserPairingPlan {
    param(
        [Parameter(Mandatory = $true)]
        [object]$SetupPlan
    )

    $blockedReasons = @()
    if (-not $SetupPlan.workspaceExists) {
        $blockedReasons += "workspacePath"
    }

    $serverRoot = [string]$SetupPlan.server
    $workspacePath = [string]$SetupPlan.workspacePath

    [pscustomobject]@{
        schema = "learnbot.local-agent.browser-pairing-plan.v1"
        mode = "browser-pairing-preview"
        previewOnly = $true
        readyToPair = $blockedReasons.Count -eq 0
        blocked = $blockedReasons.Count -gt 0
        blockedReasons = $blockedReasons
        server = $serverRoot
        webPairingUrl = "$serverRoot/code"
        workspacePath = $workspacePath
        workspaceExists = $SetupPlan.workspaceExists
        transport = $SetupPlan.transport
        cliPasswordAccepted = $false
        browserLoginRequired = $true
        tokenSecretVisible = $false
        networkCallsEnabled = $false
        localCommandsEnabled = $false
        steps = @(
            [pscustomobject]@{
                order = 1
                name = "openWebUi"
                command = "learnbot open"
                url = "$serverRoot/code"
                executionEnabled = $false
            },
            [pscustomobject]@{
                order = 2
                name = "signInInBrowser"
                url = "$serverRoot"
                executionEnabled = $false
                passwordCollectedByCli = $false
            },
            [pscustomobject]@{
                order = 3
                name = "createPairingTokenInWeb"
                endpoint = "$serverRoot/api/local-agents/pairing-token"
                executionEnabled = $false
                tokenPrintedByPlan = $false
            },
            [pscustomobject]@{
                order = 4
                name = "pairLocalAgentWithPastedToken"
                command = "learnbot pair --server $serverRoot --agent-id <agent-id-from-web> --token <pairing-token-from-web> --transport $($SetupPlan.transport)"
                executionEnabled = $false
                tokenPrintedByPlan = $false
            },
            [pscustomobject]@{
                order = 5
                name = "registerWorkspace"
                command = "learnbot workspace add `"$workspacePath`""
                executionEnabled = $false
            },
            [pscustomobject]@{
                order = 6
                name = "showStatus"
                command = "learnbot status"
                executionEnabled = $false
            }
        )
        safety = [pscustomobject]@{
            typedToolsOnly = $true
            arbitraryShellExecution = $false
            cliPasswordCollection = $false
            tokenSecretPrinted = $false
            serverLocalMutationEnabled = $false
            browserOwnsLogin = $true
            localConfigWriteEnabled = $false
            workspaceRegistrationEnabled = $false
        }
    }
}

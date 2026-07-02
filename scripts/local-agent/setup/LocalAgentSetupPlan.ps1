function Get-LearnBotLocalAgentSetupPlan {
    param(
        [string]$Server = "http://localhost:8083",
        [string]$WorkspacePath = (Get-Location).Path,
        [string]$LoginId = $env:LEARNBOT_AGENT_LOGIN_ID,
        [ValidateSet("polling", "websocket", "auto")]
        [string]$Transport = "polling",
        [string]$AgentExe = $env:LEARNBOT_AGENT_EXE,
        [string]$ConfigPath = $env:LEARNBOT_AGENT_CONFIG
    )

    $workspaceExists = Test-Path -LiteralPath $WorkspacePath -PathType Container
    $missingInputs = @()
    if ([string]::IsNullOrWhiteSpace($LoginId)) {
        $missingInputs += "loginId"
    }
    if (-not $workspaceExists) {
        $missingInputs += "workspacePath"
    }

    $resolvedWorkspace = if ($workspaceExists) { [System.IO.Path]::GetFullPath($WorkspacePath) } else { $WorkspacePath }
    $serverRoot = if ([string]::IsNullOrWhiteSpace($Server)) { "http://localhost:8083" } else { $Server.TrimEnd('/') }

    [pscustomobject]@{
        schema = "learnbot.local-agent.setup-plan.v1"
        mode = "guided-internal-pilot"
        readyToRun = $missingInputs.Count -eq 0
        server = $serverRoot
        workspacePath = $resolvedWorkspace
        workspaceExists = $workspaceExists
        transport = $Transport
        loginIdProvided = -not [string]::IsNullOrWhiteSpace($LoginId)
        passwordProvided = -not [string]::IsNullOrWhiteSpace($env:LEARNBOT_AGENT_PASSWORD)
        agentExe = if ([string]::IsNullOrWhiteSpace($AgentExe)) { $null } else { [System.IO.Path]::GetFullPath($AgentExe) }
        configPath = if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $null } else { [System.IO.Path]::GetFullPath($ConfigPath) }
        missingInputs = $missingInputs
        steps = @(
            [pscustomobject]@{
                order = 1
                name = "login"
                endpoint = "$serverRoot/api/auth/login"
                secretInputRequired = $true
                tokenPrinted = $false
            },
            [pscustomobject]@{
                order = 2
                name = "issuePairingToken"
                endpoint = "$serverRoot/api/local-agents/pairing-token"
                tokenPrinted = $false
            },
            [pscustomobject]@{
                order = 3
                name = "pairLocalAgent"
                command = "learnbot pair --server $serverRoot --agent-id <agent-id> --token <pairing-token> --transport $Transport"
                tokenPrinted = $false
            },
            [pscustomobject]@{
                order = 4
                name = "registerWorkspace"
                command = "learnbot workspace add `"$resolvedWorkspace`""
            },
            [pscustomobject]@{
                order = 5
                name = "showStatus"
                command = "learnbot status"
            }
        )
        safety = [pscustomobject]@{
            typedToolsOnly = $true
            arbitraryShellExecution = $false
            serverLocalMutationEnabled = $false
            tokenSecretPrinted = $false
            windowsService = $false
            signedInstaller = $false
        }
    }
}

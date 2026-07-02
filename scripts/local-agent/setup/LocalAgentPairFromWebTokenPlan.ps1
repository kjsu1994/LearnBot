function Get-LearnBotLocalAgentPairFromWebTokenPlan {
    param(
        [string]$Server = "http://localhost:8083",
        [string]$WorkspacePath = (Get-Location).Path,
        [string]$AgentId = $env:LEARNBOT_PAIRING_AGENT_ID,
        [string]$PairingToken = $env:LEARNBOT_PAIRING_TOKEN,
        [ValidateSet("polling", "websocket", "auto")]
        [string]$Transport = "polling",
        [string]$AgentExe = $env:LEARNBOT_AGENT_EXE,
        [string]$ConfigPath = $env:LEARNBOT_AGENT_CONFIG
    )

    $blockedReasons = @()
    $workspaceExists = Test-Path -LiteralPath $WorkspacePath -PathType Container
    if (-not $workspaceExists) {
        $blockedReasons += "workspacePath"
    }

    $parsedAgentId = [Guid]::Empty
    $agentIdProvided = -not [string]::IsNullOrWhiteSpace($AgentId)
    $agentIdValid = $agentIdProvided -and [Guid]::TryParse($AgentId, [ref]$parsedAgentId)
    if (-not $agentIdValid) {
        $blockedReasons += "agentId"
    }

    $pairingTokenProvided = -not [string]::IsNullOrWhiteSpace($PairingToken)
    if (-not $pairingTokenProvided) {
        $blockedReasons += "pairingToken"
    }

    $resolvedWorkspace = if ($workspaceExists) { [System.IO.Path]::GetFullPath($WorkspacePath) } else { $WorkspacePath }
    $serverRoot = if ([string]::IsNullOrWhiteSpace($Server)) { "http://localhost:8083" } else { $Server.TrimEnd('/') }
    $displayAgentId = if ($agentIdValid) { $parsedAgentId.ToString() } else { "<agent-id-from-web>" }

    [pscustomobject]@{
        schema = "learnbot.local-agent.pair-from-web-token-plan.v1"
        mode = "browser-token-pairing-preview"
        previewOnly = $true
        readyToRun = $blockedReasons.Count -eq 0
        blocked = $blockedReasons.Count -gt 0
        blockedReasons = $blockedReasons
        server = $serverRoot
        workspacePath = $resolvedWorkspace
        workspaceExists = $workspaceExists
        transport = $Transport
        agentId = if ($agentIdValid) { $parsedAgentId.ToString() } else { $null }
        agentIdProvided = $agentIdProvided
        agentIdValid = $agentIdValid
        pairingTokenProvided = $pairingTokenProvided
        tokenSecretVisible = $false
        tokenSecretPrinted = $false
        cliPasswordAccepted = $false
        networkCallsEnabled = $false
        localCommandsEnabled = $false
        localConfigWriteEnabled = $false
        workspaceRegistrationEnabled = $false
        agentExe = if ([string]::IsNullOrWhiteSpace($AgentExe)) { $null } else { [System.IO.Path]::GetFullPath($AgentExe) }
        configPath = if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $null } else { [System.IO.Path]::GetFullPath($ConfigPath) }
        steps = @(
            [pscustomobject]@{
                order = 1
                name = "pairLocalAgentWithPastedToken"
                command = "learnbot pair --server $serverRoot --agent-id $displayAgentId --token <pairing-token-from-web> --transport $Transport"
                executionEnabled = $false
                tokenPrintedByPlan = $false
            },
            [pscustomobject]@{
                order = 2
                name = "registerWorkspace"
                command = "learnbot workspace add `"$resolvedWorkspace`""
                executionEnabled = $false
            },
            [pscustomobject]@{
                order = 3
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
            executesPairCommand = $false
        }
    }
}

function Assert-LearnBotLocalAgentPairFromWebTokenReady {
    param(
        [Parameter(Mandatory = $true)]
        [object]$PairFromWebTokenPlan
    )

    if ($PairFromWebTokenPlan.readyToRun -ne $true) {
        $reasons = @($PairFromWebTokenPlan.blockedReasons) -join ", "
        throw "Local Agent web-token pairing plan is not ready. Blocked by: $reasons"
    }
}

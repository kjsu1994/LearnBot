function Invoke-LearnBotLocalAgentPairFromWebTokenResult {
    param(
        [Parameter(Mandatory = $true)]
        [object]$PairFromWebTokenPlan,
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$PairingToken,
        [Parameter(Mandatory = $true)]
        [scriptblock]$InvokeAgent
    )

    $steps = @()
    if ($PairFromWebTokenPlan.readyToRun -ne $true) {
        return [pscustomobject]@{
            schema = "learnbot.local-agent.pair-from-web-token-result.v1"
            status = "BLOCKED"
            blocked = $true
            blockedReasons = @($PairFromWebTokenPlan.blockedReasons)
            server = $PairFromWebTokenPlan.server
            workspacePath = $PairFromWebTokenPlan.workspacePath
            transport = $PairFromWebTokenPlan.transport
            tokenSecretPrinted = $false
            cliPasswordCollected = $false
            steps = $steps
        }
    }

    $pairArguments = @(
        "pair",
        "--server", [string]$PairFromWebTokenPlan.server,
        "--agent-id", [string]$PairFromWebTokenPlan.agentId,
        "--token", $PairingToken,
        "--transport", [string]$PairFromWebTokenPlan.transport
    )
    $pairResult = & $InvokeAgent -Arguments $pairArguments
    $steps += New-LearnBotLocalAgentPairResultStep `
        -Name "pairLocalAgentWithPastedToken" `
        -Command "learnbot pair --server $($PairFromWebTokenPlan.server) --agent-id $($PairFromWebTokenPlan.agentId) --token <pairing-token-from-web> --transport $($PairFromWebTokenPlan.transport)" `
        -Result $pairResult `
        -SecretToRedact $PairingToken `
        -TokenSecretPrinted $false
    if ($pairResult.exitCode -ne 0) {
        return New-LearnBotLocalAgentPairFromWebTokenResult `
            -Status "FAILED" `
            -Plan $PairFromWebTokenPlan `
            -Steps $steps `
            -FailureStep "pairLocalAgentWithPastedToken"
    }

    $workspaceArguments = @("workspace", "add", [string]$PairFromWebTokenPlan.workspacePath)
    $workspaceResult = & $InvokeAgent -Arguments $workspaceArguments
    $steps += New-LearnBotLocalAgentPairResultStep `
        -Name "registerWorkspace" `
        -Command "learnbot workspace add `"$($PairFromWebTokenPlan.workspacePath)`"" `
        -Result $workspaceResult `
        -SecretToRedact $PairingToken `
        -TokenSecretPrinted $false
    if ($workspaceResult.exitCode -ne 0) {
        return New-LearnBotLocalAgentPairFromWebTokenResult `
            -Status "FAILED" `
            -Plan $PairFromWebTokenPlan `
            -Steps $steps `
            -FailureStep "registerWorkspace"
    }

    $statusResult = & $InvokeAgent -Arguments @("agent", "status")
    $steps += New-LearnBotLocalAgentPairResultStep `
        -Name "showStatus" `
        -Command "learnbot agent status" `
        -Result $statusResult `
        -SecretToRedact $PairingToken `
        -TokenSecretPrinted $false
    if ($statusResult.exitCode -ne 0) {
        return New-LearnBotLocalAgentPairFromWebTokenResult `
            -Status "FAILED" `
            -Plan $PairFromWebTokenPlan `
            -Steps $steps `
            -FailureStep "showStatus"
    }

    New-LearnBotLocalAgentPairFromWebTokenResult `
        -Status "SUCCEEDED" `
        -Plan $PairFromWebTokenPlan `
        -Steps $steps `
        -FailureStep $null
}

function New-LearnBotLocalAgentPairFromWebTokenResult {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("SUCCEEDED", "FAILED")]
        [string]$Status,
        [Parameter(Mandatory = $true)]
        [object]$Plan,
        [Parameter(Mandatory = $true)]
        [object[]]$Steps,
        [string]$FailureStep
    )

    [pscustomobject]@{
        schema = "learnbot.local-agent.pair-from-web-token-result.v1"
        status = $Status
        blocked = $false
        blockedReasons = @()
        failureStep = $FailureStep
        server = $Plan.server
        workspacePath = $Plan.workspacePath
        transport = $Plan.transport
        agentId = $Plan.agentId
        tokenSecretPrinted = $false
        cliPasswordCollected = $false
        steps = $Steps
    }
}

function New-LearnBotLocalAgentPairResultStep {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string]$Command,
        [Parameter(Mandatory = $true)]
        [object]$Result,
        [string]$SecretToRedact,
        [bool]$TokenSecretPrinted = $false
    )

    $output = [string]$Result.output
    if (-not [string]::IsNullOrWhiteSpace($SecretToRedact)) {
        $output = $output.Replace($SecretToRedact, "<redacted-pairing-token>")
    }

    [pscustomobject]@{
        name = $Name
        status = if ($Result.exitCode -eq 0) { "SUCCEEDED" } else { "FAILED" }
        exitCode = $Result.exitCode
        command = $Command
        tokenSecretPrinted = $TokenSecretPrinted
        output = $output
    }
}

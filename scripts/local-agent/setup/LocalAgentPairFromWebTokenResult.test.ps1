$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentPairFromWebTokenPlan.ps1")
. (Join-Path $PSScriptRoot "LocalAgentPairFromWebTokenResult.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-pair-from-web-token-result-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $secret = "secret-token"
    $agentId = "11111111-1111-1111-1111-111111111111"
    $plan = Get-LearnBotLocalAgentPairFromWebTokenPlan `
        -Server "http://localhost:8083/" `
        -WorkspacePath $root `
        -AgentId $agentId `
        -PairingToken $secret `
        -Transport "auto"

    $calls = New-Object System.Collections.Generic.List[object]
    $success = Invoke-LearnBotLocalAgentPairFromWebTokenResult `
        -PairFromWebTokenPlan $plan `
        -PairingToken $secret `
        -InvokeAgent {
            param([string[]]$Arguments)
            $calls.Add($Arguments) | Out-Null
            [pscustomobject]@{
                exitCode = 0
                output = "ok $secret"
            }
        }

    if ($success.schema -ne "learnbot.local-agent.pair-from-web-token-result.v1" -or $success.status -ne "SUCCEEDED") {
        throw "expected succeeded pair-from-web-token result"
    }
    if ($success.steps.Count -ne 3 -or $calls.Count -ne 3) {
        throw "expected three executed result steps"
    }
    if ($success.tokenSecretPrinted -ne $false -or $success.cliPasswordCollected -ne $false) {
        throw "result must not print token or collect CLI password"
    }
    $successJson = $success | ConvertTo-Json -Depth 10
    if ($successJson -match $secret) {
        throw "result must redact token secrets from output"
    }
    if ($success.steps[0].command -notmatch "<pairing-token-from-web>") {
        throw "pair step must use token placeholder in command display"
    }

    $blockedPlan = Get-LearnBotLocalAgentPairFromWebTokenPlan `
        -Server "http://localhost:8083" `
        -WorkspacePath (Join-Path $root "missing") `
        -AgentId "not-a-guid" `
        -PairingToken "" `
        -Transport "polling"
    $blockedCalls = New-Object System.Collections.Generic.List[object]
    $blocked = Invoke-LearnBotLocalAgentPairFromWebTokenResult `
        -PairFromWebTokenPlan $blockedPlan `
        -PairingToken "" `
        -InvokeAgent {
            param([string[]]$Arguments)
            $blockedCalls.Add($Arguments) | Out-Null
            [pscustomobject]@{ exitCode = 0; output = "should-not-run" }
        }
    if ($blocked.status -ne "BLOCKED" -or $blocked.blocked -ne $true -or $blockedCalls.Count -ne 0) {
        throw "blocked result should not execute local commands"
    }
    if ($blocked.blockedReasons -notcontains "workspacePath" -or $blocked.blockedReasons -notcontains "agentId" -or $blocked.blockedReasons -notcontains "pairingToken") {
        throw "blocked result should keep readiness blockers"
    }

    $failureCalls = New-Object System.Collections.Generic.List[object]
    $failed = Invoke-LearnBotLocalAgentPairFromWebTokenResult `
        -PairFromWebTokenPlan $plan `
        -PairingToken $secret `
        -InvokeAgent {
            param([string[]]$Arguments)
            $failureCalls.Add($Arguments) | Out-Null
            if ($failureCalls.Count -eq 2) {
                [pscustomobject]@{ exitCode = 42; output = "workspace failed $secret" }
            } else {
                [pscustomobject]@{ exitCode = 0; output = "ok" }
            }
        }
    if ($failed.status -ne "FAILED" -or $failed.failureStep -ne "registerWorkspace") {
        throw "expected registerWorkspace failure result"
    }
    if ($failed.steps.Count -ne 2 -or $failureCalls.Count -ne 2) {
        throw "failed result should stop after the failing step"
    }
    $failedJson = $failed | ConvertTo-Json -Depth 10
    if ($failedJson -match $secret) {
        throw "failed result must redact token secrets"
    }

    "local-agent-pair-from-web-token-result-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

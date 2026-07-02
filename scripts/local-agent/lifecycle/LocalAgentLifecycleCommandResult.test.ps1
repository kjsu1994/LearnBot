$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentLifecycleCommandResult.ps1")

$success = Invoke-LearnBotLocalAgentLifecycleCommandResult `
    -LifecycleAction "logs" `
    -InvokeCommand {
        param([string]$LifecycleAction)
        [pscustomobject]@{
            exitCode = 0
            output = "log output"
        }
    }

if ($success.schema -ne "learnbot.local-agent.lifecycle-command-result.v1") {
    throw "unexpected schema"
}
if ($success.status -ne "SUCCEEDED" -or $success.action -ne "logs" -or $success.exitCode -ne 0) {
    throw "expected successful lifecycle command result"
}
if ($success.tokenSecretPrinted -ne $false -or $success.cliPasswordCollected -ne $false -or $success.serviceCommandExecuted -ne $false) {
    throw "lifecycle command result must keep safety flags disabled"
}

$background = Invoke-LearnBotLocalAgentLifecycleCommandResult `
    -LifecycleAction "background-start" `
    -InvokeCommand {
        param([string]$LifecycleAction)
        [pscustomobject]@{
            exitCode = 0
            output = "started"
        }
    }
if ($background.backgroundProcessRequested -ne $true -or $background.stopRequested -ne $false) {
    throw "expected background-start request flags"
}

$failed = Invoke-LearnBotLocalAgentLifecycleCommandResult `
    -LifecycleAction "background-stop" `
    -InvokeCommand {
        param([string]$LifecycleAction)
        [pscustomobject]@{
            exitCode = 7
            output = "not running"
        }
    }
if ($failed.status -ne "FAILED" -or $failed.exitCode -ne 7 -or $failed.stopRequested -ne $true) {
    throw "expected failed lifecycle command result"
}

$json = $failed | ConvertTo-Json -Depth 10
if ($json -match "secret-token") {
    throw "lifecycle command result must not print token secrets"
}

"local-agent-lifecycle-command-result-contract-ok"

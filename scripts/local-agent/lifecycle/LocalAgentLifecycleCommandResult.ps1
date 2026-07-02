function Invoke-LearnBotLocalAgentLifecycleCommandResult {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("background-start", "background-stop", "status", "logs", "doctor")]
        [string]$LifecycleAction,
        [Parameter(Mandatory = $true)]
        [scriptblock]$InvokeCommand
    )

    $result = & $InvokeCommand -LifecycleAction $LifecycleAction
    $exitCode = if ($null -eq $result.exitCode) { 1 } else { [int]$result.exitCode }

    [pscustomobject]@{
        schema = "learnbot.local-agent.lifecycle-command-result.v1"
        action = $LifecycleAction
        status = if ($exitCode -eq 0) { "SUCCEEDED" } else { "FAILED" }
        exitCode = $exitCode
        output = [string]$result.output
        tokenSecretPrinted = $false
        cliPasswordCollected = $false
        serviceCommandExecuted = $false
        backgroundProcessRequested = $LifecycleAction -eq "background-start"
        stopRequested = $LifecycleAction -eq "background-stop"
    }
}

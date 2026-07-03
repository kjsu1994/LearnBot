function Invoke-LearnBotLocalAgentM8LifecycleRunResult {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$InitialStatus,
        [Parameter(Mandatory = $true)]
        [scriptblock]$InvokeLifecycleCommand
    )

    $configured = $InitialStatus.configured -eq $true
    $approvedWorkspaceCount = if ($null -ne $InitialStatus.approvedWorkspaceCount) { [int]$InitialStatus.approvedWorkspaceCount } else { 0 }
    $alreadyRunning = $InitialStatus.running -eq $true
    $steps = @()
    $blockedReasons = @()

    if (-not $configured) {
        $blockedReasons += "agent is not paired"
    }
    if ($approvedWorkspaceCount -le 0) {
        $blockedReasons += "no approved workspace is registered"
    }

    if ($blockedReasons.Count -gt 0) {
        return [pscustomobject]@{
            schema = "learnbot.local-agent.m8-lifecycle-run-result.v1"
            status = "BLOCKED"
            ready = $false
            blockedReasons = $blockedReasons
            initialRunning = $alreadyRunning
            duplicateStartPrevented = $alreadyRunning
            startAttempted = $false
            steps = $steps
            tokenSecretPrinted = $false
            cliPasswordCollected = $false
            serviceCommandExecuted = $false
            signedInstallerUsed = $false
            autoUpdateExecuted = $false
        }
    }

    if ($alreadyRunning) {
        $steps += [pscustomobject]@{
            name = "background-start"
            status = "SKIPPED"
            reason = "agent already running"
        }
    } else {
        $start = & $InvokeLifecycleCommand -LifecycleAction "background-start"
        $steps += [pscustomobject]@{
            name = "background-start"
            status = $start.status
            exitCode = $start.exitCode
            output = $start.output
        }
    }

    $status = & $InvokeLifecycleCommand -LifecycleAction "status"
    $steps += [pscustomobject]@{
        name = "status"
        status = $status.status
        exitCode = $status.exitCode
        output = $status.output
    }

    $logs = & $InvokeLifecycleCommand -LifecycleAction "logs"
    $steps += [pscustomobject]@{
        name = "logs"
        status = $logs.status
        exitCode = $logs.exitCode
        output = $logs.output
    }

    $failed = @($steps | Where-Object { $_.status -eq "FAILED" })
    [pscustomobject]@{
        schema = "learnbot.local-agent.m8-lifecycle-run-result.v1"
        status = if ($failed.Count -eq 0) { "SUCCEEDED" } else { "FAILED" }
        ready = $failed.Count -eq 0
        blockedReasons = @()
        initialRunning = $alreadyRunning
        duplicateStartPrevented = $alreadyRunning
        startAttempted = -not $alreadyRunning
        steps = $steps
        tokenSecretPrinted = $false
        cliPasswordCollected = $false
        serviceCommandExecuted = $false
        signedInstallerUsed = $false
        autoUpdateExecuted = $false
    }
}

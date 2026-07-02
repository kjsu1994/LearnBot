$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentLifecycleStatus.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-lifecycle-status-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $configPath = Join-Path $root "agent.json"
    $statePath = Join-Path $root "agent-state.json"
    $logPath = Join-Path $root "agent.log"
    $agentExe = Join-Path $root "learnbot.exe"

    [pscustomobject]@{
        serverUrl = "http://localhost:8083"
        agentId = "11111111-1111-1111-1111-111111111111"
        token = "secret-token"
        transport = "auto"
        workspaces = @(
            [pscustomobject]@{
                id = "workspace-1"
                path = $root
                approved = $true
            }
        )
    } | ConvertTo-Json -Depth 10 | Set-Content -Path $configPath -Encoding UTF8

    [pscustomobject]@{
        status = "running"
        processId = 999999
        startedAt = "2026-07-02T00:00:00.0000000Z"
        updatedAt = "2026-07-02T00:00:01.0000000Z"
        lastEvent = "heartbeat"
        logPath = $logPath
    } | ConvertTo-Json -Depth 10 | Set-Content -Path $statePath -Encoding UTF8

    Set-Content -Path $logPath -Value "agent start" -Encoding ASCII
    Set-Content -Path $agentExe -Value "not a real executable" -Encoding ASCII

    $status = Get-LearnBotLocalAgentLifecycleStatus `
        -ConfigPath $configPath `
        -StatePath $statePath `
        -LogPath $logPath `
        -AgentExe $agentExe `
        -ServiceName "LearnBotLocalAgentContractTest"

    if ($status.schema -ne "learnbot.local-agent.lifecycle-status.v1") {
        throw "unexpected schema"
    }
    if ($status.configured -ne $true -or $status.workspaceCount -ne 1 -or $status.approvedWorkspaceCount -ne 1) {
        throw "expected configured agent with one approved workspace"
    }
    if ($status.running -ne $false -or $status.processRunning -ne $false -or $status.staleState -ne $true) {
        throw "expected stale running state when process is absent"
    }
    if ($status.service.installed -ne $false -or $status.service.registrationEnabled -ne $false) {
        throw "service registration must stay disabled in the pilot contract"
    }
    if ($status.safety.typedToolsOnly -ne $true -or $status.safety.arbitraryShellExecution -ne $false -or $status.safety.serverLocalMutationEnabled -ne $false) {
        throw "unexpected lifecycle safety boundary"
    }

    $json = $status | ConvertTo-Json -Depth 10
    if ($json -match "secret-token") {
        throw "lifecycle status must not print token secrets"
    }
    if ($json -notmatch "background-start" -or $json -notmatch "background-stop") {
        throw "expected lifecycle commands"
    }

    "local-agent-lifecycle-status-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

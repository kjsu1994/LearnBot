$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentServicePlan.ps1")
. (Join-Path $PSScriptRoot "LocalAgentServiceCommandPlan.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-service-command-plan-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $installDir = Join-Path $root "bin"
    $configPath = Join-Path $root "agent.json"
    $exe = Join-Path $installDir "learnbot.exe"
    New-Item -ItemType Directory -Path $installDir -Force | Out-Null
    Set-Content -Path $exe -Value "not a real executable" -Encoding ASCII

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

    $servicePlan = Get-LearnBotLocalAgentServicePlan `
        -InstallDir $installDir `
        -Executable $exe `
        -ConfigPath $configPath `
        -ServiceName "LearnBotLocalAgentContractTest" `
        -Transport "auto"

    $install = Get-LearnBotLocalAgentServiceCommandPlan -ServiceAction "install" -ServicePlan $servicePlan
    if ($install.schema -ne "learnbot.local-agent.service-command-plan.v1") {
        throw "unexpected schema"
    }
    if ($install.executionEnabled -ne $false -or $install.blocked -ne $true) {
        throw "service command execution must stay disabled"
    }
    if ($install.blockedReasons -notcontains "serviceCommandExecutionDisabled") {
        throw "expected disabled execution blocker"
    }
    if ($install.prerequisites.readyToInstall -ne $true) {
        throw "expected install prerequisites to be ready in fixture"
    }

    $start = Get-LearnBotLocalAgentServiceCommandPlan -ServiceAction "start" -ServicePlan $servicePlan
    if ($start.blockedReasons -notcontains "serviceNotInstalled") {
        throw "expected start to be blocked when service is absent"
    }
    if ($start.safety.requiresAdminApproval -ne $true -or $start.safety.executesServiceCommand -ne $false) {
        throw "unexpected service command safety boundary"
    }

    $json = $install | ConvertTo-Json -Depth 10
    if ($json -match "secret-token") {
        throw "service command plan must not print token secrets"
    }

    "local-agent-service-command-plan-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

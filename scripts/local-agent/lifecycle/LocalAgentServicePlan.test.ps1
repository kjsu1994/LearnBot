$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentServicePlan.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-service-plan-" + [Guid]::NewGuid().ToString("N"))
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

    $ready = Get-LearnBotLocalAgentServicePlan `
        -InstallDir $installDir `
        -Executable $exe `
        -ConfigPath $configPath `
        -ServiceName "LearnBotLocalAgentContractTest" `
        -Transport "auto" `
        -IntervalSeconds 20

    if ($ready.schema -ne "learnbot.local-agent.service-plan.v1") {
        throw "unexpected schema"
    }
    if ($ready.previewOnly -ne $true -or $ready.readyToInstall -ne $true) {
        throw "expected ready preview-only service plan"
    }
    if ($ready.service.installEnabled -ne $false -or $ready.service.startEnabled -ne $false -or $ready.service.uninstallEnabled -ne $false) {
        throw "service plan must not enable service commands"
    }
    if ($ready.safety.requiresAdminApproval -ne $true -or $ready.safety.executesServiceCommand -ne $false) {
        throw "unexpected service safety boundary"
    }
    if ($ready.plannedCommands.install -notmatch "learnbot.exe" -or $ready.plannedCommands.install -notmatch "--transport auto") {
        throw "expected install command preview"
    }

    $json = $ready | ConvertTo-Json -Depth 10
    if ($json -match "secret-token") {
        throw "service plan must not print token secrets"
    }

    Remove-Item -LiteralPath $exe -Force
    $blocked = Get-LearnBotLocalAgentServicePlan `
        -InstallDir $installDir `
        -Executable $exe `
        -ConfigPath $configPath `
        -ServiceName "LearnBotLocalAgentContractTest" `
        -Transport "polling"

    if ($blocked.readyToInstall -ne $false -or $blocked.missingPrerequisites -notcontains "installedExecutable") {
        throw "expected missing executable to block service plan"
    }

    "local-agent-service-plan-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

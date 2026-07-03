$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentServicePlan.ps1")
. (Join-Path $PSScriptRoot "LocalAgentServiceCommandResult.ps1")

function Test-LearnBotLocalAgentAdministrator {
    $true
}

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-service-command-result-" + [Guid]::NewGuid().ToString("N"))
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

    $install = Invoke-LearnBotLocalAgentServiceCommandResult `
        -ServiceAction "install" `
        -ServicePlan $servicePlan `
        -InvokeServiceCommand {
            [pscustomobject]@{ exitCode = 0; output = "installed" }
        }

    if ($install.schema -ne "learnbot.local-agent.service-command-result.v1") {
        throw "unexpected schema"
    }
    if ($install.attempted -ne $true -or $install.succeeded -ne $true -or $install.blocked -ne $false) {
        throw "expected successful install command result"
    }
    if ($install.safety.executesServiceCommand -ne $true -or $install.safety.tokenSecretPrinted -ne $false) {
        throw "unexpected install safety flags"
    }

    $start = Invoke-LearnBotLocalAgentServiceCommandResult `
        -ServiceAction "start" `
        -ServicePlan $servicePlan `
        -InvokeServiceCommand {
            throw "start should remain blocked before execution"
        }

    if ($start.attempted -ne $false -or $start.blockedReasons -notcontains "serviceNotInstalled") {
        throw "expected start to block when service is absent"
    }

    $json = $install | ConvertTo-Json -Depth 10
    if ($json -match "secret-token") {
        throw "service command result must not print token secrets"
    }

    "local-agent-service-command-result-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

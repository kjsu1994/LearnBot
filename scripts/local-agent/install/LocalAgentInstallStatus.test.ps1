$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "LocalAgentInstallStatus.ps1")

$root = Join-Path ([System.IO.Path]::GetTempPath()) ("learnbot-install-status-" + [Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $exe = Join-Path $root "learnbot.exe"
    Set-Content -Path $exe -Value "not a real executable" -Encoding ASCII

    $status = Get-LearnBotInstallStatus -InstallDir $root -Executable $exe
    if ($status.schema -ne "learnbot.local-agent.install-status.v1") {
        throw "unexpected schema"
    }
    if ($status.installMode -ne "internal-pilot") {
        throw "unexpected install mode"
    }
    if ($status.installed -ne $true) {
        throw "expected installed=true"
    }
    if ($status.limitations.windowsService -ne $false -or $status.limitations.signedInstaller -ne $false -or $status.limitations.autoUpdate -ne $false) {
        throw "pilot limitations must stay explicit"
    }
    if ([string]::IsNullOrWhiteSpace($status.commands.status) -or [string]::IsNullOrWhiteSpace($status.commands.doctor)) {
        throw "expected recommended commands"
    }

    $json = $status | ConvertTo-Json -Depth 10
    if ($json -notmatch "learnbot.local-agent.install-status.v1") {
        throw "json contract missing schema"
    }

    "local-agent-install-status-contract-ok"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

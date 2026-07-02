param(
    [string]$Server = "http://localhost:8083",
    [string]$LoginId = $env:LEARNBOT_SMOKE_LOGIN_ID,
    [string]$Password = $env:LEARNBOT_SMOKE_PASSWORD,
    [ValidateSet("polling", "websocket")]
    [string]$Transport = "polling",
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$qualityDir = Join-Path $root ".tmp\quality"
$approvedFlowDir = Join-Path $qualityDir "local-agent-approved-flow"
$workspacePath = Join-Path $qualityDir ("local-agent-live-bridge-workspace-" + [Guid]::NewGuid().ToString("N"))
$approvedReportPath = Join-Path $approvedFlowDir "live-server-approved-flow-bridge-approved-report.json"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    New-Item -ItemType Directory -Force -Path $approvedFlowDir | Out-Null
    $ReportPath = Join-Path $approvedFlowDir "live-server-approved-flow-bridge-smoke-report.json"
}

function Remove-TemporaryWorkspace {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        return
    }

    $resolvedQualityDir = (Resolve-Path $qualityDir).Path
    $resolvedWorkspace = (Resolve-Path $Path).Path
    if (-not $resolvedWorkspace.StartsWith($resolvedQualityDir, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove workspace outside quality temp directory: $resolvedWorkspace"
    }
    Remove-Item -LiteralPath $resolvedWorkspace -Recurse -Force
}

Push-Location $root
try {
    New-Item -ItemType Directory -Force -Path $workspacePath | Out-Null
    Set-Content -Path (Join-Path $workspacePath "README.md") -Value @(
        "# LearnBot Local Agent live bridge smoke"
        ""
        "This temporary workspace proves live Spring queue polling with file.read."
    ) -Encoding UTF8

    $liveSmokeArgs = @(
        "-ExecutionPolicy", "Bypass",
        "-File", ".\scripts\local-agent-smoke.ps1",
        "-Server", $Server,
        "-WorkspacePath", $workspacePath,
        "-ToolName", "file.read",
        "-Path", "README.md",
        "-Transport", $Transport
    )
    if (-not [string]::IsNullOrWhiteSpace($LoginId)) {
        $liveSmokeArgs += @("-LoginId", $LoginId)
    }
    if (-not [string]::IsNullOrWhiteSpace($Password)) {
        $liveSmokeArgs += @("-Password", $Password)
    }

    powershell.exe @liveSmokeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "live Spring Local Agent queue smoke failed with exit code $LASTEXITCODE"
    }

    powershell.exe -ExecutionPolicy Bypass -File .\scripts\quality\local-agent-flow\run-approved-server-queue-flow-smoke.ps1 `
        -ReportPath $approvedReportPath
    if ($LASTEXITCODE -ne 0) {
        throw "approved mutation runtime bridge smoke failed with exit code $LASTEXITCODE"
    }

    $summary = [pscustomobject]@{
        schema = "learnbot.quality.local-agent-live-server-approved-flow-bridge.v1"
        status = "passed"
        liveServerReadOnlyQueue = [pscustomobject]@{
            status = "passed"
            server = $Server
            transport = $Transport
            toolName = "file.read"
            path = "README.md"
            workspacePath = (Resolve-Path $workspacePath).Path
            workspaceWasTemporary = $true
        }
        approvedMutationRuntime = [pscustomobject]@{
            status = "passed"
            contract = "approved-server-queue-flow-contract"
            reportPath = (Resolve-Path $approvedReportPath).Path
        }
        limitations = @(
            "Live Spring server path currently exposes read-only enqueue only.",
            "Approved patch.apply, command.runAllowed, git.status, and rollback.restore are proven through the Local Agent approved server-queue runtime contract.",
            "Automatic final-answer publication and acknowledgement save remain disabled."
        )
    }

    $summary | ConvertTo-Json -Depth 8 | Set-Content -Path $ReportPath -Encoding UTF8
    $summary | ConvertTo-Json -Depth 8
} finally {
    Remove-TemporaryWorkspace -Path $workspacePath
    Pop-Location
}

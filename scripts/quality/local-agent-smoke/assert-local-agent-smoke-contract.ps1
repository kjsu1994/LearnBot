param(
    [string]$ScriptPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
    $ScriptPath = Join-Path $root "scripts\local-agent-smoke.ps1"
}

if (-not (Test-Path $ScriptPath)) {
    throw "Local Agent smoke script not found: $ScriptPath"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    $messages = ($parseErrors | ForEach-Object { $_.Message }) -join "; "
    throw "Local Agent smoke script does not parse: $messages"
}

$source = Get-Content -Raw $ScriptPath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern
    )

    if ($source -notmatch $Pattern) {
        throw "Local Agent smoke contract missing: $Name"
    }
}

Assert-Contains "bounded transport choices" '\[ValidateSet\("polling", "websocket"\)\]\s*\[string\]\$Transport'
Assert-Contains "bounded read-only tool choices" '\[ValidateSet\("file\.read", "git\.status", "git\.diff"\)\]\s*\[string\]\$ToolName'
Assert-Contains "websocket hidden foreground process" '-WindowStyle Hidden'
Assert-Contains "websocket ready log gate" 'websocket hello acknowledged'
Assert-Contains "websocket fallback log gate" 'websocket connect failed\|falling back to polling'
Assert-Contains "websocket pre-enqueue failure message" 'Local Agent WebSocket did not become ready before enqueue'
Assert-Contains "websocket completion path proof" 'websocket tool \.\* requestId=\$\(\$queued\.requestId\)'
Assert-Contains "polling once execution" 'agent start --once --transport polling'
Assert-Contains "agent process cleanup" 'Stop-Process -Id \$agentProcess\.Id -Force'
Assert-Contains "temporary agent config cleanup" 'Remove-Item Env:\\LEARNBOT_AGENT_CONFIG'

$websocketStart = $source.IndexOf('if ($Transport -eq "websocket")')
$enqueue = $source.IndexOf('$queued = Invoke-Json -Method POST')
$readyFlag = $source.IndexOf('$ready = $true')
$postCompletionProof = $source.IndexOf('Local Agent WebSocket smoke did not complete through the WebSocket tool path')

if ($websocketStart -lt 0 -or $enqueue -lt 0 -or $readyFlag -lt 0) {
    throw "Local Agent smoke contract missing WebSocket startup or enqueue sections"
}
if ($websocketStart -gt $enqueue) {
    throw "Local Agent smoke must start and verify WebSocket readiness before enqueueing a request"
}
if ($readyFlag -lt $websocketStart -or $readyFlag -gt $enqueue) {
    throw "Local Agent smoke must set WebSocket readiness before enqueueing a request"
}
if ($postCompletionProof -lt $enqueue) {
    throw "Local Agent smoke must verify WebSocket tool-path completion after request enqueue"
}

Write-Output "local-agent-smoke-contract-ok"

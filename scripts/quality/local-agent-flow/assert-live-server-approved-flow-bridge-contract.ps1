param(
    [string]$ScriptPath = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
    $ScriptPath = Join-Path $root "scripts\quality\local-agent-flow\run-live-server-approved-flow-bridge-smoke.ps1"
}

if (-not (Test-Path $ScriptPath)) {
    throw "Live server approved flow bridge smoke script not found: $ScriptPath"
}

$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($ScriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -gt 0) {
    $messages = ($parseErrors | ForEach-Object { $_.Message }) -join "; "
    throw "Live server approved flow bridge smoke script does not parse: $messages"
}

$source = Get-Content -Raw $ScriptPath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern
    )

    if ($source -notmatch $Pattern) {
        throw "Live server approved flow bridge contract missing: $Name"
    }
}

Assert-Contains "bounded transport choices" '\[ValidateSet\("polling", "websocket"\)\]\s*\[string\]\$Transport'
Assert-Contains "temporary workspace" 'local-agent-live-bridge-workspace-'
Assert-Contains "live Local Agent smoke script" 'scripts\\local-agent-smoke\.ps1'
Assert-Contains "live read-only file tool" '"-ToolName", "file\.read"'
Assert-Contains "polling target file" '"-Path", "README\.md"'
Assert-Contains "approved runtime wrapper" 'run-approved-server-queue-flow-smoke\.ps1'
Assert-Contains "approved runtime report" 'live-server-approved-flow-bridge-approved-report\.json'
Assert-Contains "bridge schema" 'learnbot\.quality\.local-agent-live-server-approved-flow-bridge\.v1'
Assert-Contains "read-only limitation" 'Live Spring server path currently exposes read-only enqueue only\.'
Assert-Contains "publication remains disabled" 'Automatic final-answer publication and acknowledgement save remain disabled\.'
Assert-Contains "safe temp cleanup guard" 'Refusing to remove workspace outside quality temp directory'

$liveSmoke = $source.IndexOf('scripts\local-agent-smoke.ps1')
$approvedSmoke = $source.IndexOf('run-approved-server-queue-flow-smoke.ps1')
if ($liveSmoke -lt 0 -or $approvedSmoke -lt 0) {
    throw "Live server approved flow bridge must run both live smoke and approved runtime smoke"
}
if ($liveSmoke -gt $approvedSmoke) {
    throw "Live server queue smoke must run before approved runtime bridge smoke"
}

Write-Output "live-server-approved-flow-bridge-contract-ok"

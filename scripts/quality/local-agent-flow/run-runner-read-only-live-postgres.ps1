param(
    [string]$MavenPath = ".\.tools\apache-maven-3.9.9\bin\mvn.cmd",
    [string]$BackendPom = "backend\pom.xml"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
Push-Location $root
try {
    & $MavenPath `
        -f $BackendPom `
        "-Dlearnbot.live-postgres-tests=true" `
        "-Dtest=LocalAgentToolExecutionRepositoryLivePostgresTest#runnerReadOnlyQueuePersistsClaimCompletionAndLoopTimelineEvents" `
        test
    if ($LASTEXITCODE -ne 0) {
        throw "runner read-only live PostgreSQL proof failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

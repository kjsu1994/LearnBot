param(
    [switch]$DryRun,
    [switch]$DockerCache,
    [string]$Until = "168h"
)

$ErrorActionPreference = "Stop"

Write-Host "LearnBot cleanup helper"
Write-Host "DryRun=$DryRun DockerCache=$DockerCache Until=$Until"
Write-Host ""

if ($DockerCache) {
    Write-Host "Docker disk usage:"
    docker system df
    Write-Host ""

    if ($DryRun) {
        Write-Host "Dry run only. To prune build cache and dangling images, run:"
        Write-Host "  .\scripts\cleanup.ps1 -DockerCache -Until $Until"
        Write-Host ""
        Write-Host "Named volumes are intentionally never pruned by this script."
        exit 0
    }

    Write-Host "Pruning Docker builder cache older than $Until..."
    docker builder prune --force --filter "until=$Until"
    Write-Host ""
    Write-Host "Pruning dangling Docker images..."
    docker image prune --force
    Write-Host ""
    Write-Host "Docker disk usage after prune:"
    docker system df
    exit 0
}

Write-Host "No cleanup target selected."
Write-Host "Examples:"
Write-Host "  .\scripts\cleanup.ps1 -DockerCache -DryRun"
Write-Host "  .\scripts\cleanup.ps1 -DockerCache -Until 168h"

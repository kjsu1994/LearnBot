param(
    [ValidateSet("install", "status", "uninstall")]
    [string]$Action = "install",
    [string]$InstallDir = (Join-Path $env:USERPROFILE ".learnbot\bin"),
    [string]$Runtime = "win-x64",
    [string]$Configuration = "Release",
    [switch]$AddToUserPath,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$project = Join-Path $repoRoot "local-agent\LearnBot.LocalAgent.csproj"
$exe = Join-Path $InstallDir "learnbot.exe"

function Test-OnUserPath {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ([string]::IsNullOrWhiteSpace($userPath)) {
        return $false
    }
    $comparison = [StringComparison]::OrdinalIgnoreCase
    foreach ($entry in $userPath.Split(';', [StringSplitOptions]::RemoveEmptyEntries)) {
        if ([string]::Equals([System.IO.Path]::GetFullPath($entry.Trim()), [System.IO.Path]::GetFullPath($InstallDir), $comparison)) {
            return $true
        }
    }
    return $false
}

function Add-ToUserPath {
    if (Test-OnUserPath) {
        return
    }
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $InstallDir } else { "$userPath;$InstallDir" }
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    $env:Path = "$env:Path;$InstallDir"
}

function Show-Status {
    [pscustomobject]@{
        installDir = [System.IO.Path]::GetFullPath($InstallDir)
        executable = $exe
        installed = Test-Path -LiteralPath $exe -PathType Leaf
        onUserPath = Test-OnUserPath
    } | ConvertTo-Json -Depth 5
}

switch ($Action) {
    "install" {
        if ($Clean -and (Test-Path -LiteralPath $InstallDir)) {
            Remove-Item -LiteralPath $InstallDir -Recurse -Force
        }
        New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null

        dotnet publish $project -c $Configuration -r $Runtime --self-contained false -o $InstallDir | Out-Host
        if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) {
            throw "Published executable was not found: $exe"
        }

        & $exe --help | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Published learnbot executable failed its help smoke check."
        }

        if ($AddToUserPath) {
            Add-ToUserPath
        }

        Write-Host ""
        Write-Host "LearnBot Local Agent installed:"
        Write-Host "  $exe"
        if ($AddToUserPath) {
            Write-Host "User PATH updated. Open a new PowerShell window if 'learnbot' is not immediately available."
        } else {
            Write-Host "Run directly:"
            Write-Host "  & `"$exe`" agent status"
            Write-Host "Or reinstall with -AddToUserPath to run 'learnbot' from a new PowerShell window."
        }
        Write-Host ""
        Write-Host "This is a lightweight internal pilot install, not a Windows Service, MSI, updater, or background process manager."
        Show-Status
    }
    "status" {
        Show-Status
    }
    "uninstall" {
        if (Test-Path -LiteralPath $InstallDir) {
            Remove-Item -LiteralPath $InstallDir -Recurse -Force
        }
        Show-Status
    }
}

function Test-LearnBotInstallOnUserPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstallDir
    )
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ([string]::IsNullOrWhiteSpace($userPath)) {
        return $false
    }
    $installFullPath = [System.IO.Path]::GetFullPath($InstallDir)
    $comparison = [StringComparison]::OrdinalIgnoreCase
    foreach ($entry in $userPath.Split(';', [StringSplitOptions]::RemoveEmptyEntries)) {
        if ([string]::Equals([System.IO.Path]::GetFullPath($entry.Trim()), $installFullPath, $comparison)) {
            return $true
        }
    }
    return $false
}

function Get-LearnBotInstallStatus {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstallDir,
        [string]$Executable = (Join-Path $InstallDir "learnbot.exe"),
        [switch]$IncludeExecutableStatus
    )

    $installed = Test-Path -LiteralPath $Executable -PathType Leaf
    $onUserPath = Test-LearnBotInstallOnUserPath -InstallDir $InstallDir
    $executableStatus = $null
    if ($IncludeExecutableStatus -and $installed) {
        $output = & $Executable status 2>$null
        if ($LASTEXITCODE -eq 0 -and $output) {
            try {
                $executableStatus = ($output -join [Environment]::NewLine) | ConvertFrom-Json
            } catch {
                $executableStatus = $null
            }
        }
    }

    [pscustomobject]@{
        schema = "learnbot.local-agent.install-status.v1"
        installMode = "internal-pilot"
        installDir = [System.IO.Path]::GetFullPath($InstallDir)
        executable = $Executable
        installed = $installed
        onUserPath = $onUserPath
        executableStatusAvailable = $null -ne $executableStatus
        executableStatus = $executableStatus
        commands = [pscustomobject]@{
            status = if ($onUserPath) { "learnbot status" } else { "& `"$Executable`" status" }
            doctor = if ($onUserPath) { "learnbot doctor" } else { "& `"$Executable`" doctor" }
            start = if ($onUserPath) { "learnbot agent start" } else { "& `"$Executable`" agent start" }
        }
        limitations = [pscustomobject]@{
            windowsService = $false
            signedInstaller = $false
            autoUpdate = $false
            backgroundProcessManager = $false
        }
    }
}

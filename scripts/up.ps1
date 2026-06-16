param(
    [switch]$Build,
    [switch]$Cpu
)

$ErrorActionPreference = "Stop"

function Invoke-Compose {
    param(
        [string[]]$ComposeFiles
    )

    $args = @()
    foreach ($file in $ComposeFiles) {
        $args += @("-f", $file)
    }
    $args += @("up", "-d")
    if ($Build) {
        $args += "--build"
    }

    & docker compose @args
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose exited with code $LASTEXITCODE"
    }
}

function Test-NvidiaSmi {
    $command = Get-Command nvidia-smi -ErrorAction SilentlyContinue
    if (-not $command) {
        return $false
    }

    & nvidia-smi --query-gpu=name --format=csv,noheader | Out-Null
    return $LASTEXITCODE -eq 0
}

$baseFiles = @("docker-compose.yml")
$gpuFiles = @("docker-compose.yml", "docker-compose.gpu.yml")

if ($Cpu) {
    Write-Host "Starting LearnBot with CPU Ollama."
    Invoke-Compose -ComposeFiles $baseFiles
    exit $LASTEXITCODE
}

if (Test-NvidiaSmi) {
    Write-Host "NVIDIA GPU detected. Starting LearnBot with GPU Ollama."
    try {
        Invoke-Compose -ComposeFiles $gpuFiles
        exit 0
    } catch {
        Write-Warning "GPU compose failed. Falling back to CPU Ollama. $($_.Exception.Message)"
    }
} else {
    Write-Host "No NVIDIA GPU detected. Starting LearnBot with CPU Ollama."
}

Invoke-Compose -ComposeFiles $baseFiles
exit 0

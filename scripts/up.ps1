param(
    [switch]$Build,
    [switch]$NoBuild,
    [switch]$Cpu,
    [switch]$Reranker,
    [switch]$PullOllama
)

$ErrorActionPreference = "Stop"
$shouldBuild = $Build -or -not $NoBuild

if ([string]::IsNullOrWhiteSpace($env:OLLAMA_CONTEXT_LENGTH) -or $env:OLLAMA_CONTEXT_LENGTH -eq "2048") {
    $env:OLLAMA_CONTEXT_LENGTH = "4096"
}

if ([string]::IsNullOrWhiteSpace($env:LLM_CONTEXT_WINDOW)) {
    $env:LLM_CONTEXT_WINDOW = $env:OLLAMA_CONTEXT_LENGTH
}

function Invoke-Compose {
    param(
        [string[]]$ComposeFiles
    )

    $args = @()
    foreach ($file in $ComposeFiles) {
        $args += @("-f", $file)
    }
    if ($Reranker) {
        $args += @("--profile", "reranker")
        $env:LEARNBOT_RERANKER_ENABLED = "true"
    }
    $args += @("up", "-d")
    if ($shouldBuild) {
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

function Build-FrontendDist {
    if (-not $shouldBuild) {
        return
    }
    Write-Host "Building frontend dist for nginx volume mount."
    Push-Location frontend
    try {
        & npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "npm run build exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Update-OllamaImages {
    if (-not $PullOllama) {
        return
    }

    Write-Host "Pulling latest Ollama images."
    & docker compose pull ollama ollama-pull
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose pull ollama ollama-pull exited with code $LASTEXITCODE"
    }
}

$baseFiles = @("docker-compose.yml")
$gpuFiles = @("docker-compose.yml", "docker-compose.gpu.yml")

Build-FrontendDist
Update-OllamaImages

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

$ErrorActionPreference = "Stop"

if (-not $env:GEMINI_API_KEY) {
    Write-Error "Set GEMINI_API_KEY before starting the proxy."
}

if (-not $env:GEMINI_MODEL) {
    $env:GEMINI_MODEL = "gemini-3.5-flash"
}

if (-not $env:GEMINI_OLLAMA_PROXY_PORT) {
    $env:GEMINI_OLLAMA_PROXY_PORT = "11435"
}

node "$PSScriptRoot\server.mjs"

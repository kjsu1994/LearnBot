$ErrorActionPreference = "Stop"

if (-not $env:GEMINI_MODEL) {
    $env:GEMINI_MODEL = "gemini-3.1-flash-lite"
}

if (-not $env:GEMINI_OLLAMA_PROXY_PORT) {
    $env:GEMINI_OLLAMA_PROXY_PORT = "11435"
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required to start the proxy."
}

$proxyPort = 0
if (-not [int]::TryParse($env:GEMINI_OLLAMA_PROXY_PORT, [ref]$proxyPort) -or $proxyPort -lt 1 -or $proxyPort -gt 65535) {
    throw "GEMINI_OLLAMA_PROXY_PORT must be between 1 and 65535."
}
if (@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object Port -eq $proxyPort).Count -gt 0) {
    throw "Port $proxyPort is already in use. Stop the existing proxy in its own terminal or choose another GEMINI_OLLAMA_PROXY_PORT."
}

$previousKey = $env:GEMINI_API_KEY
try {
    if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) {
        $secureKey = Read-Host "Gemini API key (hidden; not saved)" -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
        try {
            $env:GEMINI_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr).Trim()
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
            $secureKey.Dispose()
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) {
        throw "Gemini API key must not be empty."
    }
    node "$PSScriptRoot\server.mjs"
    if ($LASTEXITCODE -ne 0) { throw "Proxy exited with code $LASTEXITCODE" }
} finally {
    $env:GEMINI_API_KEY = $previousKey
    $previousKey = $null
}

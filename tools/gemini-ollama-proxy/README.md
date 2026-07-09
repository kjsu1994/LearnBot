# Gemini Ollama-compatible test proxy

This is a temporary local test proxy for LearnBot. It lets LearnBot keep calling an Ollama-shaped API while chat generation is forwarded to the Gemini OpenAI-compatible endpoint.

It is intentionally isolated under `tools/gemini-ollama-proxy` and does not change LearnBot backend code.

## Start

```powershell
$env:GEMINI_API_KEY = "제미나이키값"
$env:GEMINI_MODEL = "gemini-3.1-flash-lite"
.\tools\gemini-ollama-proxy\start.ps1
```
튜닝부에 입력 

Ollama 주소 / 포트:
http://host.docker.internal:11435
메인 모델:
gemini-3.5-flash
보조 모델:
gemini-3.5-flash



The proxy listens on:

```text
http://localhost:11435
```

If LearnBot runs in Docker, use this base URL in the admin tuning screen:

```text
http://host.docker.internal:11435
```

Set both primary and auxiliary model names to the Gemini model you want to test, for example:

```text
gemini-3.5-flash
```

## Supported Ollama endpoints

- `GET /api/tags`
- `POST /api/chat`

The proxy does not implement embeddings. Keep LearnBot embeddings on the existing local Ollama setup.

## Remove

After testing, stop the PowerShell process and delete:

```text
tools/gemini-ollama-proxy
```

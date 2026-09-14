# Gemini Ollama-compatible test proxy

This is a temporary local test proxy for LearnBot. It lets LearnBot keep calling an Ollama-shaped API while chat generation is forwarded to the Gemini OpenAI-compatible endpoint.

It is intentionally isolated under `tools/gemini-ollama-proxy` and does not change LearnBot backend code.

## Start

```powershell
$env:GEMINI_MODEL = "gemini-3.1-flash-lite"
.\tools\gemini-ollama-proxy\start.ps1
```
키가 환경변수에 없으면 숨김 입력으로 요청합니다. 키는 파일에 저장하지 않으며, 실행 중 이 터미널을 열어 두세요. 키를 채팅에 붙여 넣지 마세요. 포트가 이미 사용 중이면 기존 프로세스를 자동 종료하지 않고 중단합니다.

튜닝부에 입력 (프록시 기동 확인 후)

Ollama 주소 / 포트:
http://host.docker.internal:11435
메인 모델:
gemini-3.1-flash-lite
보조 모델:
gemini-3.1-flash-lite



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
gemini-3.1-flash-lite
```

## Supported Ollama endpoints

- `GET /api/tags`
- `POST /api/chat`

The proxy does not implement embeddings. Keep LearnBot embeddings on the existing local Ollama setup.

`GET /api/tags` checks the local proxy only; it does not verify the API key or Gemini quota. A separate small chat request is required before the live benchmark. Keep the same model and settings for both before/after runs; historical local-model scores are not a Gemini baseline.

## Remove

After testing, stop the PowerShell process and delete:

```text
tools/gemini-ollama-proxy
```

# LearnBot

LearnBot is a local RAG knowledge workspace for approved web pages and CSV/Excel files.
Uploaded source files are stored in MinIO and indexed into PostgreSQL/pgvector.

## Stack

- Frontend: React + Vite
- Backend: Spring Boot
- Database: PostgreSQL + pgvector
- Local LLM runtime: Ollama
- Default chat model: `gemma4:e2b`
- Default embedding model: `bge-m3`

## Run

From the project root:

```bash
docker compose up -d postgres ollama
docker compose --profile models up ollama-pull
docker compose up --build
```

Open:

- Frontend Nginx: http://localhost:8083
- Backend: http://localhost:8080
- Ollama: http://localhost:11434
- MinIO API: http://localhost:19000
- MinIO Console: http://localhost:19001

The Compose file uses the same production-style layout as the existing stack:

- `x-timezone-env` for `Asia/Seoul`
- explicit `nginx` service built from `nginx:stable`
- `nginx/nginx.conf` and `nginx/backend-upstream.inc`
- `minio`, `redis`, `postgres`, `ollama`, `backend`, and `nginx`

## Model Changes

Change models with environment variables:

```bash
LLM_MODEL=gemma4:e4b
EMBEDDING_MODEL=bge-m3
```

Changing the chat model is a config change. Vector search still works without the chat LLM as long as the embedding model is available. If the embedding model is unavailable, search falls back to keyword search.

Changing the embedding model can change vector dimensions, so existing documents must be reindexed and the pgvector column dimension must match the new model.

## Crawling Policy

Web ingestion is allow-list based. Configure allowed domains with:

```bash
LEARNBOT_CRAWLER_ALLOWED_DOMAINS=example.com,docs.spring.io,ollama.com
```

The current v1 ingests one approved URL at a time. Web ingestion uses the allow list, robots.txt checks, basic rate limiting, and crawl audit logs. Recursive crawling is not enabled.

## API

- `POST /api/sources/web` with `{ "url": "https://example.com" }`
- `POST /api/sources/files` with multipart field `file`
- `GET /api/documents`
- `GET /api/documents/{documentId}`
- `POST /api/documents/{documentId}/reindex`
- `DELETE /api/documents/{documentId}`
- `POST /api/search` with `{ "query": "..." }`
- `POST /api/rag/ask` with `{ "question": "...", "mode": "qa" }`

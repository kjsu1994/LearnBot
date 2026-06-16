# LearnBot

LearnBot is a local RAG knowledge workspace for approved web pages, CSV/Excel files, and private Git repositories.
Uploaded source files are stored in MinIO. Git repositories are cloned into a Docker volume and indexed into PostgreSQL/pgvector.

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
docker compose up -d --build
```

On Windows, use the helper script if you want GPU acceleration with CPU fallback:

```powershell
.\scripts\up.ps1 -Build
```

The script uses `docker-compose.gpu.yml` when `nvidia-smi` is available and Docker can attach the GPU to the Ollama container. If GPU startup fails, it starts the normal CPU-compatible Compose stack instead. To force CPU mode:

```powershell
.\scripts\up.ps1 -Cpu -Build
```

Open:

- Frontend Nginx: http://localhost:8083
- Backend: http://localhost:8080
- Ollama: http://localhost:11434
- MinIO API: http://localhost:19000
- MinIO Console: http://localhost:19001

Check whether Ollama is using GPU while a model is loaded:

```bash
docker compose exec ollama ollama ps
nvidia-smi
```

The Compose file uses the same production-style layout as the existing stack:

- `x-timezone-env` for `Asia/Seoul`
- explicit `nginx` service built from `nginx:stable`
- `nginx/nginx.conf` and `nginx/backend-upstream.inc`
- `minio`, `redis`, `postgres`, `ollama`, `backend`, and `nginx`

## Storage and Migration

Runtime data is not stored inside the Git-tracked project folder by default.

- PostgreSQL data: Docker named volume `learnbot_postgres-data`
- MinIO uploaded originals: Docker named volume `learnbot_minio-data`
- Git working copies: Docker named volume `learnbot_code-repos`

Use `pg_dump` and restore for PostgreSQL migration between PCs:

```bash
docker compose exec postgres pg_dump -U learnbot -d learnbot > learnbot-db.sql
```

Restore on another PC after starting PostgreSQL:

```bash
docker compose exec -T postgres psql -U learnbot -d learnbot < learnbot-db.sql
```

Git working copies can be recreated by reindexing registered repositories. MinIO data should be backed up separately if uploaded file originals must move with the database.

If encrypted Git tokens are stored, keep the same `LEARNBOT_CODE_CREDENTIAL_SECRET` value when migrating the database. Changing that secret makes previously stored tokens unreadable; re-enter the token from the UI if that happens.

## Code RAG

Git repositories support public/no-auth and username/token authentication for HTTP(S), plus standard Git SSH URLs when the container has usable SSH credentials. Token storage is opt-in from the UI. Stored tokens are encrypted in PostgreSQL and reused for later manual indexing.

Indexing is asynchronous and can be cancelled. Reindexing creates a new index version and only re-embeds changed files; unchanged files reuse existing chunk embeddings. A failed reindex does not replace the active index.

The UI also provides:

- repository deletion
- failed/cancelled indexing history cleanup
- source file browsing with line highlights
- symbol reference lookup for method, class, control, and event names

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
- `POST /api/code/repositories` with `{ "gitUrl": "https://host/project.git", "branch": "main", "authType": "NONE" }`
- `POST /api/code/repositories/{repositoryId}/index`
- `DELETE /api/code/repositories/{repositoryId}`
- `DELETE /api/code/repositories/{repositoryId}/jobs`
- `POST /api/code/repositories/{repositoryId}/jobs/{jobId}/cancel`
- `GET /api/code/repositories/{repositoryId}/jobs`
- `GET /api/code/repositories/{repositoryId}/files`
- `GET /api/code/repositories/{repositoryId}/files/{fileId}`
- `POST /api/code/references` with `{ "repositoryId": "...", "symbol": "MainWindow" }`
- `POST /api/code/search` with `{ "repositoryId": "...", "query": "..." }`
- `POST /api/code/ask` with `{ "repositoryId": "...", "question": "...", "mode": "locate" }`

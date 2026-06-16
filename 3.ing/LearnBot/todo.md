# LearnBot Continuation TODO

This file is the handoff point for continuing work after a disconnected session.
Skip the completed section unless regressions appear. Continue from "Remaining Work".

## Current Runtime Status

- Docker Desktop is installed and running.
- All core containers are running:
  - `learnbot-postgres`
  - `learnbot-redis`
  - `learnbot-minio`
  - `learnbot-ollama`
  - `learnbot-backend`
  - `learnbot-nginx`
- Open URLs:
  - Frontend: `http://localhost:8083`
  - Backend: `http://localhost:8080`
  - Ollama: `http://localhost:11434`
  - MinIO API: `http://localhost:19000`
  - MinIO Console: `http://localhost:19001`
- Verified API checks:
  - `GET /api/documents`
  - `POST /api/sources/files`
  - `POST /api/search`
  - `POST /api/rag/ask` with chat fallback
- Existing test document:
  - `learnbot-sample.csv`
  - Status: `INDEXED`
  - Note: this sample was uploaded before CSV BOM stripping was added, so its first header still displays BOM bytes. New uploads use the fixed extractor.

## Completed

- Created the initial Spring Boot backend under `backend/`.
- Set backend Java target and Docker runtime to Java 17.
- Installed Java 17 JDK locally:
  - `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
  - `JAVA_HOME` was set at Machine/User level.
- Created PostgreSQL + pgvector schema migration:
  - `data_sources`
  - `documents`
  - `document_chunks`
  - vector index and keyword search index
- Implemented ingestion APIs:
  - `POST /api/sources/web`
  - `POST /api/sources/files`
  - `GET /api/documents`
- Implemented CSV/XLS/XLSX extraction.
- Implemented allow-list based single-page web ingestion.
- Implemented chunking and embedding storage.
- Implemented hybrid vector + keyword search.
- Added fallback behavior:
  - If chat LLM is unavailable, `/api/rag/ask` returns retrieved context only.
  - If embedding query fails, `/api/search` falls back to keyword search.
- Set default local models:
  - Chat LLM: `gemma4:e2b`
  - Embedding: `bge-m3`
- Added model env compatibility:
  - `LLM_MODEL`
  - `OLLAMA_CHAT_MODEL`
  - `EMBEDDING_MODEL`
  - `OLLAMA_EMBEDDING_MODEL`
- Created React frontend under `frontend/`.
- Created production-style root `docker-compose.yml`.
- Matched requested Compose style:
  - `x-timezone-env`
  - `cloudflare-tunnel`
  - `minio`
  - explicit `nginx` service built from `nginx:stable`
  - `postgres`
  - `ollama`
  - `ollama-pull`
  - `redis`
  - `backend`
- Set Nginx port to `8083:80`.
- Added Nginx config files:
  - `nginx/nginx.conf`
  - `nginx/backend-upstream.inc`
- Updated `README.md` with run notes and model-change behavior.

## Remaining Work

1. Verify Docker availability.
   - Status: completed.
   - Docker Desktop was installed with `winget`.
   - Docker CLI path was added to the User `Path`.
   - Verified Docker Desktop 4.78.0 and Docker Engine 29.5.3 using the installed CLI path.

2. Validate Compose config.
   - Status: completed.
   - `docker compose config` succeeded.
   - From project root:
     ```powershell
     docker compose config
     ```
   - Fix any YAML/env errors before building.

3. Build and start infrastructure.
   - Status: completed.
   - First run failed after image pull because Docker Desktop could not create the `minio` bind mount from the `O:` drive.
   - Compose was adjusted to avoid runtime bind mounts for MinIO/Nginx/frontend output:
     - MinIO now uses named volume `minio-data`.
     - Nginx is built from `nginx/Dockerfile`, which copies Nginx config and frontend build output into the image.
     - Removed the separate `frontend-builder` runtime service.
   - Second run started Postgres, Redis, and Ollama.
   - MinIO hit a host port conflict on `127.0.0.1:9001`, so external MinIO ports were changed:
     - API: `127.0.0.1:19000`
     - Console: `127.0.0.1:19001`
   - MinIO was recreated and started successfully.
   ```powershell
   docker compose up -d postgres redis minio ollama
   ```

4. Pull local Ollama models.
   - Status: completed.
   ```powershell
   docker compose --profile models up ollama-pull
   ```
   - If `gemma4:e2b` is not available in the local Ollama registry, replace `LLM_MODEL`/`OLLAMA_CHAT_MODEL` with the exact installed tag.
   - Keep `bge-m3` as the default embedding model unless there is a strong reason to change it.
   - Verified installed models:
     - `gemma4:e2b`
     - `bge-m3:latest`

5. Build full stack.
   - Status: completed.
   - First full stack build succeeded and all services started.
   - CSV ingestion smoke test succeeded.
   - Search/RAG smoke test found a PostgreSQL nullable parameter type issue in search SQL.
   - Fix applied: nullable source/content filters now use explicit `varchar` casts, and embedding fallback no longer masks repository SQL errors.
   ```powershell
   docker compose up --build
   ```

6. Verify backend starts.
   - Status: completed.
   - Check backend logs:
     ```powershell
     docker compose logs -f backend
     ```
   - Expected:
     - Flyway migration succeeds.
     - pgvector extension is created.
     - Spring Boot listens on container port `8080`.
   - Verified backend logs:
     - Java 17 runtime.
     - Flyway migration applied.
     - Subsequent restart reported schema up to date.
     - Tomcat listening on `8080`.

7. Verify frontend through Nginx.
   - Status: completed.
   - Open:
     ```text
     http://localhost:8083
     ```
   - Confirm `/api/documents` is proxied through Nginx.
   - Verified:
     - `http://127.0.0.1:8083/` returns the React HTML shell.
     - `http://127.0.0.1:8083/api/documents` proxies to backend.

8. Smoke test ingestion.
   - Status: completed.
   - Use an allow-listed domain from `LEARNBOT_CRAWLER_ALLOWED_DOMAINS`.
   - Default allow list:
     ```text
     example.com,docs.spring.io,ollama.com
     ```
   - Test one web URL and one CSV/XLSX upload.
   - Verified CSV upload:
     - `POST /api/sources/files`
     - document indexed with status `INDEXED`

9. Smoke test search fallback.
   - Status: completed.
   - Stop or make chat model unavailable and confirm vector search still works.
   - Stop or make embedding model unavailable and confirm keyword search fallback returns results for exact text matches.
   - Verified vector search with `bge-m3`.
   - Verified RAG fallback returns retrieved supporting context when chat LLM is unavailable.
   - Verified keyword search fallback by stopping Ollama and calling `/api/search`.
   - Direct `gemma4:e2b` chat failed because Ollama's llama-server process was killed while loading the model.
   - Observed Docker/Ollama memory available to container: about `7.6 GiB`.
   - Added lower-memory Ollama settings in Compose:
     - `OLLAMA_CONTEXT_LENGTH=2048`
     - `OLLAMA_KEEP_ALIVE=0`
     - `OLLAMA_MAX_LOADED_MODELS=1`
     - `OLLAMA_NUM_PARALLEL=1`
   - Even with lower-memory settings, `gemma4:e2b` still failed under the current Docker memory limit.
   - Next decision: increase Docker Desktop memory or choose a smaller chat model for answer generation.

10. Add missing production controls before real internal use.
    - Status: remaining.
    - robots.txt handling.
    - crawl rate limiting.
    - crawl depth/page limit.
    - source audit logs.
    - document deletion and reindex APIs.
    - auth/RBAC.
    - file/object storage integration for raw uploads.
    - OCR/image pipeline if image text extraction becomes required.

## Important Notes

- Vector search requires an embedding model. Without the embedding model, the system can only fall back to keyword search.
- Chat LLM and embedding model are intentionally separate.
- Changing only the chat model does not require reindexing.
- Changing the embedding model may change vector dimensions. If that happens:
  - update `LEARNBOT_EMBEDDING_DIMENSIONS`
  - update the pgvector column dimension in migration/schema
  - reindex all documents
- `BGE-M3` is a reasonable default for this project because it is multilingual and works well for Korean/English RAG-style retrieval.
- `gemma4:e2b` may require more Docker Desktop memory than the current default. If chat generation keeps failing, increase Docker Desktop memory or switch `LLM_MODEL` to a smaller chat model while keeping `bge-m3` for embeddings.
- The current web ingestion is intentionally single-URL ingestion, not recursive crawling.
- The root `docker-compose.yml` is the active Compose file. The old `compose/docker-compose.yml` was removed.
- Runtime bind mounts from the `O:` drive caused Docker Desktop container creation failures, so persistent runtime data should use named Docker volumes unless a local non-mapped drive path is chosen.

## Current Known Limitations

- Local Maven is not installed or not on `PATH`; backend build is expected to run through Docker's Maven build stage.
- Docker CLI was installed with Docker Desktop and added to User `Path`; existing shell sessions may need reopening before `docker` resolves without a full path.
- No auth is implemented yet.
- No recursive crawler is implemented yet.
- No OCR or multimodal image search is implemented yet.
- Raw files are parsed but not yet persisted to MinIO.
- `gemma4:e2b` is downloaded but does not run under the current Docker Desktop memory limit; RAG answer generation falls back to retrieved context.

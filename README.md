docker compose up -d --build  : using CPU
.\scripts\up.ps1 -Build  : auto using GPU
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build  : using GPU


powershell.exe -ExecutionPolicy Bypass -File .\scripts\up.ps1 -Build

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

To build the deployment images with the GPU Compose overlay without starting services:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml build
```

The script uses `docker-compose.gpu.yml` when `nvidia-smi` is available and Docker can attach the GPU to the Ollama container. If GPU startup fails, it starts the normal CPU-compatible Compose stack instead. To force CPU mode:

```powershell
.\scripts\up.ps1 -Cpu -Build
```

Open:

- Frontend Nginx: http://localhost:8083
- Backend: http://localhost:8080
- Ollama: http://localhost:11436
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

### Code Graph RAG

Code indexing builds a versioned graph in PostgreSQL alongside chunks and embeddings. Neo4j is not required. Java source is analyzed with JavaParser Symbol Solver, while C# source is analyzed with a Roslyn semantic analyzer included in the backend image.

Resolved methods use qualified signatures containing their declaring type and parameter types. This separates overloads and same-named methods in different packages or namespaces. A relationship found only by text matching is stored conservatively as `REFERENCES`; it is not promoted to `CALLS` unless JavaParser, Roslyn, or the validated LLM fallback resolves it.

The graph can contain these relationships:

- structure: `DEFINES`, `CONTAINS`, `EXTENDS`, `IMPLEMENTS`, `OVERRIDES`
- execution and dependencies: `CALLS`, `INJECTS`, `RETURNS`, `ACCEPTS`, `THROWS`
- code semantics: `ANNOTATED_BY`, `READS_FIELD`, `WRITES_FIELD`
- framework semantics: `USES_ENTITY`, `MAPS_TO_TABLE`, `EXPOSES_ENDPOINT`
- UI semantics: `HANDLES_EVENT`, `BINDS_TO`
- conservative fallback: `REFERENCES`

Graph search uses a recursive PostgreSQL CTE. It prevents cycles, reduces scores for longer or lower-confidence paths, and returns the best path for each related chunk. Search strategy depends on the question:

- call-flow questions traverse `CALLS` and related execution edges forward
- impact questions traverse callers and dependencies in reverse
- UI questions prioritize XAML event, binding, endpoint, and handler edges
- overview questions prioritize containment, inheritance, implementation, and injection edges

Graph-expanded evidence includes the path, edge sequence, depth, and path score. Code answers can therefore explain a connected flow such as `Controller -> Service -> Repository` instead of listing unrelated chunks.

Configure graph behavior with environment variables:

```bash
LEARNBOT_CODE_GRAPH_ENABLED=true
LEARNBOT_CODE_GRAPH_MAX_HOP=2
LEARNBOT_CODE_GRAPH_MAX_EXPANDED_RESULTS=12
LEARNBOT_CODE_GRAPH_LLM_RELATION_ENABLED=true
LEARNBOT_CODE_GRAPH_MAX_LLM_FILES=80
LEARNBOT_CODE_GRAPH_ROSLYN_ANALYZER_PATH=/app/roslyn/LearnBot.RoslynAnalyzer.dll
```

`LEARNBOT_CODE_GRAPH_MAX_HOP` is constrained to 1-4 during traversal. The optional LLM stage only evaluates unresolved candidates, accepts known graph node keys and approved relationship types, and records its output with lower confidence. If JavaParser, Roslyn, the LLM, or graph retrieval fails, indexing/search continues with the available deterministic graph or the existing keyword/vector search.

Existing active indexes remain readable after an upgrade. Reindex each repository to create qualified signature nodes, expanded relationships, and multi-hop paths using the new analyzers. A failed reindex does not replace the previous active index.

The backend deployment image includes both Java 17 and the .NET 8 runtime required by Roslyn. Build deployment images with the GPU Compose overlay shown in the Run section; GPU access is assigned to Ollama, while source analysis remains CPU-based.

## Model Changes

Change models with environment variables:

```bash
LLM_MODEL=qwen3:8b-q4_K_M, qwen3.5:2b-q4_K_M
EMBEDDING_MODEL=bge-m3
```

Changing the chat model is a config change. Vector search still works without the chat LLM as long as the embedding model is available. If the embedding model is unavailable, search falls back to keyword search.

Changing the embedding model can change vector dimensions, so existing documents must be reindexed and the pgvector column dimension must match the new model.

## Crawling Policy

Web ingestion is allow-list based. Configure allowed domains with:

```bash
LEARNBOT_CRAWLER_ALLOWED_DOMAINS=example.com,docs.spring.io,ollama.com
```

Web ingestion uses the allow list, robots.txt checks, basic rate limiting, and crawl audit logs. By default, the UI enables recursive crawling for the same host and descendant path of the submitted URL. The default recursive limits are depth 2 and 30 fetched pages.

```bash
LEARNBOT_CRAWLER_MAX_DEPTH=2
LEARNBOT_CRAWLER_MAX_PAGES_PER_REQUEST=30
LEARNBOT_CRAWLER_MIN_CONTENT_CHARS=200
```

Each crawled page is stored as a separate document under the same source so RAG citations keep the original page URL.

## API

- `POST /api/sources/web` with `{ "url": "https://example.com/docs", "recursive": true, "maxDepth": 2, "maxPages": 30 }`
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

RAG answer responses include `confidence` and `diagnostics` fields so the UI can show when a response was generated by fallback logic or has weak evidence. The frontend renders natural-language RAG answers as Markdown, including headings, lists, inline code, and fenced code blocks.

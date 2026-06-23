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
- Default chat model: `qwen3:8b-q4_K_M`
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

Check whether Ollama and the optional reranker are using GPU memory while models are loaded:

```bash
docker compose exec ollama ollama ps
curl http://127.0.0.1:18081/ready
curl -X POST http://127.0.0.1:18081/unload
nvidia-smi
```

The GPU Compose overlay keeps the reranker disabled by default. Enable it only when reranking quality is worth the extra VRAM use:

```bash
LEARNBOT_RERANKER_ENABLED=true
LEARNBOT_RERANKER_WARMUP_ON_STARTUP=false
LEARNBOT_RERANKER_IDLE_UNLOAD_SECONDS=300
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

## Disk retention and cleanup

LearnBot keeps search-critical data by default and only purges operational data automatically.

- Operation/crawl/diagnostic logs are retained for 14 days by default.
- Admin/security audit logs are retained for 180 days by default.
- RAG export ZIP files are retained for 14 days by default.
- Deleted-source originals and MinIO objects with no `source_objects` DB reference are deleted only after a 7-day grace period.
- Document/code chunks, embeddings, graph data, active source originals, saved answers, and Ollama models are not automatically deleted.

Admins can inspect and run cleanup from `GET /api/admin/storage/retention/preview` and `POST /api/admin/storage/retention/run`. The run endpoint defaults to dry-run unless `{"dryRun": false}` is passed. Docker container logs are size-rotated through compose logging options; Docker build cache remains manual via:

```powershell
.\scripts\cleanup.ps1 -DockerCache -DryRun
.\scripts\cleanup.ps1 -DockerCache -Until 168h
```

Named Docker volumes are intentionally never pruned by the helper script.

## Code RAG

Git repositories support public/no-auth and username/token authentication for HTTP(S), plus standard Git SSH URLs when the container has usable SSH credentials. Token storage is opt-in from the UI. Stored tokens are encrypted in PostgreSQL and reused for later manual indexing.

Indexing is asynchronous and can be cancelled. Reindexing creates a new index version and only re-embeds changed files; unchanged files reuse existing chunk embeddings. A failed reindex does not replace the active index.

The UI also provides:

- repository deletion
- failed/cancelled indexing history cleanup
- source file browsing with line highlights
- symbol reference lookup for method, class, control, and event names

### Conversational Code RAG

Code questions support conversation-aware follow-ups. A follow-up question keeps the user's original question for the final answer, but uses a separate effective search question for classification, retrieval, query expansion, and evidence ranking.

When a code conversation has previous turns, LearnBot extracts code anchors from prior evidence, including chunk id, file path, class, symbol, method, and line range. Those chunks are reloaded from PostgreSQL as pinned evidence before normal hybrid search runs. Pinned evidence receives a small ranking boost, but it is ignored when it is not relevant to the current question. If pinned evidence is missing, deleted, inaccessible, or unrelated, the request falls back to the normal code RAG search path.

The prompt includes recent Q/A summaries and previous code anchors in a separate conversation section. This section is used only to resolve references such as "that method" or "the previous file"; cited facts must still come from the current source-code context.

Diagnostics include conversation-specific notes such as whether conversation context was used, how many anchors were found, and how many pinned chunks were included in the final evidence.

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

Graph search uses bounded hop-by-hop traversal instead of an unbounded recursive query. It prevents cycles, limits seed nodes, per-node edges, per-hop candidates, and total traversal rows, then returns the best path for each related chunk. Search strategy depends on the question:

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
LEARNBOT_CODE_GRAPH_MAX_SEED_NODES=24
LEARNBOT_CODE_GRAPH_MAX_EDGES_PER_NODE=12
LEARNBOT_CODE_GRAPH_MAX_CANDIDATES_PER_HOP=200
LEARNBOT_CODE_GRAPH_MAX_TRAVERSAL_ROWS=1000
LEARNBOT_CODE_GRAPH_LLM_RELATION_ENABLED=true
LEARNBOT_CODE_GRAPH_MAX_LLM_FILES=80
LEARNBOT_CODE_GRAPH_ROSLYN_ANALYZER_PATH=/app/roslyn/LearnBot.RoslynAnalyzer.dll
LEARNBOT_CODE_GRAPH_ROSLYN_MODE=AUTO
LEARNBOT_CODE_GRAPH_ROSLYN_TIMEOUT_SECONDS=120
LEARNBOT_CODE_GRAPH_EVIDENCE_RANKING_ENABLED=true
LEARNBOT_CODE_GRAPH_EVIDENCE_RANKING_DEBUG=false
LEARNBOT_CODE_GRAPH_DEPENDENCY_RESOLUTION_ENABLED=true
LEARNBOT_CODE_GRAPH_DEPENDENCY_ALLOWED_REPOSITORIES=https://repo.maven.apache.org/maven2
LEARNBOT_CODE_GRAPH_DEPENDENCY_MAX_ARTIFACTS=256
LEARNBOT_CODE_GRAPH_DEPENDENCY_MAX_BYTES=536870912
LEARNBOT_CODE_GRAPH_DEPENDENCY_TIMEOUT_SECONDS=120
```

`LEARNBOT_CODE_GRAPH_MAX_HOP` is constrained to 1-4 during traversal. When a traversal budget is reached, the best bounded results are returned with `graphTraversalTruncated` metadata instead of failing the search.

Code GraphRAG evidence ranking is deterministic and enabled by default. It combines hybrid search score, query term matches, graph path score, relationship type, graph depth, question intent, structured code evidence, and diversity penalties into `evidenceScore` metadata while preserving the original search `score`.

Roslyn `AUTO` mode selects `SAFE_SOLUTION`, `SAFE_PROJECT`, or `SIMPLE` from repository contents. `SAFE_*` modes parse project and solution descriptors statically; MSBuild targets, source generators, and repository code are never executed. Legacy `PROJECT` and `SOLUTION` config values are accepted as aliases for `SAFE_PROJECT` and `SAFE_SOLUTION`. A future `MSBUILD_WORKSPACE` mode must run only in an explicitly enabled isolated worker with network, time, and memory limits. Java dependency resolution also parses Maven/Gradle declarations without running the build. It uses the persistent `.dependency-cache` under the code workspace and only downloads release artifacts from configured HTTPS repository allow lists.

The optional LLM stage runs as a durable post-index enrichment job after the deterministic graph is active. Pending work survives restarts, retries up to three times, and is skipped when a newer index replaces it. It only accepts known graph node keys and approved relationship types, and records output with lower confidence. If JavaParser, dependency resolution, Roslyn, the LLM, or graph retrieval fails, indexing/search continues with the available deterministic graph or the existing keyword/vector search.

Each indexing job records `SUCCESS`, `PARTIAL`, `FAILED`, or `SKIPPED` diagnostics for the base graph, Java classpath, Java semantic analysis, Roslyn, and LLM enrichment. The Code workspace exposes these under **분석 진단**. They are also available from:

```text
GET /api/code/repositories/{repositoryId}/jobs/{jobId}/diagnostics
```

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

## Document RAG Indexing

Document ingestion is split into a fast searchable phase and slower quality enrichment phases.

Source status values:

- `INDEXING`: original extraction, chunking, and embedding are still running; the source is not searchable yet.
- `SEARCHABLE`: original chunks and deterministic context are stored; users can search and ask questions.
- `READY`: enabled post-processing completed or was skipped by configuration.
- `PARTIAL`: original search is available, but at least one post-processing stage failed or is waiting for retry.
- `FAILED`: extraction, embedding, or storage failed before the source became searchable.

The `INDEXED` value is still accepted for legacy data and import compatibility, but new document sources use `SEARCHABLE`, `READY`, or `PARTIAL` after successful base indexing.

Document graph rebuild runs as a durable background job instead of blocking ingestion. It stores graph nodes and edges in PostgreSQL using batched inserts. If graph rebuild fails, the source remains searchable and the UI marks it as `PARTIAL`.

LLM document context enrichment also runs as a background job. It replaces only generated `document_context` chunks, so original chunks remain available if the enrichment fails.

Document post-processing diagnostics are recorded for graph rebuild and LLM enrichment. The Documents UI exposes diagnostics next to the indexing job and shows retry buttons beside failed stages, so users can see why a retry is available before clicking it. Retry requeues only the failed post-processing stage; it does not re-run full document extraction or embedding.

Relevant APIs:

```text
GET  /api/document-indexing/jobs
GET  /api/document-indexing/jobs/{jobId}
GET  /api/document-indexing/jobs/{jobId}/diagnostics
POST /api/document-indexing/jobs/{jobId}/retry-enrichment
POST /api/document-indexing/jobs/{jobId}/retry-graph
```

## Conversational Document RAG

Document questions also support conversation-aware follow-ups. Conversational document RAG keeps three concepts separate:

- `originalQuestion`: the exact text typed by the user.
- `effectiveQuestion`: a short standalone search question generated from the follow-up and the previous document evidence.
- conversation focus: recent Q/A summaries and prior document evidence anchors included in the prompt as a separate section.

Previous document evidence is not appended to the search query as raw chat history. Instead, LearnBot extracts document anchors from prior answer evidence, including chunk id, document id, title, source URI, chunk index, page number, section title, heading path, and document type. The referenced chunks are reloaded from PostgreSQL and merged as pinned context before normal document retrieval. Pinned context is filtered for relevance and receives a small boost; if it cannot be loaded or is unrelated, the request falls back to normal document retrieval.

The final answer still cites only evidence chunks present in the current response context. Previous answers are used only to resolve follow-up references such as "that document", "that condition", or "the previous source".

Document conversation turns are stored in the RAG conversation tables. They keep the user question, generated effective question, answer, citations, evidence, diagnostics, and metadata. Conversation retention follows the existing RAG conversation retention policy.

### Document RAG streaming

The backend exposes SSE-compatible endpoints:

```text
POST /api/rag/ask/stream
POST /api/code/ask/stream
Accept: text/event-stream
```

Retrieval, context assembly, citation selection, and conversation preparation stay on the existing synchronous path. Only the Ollama chat call is streamed. The endpoint emits structured SSE events:

- `metadata`: request mode and whether the request is conversational.
- `evidence`: retrieved evidence available before the answer finishes.
- `delta`: buffered model text, emitted in small batches instead of per-token.
- `replace`: server-side fallback or answer repair replaced the visible text.
- `done`: final `AskResponse`.
- `error`: failure details.

Streaming cleanup is tied to Reactor `Flux`/`Mono.doFinally`. `CANCEL`, `ON_COMPLETE`, and `ON_ERROR` all release the stream permit; the implementation does not rely on `SseEmitter.onCompletion`, `onTimeout`, or `onError` for permit cleanup.

Streaming failure rules:

- If Ollama fails before the first `delta`, the server may fall back to the next candidate model or the frontend may fall back to the non-streaming `/ask` endpoint.
- If Ollama fails after the first `delta`, the stream emits an `error` event and no conversation turn is saved.
- Partial answers are visible while streaming but are not saveable and are not persisted as conversation turns.
- Client abort through `AbortController` must cancel the backend stream, dispose the Ollama subscription, release the permit, and avoid saving a conversation turn.

Regression tests for this area should include mid-stream failure and client abort:

- first-delta-before-failure: `error` event, partial answer not saved, permit returned.
- failure-before-first-delta: candidate model fallback or non-streaming JSON fallback, permit returned.
- client abort: browser `AbortController` cancellation, backend Flux cancel, Ollama stream subscription disposal, permit returned, no conversation turn saved.

## API

- `POST /api/sources/web` with `{ "url": "https://example.com/docs", "recursive": true, "maxDepth": 2, "maxPages": 30 }`
- `POST /api/sources/files` with multipart field `file`
- `GET /api/documents`
- `GET /api/document-indexing/jobs`
- `GET /api/document-indexing/jobs/{jobId}/diagnostics`
- `POST /api/document-indexing/jobs/{jobId}/retry-enrichment`
- `POST /api/document-indexing/jobs/{jobId}/retry-graph`
- `GET /api/documents/{documentId}`
- `POST /api/documents/{documentId}/reindex`
- `DELETE /api/documents/{documentId}`
- `POST /api/search` with `{ "query": "..." }`
- `POST /api/rag/ask` with `{ "question": "...", "mode": "qa" }`
- `POST /api/rag/ask/stream` with `{ "question": "...", "mode": "qa", "conversational": true }`, returns `text/event-stream`
- `GET /api/rag/conversations?domain=DOCUMENT`
- `GET /api/rag/conversations?domain=CODE`
- `GET /api/rag/conversations/{conversationId}`
- `DELETE /api/rag/conversations/{conversationId}`
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

Conversational RAG responses can also include `conversationId`, `turnId`, and `rewrittenQuestion`. For conversational requests, `rewrittenQuestion` is the effective standalone search question, not raw chat history.

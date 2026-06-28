docker compose up -d --build  : using CPU
.\scripts\up.ps1 -Build  : auto using GPU
./scripts/up.ps1 -NoBuild
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

Each indexing job records `SUCCESS`, `PARTIAL`, `FAILED`, or `SKIPPED` diagnostics for the base graph, Java classpath, Java semantic analysis, Roslyn, and LLM enrichment. The Code workspace exposes these under **遺꾩꽍 吏꾨떒**. They are also available from:

```text
GET /api/code/repositories/{repositoryId}/jobs/{jobId}/diagnostics
```

Existing active indexes remain readable after an upgrade. Reindex each repository to create qualified signature nodes, expanded relationships, and multi-hop paths using the new analyzers. A failed reindex does not replace the previous active index.

The backend deployment image includes both Java 17 and the .NET 8 runtime required by Roslyn. Build deployment images with the GPU Compose overlay shown in the Run section; GPU access is assigned to Ollama, while source analysis remains CPU-based.

## Model Changes

Change models with environment variables:

```bash
LLM_MODEL=qwen3:8b-q4_K_M, qwen3:4b-instruct
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


## LearnBot Service Final Architecture Plan

LearnBot's long-term product direction is a web-first local RAG agent service with per-user Local Agents for user-owned workspace changes.

```text
User
 |
 |  Web conversation, review, approvals
 v
Web UI / optional learnbot CLI
 |
 v
Central LearnBot Server
 |
 +-- Document Agent  -> Document RAG
 |
 +-- Code Agent      -> Graph Code RAG
 |
 +-- Planning / tool orchestration
 |
 +-- Change proposal / diff validation
 |
 +-- Approval / session / audit / rollback metadata
 |
 |  Outbound tool request over WebSocket or equivalent
 v
User Local Agent / learnbot CLI
 |
 +-- Approved workspace roots only
 |
 +-- Read files / write approved patches
 |
 +-- Git status / diff
 |
 +-- Allowlisted test and build commands
 |
 +-- Rollback restoration
 |
 +-- Optional local diagnostics and logs
```

Responsibility split:

- The central server owns retrieval, ranking, planning, model calls, validation, approvals, conversation history, diagnostics, and orchestration.
- The Local Agent owns user-PC side effects: file writes, test/build execution, git operations, rollback restoration, and workspace-local diagnostics.
- Server-local apply/test/rollback may exist only as a prototype, shared sandbox, migration bridge, or admin/debug capability. It must not become the default product path for user-owned repository changes.
- Server-local Patch Agent mutation is disabled by default. Set `LEARNBOT_CODE_SERVER_LOCAL_MUTATION_ENABLED=true` only for prototype/admin-debug runs.
- The preferred network model is outbound connection from each Local Agent to the central server. The server should not depend on directly reaching user PCs by LAN IP or localhost.
- The CLI should eventually feel natural from PowerShell, similar to running `codex`: users can run `learnbot` to pair, start/stop/status the agent, register workspaces, open the web UI, run diagnostics, view logs, and optionally use lightweight CLI chat/fix/review commands.

### Local Agent Protocol Baseline

The shared backend contract for future Local Agent work starts with `AgentExecutionTarget`, `LocalAgentToolName`, `LocalAgentToolRequest`, and `LocalAgentToolResponse` DTOs. Initial tools are `agent.status`, `agent.doctor`, `workspace.list`, `workspace.add`, `file.read`, `patch.apply`, `git.status`, `git.diff`, `command.runAllowed`, and `rollback.restore`.

Side-effectful tools require approval metadata and must execute through `USER_LOCAL_AGENT` for user-owned workspaces. Failure states are explicit through `LocalAgentFailureCode`, including disconnected agents, unapproved workspaces, path escape, unsafe tools, approval denial, timeout, context mismatch, test failure, rollback refusal, and generic tool failure.

The central gateway skeleton exposes `GET /api/local-agents/status` for the current user. Users can issue a one-time-visible Local Agent credential with `POST /api/local-agents/pairing-token`; the server stores only a hash in `local_agent_tokens`. Authenticated web users can inspect non-secret token metadata with `GET /api/local-agents/tokens` and revoke their own token with `DELETE /api/local-agents/tokens/{tokenId}`. Token list responses include id, agent id, label, expiry, created time, last-seen time, revoked time, and active state, but never the raw credential. A Local Agent can then call `POST /api/local-agents/heartbeat` with `X-Local-Agent-Token`, agent id, version, capabilities, and approved workspace summaries.

Tool routing now has a durable polling baseline: the server records queued Local Agent work in `local_agent_tool_executions`, a token-authenticated Local Agent can claim the next request with `GET /api/local-agents/tools/next`, and it can persist a structured result with `POST /api/local-agents/tools/{requestId}/response`. For internal smoke and diagnostics, authenticated web users can enqueue only safe read-only requests through `POST /api/local-agents/tools/read-only` and inspect the persisted result through `GET /api/local-agents/tools/{requestId}`. That diagnostic enqueue path currently allows only `file.read`, `git.status`, and `git.diff`; mutation, command execution, patch, test, and rollback requests remain blocked. WebSocket transport work follows `docs/local-agent-websocket-transport.md`: the server skeleton is behind `LEARNBOT_LOCAL_AGENT_WEBSOCKET_ENABLED=false` by default, accepts only token-authenticated messages, registers connected sessions after `hello`/`heartbeat`, can push persisted read-only `tool.request` envelopes to connected agents, and can persist `tool.response` envelopes through the same completion service. The Local Agent CLI now accepts `polling`, `websocket`, and `auto` transport modes; `websocket` and `auto` try a live WebSocket hello/heartbeat, process pushed read-only `tool.request` messages during a bounded receive window, send `tool.response`, and keep durable polling active for fallback. The Code workspace shows disconnected/stale state and Local Agent token metadata/revocation controls so side-effectful local work does not silently fall back to server-local execution and stale pairings can be retired.

### Local Agent MVP Skeleton

The first local executable skeleton lives in `local-agent/` and builds as a .NET console app named `learnbot`. It is intended for the early internal pilot path, not as the final installer/service package yet.

Current commands:

- `learnbot pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> [--transport polling|websocket|auto]`
- `learnbot agent start [--once] [--interval-seconds 15] [--transport polling|websocket|auto]`
- `learnbot agent status`
- `learnbot agent token`
- `learnbot agent stop`
- `learnbot agent logs [--tail 80]`
- `learnbot workspace add <path>`
- `learnbot workspace list`
- `learnbot file read --workspace-id <workspace-id> --path <relative-path>`
- `learnbot git status --workspace-id <workspace-id>`
- `learnbot git diff --workspace-id <workspace-id> [--path <relative-path>] [--max-bytes <bytes>]`
- `learnbot doctor`
- `learnbot open`

The skeleton stores config in `%USERPROFILE%\.learnbot\agent.json`, or in `LEARNBOT_AGENT_CONFIG` when that environment variable is set for tests. It writes a minimal run-state file and `agent.log` next to that config, so internal users can inspect `learnbot agent status` and `learnbot agent logs --tail 80` before a Windows Service or installer exists. `learnbot agent token` reports paired state, agent id, a non-secret token fingerprint, and the web management URL without printing the raw credential. Long-running `agent start` logs transient loop failures and continues polling; `--once` returns a failure exit code for smoke tests. The stored transport mode defaults to `polling`; `websocket` and `auto` try a bounded WebSocket hello/heartbeat and fall back to REST heartbeat if the endpoint is disabled, unreachable, or rejected. When WebSocket is unavailable, the run-state records the configured transport, active fallback transport, consecutive WebSocket failures, and the next retry time while durable polling continues. During the bounded WebSocket receive window, pushed `tool.request` messages reuse the same safe local handler as polling and return `tool.response`; durable polling remains active as fallback. It sends heartbeat, reports approved local workspace summaries, polls the durable tool queue, and handles `agent.status`, `agent.doctor`, `workspace.list`, path-contained `file.read`, read-only `git.status`, and bounded read-only `git.diff`. File reads are limited to approved workspace roots, reject path traversal/workspace escape, reject binary files, and cap returned content. Git status uses a fixed `git status --porcelain=v1 -b --untracked-files=all` command with optional locks disabled, and requires the approved workspace to be a Git worktree root. Git diff reads both staged and unstaged changes with `--no-ext-diff`, supports an optional workspace-contained relative `path`, and caps returned diff bytes. It rejects file mutation, command execution, patch, test, and rollback tools by default until the safety model and approval flow are implemented.

A live polling smoke can be run against the local stack after `.\scripts\up.ps1 -Build`:

```powershell
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -ToolName file.read -Path README.md
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -ToolName git.status
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -ToolName git.diff -Path README.md
```

The script logs in, issues a pairing token, stores a temporary Local Agent config, registers the workspace, queues a read-only server request, runs the agent once, and verifies that the server persisted a `SUCCEEDED` tool response.

When the backend is started with `LEARNBOT_LOCAL_AGENT_WEBSOCKET_ENABLED=true`, the same helper can require the WebSocket path. With the normal Docker helper, set the environment variable before starting or recreating the stack so the backend enables `/api/local-agents/ws` and Nginx forwards the WebSocket upgrade:

```powershell
$env:LEARNBOT_LOCAL_AGENT_WEBSOCKET_ENABLED = "true"
.\scripts\up.ps1 -Build
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -ToolName file.read -Path README.md -Transport websocket
```

In WebSocket mode, the helper starts the Local Agent before enqueueing the request and fails unless the agent log shows that the request completed through the WebSocket tool path.
To verify fallback safety afterward, unset the flag or start the stack with the default `false` value and run the same smoke without `-Transport websocket`; the polling path should still persist a `SUCCEEDED` result.

For internal foreground pilot use, `scripts/local-agent.ps1` wraps the common setup and run commands without pretending to be the final installer:

```powershell
$env:LEARNBOT_AGENT_LOGIN_ID = "jinsu.kim"
$env:LEARNBOT_AGENT_PASSWORD = "admin1234"
.\scripts\local-agent.ps1 -Action setup -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -Transport polling
.\scripts\local-agent.ps1 -Action status
.\scripts\local-agent.ps1 -Action token
.\scripts\local-agent.ps1 -Action logs -Tail 80
.\scripts\local-agent.ps1 -Action start
.\scripts\local-agent.ps1 -Action background-start
.\scripts\local-agent.ps1 -Action background-stop
```

The helper performs web login, requests a pairing token, runs `learnbot pair`, registers the workspace, and starts the foreground polling loop. It accepts `-Transport polling|websocket|auto`; `websocket` and `auto` try WebSocket hello/heartbeat first, then keep polling available for durable tool queue fallback. It is not a Windows Service, MSI, or background process manager.
For the internal pilot, `background-start` launches the installed `learnbot.exe` in a hidden window, refuses duplicate starts when the recorded Local Agent process is running, and `background-stop` stops only the PID recorded by the Local Agent state file. This is still a helper, not a service supervisor.

To publish a lightweight local executable for the internal pilot:

```powershell
.\scripts\local-agent-install.ps1 -Action install
.\scripts\local-agent-install.ps1 -Action install -AddToUserPath
learnbot agent status
```

The install helper publishes `local-agent/` to `%USERPROFILE%\.learnbot\bin` by default. It can optionally add that directory to the user's PATH. This remains a framework-dependent pilot install and does not create a Windows Service, MSI, updater, or background process manager.
After publishing, `scripts/local-agent.ps1` uses the installed `%USERPROFILE%\.learnbot\bin\learnbot.exe` automatically when it exists; otherwise it falls back to `learnbot` on PATH or `dotnet run --project local-agent`.

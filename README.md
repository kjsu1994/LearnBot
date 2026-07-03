---------------RAG------------------------------------
docker compose up -d --build  : using CPU
.\scripts\up.ps1 -Build  : auto using GPU
./scripts/up.ps1 -NoBuild
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build  : using GPU

./scripts/up.ps1 -Build -Reranker  : need reranker

---------------Agent-----------------------------------
1. 최초 1회 설치
관리자 PowerShell에서:
cd C:\Users\honeybadger\Desktop\LearnBot
.\scripts\local-agent-install.ps1 -Action install -AddToUserPath
.\scripts\local-agent.ps1 -Action setup
.\scripts\local-agent.ps1 -Action service-command -ServiceAction install
.\scripts\local-agent.ps1 -Action service-command -ServiceAction start
설치 확인: Get-Service LearnBotLocalAgent
.\scripts\local-agent.ps1 -Action status  // Running이면 정상입니다.

2. learnbot login 아이디/비밀번호 입력
3. 명령어 
- 코드 수정: learnbot fix "README의 오타를 찾아 고쳐줘"
-  learnbot review "최근 변경사항 리뷰해줘"


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

- `POST /api/auth/cli-device-session/plan` returns a disabled CLI device/session bridge plan for future browser-approved CLI login. It is read-only and does not issue device codes, claim tokens, access tokens, refresh tokens, cookies, or stored sessions.
- `POST /api/auth/cli-device-session/create/plan` returns a disabled device-code creation plan for the future browser approval flow. It previews verification path, user-code format, expiry, and polling interval, but does not create a device code, user code, token, cookie, or stored session.
- `POST /api/auth/cli-device-session/claim/plan` returns a disabled CLI device/session claim plan for future polling and local web-session artifact storage. It is read-only and does not poll, claim, issue tokens, write local files, accept Local Agent tokens, or persist cookies.
- `POST /api/auth/cli-device-session/claim-result/plan` returns a disabled claim-result artifact-writer plan. It fixes future browser-approved claim-result preconditions and encrypted `web-session.json` writer shape without accepting tokens, serializing plaintext secrets, writing local files, accepting Local Agent tokens, or persisting cookies.
- The Local Agent CLI can inspect these public contracts with `learnbot session plan`, `learnbot session create-plan`, `learnbot session claim-plan`, and `learnbot session claim-result-plan`; these commands fall back to local static disabled plans when the server is offline or `--offline` is passed, and none uses the Local Agent pairing token. The claim plan includes the future encrypted `web-session.json` body preview with encrypted token placeholders only; the claim-result plan includes the disabled artifact-writer preflight shape.
- `learnbot session artifact-writer-preflight` validates the future browser-approved claim-result boundary from metadata flags only. It checks approved-claim state, token-presence flags, parseable expiry fields, and plaintext-token serialization refusal while keeping `web-session.json` writes, OS secret-store probing, token loading, Local Agent token use, and token printing disabled.
- `learnbot session artifact-writer-test-write` is an explicit `--test-only` proof for atomic encrypted artifact creation. It writes test-only AES-GCM encrypted placeholder token material to `web-session.json` without serializing plaintext token material or printing secrets; the derived test-only key is not stored in the artifact, so it is not a usable login session.
- `learnbot session artifact-reader-test-validate --test-only` reads only that test-only artifact provider, parses the schema, verifies encryption metadata, decrypts placeholder token material, and returns fingerprints only. Production stored-session loading and DPAPI/OS secret-store decryption remain disabled.
- `learnbot session artifact-production-crypto-preview --preview-only` verifies the future production artifact crypto boundary in memory with DPAPI current-user placeholder token material. It returns fingerprints and metadata only, and still does not write `web-session.json`, read stored sessions, load credentials, refresh tokens, or print token material.
- `learnbot session artifact-production-writer-preview --preview-only` prepares the future production `web-session.json` body shape in memory from simulated approved claim-result metadata plus the crypto preview proof. It also returns a write-disabled atomic replace plan with the future session path and temp path pattern, while still not accepting real token values, outputting encrypted token blobs, writing files, loading credentials, refreshing tokens, or printing token material.
- `learnbot session artifact-production-reader-preview --preview-only` models the future production artifact read/decrypt path without reading `web-session.json`, parsing stored JSON, decrypting stored token fields, loading credentials, refreshing tokens, or enabling stored-session server-plan auth.
- `learnbot session stored-session-auth-readiness` returns `learnbot.local-agent.web-session-stored-session-auth-readiness.v1`, a disabled readiness contract for future stored-session authenticated `fix`/`review --server-plan`. It records required browser claim, artifact read/decrypt, access/refresh token, expiry, refresh eligibility, and server-plan auth preconditions while still refusing file reads, token decrypt/load, refresh calls, request creation, mutation, Local Agent token use, and token printing.
- `learnbot session secret-provider-plan` returns a disabled production secret-store boundary for future Windows DPAPI/current-user or OS secret-store encryption. Automatic provider probing, production encryption/decryption, stored-session loading, plaintext token serialization, token printing, Local Agent token use, and accepting the test-only provider for production all remain disabled.
- `learnbot session secret-provider-probe` runs a no-secret Windows DPAPI current-user probe where available. It only protect/unprotect round-trips a sentinel string and still does not read, write, encrypt, decrypt, load, or print web-session token material.
- `learnbot session server-plan-readiness` returns `learnbot.local-agent.web-session-server-plan-readiness.v1`, a read-only bridge for future stored-session authenticated `fix`/`review --server-plan`. It reports environment-token fallback readiness by fingerprint only, keeps stored session loading disabled, and does not create requests or enable mutation.
- `learnbot session status` and `learnbot session server-plan-readiness` include `learnbot.local-agent.web-session-artifact-validation.v1`, `learnbot.local-agent.web-session-secret-provider-plan.v1`, and `learnbot.local-agent.web-session-stored-session-auth-readiness.v1`, disabled previews for the future encrypted `web-session.json` artifact, production secret-store provider, and stored-session auth/refresh path. The artifact validator also includes `learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1`, pointing to the required non-writing DPAPI crypto preview proof before production stored-session loading can be enabled. They fix required encrypted token, expiry, provider-readiness, crypto-proof, refresh, and server-plan-auth fields without reading, parsing, decrypting, refreshing, or printing token secrets.
- `learnbot fix|review` includes `learnbot.local-agent.codex-one-cycle-preview.v1`, a first-class user-cycle preview for goal input, workspace/file discovery, file reads, planning, patch dry-run, approval, apply/test, failure-log retry, final report, and RAG freshness update. It embeds `learnbot.local-agent.codex-file-discovery-read-plan.v1`, a dry-run-only read plan with candidate tools, bounded path/query hints, planned tree/search/status/read steps, and disabled `learnbot.local-agent.codex-read-only-request-envelope-preview.v1` envelopes for `workspace.tree`, `workspace.search`, and `git.status` while keeping file-content reads, enqueue/claim, request creation, mutation, and token printing disabled. With `--observe-read-only`, the CLI can execute only those first three read-only observations against a paired approved workspace and returns `learnbot.local-agent.codex-read-only-observation.v1`; search snippets are redacted, and nested `learnbot.local-agent.codex-read-only-candidate-selection.v1` ranks matched paths into bounded `file.read` candidates while keeping request creation and mutation disabled. Adding `--read-selected` explicitly reads only those selected candidates through bounded `file.read`, returns `learnbot.local-agent.codex-selected-file-read.v1`, prepares `learnbot.local-agent.codex-patch-intent-preview.v1`, exposes placeholder `learnbot.local-agent.codex-patch-proposal-preview.v1` metadata, carries disabled `learnbot.local-agent.codex-diff-source-input-preview.v1` for future `local-model`, `server-planner`, `inline`, or `file` diff sources, carries disabled `learnbot.local-agent.codex-planner-diff-output-preview.v1` for future planner output envelopes, carries `learnbot.local-agent.codex-generated-diff-acceptance-preview.v1`, carries `learnbot.local-agent.codex-planner-diff-validation-handoff-preview.v1`, adds `learnbot.local-agent.codex-diff-source-validation-preview.v1`, carries `learnbot.local-agent.codex-patch-dry-run-request-envelope-preview.v1`, carries `learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1`, and carries `learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1`. The diff-source input boundary accepts metadata such as `--diff-source`, `--diff-file`, or `--diff-text` presence but does not read files, accept inline diff bodies, or run planners. When `local-model` or `server-planner` is requested, the planner-output preview fixes the future output envelope shape with target files and unified diff requirement while keeping planner execution and normal diff generation disabled. A bounded in-memory generated diff can be accepted only with `--accept-generated-diff-preview --generated-diff ...`, only from the local-model/server-planner envelope path, and never from a diff file; if accepted, the handoff forwards it to validation preview and reports whether it parses and touches selected target files only. The validation boundary only prepares future `patch.apply` dry-run input when the accepted diff parses and touches selected target files only; the dry-run request envelope preview then fixes the future `patch.apply` request shape with `dryRunOnly=true`, `allowMutation=false`, `USER_LOCAL_AGENT`, and approval required before snapshot-writing dry-run, while request creation, enqueue, claim, snapshot creation, execution, mutation, tests, final-report publication, and partial reindex remain disabled. With `--run-nonwriting-preflight-preview`, a paired approved workspace can run only the existing non-writing context preflight from the accepted generated diff; it reads target files and validates hunks, but still creates no requests, snapshots, file writes, mutation, tests, final-report publication, or partial reindex. When that preflight passes, the approval handoff preview can reach `APPROVAL_HANDOFF_PREPARED` and carries repository id, workspace id, target files, request envelope status, and preflight status for the future snapshot-writing dry-run approval gate while every execution and persistence flag remains disabled. Default CLI output still keeps `diffGenerated=false`, patch dry-run execution, request creation, mutation, tests, final-report publication, and partial reindex disabled. Read-only discovery/read/plan stages can become ready once the CLI is paired, the workspace is approved, the goal is present, and the repository id is supplied; patch/test/final-report/partial-reindex stages remain disabled until authenticated server handoff, explicit approval, and release gates are real.
- `learnbot fix|review --server-plan` embeds that readiness report, the same one-cycle preview, the same dry-run file discovery/read plan, and `learnbot.local-agent.codex-read-only-server-bridge.v1` inside `learnbot.local-agent.codex-server-plan-fetch-result.v1` before deciding whether it is blocked for auth, can use `LEARNBOT_WEB_TOKEN`, or is still waiting for future stored-session support. The read-only bridge names the server runner preview/select/enqueue endpoints for the discovery/read/plan stages while keeping request creation, file reads, patch dry-run, mutation, token printing, and stored-session auth disabled. Adding `--include-approval-handoff-preview` builds the same CLI read-only/generated-diff/non-writing-preflight preview used by `--observe-read-only` and includes its `patchDryRunApprovalHandoffPreview` payload in the disabled server submission body. The server submission-plan endpoint also accepts that optional payload and returns disabled `learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1` and `learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1` contracts for reviewing validated CLI dry-run approval evidence in the browser without creating approval, release, queue, claim, snapshot, mutation, publication, or reindex work.
- The Code workspace also fetches the disabled submission-plan contract while previewing an agent loop and renders `patchDryRunApprovalReviewPreview` as a server-review panel. This panel shows repository/space/agent/workspace identity, target files, dry-run evidence readiness, future approval/release routes, and disabled approval/request/queue/claim/snapshot/test/publication/reindex/mutation flags without creating Local Agent work.
- `POST /api/code-agent/loop/runner/patch-dry-run-approval-intent-preview` consumes that server review preview and returns `learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1`, the disabled future browser approval-intent contract for snapshot-writing `patch.apply` dry-run. The Code workspace fetches and renders it after the submission plan, showing review evidence, approval intent readiness, future routes, and disabled intent creation, approval persistence, request creation, queueing, claim, snapshot, dry-run execution, tests, final publication, partial reindex, bypass, and mutation flags.
- `POST /api/code-agent/loop/runner/patch-dry-run-approval-request-creation-preview` consumes the approval-intent preview and returns `learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1`, a refusal-only browser approval persistence and approval-request creation boundary. It models the future approval save and Local Agent `patch.apply` dry-run request shape, but keeps approval persistence, approval request creation, request creation, server approval record creation, Local Agent tool request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it as the next read-only panel in the same submission-plan chain.
- `POST /api/code-agent/loop/runner/patch-dry-run-approval-decision-preview` consumes that request-creation preview and returns `learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1`, a disabled browser decision boundary with future approve/deny options and a held-request review preview. It keeps decision persistence, approval persistence, approval request creation, request creation, server approval record creation, decision recording, Local Agent tool request creation, held request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after request-creation preview so the user-visible chain reaches the future browser decision point without creating work.
- `POST /api/code-agent/loop/runner/patch-dry-run-approval-decision-persistence-preview` consumes the approval-decision preview and returns `learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1`, a disabled decision-persistence and held-request review boundary. It carries the future decision persistence envelope and held-request review source while keeping decision persistence, approval persistence, request creation, server approval record creation, decision recording/persistence, Local Agent tool request creation, held request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the decision preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-held-request-review-preview` consumes the approval-decision persistence preview and returns `learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1`, a disabled browser held-request review action boundary. It carries future review/approve/deny action names and the held-request review source while keeping held review execution, held request creation, decision persistence, approval persistence, request creation, server approval record creation, decision recording/persistence, Local Agent tool request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the decision-persistence preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-approval-action-preview` consumes the held-request review action preview and returns `learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1`, a disabled approve/deny action boundary for the browser review surface. It carries future approve and deny action names while keeping approval action execution, held review execution, held request creation, decision persistence, approval persistence, request creation, server approval record creation, decision/action recording or persistence, Local Agent tool request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the held-request review preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-approval-action-persistence-preview` consumes the approval-action preview and returns `learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1`, a disabled approval-action persistence and approval-record boundary. It carries the future approval-action persistence envelope while keeping approval action persistence, approval action execution, held review execution, held request creation, decision persistence, approval persistence, request creation, server approval record creation, decision/action recording or persistence, Local Agent tool request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the approval-action preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-approval-record-preview` consumes the approval-action persistence preview and returns `learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1`, a disabled approval-record and Local Agent request-creation boundary. It carries the future approval-record envelope while keeping approval record creation, approval action persistence, approval action execution, approval persistence, request creation, server approval record creation, Local Agent tool request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the approval-action persistence preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-request-envelope-preview` consumes the approval-record preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1`, a disabled Local Agent `patch.apply` dry-run request-envelope boundary. It carries the future request envelope with `USER_LOCAL_AGENT`, `SNAPSHOT_WRITING_DRY_RUN`, `dryRunOnly=true`, and mutation disallowed while keeping approval record creation, approval persistence, request creation, Local Agent tool request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the approval-record preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-request-creation-preview` consumes the Local Agent request-envelope preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1`, a disabled durable Local Agent request creation and queue-handoff boundary. It carries the future request-creation envelope while keeping request row creation, Local Agent tool request creation, enqueue, push, claim, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the request-envelope preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-queue-preview` consumes the Local Agent request-creation preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1`, a disabled queue, push, and claim handoff boundary for the future Local Agent `patch.apply` dry-run request. It carries queue/push/claim readiness while keeping request row creation, Local Agent tool request creation, enqueue, push, claim, claimability, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the request-creation preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-claim-readiness-preview` consumes the Local Agent queue preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1`, a disabled claim and snapshot-writing dry-run readiness boundary for the future Local Agent `patch.apply` dry-run request. It carries future claim/snapshot dry-run readiness while keeping approval record/action persistence, request creation, durable Local Agent request creation, enqueue, push, claim, claimability, snapshot creation, dry-run execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the queue preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-snapshot-dry-run-preview` consumes the claim-readiness preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1`, a disabled snapshot-writing dry-run observation boundary. It shows that future dry-run observation can be modeled while keeping request creation, durable Local Agent request creation, enqueue, push, claim, claimability, snapshot creation, dry-run execution, dry-run result recording, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the claim-readiness preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-dry-run-result-preview` consumes the snapshot dry-run preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1`, a disabled dry-run result, failure-log analysis, and retry-decision boundary. It exposes success/failure/retry decision fields for the future closed loop while keeping request creation, durable Local Agent request creation, enqueue, push, claim, claimability, snapshot creation, dry-run execution, result recording, failure-log recording, retry recording, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the snapshot dry-run preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-retry-input-preview` consumes the dry-run result preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1`, a disabled retry-input and replan boundary. It models how a future failed dry-run can become either bounded retry patch input or a replan-required user-visible decision while keeping approval record creation, approval persistence, request creation, durable Local Agent request creation, enqueue, push, claim, claimability, snapshot creation, dry-run execution, result recording, failure-log recording, retry decision recording, retry patch generation, retry request creation/execution, replan execution, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the dry-run result preview.
- `POST /api/code-agent/loop/runner/patch-dry-run-local-agent-retry-proposal-preview` consumes the retry-input preview and returns `learnbot.server.code-agent.patch-dry-run-local-agent-retry-proposal-preview.v1`, a disabled retry patch proposal and final-stop decision boundary. It models how a future bounded retry patch proposal or stop-with-replan decision would be surfaced while keeping retry patch generation, retry request creation/execution, replan execution, request creation, durable Local Agent request creation, enqueue, push, claim, snapshot creation, dry-run execution, result recording, tests, rollback, final publication, partial reindex, approval bypass, and mutation disabled. The Code workspace fetches and renders it after the retry-input preview.
- `/settings/local-agent/device` is a disabled browser approval route for the future CLI device-code login flow. It routes to the Code workspace and displays `learnbot.web.local-agent.device-approval-route-plan.v1` without validating device codes, approving sessions, issuing tokens, persisting cookies, accepting Local Agent credentials, or printing secrets.
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
- `POST /api/code-agent/loop/preview` with `{ "repositoryId": "...", "instruction": "...", "maxSteps": 6 }`
- `GET /api/code-agent/loop/timelines?repositoryId=...&limit=5`

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

The central gateway skeleton exposes `GET /api/local-agents/status` for the current user. Users can issue a one-time-visible Local Agent credential with `POST /api/local-agents/pairing-token`; the server stores only a hash in `local_agent_tokens`. Authenticated web users can inspect non-secret token metadata with `GET /api/local-agents/tokens` and revoke their own token with `DELETE /api/local-agents/tokens/{tokenId}`. Token list responses include id, agent id, label, expiry, created time, last-seen time, revoked time, and active state, but never the raw credential. A Local Agent can then call `POST /api/local-agents/heartbeat` with `X-Local-Agent-Token`, agent id, version, capabilities, approved workspace summaries, configured transport, active transport, WebSocket failure count, and next WebSocket retry time.

Tool routing now has a durable polling baseline: the server records queued Local Agent work in `local_agent_tool_executions`, a token-authenticated Local Agent can claim the next request with `GET /api/local-agents/tools/next`, and it can persist a structured result with `POST /api/local-agents/tools/{requestId}/response`. For internal smoke and diagnostics, authenticated web users can enqueue only safe read-only requests through `POST /api/local-agents/tools/read-only` and inspect the persisted result through `GET /api/local-agents/tools/{requestId}`. That diagnostic enqueue path currently allows only `file.read`, `git.status`, and `git.diff`; mutation, command execution, patch, test, and rollback requests remain blocked. WebSocket transport work follows `docs/local-agent-websocket-transport.md`: the server skeleton is behind `LEARNBOT_LOCAL_AGENT_WEBSOCKET_ENABLED=false` by default, accepts only token-authenticated messages, registers connected sessions after `hello`/`heartbeat`, can push persisted read-only `tool.request` envelopes to connected agents, and can persist `tool.response` envelopes through the same completion service. The Local Agent CLI now accepts `polling`, `websocket`, and `auto` transport modes; `websocket` and `auto` try a live WebSocket hello/heartbeat, process pushed read-only `tool.request` messages during a bounded receive window, send `tool.response`, and keep durable polling active for fallback. The Code workspace shows disconnected/stale state, active transport state, WebSocket retry state, and Local Agent token metadata/revocation controls so side-effectful local work does not silently fall back to server-local execution and stale pairings can be retired.

Patch proposal and mutation execution are intentionally separated. `GET /api/code-agent/mutation-policy` exposes the current boundary for the web UI: patch proposals are available, the intended execution target for user-owned mutation is `USER_LOCAL_AGENT`, Local Agent patch/test/rollback execution is still disabled, and server-local mutation remains prototype/admin/debug-only behind `LEARNBOT_CODE_SERVER_LOCAL_MUTATION_ENABLED=false` by default.
Milestone 6 starts with a non-executing loop preview at `POST /api/code-agent/loop/preview`. It resolves repository access, persists an audit-only row in `code_agent_loop_timelines`, records preview events in `code_agent_loop_timeline_events`, and returns a bounded agent-loop outline with max steps, timeout, plan/tool/approval/observation/completion phases, stop conditions, and explicit `mutationEnabled=false`/cancellation-disabled flags. `GET /api/code-agent/loop/timelines` resolves the same repository access and returns recent persisted audit timelines with ordered events for the current user/repository, limited to a bounded read-only history window. The Code workspace can request and render the live preview as read-only loop telemetry, including mutation/timeline/cancellation state, planned phases, stop conditions, and warnings; it also fetches and renders recent persisted loop history with event `mayMutate=false` telemetry after preview refresh or a manual history refresh. The event rows classify the preview phases as model-decision, tool-selection, approval-checkpoint, observation-wait, and completion-decision previews, then append audit-only timeout, cancellation, final-result, and stop-outcome policy records so weak evidence, missing agent, tool failure, and approval denial have explicit non-executing outcomes before any real loop runner exists. When a Local Agent repository observation or patch dry-run response completes and the original tool request input identifies the code repository, the server appends a `LOCAL_AGENT_OBSERVATION_RESULT` event to the correlated loop timeline when `loopId` is present, otherwise to the latest audit timeline for that user/repository. That event records request/session/agent/workspace ids, status, source/release attempt linkage, fresh-observation, dry-run, mutation-applied, repository verification, snapshot-created, and warning details while keeping `may_mutate=false`; failed, timed-out, cancelled, and disconnected observations also append `STOP_OUTCOME_RECORDED` events for `TOOL_FAILED`, `TIMEOUT`, `CANCELLATION`, or `AGENT_UNAVAILABLE` with final-result publication, acknowledgement, and mutation disabled. When a user approves or denies a prepared Local Agent tool request and the stored request input identifies the code repository, the server also appends a `LOCAL_AGENT_APPROVAL_DECISION` event with approval state and held/rejected status using the same `loopId` correlation fallback while keeping the request non-claimable unless a future explicit release path enables it; denied approvals also append a `STOP_OUTCOME_RECORDED` event for `APPROVAL_DENIED` with final-result publication, acknowledgement, and mutation disabled. If an approval request or enqueue attempt sees the selected Local Agent is not connected before any tool request is created or pushed, the same timeline records `AGENT_UNAVAILABLE` without changing the existing rejection behavior. This preview/history path does not create, push, claim, release, or execute Local Agent mutation requests; it fixes the future loop contract before real apply/test/rollback/RAG-freshness work is enabled.
For the next migration step, `POST /api/code-agent/local-patch-request` can prepare a non-executing `patch.apply` Local Agent tool request. It validates the unified diff, records repository/workspace/agent ids, target files, expected indexed file hashes, snapshot requirement, snapshot policy, rollback policy, and stale-index policy, then persists the request as `APPROVAL_REQUIRED`. That request is not pushed to the Local Agent and cannot be claimed until a later approval/execution slice explicitly enables it.
The Code workspace can prepare and display this request for review, including request id, status, target files, expected hashes, request warnings, snapshot requirement/policy, rollback policy, stale-index policy, source repository identity, selected Local Agent workspace identity, and workspace/repository verification state. Users can explicitly approve or deny the prepared request through `POST /api/local-agents/tools/{requestId}/approval`. Approval moves it to `APPROVED_HELD`, which records intent but remains non-claimable and non-executing; denial moves it to `REJECTED`. `GET /api/local-agents/tools/{requestId}/readiness` exposes the non-executing preflight checklist for a held patch request: held approval state, user-local target, matching connected agent, approved workspace, `patch.apply` capability, `rollback.restore` capability, request schema, diff/target/expected hash data, snapshot requirement, target-file snapshot policy, rollback policy, stale-index policy, workspace/repository verification, latest linked dry-run snapshot manifest/rollback precondition observations, the latest server-recorded repository observation verification linked by `sourceRequestId` when available, a `snapshotReadiness` summary (`MISSING`, `INVALID`, `PREVIEW_ONLY`, or `CREATED`), a non-executing `rollbackReadiness` summary, a non-mutating `patchReleaseReadiness` summary, a non-public `patchExecutionGate` summary, a typed top-level `releaseAttemptModel` read model, and the explicit release gate. `snapshotReadiness` accepts snapshot evidence only from a non-mutating Local Agent dry-run with `dryRun=true` and `mutationApplied=false`; if mutation has already been applied, the snapshot evidence is marked `INVALID` and remains blocking. `rollbackReadiness` validates only the created manifest contract: target paths must be workspace-relative, snapshot source paths must stay under `files/`, restore preconditions must exist, and user approval must be required. `patchReleaseReadiness` combines the pre-apply prerequisites into one release-blocked view: explicit approval, repository verification, hash/context dry-run preflight, created snapshot, rollback manifest validation, `patch.apply` capability, and `rollback.restore` capability. `patchExecutionGate` mirrors whether those prerequisites are internally visible, but keeps `claimEnabled=false`, `writeHelperEnabled=false`, `mutationEnabled=false`, and `releaseGateEnabled=false`. It also reports `preReleaseRevalidation=REQUIRED_BEFORE_RELEASE`: any future release must run fresh Local Agent repository verification and dry-run/snapshot checks immediately before making the held request claimable instead of trusting stale readiness evidence. The typed `releaseAttemptModel` also remains mirrored inside `patchExecutionGate` for UI compatibility; it uses schema `learnbot.local-agent.patch-release-attempt.v1`, a 120-second stale window, and required evidence keys for release attempt id, source request id, fresh repository verification, fresh patch dry-run, created snapshot manifest, rollback manifest validation, and explicit user release approval. A disabled release-attempt persistence skeleton now exists in `local_agent_patch_release_attempts` with source request linkage, session/user/agent/workspace ids, status, claimable flag, stale window, evidence JSON, failure reasons, and timestamps. Readiness can surface the latest stored attempt as audit-only `latestAttempt` data including `ageSeconds`, `expiresAt`, `freshnessStatus` (`FRESH`, `STALE`, or `UNKNOWN`), `stale`, and `freshObservationRequirements` for repository verification after attempt creation, patch dry-run after attempt creation, snapshot creation after fresh dry-run, rollback validation after fresh snapshot, and user release approval after fresh evidence; these remain informational and never make the attempt claimable. If the internal release path is called while preconditions are visible, it records a non-claimable disabled attempt envelope and still refuses with the release gate disabled; no path creates a claimable attempt yet. The backend also has a disabled-by-default release skeleton that would later transition an `APPROVED_HELD` patch to claimable `APPROVED` only after gate evidence passes and an explicit release flag is enabled; with the current flag disabled, the transition refuses and no request becomes claimable. The release gate is currently disabled, so readiness reports why execution remains blocked instead of applying files. Workspace/repository verification is conservative: the prepared request records `UNVERIFIED` by default, and readiness may compute an effective `VERIFIED` result only from a trusted latest `MATCH` repository observation whose non-skipped checks all match. `MISMATCH` and `UNVERIFIED` observations remain blocking and visible. Even after effective verification passes, `snapshotReadiness=CREATED`, `rollbackReadiness=RESTORE_VALIDATED`, and `patchReleaseReadiness=PRECONDITIONS_READY_RELEASE_DISABLED`, the disabled release gate still prevents patch execution. The Code workspace can queue a read-only repository observation and compare observed local branch/HEAD/remote values with the indexed source metadata, showing `MATCH`, `MISMATCH`, or `UNVERIFIED`. When a `git.status` observation completes with source repository metadata, the backend records that comparison as `repositoryVerification` on the persisted observation output. Repository observation requests also carry `sourceRequestId`, so readiness can surface the latest recorded verification for the held patch request. This remains visibility-only and does not open mutation release. For local observations without release, `POST /api/local-agents/tools/{requestId}/dry-run` creates a separate `patch.apply` tool request with `dryRunOnly=true`, `mutationAllowed=false`, and `sourceRequestId` pointing at the held request. The original `APPROVED_HELD` request stays held, while the Local Agent can claim the dry-run clone and return hash/context observations plus a managed Local Agent snapshot manifest after dry-run preconditions pass. The Local Agent copies target files to `%USERPROFILE%\.learnbot\snapshots\<manifestId>\files\`, writes `manifest.json`, and still returns `mutationApplied=false`; patch execution, test, and rollback controls remain disabled until the later release gates are implemented.
The first real snapshot creation boundary is specified in `docs/local-agent-snapshot-implementation-plan.md`. Snapshot creation support is the recovery baseline before any patch application release is considered.


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

The skeleton stores config in `%USERPROFILE%\.learnbot\agent.json`, or in `LEARNBOT_AGENT_CONFIG` when that environment variable is set for tests. It writes a minimal run-state file and `agent.log` next to that config, so internal users can inspect `learnbot agent status` and `learnbot agent logs --tail 80`; a guarded Windows Service helper is also available through `scripts/local-agent.ps1 -Action service-command` for administrator-run install/start/stop/uninstall. `learnbot agent token` reports paired state, agent id, a non-secret token fingerprint, and the web management URL without printing the raw credential. Long-running `agent start` logs transient loop failures and continues polling; `--once` returns a failure exit code for smoke tests. The stored transport mode defaults to `polling`; `websocket` and `auto` try a bounded WebSocket hello/heartbeat and fall back to REST heartbeat if the endpoint is disabled, unreachable, or rejected. When WebSocket is unavailable, the run-state records the configured transport, active fallback transport, consecutive WebSocket failures, and the next retry time while durable polling continues. During the bounded WebSocket receive window, pushed `tool.request` messages reuse the same safe local handler as polling and return `tool.response`; durable polling remains active as fallback. It sends heartbeat, reports approved local workspace summaries, polls the durable tool queue, and handles `agent.status`, `agent.doctor`, `workspace.list`, path-contained `file.read`, read-only `git.status`, bounded read-only `git.diff`, and approved typed `command.runAllowed` requests. The backend durable tool queue now gives claimed requests a five-minute execution lease, expires stale `RUNNING` rows as `TIMED_OUT` before the next claim, records the timeout stop outcome when repository/loop context is present, and refuses to let late completion responses overwrite terminal timeout/cancel/failure rows. File reads are limited to approved workspace roots, reject path traversal/workspace escape, reject binary files, and cap returned content. Git status uses a fixed `git status --porcelain=v1 -b --untracked-files=all` command with optional locks disabled, requires the approved workspace to be a Git worktree root, and also returns read-only repository identity observations when available: git root, current branch, HEAD commit, remote name, and remote URL. Git diff reads both staged and unstaged changes with `--no-ext-diff`, supports an optional workspace-contained relative `path`, and caps returned diff bytes. `command.runAllowed` accepts only typed allowlisted ids such as `dotnet.build`, `dotnet.test`, `npm.run.build`, `npm.test`, `maven.test`, and `maven.backend.test`, requires `approvalState=APPROVED`, caps timeout/output, and returns structured stdout/stderr/exit-code data without accepting arbitrary shell strings. The Local Agent can dry-run a `patch.apply` request with `dryRunOnly=true` and `mutationAllowed=false`; that path resolves the approved workspace, rejects path escape, verifies expected hashes or hunk context, creates a managed snapshot of target files after preflight passes, and returns `mutationApplied=false`. A narrow approved `patch.apply` mutation path is also available inside the Local Agent runtime for one file at a time: it requires `mutationAllowed=true`, `dryRunOnly=false`, a managed rollback snapshot manifest, immediate target-hash recheck, and the guarded temp-file rewrite helper. Approved `rollback.restore` requests can restore only from a Local Agent managed snapshot manifest under `%USERPROFILE%\.learnbot\snapshots`; the agent revalidates manifest schema/id, approved workspace id/root, managed snapshot paths, and workspace-contained targets before copying snapshot files back. The backend response completion path now adds an audit-only `mutationResultIntakeCandidate` classification and an `acceptedMutationObservation` envelope for release-attempt mutation-sequence results from `patch.apply`, `command.runAllowed`, post-write `git.status`, and `rollback.restore`; both are preserved inside the completed tool response. Accepted observations are also upserted into `local_agent_mutation_observation_intake`, a dedicated durable read-model table keyed by request/source/release attempt. Readiness surfaces the latest accepted observation as `acceptedMutationObservationReadiness`, reading the durable table first and falling back to legacy response JSON, and also summarizes durable observations by tool/status/count for the final mutation report contract, RAG freshness gate, mutation result aggregation plan, final mutation report draft, finalization boundary, and final-answer publication boundary. The approved execution flow can now publish a final result, save the deterministic final answer to Saved Answers, and append a loop timeline publication event; RAG freshness updates, rollback fallback execution, and follow-up mutation remain gated with stale-index disclosure as fallback.

The disabled final-answer completion and persistence surfaces also carry the publication rollback-summary accepted-observation counts and risk context forward as read-only audit data before refusing persistence and delivery work.
The disabled final-answer conversation-save and user-visible completion surfaces continue that rollback-summary context forward before refusing conversation save, user-visible completion, and final-response handoff work.
The disabled final-response handoff and final-answer delivery surfaces continue that rollback-summary context forward before refusing final-response handoff, delivery handoff, and final-answer delivery work.
The disabled final-answer delivery receipt surface continues that rollback-summary context forward before refusing delivery receipt recording and acknowledgement-save work.
The disabled mutation completion, handoff, and execution-readiness surfaces continue that rollback-summary context forward before refusing handoff, runtime execution, result intake, and acknowledgement-save work.
The disabled mutation tool-runner and result-completion surfaces continue that rollback-summary context forward before refusing runner invocation, completed-result transition, persistence, observation capture, result intake, and acknowledgement-save work.
The disabled result-intake persistence, rollback fallback, and RAG freshness surfaces now carry a durable accepted-observation rollback-summary alias forward as read-only audit data before refusing intake persistence, rollback fallback execution, and RAG freshness update work. This avoids a circular direct dependency from result-completion back into earlier intake assembly while keeping the same summary/risk context visible downstream.
The disabled result aggregation and publication surfaces now continue that RAG freshness rollback-summary alias forward before refusing aggregation, publication, final-answer generation, and acknowledgement-save work.
The disabled result aggregation and publication surfaces now also continue the publication-gate status/schema and identity carried by RAG freshness before refusing aggregation, publication, final-answer generation, and acknowledgement-save work.
The disabled final-answer generation and completion surfaces now preserve the publication-gate status/schema together with the publication rollback-summary context before refusing final-answer generation, completion, delivery, and acknowledgement-save work.
The disabled final-answer generation and completion surfaces now render that publication-gate status/schema/session/agent/workspace identity before the publication observation/risk/latest/rollback-summary context while final-answer generation, completion, delivery, and acknowledgement-save work remain disabled.
The disabled final-answer persistence and conversation-save surfaces now preserve that publication-gate status/schema and identity together with the publication rollback-summary context before refusing persistence, conversation save, user-visible completion, delivery, and acknowledgement-save work.
The disabled final-answer user-visible completion and final-response handoff surfaces now continue that publication-gate status/schema and identity before refusing user-visible completion, final-response handoff, delivery handoff, and final-answer delivery work.
The disabled final-answer delivery and delivery-receipt surfaces now continue that publication-gate status/schema and identity before refusing final-answer delivery, delivery handoff, delivery receipt recording, and acknowledgement-save work.
The disabled mutation completion, handoff, and execution-readiness surfaces now continue that publication-gate status/schema and identity before refusing completion handoff, runtime execution, result intake, and acknowledgement-save work.
The disabled mutation completion summary, handoff summary, and execution-readiness boundary render their existing publication-gate status/schema/session/user/agent/workspace identity before publication boundary, observation/risk/latest-observation, and rollback-summary context. This remains display-only and does not add completion handoff, runtime execution, request creation, push, claim, tool runner, result intake, acknowledgement save, publication, aggregation, RAG freshness, rollback fallback, or mutation controls.
The disabled mutation tool-runner and result-completion surfaces now continue that publication-gate status/schema and identity before refusing runner invocation, completed-result transition, observation capture, result intake, and acknowledgement-save work.
The disabled mutation tool-runner and result-completion boundary displays render their existing publication-gate status/schema/session/user/agent/workspace identity before publication boundary, observation/risk/latest-observation, and rollback-summary context. This remains display-only and does not add runner invocation, running transition, completed-result transition, result persistence, observation capture, result intake, acknowledgement save, publication, aggregation, RAG freshness, rollback fallback, or mutation controls.
The disabled result-intake persistence, rollback fallback, and RAG freshness surfaces now continue that publication-gate status/schema and identity from the accepted-observation summary before refusing accepted-observation persistence, rollback fallback execution, RAG freshness update, aggregation, publication, final-answer generation, and acknowledgement-save work.
The disabled result-intake persistence, rollback fallback, and RAG freshness displays render that publication-gate status/schema/session/user/agent/workspace identity before accepted-observation readiness/latest, accepted-observation summary/risk, and rollback-summary context. This remains display-only and does not add accepted-observation persistence, rollback fallback execution, RAG freshness update, aggregation, publication, final-answer generation, acknowledgement save, or mutation controls.
The disabled result-intake persistence, rollback fallback, and RAG freshness id displays now also include the owning user id between session and agent so the visible disabled chain keeps session/user/agent/workspace identity together before refusing intake persistence, rollback fallback execution, and RAG freshness update work.
The disabled result-aggregation and publication displays render that publication-gate status/schema/session/user/agent/workspace identity before accepted-observation summary/risk/latest and rollback-summary context. This remains display-only and does not add aggregation execution, publication, final-answer generation, acknowledgement save, RAG freshness update, rollback fallback execution, or mutation controls.
The disabled result-aggregation and publication id displays now also include the owning user id between session and agent so the visible disabled chain keeps session/user/agent/workspace identity together before refusing aggregation and publication work.
The disabled final-answer generation and completion displays render that publication-gate status/schema/session/user/agent/workspace identity before publication boundary, accepted-observation summary/risk/latest, and rollback-summary context. This remains display-only and does not add final-answer generation, completion, delivery, final-response handoff, delivery receipt, acknowledgement save, publication, aggregation, RAG freshness update, rollback fallback execution, or mutation controls.
The disabled final-answer generation and completion id displays now also include the owning user id between session and agent so the visible disabled chain keeps session/user/agent/workspace identity together before refusing final-answer generation and completion work.
The disabled final-answer persistence and conversation-save id displays now also include the owning user id between session and agent so the visible disabled chain keeps session/user/agent/workspace identity together before refusing final-answer persistence and conversation-save work.
The disabled final-answer user-visible completion and final-response handoff id displays now also include the owning user id between session and agent so the visible disabled chain keeps session/user/agent/workspace identity together before refusing user-visible completion and final-response handoff work.
The disabled final-answer delivery and delivery-receipt id displays now also include the owning user id between session and agent so the visible disabled chain keeps session/user/agent/workspace identity together before refusing final-answer delivery and delivery-receipt work.
The disabled mutation completion, handoff, and execution-readiness summary displays now also render their own session/user/agent/workspace identity before source-context details, closing the visible id trail through the final disabled readiness summary surfaces. Further work should move from display-only alignment toward a narrow Milestone 6 Local Agent execution-flow verification slice using the already implemented lease/timeout, `patch.apply`, `rollback.restore`, and allowlisted test runner pieces.
The Local Agent now advertises `patch.apply`, `command.runAllowed`, and `rollback.restore` in heartbeat capabilities, matching the tool handlers it already supports. A new `approved-execution-flow-contract` self-test runs a narrow approved Local Agent flow in a temporary workspace: managed snapshot creation, approved single-file `patch.apply`, allowlisted `command.runAllowed`, `git.status` observation, and approved `rollback.restore`, while preserving session/user/agent/workspace identity and keeping broad release/mutation orchestration disabled.
The backend now has a narrow server-side approved execution-flow contract for the same ordered response sequence: `patch.apply`, `command.runAllowed`, post-write `git.status`, and `rollback.restore`. It verifies source/release linkage, shared session/user/agent/workspace identity, per-step verification statuses, accepted audit-only observations, and disabled request creation, push, claim, result intake, acknowledgement save, and follow-up mutation flags without making any request claimable.
The live PostgreSQL Local Agent tool repository test now connects that approved execution-flow contract to durable lease/response persistence. Its opt-in regression creates four already-approved rows, claims and completes them in order through `claimNext`/`complete`, reconstructs the completed response envelopes from persisted rows, and proves the contract still reports ordered `patch.apply`, `command.runAllowed`, `git.status`, and `rollback.restore` verification while request creation, push, broad claim enabling, result intake, acknowledgement save, follow-up mutation, and orchestration remain disabled.
`LocalAgentToolGatewayService.inspectApprovedExecutionFlow` now exposes the same persisted-row proof as a read-only service model. It reads caller-owned completed execution rows by id, rebuilds the contract steps, and returns repository-backed/read-model-only summary flags while tests verify it does not create, push, claim, complete, persist intake, acknowledge, orchestrate, or enable follow-up mutation.
The service read model now rejects missing rows, rows owned by a different user, and nonterminal or unfinished rows before building the approved execution-flow summary. These fallback checks keep the inspection surface read-only and prevent running/held/incomplete tool executions from being presented as completed approved-flow evidence.
`POST /api/local-agents/tools/approved-execution-flow/inspection` exposes that hardened service model through a narrow read-only API. The endpoint accepts an ordered `requestIds` list for the current user and returns the repository-backed approved-flow summary without creating, pushing, claiming, completing, persisting intake, acknowledging, orchestrating, or enabling mutation.
The Code workspace now has a read-only approved execution-flow inspection surface wired to that endpoint. When a readiness model provides ordered completed request ids, the UI can request the backend summary and render ordered/identity/release-linkage/terminal status, disabled control flags, request ids, per-step verification, and the audit-only message without creating, pushing, claiming, completing, persisting intake, acknowledging, orchestrating, or enabling mutation.
The approved-flow inspection endpoint and frontend client now have guardrail tests for invalid payloads and client request shaping. Empty and oversized `requestIds` lists fail validation before the service is called, and the frontend client filters blank ids, posts the bounded ordered ids to the read-only endpoint, stores the summary, and no-ops when no ids are available.
The approved-flow inspection surface can now derive ordered request ids from durable completed Local Agent rows by `releaseAttemptId`. `POST /api/local-agents/tools/approved-execution-flow/inspection/by-release-attempt` asks the repository for completed approved `patch.apply`, `command.runAllowed`, `git.status`, and `rollback.restore` rows, summarizes the same read-only contract, and reports `requestIdSource=durableCompletedRows`; the Code workspace client prefers this release-attempt path and falls back to caller-provided ids only when needed.

A live polling smoke can be run against the local stack after `.\scripts\up.ps1 -Build`:

```powershell
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -ToolName file.read -Path README.md
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -ToolName git.status
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath C:\Users\honeybadger\Desktop\LearnBot -ToolName git.diff -Path README.md
```

The script logs in, issues a pairing token, stores a temporary Local Agent config, registers the workspace, queues a read-only server request, runs the agent once, and verifies that the server persisted a `SUCCEEDED` tool response.

To verify the guarded backend-created approved execution sequence, restart the backend and then run the release-created smoke:

```powershell
docker compose up -d --build backend nginx
powershell.exe -ExecutionPolicy Bypass -File .\scripts\quality\local-agent-flow\run-live-server-release-created-flow-smoke.ps1
```

`LEARNBOT_LOCAL_AGENT_PATCH_EXECUTION_RELEASE_ENABLED` and `LEARNBOT_LOCAL_AGENT_APPROVED_EXECUTION_SEQUENCE_CREATION_ENABLED` default to `true` for the local stack, while explicit environment overrides can still disable them for rollback. The smoke creates held source/evidence rows, calls the backend release endpoint, and expects the server-created durable sequence to be completed by Local Agent polling while final-answer publication and acknowledgement save remain disabled.

To verify the Code workspace release surface and that same live release path in one repeatable command, use the UI-flow wrapper:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\quality\local-agent-flow\run-live-server-release-ui-flow-smoke.ps1 -StartFlaggedStack -RestoreDefaultStack
```

This wrapper runs the route-level Code workspace release smoke, then runs the live release-created Local Agent polling smoke against the flagged stack and restores the default release-disabled backend afterward. It is still route-level UI coverage rather than a true interactive browser click.

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

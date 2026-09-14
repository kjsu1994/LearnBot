# LearnBot

LearnBot is a local RAG knowledge workspace for approved web pages, CSV/Excel files, and private Git repositories.
Uploaded source files are stored in MinIO. Git repositories are cloned into a Docker volume and indexed into PostgreSQL/pgvector.

## Stack

- Frontend: React + Vite
- Backend: Spring Boot
- Database: PostgreSQL + pgvector
- Local LLM runtime: Ollama
- Default chat model: `ornith:9b`
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

Roslyn `AUTO` mode selects `SAFE_SOLUTION`, `SAFE_PROJECT`, or `SIMPLE` from repository contents. `SAFE_PROJECT` and `SAFE_SOLUTION` load project metadata through the bundled Roslyn `MSBuildWorkspace` and fall back to the bounded simple parser when workspace loading fails. Legacy `PROJECT` and `SOLUTION` config values are accepted as aliases for `SAFE_PROJECT` and `SAFE_SOLUTION`. Repository build commands and source generators are not launched as a separate build step by LearnBot. Java dependency resolution parses Maven/Gradle declarations without running the repository build, uses the persistent `.dependency-cache` under the code workspace, and only downloads release artifacts from configured HTTPS repository allow lists.

The optional LLM stage runs as a durable post-index enrichment job after the deterministic graph is active. Pending work survives restarts, retries up to three times, and is skipped when a newer index replaces it. It only accepts known graph node keys and approved relationship types, and records output with lower confidence. If JavaParser, dependency resolution, Roslyn, the LLM, or graph retrieval fails, indexing/search continues with the available deterministic graph or the existing keyword/vector search.

Each indexing job records `SUCCESS`, `PARTIAL`, `FAILED`, or `SKIPPED` diagnostics for the base graph, Java classpath, Java semantic analysis, Roslyn, and LLM enrichment. The Code workspace exposes these under **분석 진단**. They are also available from:

```text
GET /api/code/repositories/{repositoryId}/jobs/{jobId}/diagnostics
```

Existing active indexes remain readable after an upgrade. Reindex a repository when its analyzer version or index schema version is older than the version required by the current image; this creates qualified signature nodes, expanded relationships, authority metadata, and multi-hop paths using the current analyzers. A failed reindex does not replace the previous active index.

The backend deployment image includes both Java 17 and the .NET 8 runtime required by Roslyn. Build deployment images with the GPU Compose overlay shown in the Run section; GPU access is assigned to Ollama, while source analysis remains CPU-based.

### Code RAG retrieval flow and regression checklist

Current Code RAG retrieval is hybrid and iterative. The server performs bounded seed and endpoint retrieval, builds a versioned repository map, and asks the LLM planner for atomic claims and typed evidence operations. The server validates and executes only observed operands, expands indexed graph relations, ranks and diversifies candidates, and mechanically checks that required claim evidence remains in the final prompt. No production rule contains a benchmark question, repository name, target file, or expected answer.

```text
POST /api/code/ask
-> CodeController.ask
   - resolves access scope and optional conversation context
-> CodeRagService.ask / askConversational
   - backward-compatible controller facade
-> CodeRagOrchestrator.askPrioritized
   -> CodeQuestionRouter
      - resolves conversation-aware question, route, mode, and bounded result limit
   -> CodeRagOrchestrator.retrieveCodeEvidence
   -> CodeSearchService / CodeRepository seed, endpoint, lexical, and reference retrieval
   -> CodeRagOrchestrator.expandGraphEvidenceOnce
      -> CodeSearchService.expandGraph
      -> CodeRepository.graphRelatedChunks
   -> RepositoryQuestionMapBuilder
   -> RagPipelineService.planCodeEvidenceSearch
      - combined planning returns route, atomic claims, and typed operations
   -> CodeRetrievalPlanValidator
      - rejects unobserved operands and duplicate operations before execution
   -> CodeEvidenceOperationExecutor
      - validates claim/origin/operand provenance and executes bounded reads/search/traversal
   -> CodeEvidenceRanker.rank
   -> CodeEvidenceSelectionPolicy and CodeEvidenceFileDiversity
   -> RagPipelineService.planCodeEvidenceFollowUp / planCodeEvidenceIteration
      - unresolved claims can request another bounded operation round
-> EvidenceExtractorRegistry
   -> EndpointEvidenceExtractor / AssignmentEvidenceExtractor / TransactionEvidenceExtractor
   -> NavigationEvidenceExtractor / PersistenceEvidenceExtractor
   -> CodeEvidenceAccumulator -> Code Intelligence IR -> CodeEvidenceAdjudicator
-> CodeContextAssembler
   - orders evidence, renders excerpts, and enforces the prompt budget
-> CodeEvidenceCoverageGate
   - verifies evidence identity, direct proof, and final-context retention
-> CodeAnswerGenerator / OllamaCodeAnswerGenerator
-> CodeAnswerVerifier
   - accepts, retries, blocks, or selects the evidence-fidelity fallback
-> CodeRagDiagnosticsBuilder
-> CodeAskResponse
```

`CodeRetrievalCoordinator` and `CodeRetrievalLoop` define and test the bounded retrieval-loop boundary, including operation fingerprints, limits, stagnation, deadlines, and failure isolation. The production iterative retrieval body still runs inside `CodeRagOrchestrator` while that boundary is migrated under parity tests and the Live E2E quality gate; do not treat the new class names alone as proof that the full production loop has already moved.

Question-type evidence rules must be added through the `EvidenceExtractor` SPI instead of as new branches in `CodeRagOrchestrator`. Extractors run at the post-operation and pre-answer stages and emit the shared Code Intelligence IR (`CodeEvidenceIr`, facts, constraints, signals, items, and navigation handles). Accumulation and adjudication consume that IR without depending on a benchmark question or repository.

When combined planning is available, routing is returned by the initial repository planner instead of consuming a standalone route-model call. The original user question remains the answer question; an effective conversational question and route hints are retrieval context only.

The evidence checklist and atomic claims are intentionally generic. They are generated from the user's question, while the server supplies stable claim IDs, validates operation provenance, executes bounded retrieval, and applies a mechanical coverage gate. Exact endpoint matches, lexical symbols, observed calls/types, implementation completeness, graph authority, and file diversity are generic ranking signals rather than project-specific answer rules.

The LLM is the semantic planner and claim verifier; the server is the bounded executor and provenance gate. A central service method can prove orchestration, but a concrete search, graph traversal, ranking, persistence, transaction, answer-context, or model-call claim should use the implementation that performs that phase. In combined planning, verifier-selected evidence can be used directly; legacy or incomplete selections may still use a bounded adjudication path.

These regression questions describe a frozen historical LearnBot repository, including retired Agent code. For RAG-only removal verification, use the same frozen repository before and after deployment; do not reindex the changing working tree or delete old expectations. A good answer should cite the listed components together, not just mention a high-level orchestrator.

1. Code RAG request flow

```text
코드 질문을 하면 /api/code/ask 요청이 Controller에서 어떤 Service들을 거쳐 검색, evidence ranking, 답변 생성까지 이어지는지 설명해줘
```

Expected citation mix:

- `CodeController`
- `CodeRagService`
- `CodeRagOrchestrator`
- `CodeQuestionRouter`
- `CodeSearchService`
- `CodeEvidenceRanker`
- `RagPipelineService`
- `CodeContextAssembler`
- `CodeAnswerGenerator` / `OllamaClient`

For the graph-specific version:

```text
코드 질문을 하면 /api/code/ask 요청이 Controller에서 어떤 Service들을 거쳐 검색, graph expansion, evidence ranking, 답변 생성까지 이어지는지 설명해줘
```

Expected flow:

```text
CodeController
-> CodeRagService
-> CodeRagOrchestrator / CodeQuestionRouter
-> RagPipelineService
-> CodeSearchService / CodeRepository
-> CodeEvidenceRanker / EvidenceExtractor SPI / evidence selection / coverage gate
-> CodeContextAssembler
-> CodeAnswerGenerator / CodeAnswerVerifier / OllamaClient
```

2. Indexing to graph storage

```text
코드 저장소를 인덱싱하면 파일 청크 생성부터 code_graph_nodes, code_graph_edges 저장까지 어떤 흐름으로 처리돼?
```

Expected citation mix:

- `CodeController` endpoint for repository indexing, such as `/repositories/{repositoryId}/index`
- `CodeIndexingService`
- `CodeChunkParser`
- `CodeGraphBuilder`
- `JavaSemanticGraphAnalyzer` when Java semantic graph evidence is relevant
- `CodeRepository.replaceGraph`
- `code_graph_nodes`
- `code_graph_edges`

Important distinction: base indexing graph storage uses `replaceGraph`. `mergeGraphEdges` is for adding enrichment edges after an active graph exists.

3. Java Spring graph analysis

```text
LearnBot의 Java Spring 분석기는 Controller, Service, Repository, Transaction 관계를 어떻게 graph edge로 저장해?
```

Expected citation mix:

- `JavaSemanticGraphAnalyzer`
- `EXPOSES_ENDPOINT`
- `DECLARES_BEAN`
- `INJECTS`
- `TRANSACTION_BOUNDARY`
- `REPOSITORY_FOR`
- `QUERIES_ENTITY`

4. Graph expansion and evidence ranking

```text
Code GraphRAG에서 graph edge가 검색 확장과 evidence ranking에 어떻게 반영되는지 설명해줘
```

Expected citation mix:

- `CodeSearchService.graphEdgeTypes`
- `CodeSearchService.graphBoost`
- `CodeRepository.graphRelatedChunks`
- `CodeEvidenceRanker`

5. Direct citation vs inferred graph evidence

```text
GraphRAG로 확장된 근거와 직접 코드 근거는 답변에서 어떻게 구분돼?
```

Expected citation mix:

- `CodeRagOrchestrator` graph-expansion and evidence-assembly path
- `CodeContextAssembler`
- `graphEvidence=inferred`
- `graphEvidenceKind`
- `graphConfidence`
- `CodeRagDiagnosticsBuilder` diagnostics text that reports graph evidence

6. Auth API flow and transaction boundary

```text
로그인 API는 AuthController에서 AuthService와 SecurityRepository까지 어떤 흐름으로 동작하고 트랜잭션은 어디에 걸려 있어?
```

Expected citation mix:

- `AuthController`
- `AuthService`
- `SecurityRepository`
- `@Transactional`

7. Local Agent tool request/response flow

```text
Local Agent가 tool 요청을 가져오고 응답을 저장하는 API 흐름을 Controller, Service, Repository 기준으로 설명해줘
```

Expected citation mix:

- `LocalAgentController`
- `LocalAgentGatewayService`
- `LocalAgentToolGatewayService`
- `LocalAgentToolExecutionRepository`

8. Saved answer flow

```text
답변 저장 기능은 SavedAnswerController에서 Service와 Repository를 거쳐 어떻게 저장되고 삭제돼?
```

Expected citation mix:

- `SavedAnswerController`
- saved-answer service layer
- `SavedAnswerRepository`
- `@Transactional` when present on the service path

9. Admin settings flow

```text
관리자 설정 변경 API는 AdminController에서 어떤 Service와 Repository를 거쳐 저장돼?
```

Expected citation mix:

- `AdminController`
- `AdminSettingsService`
- `AppSettingsRepository`

10. Graph analysis failure fallback

```text
Java semantic graph 분석이 실패하거나 일부만 성공하면 Code RAG는 어떻게 fallback해서 답변을 생성해?
```

Expected citation mix:

- `CodeGraphBuilder` analyzer failure handling and diagnostics
- Java/Roslyn analyzer diagnostic result when relevant
- `CodeSearchService` keyword/vector fallback behavior
- `CodeRagOrchestrator` fallback path
- `CodeAnswerVerifier`, `CodeEvidenceFidelityFallback`, and `CodeRagDiagnosticsBuilder`

When evaluating these answers, direct source-code evidence and inferred graph evidence should be described separately. If the retrieved context only proves an orchestrator method, the answer should not claim it proves every internal phase unless direct evidence for those phases is also cited.

### Code RAG live E2E quality benchmark

The live quality benchmark sends fixture questions through the real `/api/code/ask` endpoint, active repository index, configured model provider, retrieval loop, evidence ranking, answer generation, and response evaluator. It is therefore a live E2E RAG quality evaluation, not only a deterministic API smoke test.

After changing Code RAG implementation packages, rebuild and restart the backend, then reindex and repin every benchmark repository before running Live E2E. The refactor moved implementation symbols from `CodeRagService.java` into `service/coderag/**`; an older active index can still return stale implementation chunks from the facade path and cannot validate the current orchestration, extractor, IR, answer, or diagnostics boundaries.

- `scripts/quality/rag-quality/code-live-fixtures.template.json` contains the question, repository placeholder, expected files/symbols, required claim groups, forbidden claims, answer mode, and latency limit.
- `scripts/quality/rag-quality/balanced-20.case-ids.txt` selects the current Java 10 and C# 10 development cohort without passing one long PowerShell argument.
- `balanced-10`, `balanced-20`, and `balanced-30` are development sets because their results can be used to improve production behavior. They are not holdouts.
- A case passes only when every configured strong gate passes. The resulting strict case pass rate is not yet a separate claim-evidence semantic-accuracy measurement.
- The current development sequence is `balanced-20`, then ten additional non-duplicate Korean complex questions for `balanced-30`, then a production freeze and sealed holdouts on previously unused Java and C# repositories.
- Do not add a fixture ID, repository, path, symbol, framework, question wording, or expected answer to production logic to improve a benchmark score.

Set the login and repository identity environment variables described by the fixture template, then run the fixed 20-case cohort from PowerShell:

```powershell
$arguments20 = @(
    "scripts/quality/rag-quality/evaluate-rag-quality-fixtures.mjs"
    "--fixtures"
    "scripts/quality/rag-quality/code-live-fixtures.template.json"
    "--live"
    "--server"
    "http://localhost:8083"
    "--case-ids-file"
    "scripts/quality/rag-quality/balanced-20.case-ids.txt"
    "--timeout-ms"
    "120000"
    "--case-delay-ms"
    "30000"
    "--report"
    ".tmp/quality/b20-report.json"
    "--live-fixtures-report"
    ".tmp/quality/b20-capture.json"
)

& node $arguments20
```

The current development gates are at least 16/20 overall, 8/10 Java, 8/10 C#, and at least 90 percent required-claim, expected-file, expected-symbol, and implementation-body coverage by language. A completed development run does not prove generalization; final completion also requires unused Java and C# repository holdouts after production code, prompts, retrieval/ranking policies, and evaluator criteria are frozen.

Index identity matters. As of the 2026-07-14 audit, `LocalLearnBot` uses `code-index-v3-ir1`, while `WPF-Samples` still uses `code-index-v2`. Reindex and repin the C# fixture identity before treating a C# run as current-IR validation. The target request contract is one initial retrieval round plus at most two additional rounds and no more than five successful LLM calls including answer generation; these limits must be enforced and measured before the sealed holdout run.

## Model Changes

Change models with environment variables:

```bash
PRIMARY_LLM_MODEL=ornith:9b
AUXILIARY_LLM_MODEL=qwen3:4b-instruct
EMBEDDING_MODEL=bge-m3
```

These values are the local Ollama defaults. An administrator can select a configured remote provider for live requests without changing the embedding index; quality reports should record the actual runtime provider and model rather than infer them from these defaults. Changing the chat model is a config change. Vector search still works without the chat LLM as long as the embedding model is available. If the embedding model is unavailable, search falls back to keyword search.

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

## RAG-only deployment

Agent execution and Local Agent installation have been retired. Existing database history and packages are retained offline; web login, Code/Document RAG and conversation history remain available. See [transition and regression verification](docs/rag-only-transition.md).

For a LAN server, run `scripts/up.ps1 -Build -Lan`; use `-ServerLanIp` and `-Port` to select the server address when needed. The address must belong to the server and is never hardcoded in source. GPU selection is the same as the normal startup command. No agent package or signing certificate is needed.

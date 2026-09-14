# RAG-only transition

## Scope

LearnBot retains web authentication, spaces, document crawling/upload/indexing, code repository registration/indexing, search, references, evidence-grounded answers, streaming, and saved conversations. Code Intelligence IR, extraction, retrieval loops and answer verification remain RAG functionality.

Removed: Agent plans and execution, change assistance and patch preview/application, local device enrollment, CLI device authentication, tool polling/WebSocket gateways, installer publishing and Agent-specific UI, tests and scripts. Shared RAG algorithms and scoring expectations are not tuned as part of this removal.

## Preserved history

Historical Flyway migrations and existing database rows are retained unchanged. Existing Agent metadata in conversation history is ignored by the UI; the answer and evidence remain readable. Previously produced packages under `artifacts/local-agent` are retained offline, but nginx no longer serves `/downloads/local-agent/`. External installations are not uninstalled by this source change. Database/package destruction is a separate explicit operation.

Benchmark fixtures can still ask about Agent code in the frozen historical evaluation repository. They describe test data, not enabled product features. Do not delete or relax those expectations to make removal pass.

## Startup

From the repository root in PowerShell:

```powershell
.\scripts\up.ps1 -Build
```

For LAN HTTP deployment:

```powershell
.\scripts\up.ps1 -Build -Lan
```

The initializer selects a local private IPv4 address. If multiple adapters require selection, supply `-ServerLanIp` with an actual address assigned to that server. `-Port` defaults to 8083. Generated `.env.lan-http` and nginx policy are machine-specific and ignored by Git. No signed Agent package is required. `-Cpu` explicitly selects CPU; normal startup attempts GPU when NVIDIA is available and reports any CPU fallback.

## Regression verification

1. Before replacing the running backend, freeze the pre-removal Java source into a separate ZIP repository. Wait for successful indexing and an active index. Do not reindex the mutable working tree for the before/after comparison.
2. Run balanced-20 against the old backend with a fixed local primary/auxiliary model, model digests and inference settings. Pin repository identity using the evaluator's environment variables. ZIP identity uses `LEARNBOT_JAVA_EXPECTED_SOURCE_TYPE=ZIP`; keep C# unchanged.
3. Run clean backend tests, frontend build, UI/route tests and the regression harness. Compare failures to the preserved pre-removal test reports; do not count historical failures as new regressions or ignore new failures.
4. Rebuild/restart the GPU backend and nginx. Check login, Code/Document RAG, references, SSE, conversation history and indexing. Check that retired routes are unavailable. Verify existing DB compatibility and a fresh database separately without deleting production data.
5. Run balanced-20 again against the exact same frozen repositories and model settings. Keep both report/capture files and identity sidecars.

The report comparator defaults to the existing absolute quality gate. For before/after comparison:

```powershell
node scripts/quality/rag-quality/compare-rag-quality-reports.mjs `
  --baseline .tmp/quality/rag-only/before-report.json `
  --current .tmp/quality/rag-only/after-report.json `
  --mode non-regression `
  --baseline-identity .tmp/quality/rag-only/before-identity.json `
  --current-identity .tmp/quality/rag-only/after-identity.json
```

Identity sidecars must contain `fixtureHash`, `model`, `modelDigest`, `inferenceSettings`, and `repositories` (including pinned source/active-index identity). The comparison rejects identity mismatch, missing/duplicate/skipped cases, pass-to-fail changes, newly failed gates and decreases in grounding metrics; P95 latency may increase by at most 20%. A changed model is a new baseline, not a direct comparison to historical Gemini scores. Unit tests alone do not establish live answer-quality non-regression.

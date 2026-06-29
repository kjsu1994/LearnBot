# LearnBot Ultimate Goal Execution Plan

This file is the working roadmap for reaching LearnBot's long-term product goal. Each future Goal run should read this file together with `agent.md` and the `LearnBot Service Final Architecture Plan` section in `README.md` before changing code.

## Product Goal

LearnBot should become a web-first local RAG agent service for small internal teams.

- Users normally interact through the LearnBot website for chat, review, approvals, evidence, and results.
- The central LearnBot server owns retrieval, ranking, model calls, planning, validation, approval, conversation history, diagnostics, and orchestration.
- User-owned file changes, tests, builds, git operations, and rollback restoration should be executed by each user's Local Agent on that user's PC.
- Server-local apply/test/rollback is allowed only as a prototype, shared sandbox, migration bridge, or admin/debug capability. It must not become the default product path.
- The final developer experience should include a simple PowerShell-friendly `learnbot` CLI, similar in spirit to running `codex`.
- Proven open-source components should be used where they improve quality or reduce risk, but LearnBot's product direction must not be bent to fit a library.

Current pointer: continue Milestone 5 by reviewing the disabled Local Agent mutation completion summary display in the Code workspace, `mutationCompletionSummary`, completion summary assembly in the Local Agent mutation gate chain, and the surrounding handoff summary display. `mutationResultIntakeBoundary`, `mutationResultCompletionBoundary`, `mutationResultIntakePersistenceGate`, `mutationRollbackFallbackGate`, `mutationRagFreshnessGate`, `mutationResultAggregationGate`, `mutationPublicationGate`, `mutationFinalAnswerGenerationGate`, `mutationFinalAnswerCompletionGate`, `mutationFinalAnswerPersistenceGate`, `mutationFinalAnswerConversationSaveGate`, `mutationFinalAnswerUserVisibleCompletionGate`, `mutationFinalResponseHandoffGate`, `mutationFinalAnswerDeliveryGate`, and `mutationFinalAnswerDeliveryReceiptGate` are now covered by lightweight frontend helper/render regressions while backend side-effect paths remain closed. The next safe slice should add a browser-level read-only UI smoke if a local app run is already available, or continue extracting/helper-testing the next large inline disabled display such as `mutationCompletionSummary`, while keeping every side-effect path closed. Do not enable release, claim, request creation, push, running transition, tool runner, write helper, apply, test, rollback restore, completed-result transition, result persistence, observation capture, result intake, rollback fallback, RAG freshness update, result aggregation, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, final response handoff, delivery handoff, final-answer delivery, delivery receipt, or mutation yet.

## Per-Run Rules

At the start of every work session:

- Read `agent.md`, `README.md`, and this `todo.md`.
- Review the relevant current code before making changes.
- Preserve answer quality, grounding, citation quality, safety, rollbackability, and fallbacks.
- Do not optimize speed by weakening answer quality or evidence quality.
- Keep changes scoped to the current milestone.
- Do not remove or rewrite unrelated user changes.
- Update this file at the end of the session with progress, verification, remaining risk, and the next recommended step.

Recommended Goal prompt:

```text
Read agent.md, README.md, and todo.md first.
Work toward LearnBot's ultimate goal using todo.md as the roadmap.
Start from the next incomplete milestone.
Review the relevant current code before changing anything.
Do not attempt to complete the entire ultimate product in one run.
Complete only the safest coherent slice for this run.
Preserve answer quality, fallbacks, rollbackability, and regression safety.
After implementation, run the relevant tests/builds.
Then update todo.md with completed work, verification, remaining risk, and the next recommended step.
```

Do not interpret this roadmap as permission to build everything at once. Each run should finish a small, verifiable slice. If a milestone is too large, split it into an explicit sub-step, implement that sub-step, verify it, and record the remaining work.

Before implementing a milestone, make its design concrete from the current code. If the milestone needs details that are not yet specified here, first inspect the code and then choose the smallest design that satisfies the Product Goal. Do not invent broad abstractions unless they are needed for the current milestone or clearly protect the Local Agent/server boundary.

Use this progress format after each run:

```text
## Progress Log

### YYYY-MM-DD - Run N

- Completed:
- Verified:
- Remaining risk:
- Next recommended step:
```

Keep `todo.md` compact. It should remain a roadmap plus the latest working state, not an unlimited history file.

- Keep only the latest 3-5 run entries in `todo.md`.
- Move older detailed logs to `docs/progress/YYYY-MM.md` when needed.
- Keep old progress summarized in one compact bullet if it is still useful.
- Do not paste full test logs, stack traces, diffs, or long reasoning transcripts into `todo.md`.

## Milestone 1 - Stabilize Current Patch Agent Boundary

Intent: make the current server-local patch implementation safe as a transitional capability while preventing it from becoming the default product path.

Key work:

- Review existing Code Agent/Patch Agent APIs, frontend controls, migration state, and tests.
- Keep plan, patch proposal, diff validation, approval/session concepts, and UI diff review.
- Hide, feature-flag, or clearly label server-local apply/test/rollback as prototype/admin/debug only.
- Ensure normal users are guided toward Local Agent execution for user-owned repository changes once that path exists.
- Preserve existing RAG/code search behavior.

Acceptance criteria:

- The UI and backend no longer imply that central-server file mutation is the intended final path.
- Existing code RAG question answering and patch proposal still work.
- Server-local apply/test/rollback is gated or clearly non-default.

## Milestone 2 - Define Local Agent Protocol

Intent: create a decision-complete contract between the central server and user Local Agents.

Key work:

- Introduce execution target concepts: `SERVER_LOCAL` and `USER_LOCAL_AGENT`.
- Define tool request/response envelopes with session id, user id, agent id, workspace id, tool name, input, output, status, timestamps, warnings, and error fields.
- Define initial tools:
  - `agent.status`
  - `agent.doctor`
  - `workspace.list`
  - `workspace.add`
  - `file.read`
  - `patch.apply`
  - `git.status`
  - `git.diff`
  - `command.runAllowed`
  - `rollback.restore`
- Require approval metadata for side-effectful tools.
- Ban arbitrary shell execution. Only typed tools and allowlisted commands are allowed.
- Define failure states for disconnected agent, unapproved workspace, path escape, timeout, context mismatch, test failure, and rollback refusal.

Acceptance criteria:

- Backend and Local Agent can be implemented independently from the protocol.
- Tool execution location is explicit.
- Approval and audit state are represented in the model.

## Milestone 3 - Build Central Agent Gateway

Intent: let the central server coordinate Local Agents without directly reaching user PCs.

Key work:

- Add outbound Local Agent connection support, preferably WebSocket from agent to server.
- Track connected agents per user, capabilities, version, heartbeat, and approved workspaces.
- Route tool requests to the correct connected Local Agent.
- Record tool requests, responses, approval state, and errors in session history.
- Show Local Agent connection/workspace status in the web UI.
- If no Local Agent is connected, block side-effectful actions and show clear guidance.

Acceptance criteria:

- The server can tell whether a user's Local Agent is connected.
- The web UI can show connected/disconnected and workspace-ready/not-ready states.
- Side-effectful requests do not silently fall back to server-local execution.

## Milestone 4 - Build Local Agent MVP and CLI

Intent: give each developer a lightweight local executable that manages workspace-safe tool execution.

Recommended implementation direction:

- Prefer Go or .NET for a lightweight, deployment-friendly Windows executable.
- Initial distribution: PowerShell installer downloads the executable, creates config, pairs with LearnBot, registers workspace roots, and starts the agent.
- Practical internal use: Windows Service or equivalent background process with logs, restart behavior, config, and update support.
- Mature distribution: signed MSI/EXE installer with uninstall, auto-update, proxy/internal-network support, and optional GUI settings.

Minimum CLI commands:

- `learnbot pair`
- `learnbot agent start`
- `learnbot agent stop`
- `learnbot agent status`
- `learnbot workspace add .`
- `learnbot workspace list`
- `learnbot doctor`
- `learnbot open`

Safety requirements:

- Access only user-approved workspace roots.
- Reject path traversal and workspace escape.
- Snapshot before file writes.
- Apply patches only after approval.
- Run only allowlisted test/build commands.
- Return structured observations to the server.

Acceptance criteria:

- A user can run `learnbot` commands from PowerShell to pair, start the agent, register a workspace, and confirm connection from the web UI.
- The Local Agent can execute safe read-only tools and reject unsafe requests.

## Milestone 5 - Move File Mutation to Local Agent

Intent: make user-owned file changes happen on the user's PC, not in the central server's Docker volume.

Target flow:

```text
User request in web UI
-> central RAG retrieval and planning
-> diff proposal and validation
-> user approval
-> tool request to USER_LOCAL_AGENT
-> Local Agent patch apply with snapshot
-> Local Agent allowlisted test/build
-> observations returned to server
-> final answer with changed files, test result, evidence, and residual risk
```

Key work:

- Reuse existing patch proposal and validation where possible.
- Replace default apply/test/rollback calls with Local Agent tool requests.
- Keep server-local apply/test/rollback only as non-default prototype/admin/debug path.
- Add RAG freshness handling after local file changes: partial reindex, freshness marker, or explicit stale-index warning.

Acceptance criteria:

- Approved patches modify only the user's registered local workspace.
- Server-local clone or Docker volume is not used for normal user file changes.
- Context mismatch, failed tests, disconnected agent, and rollback refusal are visible in the UI.

## Milestone 6 - Implement Agent Loop

Intent: move from one-shot patch generation to a Codex-like loop of planning, tool use, observation, and completion judgment.

Key work:

- Add a bounded loop: plan, select tool, request approval if needed, execute, observe, decide next step.
- Set maximum step count, timeout, cancellation, and user approval checkpoints.
- Preserve citations and code evidence in final answers.
- If evidence is weak or the model is uncertain, ask for clarification instead of making risky changes.
- Persist session timeline with model decisions, tool calls, observations, approvals, and final result.

Acceptance criteria:

- A request such as "fix this bug" can progress through analysis, patch proposal, apply, test, and final report.
- The system can stop safely on ambiguity, missing agent, failed tool, or approval denial.

## Milestone 7 - Quality and Regression Harness

Intent: make answer quality and agent safety measurable before and after changes.

Key work:

- Build regression cases for document RAG, code RAG, streaming, patch proposal, Local Agent tool use, and rollback.
- Track citation correctness, evidence relevance, hallucination risk, follow-up quality, patch validity, rollbackability, first-token latency, and total latency.
- Keep fallback behavior explicit for model failure, embedding failure, streaming failure, crawler failure, graph enrichment failure, and Local Agent disconnection.
- Ensure speed improvements come from reducing duplicate work, prompt waste, and unnecessary output, not from removing grounding.

Acceptance criteria:

- Important RAG and agent workflows can be compared across runs.
- Regressions in answer quality or safety are visible before release.

## Milestone 8 - Improve Web Crawling and Indexing Quality

Intent: make URL ingestion reliable, transparent, and useful for RAG answers.

Key work:

- Review crawler, extractor, indexing diagnostics, status transitions, and frontend visibility.
- Improve page extraction quality and stored metadata so users can understand what was indexed.
- Show why pages were skipped: allowlist, robots.txt, weak extraction, duplicate, depth/page limit, fetch failure, or unsupported content.
- Preserve per-page citation URLs.
- Improve recursive crawl controls: same-host/path policy, max depth, max pages, canonical URL, duplicate detection, and graceful partial success.
- Consider proven open-source extraction/parsing libraries behind a clear internal interface.

Acceptance criteria:

- Users can see which URLs were indexed, skipped, or partially processed and why.
- Searchable content remains available even when enrichment fails.
- Crawled evidence shown in answers is cleaner and more trustworthy.

## Verification Commands

Use the local Maven installation under `.tools`.

From the repository root:

```powershell
.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml test
```

From `backend`:

```powershell
..\.tools\apache-maven-3.9.9\bin\mvn.cmd test
```

Frontend:

```powershell
cd frontend
npm run build
```

Local runtime smoke checks should include:

- `.\scripts\up.ps1 -Build`
- Web UI loads at `http://localhost:8083`
- Backend routes do not return unexpected 404 for enabled features
- Local Agent disconnected state is visible and safe
- Local Agent connected state can execute only approved tools

## Progress Log

Older detailed entries are archived in `docs/progress/2026-06.md`.

### 2026-06-29 - Run 198

- Completed: Extracted the existing Code workspace `mutationFinalAnswerDeliveryReceiptGate` display into `mutationFinalAnswerDeliveryReceiptGate.js` so the refused delivery-receipt audit text is assembled outside the large `CodeWorkspace.jsx` component. Added `mutationFinalAnswerDeliveryReceiptGate.test.mjs` covering the header, ids, result counts, disabled controls, policy lines, blocking keys, hidden/null path, and explicit refusal message. `CodeWorkspace.jsx` now renders the gate through the helper without adding release, request creation, push, claim, execution, write-helper, apply, test, rollback restore, RAG freshness update, result aggregation execution, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, final-response handoff, delivery handoff, final-answer delivery, delivery receipt, server-local mutation, result intake execution, or file mutation controls. README documents the helper/rendering.
- Verified: `node src/components/code/mutationFinalAnswerDeliveryReceiptGate.test.mjs`, `node src/components/code/mutationFinalAnswerDeliveryGate.test.mjs`, `node src/components/code/mutationFinalResponseHandoffGate.test.mjs`, `node src/components/code/mutationFinalAnswerUserVisibleCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerConversationSaveGate.test.mjs`, `node src/components/code/mutationFinalAnswerPersistenceGate.test.mjs`, `node src/components/code/mutationFinalAnswerCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerGenerationGate.test.mjs`, `node src/components/code/mutationPublicationGate.test.mjs`, `node src/components/code/mutationResultAggregationGate.test.mjs`, `node src/components/code/mutationRagFreshnessGate.test.mjs`, `node src/components/code/mutationRollbackFallbackGate.test.mjs`, `node src/components/code/mutationResultIntakePersistenceGate.test.mjs`, `node src/components/code/mutationResultIntakeBoundary.test.mjs`, `node src/components/code/mutationResultCompletionBoundary.test.mjs`, `node src/components/code/mutationToolRunnerBoundary.test.mjs`, `node src/components/code/mutationExecutionReadinessBoundary.test.mjs`, `node src/components/code/mutationHandoffSummary.test.mjs`, and `node src/components/code/releaseAttemptDisplaySummary.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: Milestone 5 still does not execute real Local Agent mutations. The delivery-receipt gate is helper-tested at the display layer, but there is still no browser-level DOM smoke for the full Code workspace panel and no actual delivery receipt, acknowledgement save, final-answer delivery, or mutation execution path is enabled.
- Next recommended step: Add a browser-level read-only smoke for the Code workspace readiness panel if a local app run is available, or continue extracting/helper-testing the next large inline disabled display such as `mutationCompletionSummary` while preserving all mutation side-effect paths disabled.

### 2026-06-29 - Run 197

- Completed: Extracted the existing Code workspace `mutationFinalAnswerDeliveryGate` display into `mutationFinalAnswerDeliveryGate.js` so the refused final-answer-delivery audit text is assembled outside the large `CodeWorkspace.jsx` component. Added `mutationFinalAnswerDeliveryGate.test.mjs` covering the header, ids, result counts, disabled controls, policy lines, blocking keys, hidden/null path, and explicit refusal message. `CodeWorkspace.jsx` now renders the gate through the helper without adding release, request creation, push, claim, execution, write-helper, apply, test, rollback restore, RAG freshness update, result aggregation execution, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, final-response handoff, delivery handoff, final-answer delivery, server-local mutation, result intake execution, or file mutation controls. README documents the helper/rendering.
- Verified: `node src/components/code/mutationFinalAnswerDeliveryGate.test.mjs`, `node src/components/code/mutationFinalResponseHandoffGate.test.mjs`, `node src/components/code/mutationFinalAnswerUserVisibleCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerConversationSaveGate.test.mjs`, `node src/components/code/mutationFinalAnswerPersistenceGate.test.mjs`, `node src/components/code/mutationFinalAnswerCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerGenerationGate.test.mjs`, `node src/components/code/mutationPublicationGate.test.mjs`, `node src/components/code/mutationResultAggregationGate.test.mjs`, `node src/components/code/mutationRagFreshnessGate.test.mjs`, `node src/components/code/mutationRollbackFallbackGate.test.mjs`, `node src/components/code/mutationResultIntakePersistenceGate.test.mjs`, `node src/components/code/mutationResultIntakeBoundary.test.mjs`, `node src/components/code/mutationResultCompletionBoundary.test.mjs`, `node src/components/code/mutationToolRunnerBoundary.test.mjs`, `node src/components/code/mutationExecutionReadinessBoundary.test.mjs`, `node src/components/code/mutationHandoffSummary.test.mjs`, and `node src/components/code/releaseAttemptDisplaySummary.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: Milestone 5 still does not execute real Local Agent mutations. The final-answer-delivery gate is helper-tested at the display layer, but there is still no browser-level DOM smoke for the full Code workspace panel and no actual final-answer delivery, delivery handoff, delivery receipt, or mutation execution path is enabled.
- Next recommended step: Add a browser-level read-only smoke for the Code workspace readiness panel if a local app run is available, or continue extracting/helper-testing the next large inline disabled gate display such as `mutationFinalAnswerDeliveryReceiptGate` while preserving all mutation side-effect paths disabled.

### 2026-06-29 - Run 196

- Completed: Extracted the existing Code workspace `mutationFinalResponseHandoffGate` display into `mutationFinalResponseHandoffGate.js` so the refused final-response-handoff audit text is assembled outside the large `CodeWorkspace.jsx` component. Added `mutationFinalResponseHandoffGate.test.mjs` covering the header, ids, result counts, disabled controls, policy lines, blocking keys, hidden/null path, and explicit refusal message. `CodeWorkspace.jsx` now renders the gate through the helper without adding release, request creation, push, claim, execution, write-helper, apply, test, rollback restore, RAG freshness update, result aggregation execution, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, final-response handoff, delivery handoff, final-answer delivery, server-local mutation, result intake execution, or file mutation controls. README documents the helper/rendering.
- Verified: `node src/components/code/mutationFinalResponseHandoffGate.test.mjs`, `node src/components/code/mutationFinalAnswerUserVisibleCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerConversationSaveGate.test.mjs`, `node src/components/code/mutationFinalAnswerPersistenceGate.test.mjs`, `node src/components/code/mutationFinalAnswerCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerGenerationGate.test.mjs`, `node src/components/code/mutationPublicationGate.test.mjs`, `node src/components/code/mutationResultAggregationGate.test.mjs`, `node src/components/code/mutationRagFreshnessGate.test.mjs`, `node src/components/code/mutationRollbackFallbackGate.test.mjs`, `node src/components/code/mutationResultIntakePersistenceGate.test.mjs`, `node src/components/code/mutationResultIntakeBoundary.test.mjs`, `node src/components/code/mutationResultCompletionBoundary.test.mjs`, `node src/components/code/mutationToolRunnerBoundary.test.mjs`, `node src/components/code/mutationExecutionReadinessBoundary.test.mjs`, `node src/components/code/mutationHandoffSummary.test.mjs`, and `node src/components/code/releaseAttemptDisplaySummary.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: Milestone 5 still does not execute real Local Agent mutations. The final-response-handoff gate is helper-tested at the display layer, but there is still no browser-level DOM smoke for the full Code workspace panel and no actual final-response handoff, delivery handoff, final-answer delivery, or mutation execution path is enabled.
- Next recommended step: Add a browser-level read-only smoke for the Code workspace readiness panel if a local app run is available, or continue extracting/helper-testing the next large inline disabled gate display such as `mutationFinalAnswerDeliveryGate` while preserving all mutation side-effect paths disabled.

### 2026-06-29 - Run 195

- Completed: Extracted the existing Code workspace `mutationFinalAnswerUserVisibleCompletionGate` display into `mutationFinalAnswerUserVisibleCompletionGate.js` so the refused user-visible-completion audit text is assembled outside the large `CodeWorkspace.jsx` component. Added `mutationFinalAnswerUserVisibleCompletionGate.test.mjs` covering the header, ids, result counts, disabled controls, policy lines, blocking keys, hidden/null path, and explicit refusal message. `CodeWorkspace.jsx` now renders the gate through the helper without adding release, request creation, push, claim, execution, write-helper, apply, test, rollback restore, RAG freshness update, result aggregation execution, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, final-response handoff, server-local mutation, result intake execution, or file mutation controls. README documents the helper/rendering.
- Verified: `node src/components/code/mutationFinalAnswerUserVisibleCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerConversationSaveGate.test.mjs`, `node src/components/code/mutationFinalAnswerPersistenceGate.test.mjs`, `node src/components/code/mutationFinalAnswerCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerGenerationGate.test.mjs`, `node src/components/code/mutationPublicationGate.test.mjs`, `node src/components/code/mutationResultAggregationGate.test.mjs`, `node src/components/code/mutationRagFreshnessGate.test.mjs`, `node src/components/code/mutationRollbackFallbackGate.test.mjs`, `node src/components/code/mutationResultIntakePersistenceGate.test.mjs`, `node src/components/code/mutationResultIntakeBoundary.test.mjs`, `node src/components/code/mutationResultCompletionBoundary.test.mjs`, `node src/components/code/mutationToolRunnerBoundary.test.mjs`, `node src/components/code/mutationExecutionReadinessBoundary.test.mjs`, `node src/components/code/mutationHandoffSummary.test.mjs`, and `node src/components/code/releaseAttemptDisplaySummary.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: Milestone 5 still does not execute real Local Agent mutations. The user-visible-completion gate is helper-tested at the display layer, but there is still no browser-level DOM smoke for the full Code workspace panel and no actual user-visible completion, final-response handoff, delivery, or mutation execution path is enabled.
- Next recommended step: Add a browser-level read-only smoke for the Code workspace readiness panel if a local app run is available, or continue extracting/helper-testing the next large inline disabled gate display such as `mutationFinalResponseHandoffGate` while preserving all mutation side-effect paths disabled.

### 2026-06-29 - Run 194

- Completed: Extracted the existing Code workspace `mutationFinalAnswerConversationSaveGate` display into `mutationFinalAnswerConversationSaveGate.js` so the refused conversation-save audit text is assembled outside the large `CodeWorkspace.jsx` component. Added `mutationFinalAnswerConversationSaveGate.test.mjs` covering the header, ids, result counts, disabled controls, policy lines, blocking keys, hidden/null path, and explicit refusal message. `CodeWorkspace.jsx` now renders the gate through the helper without adding release, request creation, push, claim, execution, write-helper, apply, test, rollback restore, RAG freshness update, result aggregation execution, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, server-local mutation, result intake execution, or file mutation controls. README documents the helper/rendering.
- Verified: `node src/components/code/mutationFinalAnswerConversationSaveGate.test.mjs`, `node src/components/code/mutationFinalAnswerPersistenceGate.test.mjs`, `node src/components/code/mutationFinalAnswerCompletionGate.test.mjs`, `node src/components/code/mutationFinalAnswerGenerationGate.test.mjs`, `node src/components/code/mutationPublicationGate.test.mjs`, `node src/components/code/mutationResultAggregationGate.test.mjs`, `node src/components/code/mutationRagFreshnessGate.test.mjs`, `node src/components/code/mutationRollbackFallbackGate.test.mjs`, `node src/components/code/mutationResultIntakePersistenceGate.test.mjs`, `node src/components/code/mutationResultIntakeBoundary.test.mjs`, `node src/components/code/mutationResultCompletionBoundary.test.mjs`, `node src/components/code/mutationToolRunnerBoundary.test.mjs`, `node src/components/code/mutationExecutionReadinessBoundary.test.mjs`, `node src/components/code/mutationHandoffSummary.test.mjs`, and `node src/components/code/releaseAttemptDisplaySummary.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: Milestone 5 still does not execute real Local Agent mutations. The conversation-save gate is helper-tested at the display layer, but there is still no browser-level DOM smoke for the full Code workspace panel and no actual conversation save, user-visible completion, delivery, or mutation execution path is enabled.
- Next recommended step: Add a browser-level read-only smoke for the Code workspace readiness panel if a local app run is available, or continue extracting/helper-testing the next large inline disabled gate display such as `mutationFinalAnswerUserVisibleCompletionGate` while preserving all mutation side-effect paths disabled.

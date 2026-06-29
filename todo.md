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

Current pointer: continue Milestone 5 by adding a real browser-level read-only UI smoke for the Code workspace readiness panel when the in-app browser is available, or by reviewing the next narrow display-level gap in the disabled Local Agent mutation readiness chain. The route-level Code workspace readiness panel now has a Vite SSR smoke that renders `CodeWorkspace.jsx` with a disabled release-attempt fixture and verifies result-intake, mutation dispatch-envelope, mutation dispatch-preflight, mutation dispatch-decision, mutation request-blueprint, mutation request-creation, mutation request-push, mutation request-claim, mutation execution-gate, mutation write-helper safety, mutation post-execution observation, mutation observation acceptance, fresh observation request/evidence/completeness/enqueue audit surfaces, release-attempt final readiness, final mutation report contract, final mutation report finalization, final-answer publication boundary, release enablement checklist, mutation execution-readiness, mutation tool-runner, result-completion, result-intake-persistence, rollback-fallback, RAG freshness, result-aggregation, publication, final-answer generation, final-answer completion, final-answer persistence, final-answer conversation-save, final-answer user-visible completion, final-response handoff, final-answer delivery, delivery-receipt, mutation completion summary, and mutation handoff summary text appears in actual panel markup. The smoke and route prop harness now scan the disabled latest-attempt fixture and full route props recursively through `mutationDisabledFlagGuard.js`; its `assertNoForbiddenTrueFlags` helper fails with exact paths if release, request creation, push, claim, running/execution, tool runner, write helper, apply, test, rollback restore, RAG freshness, result aggregation, publication, final-answer, delivery, receipt, or mutation control flags become `true`, and the helper now combines an explicit denylist with Local Agent control prefix/suffix detection so newly added mutation-related `*Enabled`, `*Allowed`, `claimableAfter*`, or other claimable-style flags are harder to miss. The route render props are assembled by `codeWorkspaceReadinessSmokeHarness.mjs`, with a lightweight regression covering the held request, disabled patch execution gate, release-attempt model shape, and generated-props disabled flag guard. `mutationDisabledFlagGuard`, `mutationResultIntakeBoundary`, `mutationDispatchEnvelopeContract`, `mutationDispatchPreflightBoundary`, `mutationDispatchDecisionModel`, `mutationRequestBlueprint`, `mutationRequestCreationGate`, `mutationRequestPushGate`, `mutationRequestClaimGate`, `mutationExecutionGate`, `mutationWriteHelperSafetyGate`, `mutationPostExecutionObservationGate`, `mutationObservationAcceptanceGate`, `mutationExecutionReadinessBoundary`, `mutationToolRunnerBoundary`, `mutationResultCompletionBoundary`, `mutationResultIntakePersistenceGate`, `mutationRollbackFallbackGate`, `mutationRagFreshnessGate`, `mutationResultAggregationGate`, `mutationPublicationGate`, `mutationFinalAnswerGenerationGate`, `mutationFinalAnswerCompletionGate`, `mutationFinalAnswerPersistenceGate`, `mutationFinalAnswerConversationSaveGate`, `mutationFinalAnswerUserVisibleCompletionGate`, `mutationFinalResponseHandoffGate`, `mutationFinalAnswerDeliveryGate`, `mutationFinalAnswerDeliveryReceiptGate`, `mutationCompletionSummary`, and `mutationHandoffSummary` are covered by lightweight frontend helper/render regressions while backend side-effect paths remain closed. Do not enable release, claim, request creation, request blueprint, push, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result transition, result persistence, observation capture, observation acceptance, result intake, rollback fallback, RAG freshness update, result aggregation, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, final response handoff, delivery handoff, final-answer delivery, delivery receipt, dispatch, dispatch decision, dispatch preflight, or mutation yet.

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

### 2026-06-30 - Run 252

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed `CodeWorkspace.jsx`, `codeWorkspaceReadinessPanelSmoke.test.mjs`, `codeWorkspaceReadinessSmokeHarness.mjs`, and `mutationDisabledFlagGuard.js`. Expanded the route-level Code workspace SSR smoke fixture so `releaseAttemptModel.latestAttempt` now includes audit-only `freshObservationRequestPlan`, `freshObservationEvidenceStatus`, `freshObservationEvidenceCompleteness`, and `evidence.freshObservationEnqueueBoundary`, then asserted the rendered panel shows the disabled request plan, linked evidence status, completeness summary, blocking evidence, enqueue boundary, and planned request rows. The new fixture keeps request creation, push, enqueue, claim-after-enqueue, claimable, and mutation flags false, and the recursive disabled flag guard still scans the full latest attempt and route props. No browser-only behavior, release endpoint, request creation, push, claim, enqueue, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/mutationDisabledFlagGuard.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run pins the fresh-observation audit display but does not replace backend end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 251

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed `mutationDisabledFlagGuard.js`, `mutationDisabledFlagGuard.test.mjs`, `codeWorkspaceReadinessSmokeHarness.test.mjs`, and `codeWorkspaceReadinessPanelSmoke.test.mjs`. Tightened the disabled mutation flag guard for claimability transitions by treating future `claimableAfter*` keys as forbidden enabled controls even when they are not yet present in the explicit denylist. Added a focused regression for `claimableAfterDispatch: true` alongside the existing future mutation/final-answer/delivery control flag coverage. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/mutationDisabledFlagGuard.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run reduces the risk of missing newly named claimability-transition controls but does not replace end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 250

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed `mutationDisabledFlagGuard.js`, `mutationDisabledFlagGuard.test.mjs`, `codeWorkspaceReadinessSmokeHarness.test.mjs`, and `codeWorkspaceReadinessPanelSmoke.test.mjs`. Strengthened the disabled mutation flag guard so it still uses the explicit forbidden-key denylist but also catches future Local Agent mutation-control keys with known prefixes and `*Enabled`, `*Allowed`, `*InvocationEnabled`, transition/persistence/handoff, or claimable-style suffixes. Added a focused regression proving newly introduced mutation/final-answer/delivery control flags are caught while unrelated `harmlessEnabled` remains ignored. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/mutationDisabledFlagGuard.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run reduces the risk of missing newly named disabled control flags but does not replace end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 249

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed `mutationDisabledFlagGuard.js`, `mutationDisabledFlagGuard.test.mjs`, `codeWorkspaceReadinessSmokeHarness.test.mjs`, and `codeWorkspaceReadinessPanelSmoke.test.mjs`. Added `assertNoForbiddenTrueFlags` as a reusable guard assertion that throws with exact enabled mutation flag paths, then switched the route prop harness and route-level SSR smoke to use it for disabled latest-attempt and full-props safety checks. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/mutationDisabledFlagGuard.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run improves failure clarity and reuse of the disabled flag guard but does not replace end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 248

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed `codeWorkspaceReadinessSmokeHarness.mjs`, `codeWorkspaceReadinessSmokeHarness.test.mjs`, `mutationDisabledFlagGuard.js`, and `codeWorkspaceReadinessPanelSmoke.test.mjs`. Extended the route prop harness regression so generated Code workspace readiness props are also scanned by `collectForbiddenTrueFlags(props, 'props')`, catching accidental enabled release/claim/mutation flags before the route render smoke runs. The harness fixture now includes explicit disabled final-answer delivery and mutation flags to preserve the guard contract. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/mutationDisabledFlagGuard.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run strengthens generated-props safety coverage but does not replace end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 247

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed `mutationDisabledFlagGuard.js`, `mutationDisabledFlagGuard.test.mjs`, `codeWorkspaceReadinessSmokeHarness.mjs`, and `codeWorkspaceReadinessPanelSmoke.test.mjs`. Expanded the disabled-control safety regression so the route-level SSR smoke now scans the full rendered props object, not only `latestAttempt`, catching accidental `true` release/claim/mutation flags on top-level readiness surfaces such as `patchExecutionGate`. Added a focused guard test for custom root paths so violations report as `props...` when scanning route props. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/mutationDisabledFlagGuard.test.mjs`, `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, and `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run broadens the disabled safety regression but does not replace end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 246

- Completed: Reviewed the route-level `codeWorkspaceReadinessPanelSmoke.test.mjs` safety guard added in the previous run and split the disabled-control flag scanner into `mutationDisabledFlagGuard.js` with a focused `mutationDisabledFlagGuard.test.mjs`. The Code workspace SSR smoke now imports the helper instead of carrying the forbidden flag list inline, preserving the recursive guard that fails if release, request creation, push, claim, execution, tool runner, write helper, apply, test, rollback restore, RAG freshness update, result aggregation, publication, final-answer, delivery, receipt, or mutation controls accidentally become enabled. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/mutationDisabledFlagGuard.test.mjs`, `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, and `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run improves maintainability of the disabled safety regression but does not replace end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 245

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed the growing `codeWorkspaceReadinessPanelSmoke.test.mjs` disabled latest-attempt fixture and added a scoped route-level safety regression: the fixture is now stored as one `latestAttempt` object and recursively scanned so release, request creation, push, claim, claimable, running/execution, tool runner, write helper, apply, test, rollback restore, RAG freshness update, result aggregation, publication, final-answer, delivery, receipt, and mutation control flags cannot become `true` without failing the smoke. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/mutationExecutionGate.test.mjs`, and `node src/components/code/mutationObservationAcceptanceGate.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run adds a stronger safety regression around the disabled fixture but does not replace end-to-end Local Agent execution testing.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

### 2026-06-30 - Run 244

- Completed: Rechecked the in-app browser path through the browser plugin and confirmed the in-app browser target list is still empty, so browser-level smoke remains unavailable. Reviewed the final readiness/final report/release enablement render path in `CodeWorkspace.jsx` and the existing route-level `codeWorkspaceReadinessPanelSmoke.test.mjs`. Expanded the disabled latest-attempt fixture so the actual Code workspace SSR markup now verifies `releaseAttemptFinalReadiness`, `finalMutationReportContract`, `finalMutationReportFinalizationBoundary`, `finalAnswerPublicationBoundary`, and `releaseEnablementChecklist` text, including disabled release/claim/mutation flags, final report source outcomes/guardrails, finalization refusal, publication refusal, release checklist items, and blocking keys. No browser-only behavior, release endpoint, request creation, push, claim, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result persistence, observation capture, observation acceptance, RAG freshness update, result aggregation, publication, final-answer generation, or mutation path was enabled.
- Verified: `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/mutationCompletionSummary.test.mjs`, and `node src/components/code/mutationHandoffSummary.test.mjs` passed from `frontend`. `npm run build` from `frontend` passed with only existing Vite large-chunk warnings.
- Remaining risk: The route-level smoke still verifies SSR markup, not a live browser DOM, because the in-app browser surface remains unavailable. Milestone 5 still does not execute real Local Agent mutations or any side-effect path; this run only closes a display-level regression gap for existing disabled final-readiness/report/release-enablement surfaces.
- Next recommended step: Continue with a browser-level read-only smoke when the in-app browser becomes available; otherwise review the next narrow disabled display or structure gap in the Code workspace readiness panel while keeping all mutation paths closed.

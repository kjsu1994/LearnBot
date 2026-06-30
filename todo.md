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

Current pointer: continue Milestone 5 by adding a real browser-level read-only UI smoke for the Code workspace readiness panel when the in-app browser is available, or by reviewing the next narrow backend/frontend gap in the disabled Local Agent mutation readiness chain. The backend fresh-observation enqueue regression now verifies that queued release-attempt observation requests use new ids, the held source patch remains `APPROVED_HELD` with approval `APPROVED`, and the patch dry-run observation keeps `dryRunOnly=true`, `mutationAllowed=false`, `sourceRequestId`, `releaseAttemptId`, and `freshObservationOnly=true`. The MockMvc fresh-observations regression now routes `POST /api/local-agents/tools/{requestId}/fresh-observations` and verifies it returns only the two observation request ids, never the held source patch id, with wire-name tool names, `freshObservationOnly=true`, dry-run patch metadata, and no mutation-allowed repository observation payload. The backend result-completion readiness regression now also pins source session/user/agent/workspace ids, source tool-runner delivery-receipt context, source post-execution observation context, acknowledgement-save disabled flags, and the explicit refusal message on the disabled `mutationResultCompletionBoundary`, so future completed-result envelopes remain tied to the correct Local Agent context while completed-result transition, persistence, observation capture, result intake, acknowledgement save, and mutation stay disabled. The backend result-intake-persistence regression now pins source session/user/agent/workspace ids, source observation-acceptance gate schema/status, final-answer/final-response/delivery/receipt/acknowledgement disabled flags, and refusal messaging on the disabled `mutationResultIntakePersistenceGate`, so accepted-observation persistence, rollback fallback, RAG freshness update, aggregation, publication, final-answer generation, delivery, receipt, acknowledgement save, and mutation remain closed. The backend rollback-fallback and RAG freshness readiness regressions now pin source session/user/agent/workspace ids, upstream gate schema/status, acknowledgement-save disabled flags, and refusal messaging on the disabled `mutationRollbackFallbackGate` and `mutationRagFreshnessGate`, so rollback fallback execution, RAG freshness update, aggregation, publication, final-answer generation, acknowledgement save, and mutation remain closed. The backend result-aggregation and publication readiness regressions now pin source session/user/agent/workspace ids, upstream gate schema/status, acknowledgement-save disabled flags, and refusal messaging on the disabled `mutationResultAggregationGate` and `mutationPublicationGate`, so aggregation, publication, final-answer generation, acknowledgement save, and mutation remain closed. The backend final-answer generation and completion readiness regressions now pin source session/user/agent/workspace ids, upstream gate schema/status, acknowledgement-save disabled flags, and refusal messaging on the disabled `mutationFinalAnswerGenerationGate` and `mutationFinalAnswerCompletionGate`, so final-answer generation, completion, delivery, acknowledgement save, and mutation remain closed. The backend final-answer persistence and conversation-save readiness regressions now pin source session/user/agent/workspace ids, upstream gate schema/status, acknowledgement-save disabled flags, and refusal messaging on the disabled `mutationFinalAnswerPersistenceGate` and `mutationFinalAnswerConversationSaveGate`, so final-answer persistence, conversation-turn save, user-visible completion, delivery, acknowledgement save, and mutation remain closed. The backend final-answer user-visible completion and final-response handoff readiness regressions now pin source session/user/agent/workspace ids, upstream gate schema/status, acknowledgement-save disabled flags, and refusal messaging on the disabled `mutationFinalAnswerUserVisibleCompletionGate` and `mutationFinalResponseHandoffGate`, so user-visible completion, final-response handoff, delivery handoff, final-answer delivery, acknowledgement save, and mutation remain closed. The backend final-answer delivery and delivery-receipt readiness regressions now pin source session/user/agent/workspace ids, upstream gate schema/status, acknowledgement-save disabled flags, and refusal messaging on the disabled `mutationFinalAnswerDeliveryGate` and `mutationFinalAnswerDeliveryReceiptGate`, so final-answer delivery, delivery handoff, delivery receipt recording, acknowledgement save, and mutation remain closed. The backend mutation completion and handoff summary regressions now pin source session/user/agent/workspace ids, source completion-summary context, source delivery-receipt context, aggregate acknowledgement-save disabled flags, and refusal messaging on `mutationCompletionSummary` and `mutationHandoffSummary`, so completion handoff, final response, delivery receipt, acknowledgement save, and mutation remain closed. The backend execution-readiness boundary regression now pins source session/user/agent/workspace ids, source handoff-summary context, source delivery-receipt context, source execution-gate context, source write-helper safety context, disabled runtime/tool-runner/write-helper/result-intake/acknowledgement-save flags, and refusal messaging on `mutationExecutionReadinessBoundary`, so runtime execution, result intake, final-response handoff, delivery receipt, acknowledgement save, and mutation remain closed. The backend tool-runner boundary regression now pins source session/user/agent/workspace ids, source execution-readiness context, source delivery-receipt context, source execution-gate context, expected/running/completed request counts, disabled running/result-intake/tool-runner/acknowledgement-save flags, and refusal messaging on `mutationToolRunnerBoundary`, so runner invocation, running transition, result completion, result intake, acknowledgement save, and mutation remain closed. The route-level Code workspace readiness panel has a Vite SSR smoke that renders `CodeWorkspace.jsx` with a disabled release-attempt fixture and verifies result-intake, mutation dispatch-envelope, mutation dispatch-preflight, mutation dispatch-decision, mutation request-blueprint, mutation request-creation, mutation request-push, mutation request-claim, mutation execution-gate, mutation write-helper safety, mutation post-execution observation, mutation observation acceptance, fresh observation request/evidence/completeness/enqueue audit surfaces, disabled release-fresh-observation queue control, release-attempt final readiness, final mutation report contract, final mutation report finalization, final-answer publication boundary, release enablement checklist, mutation execution-readiness, mutation tool-runner, result-completion, result-intake-persistence, rollback-fallback, RAG freshness, result-aggregation, publication, final-answer generation, final-answer completion, final-answer persistence, final-answer conversation-save, final-answer user-visible completion, final-response handoff, final-answer delivery, delivery-receipt, mutation completion summary, and mutation handoff summary text appears in actual panel markup. The disabled release-fresh-observation queue assertion is scoped to the same rendered button element, so a different disabled control cannot satisfy the check accidentally. The smoke fixture now also renders the completion-summary final-response handoff item and the handoff-summary finalResponse stage so acknowledgement-save disabled text is pinned in the actual route markup, not only helper-level tests. The smoke and route prop harness scan the disabled latest-attempt fixture and full route props recursively through `mutationDisabledFlagGuard.js`; its `assertNoForbiddenTrueFlags` helper fails with exact paths if release, request creation, push, claim, running/execution, tool runner, write helper, apply, test, rollback restore, RAG freshness, result aggregation, publication, final-answer, delivery, receipt, acknowledgement save, or mutation control flags become `true`, and the helper now combines an explicit denylist with Local Agent control prefix/suffix detection so newly added mutation-related `*Enabled`, `*Allowed`, `claimableAfter*`, or other claimable-style flags are harder to miss. The route render props are assembled by `codeWorkspaceReadinessSmokeHarness.mjs`, with a lightweight regression covering the held request, disabled patch execution gate, release-attempt model shape, fresh-observation request/evidence/completeness/enqueue audit fields, and generated-props disabled flag guard. `mutationDisabledFlagGuard`, `mutationResultIntakeBoundary`, `mutationDispatchEnvelopeContract`, `mutationDispatchPreflightBoundary`, `mutationDispatchDecisionModel`, `mutationRequestBlueprint`, `mutationRequestCreationGate`, `mutationRequestPushGate`, `mutationRequestClaimGate`, `mutationExecutionGate`, `mutationWriteHelperSafetyGate`, `mutationPostExecutionObservationGate`, `mutationObservationAcceptanceGate`, `mutationExecutionReadinessBoundary`, `mutationToolRunnerBoundary`, `mutationResultCompletionBoundary`, `mutationResultIntakePersistenceGate`, `mutationRollbackFallbackGate`, `mutationRagFreshnessGate`, `mutationResultAggregationGate`, `mutationPublicationGate`, `mutationFinalAnswerGenerationGate`, `mutationFinalAnswerCompletionGate`, `mutationFinalAnswerPersistenceGate`, `mutationFinalAnswerConversationSaveGate`, `mutationFinalAnswerUserVisibleCompletionGate`, `mutationFinalResponseHandoffGate`, `mutationFinalAnswerDeliveryGate`, `mutationFinalAnswerDeliveryReceiptGate`, `mutationCompletionSummary`, and `mutationHandoffSummary` are covered by lightweight frontend helper/render regressions while backend side-effect paths remain closed. Do not enable release, claim, request creation, request blueprint, push, running transition, execution, tool runner, write helper, apply, test, rollback restore, completed-result transition, result persistence, observation capture, observation acceptance, result intake, rollback fallback, RAG freshness update, result aggregation, publication, final-answer generation, final-answer completion, final-answer persistence, conversation save, user-visible completion, final response handoff, delivery handoff, final-answer delivery, delivery receipt, acknowledgement save, dispatch, dispatch decision, dispatch preflight, or mutation yet. Next narrow review should use the real browser-level read-only UI smoke when available, or continue with the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

Current pointer addendum after Run 325: the in-app browser target list was still empty, so the real browser-level read-only UI smoke remains unavailable in this session. As the fallback slice, `dryRunPatchFilesSummary.js` now owns Local Agent dry-run patch file row display text for context-ok/context-blocked guidance, and `dryRunPatchFilesSummary.test.mjs` pins mixed rows, missing-context fallback, and empty rendering. Continue with the real browser-level read-only UI smoke when the in-app browser is available, or review the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

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

Latest Milestone 5 pointer update: backend readiness, release-attempt summaries, frontend route-level display, the display-summary helper, the release-attempt model summary helper, the patch-execution-gate summary helper, the patch-release-readiness summary helper, the repository-verification summary helper, the workspace-verification summary helper, the snapshot-readiness summary helper, the rollback-readiness summary helper, the generic readiness-check summary helper, the dry-run result summary helper, the dry-run patch-files summary helper, the dry-run snapshot-observation summary helper, the dry-run rollback-observation summary helper, the fresh-observation request-plan/enqueue-boundary/evidence-summary helpers, and the shared Code workspace SSR harness are now aligned with the Local Agent `patch-dry-run-contract` and disabled release-attempt audit model. `snapshotReadiness` and snapshot manifest validation require non-mutating dry-run evidence with `mutationApplied=false`; invalid `snapshotCreated=true` plus `mutationApplied=true` evidence is pinned in backend readiness, release-attempt final readiness/checklist/display summary, route markup, route prop harness regression, and display-summary helper regression so it cannot be mistaken for release evidence. The route-level smoke and helper tests now render release blocked when linked evidence is complete but patch preconditions fail, render the fresh-observation request plan and enqueue boundary as audit-only with request creation, push, enqueue, claim, and mutation false, render source-only fallback evidence as blocking/non-claimable, pin that pre-release revalidation requires fresh dry-run and repository verification before any future release, keep the internal patch execution gate display text helper-tested as blocked/non-claimable, keep the pre-apply release readiness checklist helper-tested as non-mutating and release-disabled, keep recorded repository verification linkage/check rows helper-tested as read-only evidence, keep effective workspace verification helper-tested as blocking/non-blocking read-only evidence, keep snapshot readiness helper-tested as non-mutating dry-run rollback safety evidence, keep rollback manifest readiness helper-tested as display-only blocking evidence, keep generic readiness check filtering helper-tested so dedicated snapshot/rollback checks are not double-rendered, and keep dry-run result, patch file, snapshot observation, and rollback observation text helper-tested as display-only evidence. The next narrow review should use the real browser-level read-only UI smoke when available, or continue with the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

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

### 2026-06-30 - Run 325

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then retried the recommended real browser-level read-only Code workspace smoke. The browser runtime still had no in-app browser target, so the session used the fallback path. Reviewed the Local Agent dry-run patch file row rendering in `CodeWorkspace.jsx`, the adjacent dry-run result/snapshot/rollback helpers, and the route-level SSR smoke coverage. Extracted dry-run patch file context row display text into `dryRunPatchFilesSummary.js` and added `dryRunPatchFilesSummary.test.mjs` to pin context-ok/context-blocked formatting, missing-context fallback, and empty rendering. `CodeWorkspace.jsx` now renders the same read-only dry-run patch file evidence through the helper. No browser-only behavior, release behavior, mutation request creation, mutation push, claimability transition, running transition, write helper, apply, test, snapshot creation, rollback restore, result intake, final answer, acknowledgement save, or mutation path was enabled.
- Verified: Browser plugin initialization succeeded but `agent.browsers.list()` returned `[]`, so real browser UI smoke could not run. `node src/components/code/dryRunPatchFilesSummary.test.mjs`, `node src/components/code/dryRunResultSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, and `npm run build` passed from `frontend`; the build reported only the existing Vite chunk-size warning. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest,LocalAgentControllerTest" test` passed with 31 tests, 0 failures, 0 errors, 0 skipped. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed with LF/CRLF warnings only.
- Remaining risk: This is still helper and SSR route coverage plus targeted backend/controller regression coverage, not a live browser interaction or real server-to-agent release/mutation flow. Milestone 5 still does not create, push, claim, run, write, apply, test, rollback restore, persist completed mutation results, ingest mutation observations, update RAG freshness, aggregate mutation results, publish or deliver final answers, record delivery receipts, save acknowledgements, or perform real user-owned file mutation.
- Next recommended step: Continue Milestone 5 by using the real browser-level read-only UI smoke when the in-app browser is available, or by reviewing the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

### 2026-06-30 - Run 324

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then retried the recommended real browser-level read-only Code workspace smoke. The browser runtime still had no in-app browser target, so the session used the fallback path. Reviewed the Local Agent dry-run result header/status rendering in `CodeWorkspace.jsx`, the adjacent dry-run snapshot/rollback helpers, and the route-level SSR smoke coverage. Extracted dry-run result header, error, failure/safety-gate text, linked release-attempt evidence, preflight, mutation-applied, and snapshot-created display text into `dryRunResultSummary.js`, and added `dryRunResultSummary.test.mjs` to pin expected-refusal, failure, minimal, and null fallback rendering. `CodeWorkspace.jsx` now renders the same read-only dry-run status evidence through the helper. No browser-only behavior, release behavior, mutation request creation, mutation push, claimability transition, running transition, write helper, apply, test, snapshot creation, rollback restore, result intake, final answer, acknowledgement save, or mutation path was enabled.
- Verified: Browser plugin initialization succeeded but `agent.browsers.list()` returned `[]`, so real browser UI smoke could not run. `node src/components/code/dryRunResultSummary.test.mjs`, `node src/components/code/dryRunSnapshotObservationSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, and `npm run build` passed from `frontend`; the build reported only the existing Vite chunk-size warning. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest,LocalAgentControllerTest" test` passed with 31 tests, 0 failures, 0 errors, 0 skipped. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed with LF/CRLF warnings only.
- Remaining risk: This is still helper and SSR route coverage plus targeted backend/controller regression coverage, not a live browser interaction or real server-to-agent release/mutation flow. Milestone 5 still does not create, push, claim, run, write, apply, test, rollback restore, persist completed mutation results, ingest mutation observations, update RAG freshness, aggregate mutation results, publish or deliver final answers, record delivery receipts, save acknowledgements, or perform real user-owned file mutation.
- Next recommended step: Continue Milestone 5 by using the real browser-level read-only UI smoke when the in-app browser is available, or by reviewing the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

### 2026-06-30 - Run 323

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then retried the recommended real browser-level read-only Code workspace smoke. The browser runtime still had no in-app browser target, so the session used the fallback path. Reviewed the Local Agent dry-run snapshot observation rendering in `CodeWorkspace.jsx`, the adjacent dry-run rollback helper, and the route-level SSR smoke coverage. Extracted dry-run snapshot observation display text into `dryRunSnapshotObservationSummary.js` and added `dryRunSnapshotObservationSummary.test.mjs` to pin would-create/created/scope/location output, manifest preview text, snapshot file hash/context rows, minimal rendering, empty-manifest fallback, and null fallback. `CodeWorkspace.jsx` now renders the same read-only dry-run snapshot evidence through the helper and no longer owns that file-summary formatter. No browser-only behavior, snapshot creation, rollback restore, release behavior, mutation request creation, mutation push, claimability transition, running transition, write helper, apply, test, result intake, final answer, acknowledgement save, or mutation path was enabled.
- Verified: Browser plugin initialization succeeded but `agent.browsers.list()` returned `[]`, so real browser UI smoke could not run. `node src/components/code/dryRunSnapshotObservationSummary.test.mjs`, `node src/components/code/dryRunRollbackObservationSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, and `npm run build` passed from `frontend`; the build reported only the existing Vite chunk-size warning. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest,LocalAgentControllerTest" test` passed with 31 tests, 0 failures, 0 errors, 0 skipped. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed with LF/CRLF warnings only.
- Remaining risk: This is still helper and SSR route coverage plus targeted backend/controller regression coverage, not a live browser interaction or real server-to-agent release/mutation flow. Milestone 5 still does not create, push, claim, run, write, apply, test, rollback restore, persist completed mutation results, ingest mutation observations, update RAG freshness, aggregate mutation results, publish or deliver final answers, record delivery receipts, save acknowledgements, or perform real user-owned file mutation.
- Next recommended step: Continue Milestone 5 by using the real browser-level read-only UI smoke when the in-app browser is available, or by reviewing the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

### 2026-06-30 - Run 322

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then retried the recommended real browser-level read-only Code workspace smoke. The browser runtime still had no in-app browser target, so the session used the fallback path. Reviewed the Local Agent dry-run rollback observation rendering in `CodeWorkspace.jsx`, the adjacent readiness summary helpers, and the route-level SSR smoke coverage. Extracted the dry-run rollback observation display text into `dryRunRollbackObservationSummary.js` and added `dryRunRollbackObservationSummary.test.mjs` to pin would-restore/restored/tool/restore-scope output plus minimal, empty-object, and null fallback rendering. `CodeWorkspace.jsx` now renders the same read-only dry-run rollback evidence through the helper. No browser-only behavior, rollback restore, release behavior, mutation request creation, mutation push, claimability transition, running transition, write helper, apply, test, result intake, final answer, acknowledgement save, or mutation path was enabled.
- Verified: Browser plugin initialization succeeded but `agent.browsers.list()` returned `[]`, so real browser UI smoke could not run. `node src/components/code/dryRunRollbackObservationSummary.test.mjs`, `node src/components/code/readinessChecksSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, and `npm run build` passed from `frontend`; the build reported only the existing Vite chunk-size warning. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest,LocalAgentControllerTest" test` passed with 31 tests, 0 failures, 0 errors, 0 skipped. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed with LF/CRLF warnings only.
- Remaining risk: This is still helper and SSR route coverage plus targeted backend/controller regression coverage, not a live browser interaction or real server-to-agent release/mutation flow. Milestone 5 still does not create, push, claim, run, write, apply, test, rollback restore, persist completed mutation results, ingest mutation observations, update RAG freshness, aggregate mutation results, publish or deliver final answers, record delivery receipts, save acknowledgements, or perform real user-owned file mutation.
- Next recommended step: Continue Milestone 5 by using the real browser-level read-only UI smoke when the in-app browser is available, or by reviewing the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

### 2026-06-30 - Run 321

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then retried the recommended real browser-level read-only Code workspace smoke. The browser runtime still had no in-app browser target, so the session used the fallback path. Reviewed the generic readiness check rendering in `CodeWorkspace.jsx`, the dedicated snapshot/rollback summary helpers, and the route-level SSR smoke coverage. Extracted the generic readiness check filtering into `readinessChecksSummary.js` and added `readinessChecksSummary.test.mjs` to pin pass/blocking rows, exclusion of `snapshotManifestPreview` and `rollbackRestorePreconditions` from the generic list, empty rendering, and null fallback. `CodeWorkspace.jsx` now consumes the helper rows while the dedicated snapshot/rollback summaries remain responsible for their own checks. No browser-only behavior, release behavior, mutation request creation, mutation push, claimability transition, running transition, write helper, apply, test, rollback restore, result intake, final answer, acknowledgement save, or mutation path was enabled.
- Verified: Browser plugin initialization succeeded but `agent.browsers.list()` returned `[]`, so real browser UI smoke could not run. `node src/components/code/readinessChecksSummary.test.mjs`, `node src/components/code/rollbackReadinessSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs`, and `npm run build` passed from `frontend`; the build reported only the existing Vite chunk-size warning. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest,LocalAgentControllerTest" test` passed with 31 tests, 0 failures, 0 errors, 0 skipped. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed with LF/CRLF warnings only.
- Remaining risk: This is still helper and SSR route coverage plus targeted backend/controller regression coverage, not a live browser interaction or real server-to-agent release/mutation flow. Milestone 5 still does not create, push, claim, run, write, apply, test, rollback restore, persist completed mutation results, ingest mutation observations, update RAG freshness, aggregate mutation results, publish or deliver final answers, record delivery receipts, save acknowledgements, or perform real user-owned file mutation.
- Next recommended step: Continue Milestone 5 by using the real browser-level read-only UI smoke when the in-app browser is available, or by reviewing the next backend/frontend gap in the disabled Local Agent mutation readiness chain.

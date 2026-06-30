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

Current pointer addendum after Run 340: Milestone 6 now persists audit-only stop-outcome policy records for weak evidence, missing Local Agent, failed tool observation, and approval denial after preview timeout/cancellation/final-result policy records. These outcomes render in the Code workspace timeline with `may_mutate=false`, and still create no Local Agent request push, claim, release, write-helper call, patch apply, test execution, rollback restore, RAG freshness update, final-answer publication, acknowledgement save, or mutation. Continue Milestone 6 by carrying the loop id into future completion/publication-readiness records, or by appending audit-only real stop outcome events when approval denial or failed observations occur, without enabling mutation release.

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

### 2026-06-30 - Run 340

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then completed the final requested slice before reboot by adding audit-only stop-outcome policy records for `WEAK_EVIDENCE`, `AGENT_UNAVAILABLE`, `TOOL_FAILED`, and `APPROVAL_DENIED`. `CodeAgentLoopTimelineRepository.createPreview(...)` now appends `STOP_OUTCOME_POLICY_REGISTERED` records after timeout/cancellation/final-result policy records, with non-executing outcomes such as ask for clarification, wait for Local Agent, report tool failure, and report approval denial. Updated the Code workspace timeline summary helper and smoke fixtures so the stop key/outcome render in read-only history, and updated `README.md` to document that these stop outcomes are not executable controls. No Local Agent request push, claim, release, write-helper call, patch apply, test execution, rollback restore, RAG freshness update, final-answer publication, acknowledgement save, or mutation path was enabled.
- Verified: `node src/components/code/agentLoopTimelineSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed. The first sandboxed Maven attempt failed resolving the Spring Boot parent POM because network/cache access was denied; rerunning `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,CodeAgentLocalPatchRequestServiceTest,LocalAgentToolGatewayServiceTest" test` with dependency-cache/network access passed with 44 tests. The expanded Maven run with `CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,CodeAgentLocalPatchRequestServiceTest,LocalAgentToolGatewayServiceTest,LocalAgentControllerTest,LocalAgentWebSocketHandlerTest,LocalAgentToolExecutionRepositoryLivePostgresTest` passed with 53 tests, 1 live PostgreSQL smoke skipped by default. `dotnet build local-agent\LearnBot.LocalAgent.csproj -c Release`, `npm run build`, and `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed; the frontend build kept the existing large-chunk warning and diff check reported only LF/CRLF warnings.
- Remaining risk: These are still preview-derived policy records. The system does not yet append real runtime stop outcome events when an approval is denied, a tool observation fails, the selected agent is unavailable, or evidence is weak during actual loop execution. Mutation remains intentionally disabled.
- Next recommended step: After reboot, continue Milestone 6 by carrying `loopId` into future completion/publication-readiness records, or by appending audit-only real stop outcome events from existing approval denial and failed-observation paths without enabling patch apply, test execution, rollback restore, RAG freshness update, final-answer publication, acknowledgement save, or mutation release.

### 2026-06-30 - Run 339

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then continued Milestone 6 by adding audit-only timeout, cancellation, and final-result policy records to persisted loop timelines. `CodeAgentLoopTimelineRepository.createPreview(...)` now appends `TIMEOUT_POLICY_REGISTERED`, `CANCELLATION_POLICY_REGISTERED`, and `FINAL_RESULT_POLICY_REGISTERED` after stop conditions, with details for timeout seconds, cancellation disabled state, final-result disabled state, publication disabled state, acknowledgement disabled state, and `mutationEnabled=false`. Updated the Code workspace timeline summary helper and smoke fixtures so these records render as read-only timeline lines with `may mutate false`. Updated `README.md` to document that these policy records are audit-only and not executable controls. No Local Agent request push, claim, release, write-helper call, patch apply, test execution, rollback restore, RAG freshness update, final-answer publication, acknowledgement save, or mutation path was enabled.
- Verified: `node src/components/code/agentLoopTimelineSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed. The first sandboxed Maven attempt failed resolving the Spring Boot parent POM because network/cache access was denied; rerunning `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,CodeAgentLocalPatchRequestServiceTest,LocalAgentToolGatewayServiceTest" test` with dependency-cache/network access passed with 44 tests. The expanded Maven run with `CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,CodeAgentLocalPatchRequestServiceTest,LocalAgentToolGatewayServiceTest,LocalAgentControllerTest,LocalAgentWebSocketHandlerTest,LocalAgentToolExecutionRepositoryLivePostgresTest` passed with 53 tests, 1 live PostgreSQL smoke skipped by default. `dotnet build local-agent\LearnBot.LocalAgent.csproj -c Release`, `npm run build`, and `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed; the frontend build kept the existing large-chunk warning and diff check reported only LF/CRLF warnings.
- Remaining risk: These are preview-derived policy records only. The loop still does not persist concrete stop outcomes for ambiguity, missing agent, failed tool, approval denial, cancellation request, timeout occurrence, or final completion/publication. Mutation remains intentionally disabled.
- Next recommended step: Continue Milestone 6 by carrying the loop id into future completion/publication-readiness records, or by adding audit-only stop-on-failure outcome records for ambiguity, missing agent, failed tool, and approval denial without enabling patch apply, test execution, rollback restore, RAG freshness update, final-answer publication, acknowledgement save, or mutation release.

### 2026-06-30 - Run 338

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then continued Milestone 6 by tying prepared Local Agent work back to a durable loop session id. Added optional `loopId` to `CodeAgentLocalPatchRequest`, preserved it in the prepared `patch.apply` request input, sent the current `codeAgentLoopPreview.loopId` from the Code workspace, and propagated the same id into manual read-only repository observations. Updated `CodeAgentLoopTimelineRepository` so approval-decision and observation-result events prefer the explicit loop timeline and only fall back to latest user/repository timeline for older requests. The prepared Local Agent request panel now displays the loop id. No Local Agent push, claim, release, write-helper call, patch apply, test execution, rollback restore, RAG freshness update, final-answer publication, acknowledgement save, or mutation path was enabled.
- Verified: `node src/components/code/agentLoopTimelineSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed. The first sandboxed Maven attempt failed resolving the Spring Boot parent POM because network/cache access was denied; rerunning `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,CodeAgentLocalPatchRequestServiceTest,LocalAgentToolGatewayServiceTest" test` with dependency-cache/network access passed with 44 tests. The expanded Maven run with `CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,CodeAgentLocalPatchRequestServiceTest,LocalAgentToolGatewayServiceTest,LocalAgentControllerTest,LocalAgentWebSocketHandlerTest,LocalAgentToolExecutionRepositoryLivePostgresTest` passed with 53 tests, 1 live PostgreSQL smoke skipped by default. `dotnet build local-agent\LearnBot.LocalAgent.csproj -c Release`, `npm run build`, and `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed; the frontend build kept the existing large-chunk warning and diff check reported only LF/CRLF warnings.
- Remaining risk: Cancellation, timeout, final-result, publication-readiness, and acknowledgement-readiness events are still not represented. The loop id is now carried through prepared Local Agent request input and direct repository observations, but future completion/final-answer records still need the same correlation. Mutation remains intentionally disabled.
- Next recommended step: Continue Milestone 6 by adding audit-only cancellation/timeout/final-result timeline records, or by carrying the loop id into final completion/publication-readiness timeline records without enabling patch apply, test execution, rollback restore, RAG freshness update, final-answer publication, acknowledgement save, or mutation release.

### 2026-06-30 - Run 337

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then continued Milestone 6 by appending audit-only approval decision events into loop timelines. Added `CodeAgentLoopTimelineRepository.appendApprovalDecision` and wired `LocalAgentToolGatewayService.approveHeld(...)`/`deny(...)` so approval or denial of a prepared Local Agent request appends `LOCAL_AGENT_APPROVAL_DECISION` to the latest user/repository loop timeline when the stored request input carries `repositoryId` or `sourceRepository.id`. The event details include request/session/agent/workspace ids, approval state, held/rejected status, source/release attempt linkage, and `may_mutate=false`. Updated frontend timeline rendering and smoke fixtures so approval decision events show `approval state APPROVED` and `status APPROVED_HELD`. No Local Agent request creation, push, claim, release, write-helper call, apply, test, rollback restore, RAG freshness update, result intake, final-answer publication, acknowledgement save, or mutation path was enabled.
- Verified: `node src/components/code/agentLoopTimelineSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,LocalAgentToolGatewayServiceTest" test` passed after rerunning with dependency-cache/network access because the sandboxed Maven attempt could not resolve the Spring Boot parent POM. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,LocalAgentToolGatewayServiceTest,LocalAgentControllerTest,LocalAgentWebSocketHandlerTest,LocalAgentToolExecutionRepositoryLivePostgresTest" test` passed with the live PostgreSQL smoke skipped by default after the same sandbox dependency-resolution fallback. `dotnet build local-agent\LearnBot.LocalAgent.csproj -c Release`, `npm run build`, and `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed; the frontend build kept the existing large-chunk warning and diff check reported only LF/CRLF warnings.
- Remaining risk: Approval and observation events still attach to the latest user/repository preview timeline rather than a durable explicit loop session id. Cancellation, timeout, final-result, publication, and acknowledgement events are still not represented. Mutation remains intentionally disabled.
- Next recommended step: Continue Milestone 6 by adding audit-only cancellation/timeout/final-result timeline records, or by introducing a durable loop-session correlation id through preview, approvals, observations, and completion without enabling execution.

### 2026-06-30 - Run 336

- Completed: Re-read `agent.md`, `README.md`, and the Current pointer, then continued Milestone 6 by appending audit-only Local Agent observation results into loop timelines. Added `CodeAgentLoopTimelineRepository.appendObservationResult`, injected the timeline repository into `LocalAgentToolGatewayService`, and appended `LOCAL_AGENT_OBSERVATION_RESULT` to the latest user/repository loop timeline after `complete(...)` persists a Local Agent response and the stored request input carries `repositoryId` or `sourceRepository.id`. The event details include request/session/agent/workspace ids, status, source/release attempt linkage, fresh-observation, dry-run, mutation-applied, repository verification, snapshot-created, error, and warnings, with `may_mutate=false`. Updated frontend timeline rendering and smoke fixtures so observation-result events show status, fresh-observation, dry-run, and `mutation applied false`. No Local Agent request creation, push, claim, release, approval, write-helper call, apply, test, rollback restore, RAG freshness update, result intake, final-answer publication, acknowledgement save, or mutation path was enabled.
- Verified: `node src/components/code/agentLoopTimelineSummary.test.mjs`, `node src/components/code/codeWorkspaceReadinessSmokeHarness.test.mjs`, and `node src/components/code/codeWorkspaceReadinessPanelSmoke.test.mjs` passed. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,LocalAgentToolGatewayServiceTest" test` passed after rerunning with dependency-cache/network access because the sandboxed Maven attempt could not resolve the Spring Boot parent POM. `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=CodeAgentLoopTimelineRepositoryTest,CodeAgentLoopPreviewServiceTest,CodeAgentControllerTest,LocalAgentToolGatewayServiceTest,LocalAgentControllerTest,LocalAgentWebSocketHandlerTest,LocalAgentToolExecutionRepositoryLivePostgresTest" test` passed with the live PostgreSQL smoke skipped by default after the same sandbox dependency-resolution fallback. `dotnet build local-agent\LearnBot.LocalAgent.csproj -c Release`, `npm run build`, and `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check` passed; the frontend build kept the existing large-chunk warning and diff check reported only LF/CRLF warnings.
- Remaining risk: Observation-result events currently attach to the latest user/repository preview timeline, not a durable explicit loop session id. Approval decisions, cancellation, timeout, final-result, publication, and acknowledgement events are still not represented. Mutation remains intentionally disabled.
- Next recommended step: Continue Milestone 6 by adding audit-only approval/cancellation/timeout/final-result timeline records, or by introducing a durable loop-session correlation id through preview and observation requests without enabling execution.

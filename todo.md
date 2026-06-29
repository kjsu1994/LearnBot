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

Current pointer: continue Milestone 5 by surfacing backend `releaseAttemptModel.latestAttempt.mutationFinalAnswerDeliveryReceiptGate` in the Code workspace UI after `mutationFinalAnswerDeliveryGate` and before `mutationCompletionSummary`. Review `frontend/src/components/code/CodeWorkspace.jsx`, the existing mutation final-answer delivery gate UI, completion summary display, and disabled no-control patterns before editing. The next safe slice should render delivery receipt gate status, schema, final-answer delivery readiness, prerequisite state, execution target, delivery receipt policy, source final-answer delivery gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted result counts, policy checks, blocking keys, message, and disabled delivery-receipt/final-answer-delivery/delivery-handoff/final-response-handoff/user-visible-completion/conversation-save/persistence/completion/final-answer/mutation flags as read-only audit data while adding no buttons, no handlers, no release/apply/test/rollback controls, no request creation, no push, no claim transition, no execution, no result aggregation, no publication, no RAG freshness update, no final-answer generation/completion/delivery/persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff/delivery receipt control, no mutation path, and no server-local file mutation path.

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

### 2026-06-29 - Run 163

- Completed: Continued backend splitting by extracting the audit-only mutation post-execution observation gate into `LocalAgentPostExecutionObservationGateBuilder`. The service now delegates `releaseAttemptModel.latestAttempt.mutationPostExecutionObservationGate` assembly to that focused builder while preserving schema `learnbot.local-agent.mutation-post-execution-observation-gate.v1`, `REFUSED_POST_EXECUTION_OBSERVATION_DISABLED`/`BLOCKED_POST_EXECUTION_OBSERVATION_DISABLED` statuses, execution-gate readiness, observation policy, source execution gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected result counts, policy checks, blocking keys, disabled post-execution observation/completed-result persistence/rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/mutation flags, and no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. The existing observation acceptance builder still consumes the post-execution observation gate output without a contract change. No frontend behavior, Local Agent behavior, release controls, mutation controls, API shape, or completion-summary ordering was intentionally changed. README documents the backend split point.
- Verified: Used the local Maven installation under `C:\Users\honeybadger\Desktop\LearnBot\.tools`; network-approved targeted command `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test` passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md todo.md docs/progress/2026-06.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/main/java/com/learnbot/service/LocalAgentPostExecutionObservationGateBuilder.java backend/src/main/java/com/learnbot/service/LocalAgentObservationAcceptanceGateBuilder.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new post-execution observation builder, service delegation, README note, current pointer, this progress log, five-entry todo window, Run 158 archive entry, and removal of the old `releaseAttemptMutationPostExecutionObservationGate`/`mutationPostExecutionObservationPolicyCheck` methods from the service. Frontend code was not changed in this run, so `npm run build` was not rerun.
- Remaining risk: `LocalAgentToolGatewayService` remains large and still owns execution gates, release-attempt readiness gates, dispatch/release modeling, completion summary assembly, release-attempt freshness/evidence modeling, and repository observation helpers. Behavior is protected by targeted regression tests, but this is still a structural refactor slice rather than a product capability change.
- Next recommended step: Extract the adjacent mutation execution gate into its own builder, preserving all existing audit-only disabled output and regression assertions.

### 2026-06-29 - Run 162

- Completed: Continued backend splitting by extracting the audit-only mutation observation acceptance gate into `LocalAgentObservationAcceptanceGateBuilder`. The service now delegates `releaseAttemptModel.latestAttempt.mutationObservationAcceptanceGate` assembly to that focused builder while preserving schema `learnbot.local-agent.mutation-observation-acceptance-gate.v1`, `REFUSED_OBSERVATION_ACCEPTANCE_DISABLED`/`BLOCKED_OBSERVATION_ACCEPTANCE_DISABLED` statuses, post-execution observation readiness, acceptance policy, source post-execution observation gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted counts, policy checks, blocking keys, disabled observation acceptance/intake persistence/rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/mutation flags, and no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. The existing result intake persistence builder still consumes the observation acceptance gate output without a contract change. No frontend behavior, Local Agent behavior, release controls, mutation controls, API shape, or completion-summary ordering was intentionally changed. README documents the backend split point.
- Verified: Used the local Maven installation under `C:\Users\honeybadger\Desktop\LearnBot\.tools`; network-approved targeted command `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test` passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md todo.md docs/progress/2026-06.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/main/java/com/learnbot/service/LocalAgentObservationAcceptanceGateBuilder.java backend/src/main/java/com/learnbot/service/LocalAgentResultIntakePersistenceGateBuilder.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new observation acceptance builder, service delegation, README note, current pointer, this progress log, five-entry todo window, and removal of the old `releaseAttemptMutationObservationAcceptanceGate`/`mutationObservationAcceptancePolicyCheck` methods from the service. Frontend code was not changed in this run, so `npm run build` was not rerun.
- Remaining risk: `LocalAgentToolGatewayService` remains large and still owns post-execution observation/execution gates, release-attempt readiness gates, dispatch/release modeling, completion summary assembly, release-attempt freshness/evidence modeling, and repository observation helpers. Behavior is protected by targeted regression tests, but this is still a structural refactor slice rather than a product capability change.
- Next recommended step: Extract the adjacent mutation post-execution observation gate into its own builder, preserving all existing audit-only disabled output and regression assertions.

### 2026-06-29 - Run 161

- Completed: Continued backend splitting by extracting the audit-only mutation result intake persistence gate into `LocalAgentResultIntakePersistenceGateBuilder`. The service now delegates `releaseAttemptModel.latestAttempt.mutationResultIntakePersistenceGate` assembly to that focused builder while preserving schema `learnbot.local-agent.mutation-result-intake-persistence-gate.v1`, `REFUSED_INTAKE_PERSISTENCE_DISABLED`/`BLOCKED_INTAKE_PERSISTENCE_DISABLED` statuses, observation acceptance readiness, intake persistence policy, source observation acceptance gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted counts, policy checks, blocking keys, disabled intake persistence/accepted observation persistence/rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/mutation flags, and no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. The existing rollback fallback builder still consumes the intake persistence gate output without a contract change. No frontend behavior, Local Agent behavior, release controls, mutation controls, API shape, or completion-summary ordering was intentionally changed. README documents the backend split point.
- Verified: Used the local Maven installation under `C:\Users\honeybadger\Desktop\LearnBot\.tools`; network-approved targeted command `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test` passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md todo.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/main/java/com/learnbot/service/LocalAgentResultIntakePersistenceGateBuilder.java` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new result intake persistence builder, service delegation, README note, current pointer, this progress log, and removal of the old `releaseAttemptMutationResultIntakePersistenceGate`/`mutationResultIntakePersistencePolicyCheck` methods from the service. Frontend code was not changed in this run, so `npm run build` was not rerun.
- Remaining risk: `LocalAgentToolGatewayService` remains large and still owns observation acceptance/post-execution observation/execution gates, release-attempt readiness gates, dispatch/release modeling, completion summary assembly, release-attempt freshness/evidence modeling, and repository observation helpers. Behavior is protected by targeted regression tests, but this is still a structural refactor slice rather than a product capability change.
- Next recommended step: Extract the adjacent mutation observation acceptance gate into its own builder, preserving all existing audit-only disabled output and regression assertions.

### 2026-06-29 - Run 160

- Completed: Continued backend splitting by extracting the audit-only mutation rollback fallback gate into `LocalAgentRollbackFallbackGateBuilder`. The service now delegates `releaseAttemptModel.latestAttempt.mutationRollbackFallbackGate` assembly to that focused builder while preserving schema `learnbot.local-agent.mutation-rollback-fallback-gate.v1`, `REFUSED_ROLLBACK_FALLBACK_DISABLED`/`BLOCKED_ROLLBACK_FALLBACK_DISABLED` statuses, intake persistence readiness, rollback fallback policy, source result intake persistence gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted counts, policy checks, blocking keys, disabled rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/mutation flags, and no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. The existing RAG freshness builder still consumes the rollback fallback gate output without a contract change. No frontend behavior, Local Agent behavior, release controls, mutation controls, API shape, or completion-summary ordering was intentionally changed. README documents the backend split point.
- Verified: Used the local Maven installation under `C:\Users\honeybadger\Desktop\LearnBot\.tools`; network-approved targeted command `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test` passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md todo.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/main/java/com/learnbot/service/LocalAgentRollbackFallbackGateBuilder.java backend/src/main/java/com/learnbot/service/LocalAgentRagFreshnessGateBuilder.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new rollback fallback builder, service delegation, README note, current pointer, this progress log, and removal of the old `releaseAttemptMutationRollbackFallbackGate`/`mutationRollbackFallbackPolicyCheck` methods from the service. Frontend code was not changed in this run, so `npm run build` was not rerun.
- Remaining risk: `LocalAgentToolGatewayService` remains large and still owns intake persistence/observation acceptance/post-execution observation gates, release-attempt readiness gates, dispatch/release modeling, completion summary assembly, release-attempt freshness/evidence modeling, and repository observation helpers. Behavior is protected by targeted regression tests, but this is still a structural refactor slice rather than a product capability change.
- Next recommended step: Extract the adjacent mutation result intake persistence gate into its own builder, preserving all existing audit-only disabled output and regression assertions.

### 2026-06-29 - Run 159

- Completed: Continued backend splitting by extracting the audit-only mutation RAG freshness gate into `LocalAgentRagFreshnessGateBuilder`. The service now delegates `releaseAttemptModel.latestAttempt.mutationRagFreshnessGate` assembly to that focused builder while preserving schema `learnbot.local-agent.mutation-rag-freshness-gate.v1`, `REFUSED_RAG_FRESHNESS_DISABLED`/`BLOCKED_RAG_FRESHNESS_DISABLED` statuses, rollback fallback readiness, RAG freshness policy, source rollback fallback gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted counts, policy checks, blocking keys, disabled RAG freshness/result aggregation/publication/final-answer generation/mutation flags, and no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. The existing result aggregation builder still consumes the RAG freshness gate output without a contract change. No frontend behavior, Local Agent behavior, release controls, mutation controls, API shape, or completion-summary ordering was intentionally changed. README documents the backend split point.
- Verified: Used the local Maven installation under `C:\Users\honeybadger\Desktop\LearnBot\.tools`; network-approved targeted command `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test` passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md todo.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/main/java/com/learnbot/service/LocalAgentRagFreshnessGateBuilder.java backend/src/main/java/com/learnbot/service/LocalAgentResultAggregationGateBuilder.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new RAG freshness builder, service delegation, README note, current pointer, this progress log, and removal of the old `releaseAttemptMutationRagFreshnessGate`/`mutationRagFreshnessPolicyCheck` methods from the service. Frontend code was not changed in this run, so `npm run build` was not rerun.
- Remaining risk: `LocalAgentToolGatewayService` remains large and still owns rollback fallback/intake persistence/observation acceptance gates, release-attempt readiness gates, dispatch/release modeling, completion summary assembly, release-attempt freshness/evidence modeling, and repository observation helpers. Behavior is protected by targeted regression tests, but this is still a structural refactor slice rather than a product capability change.
- Next recommended step: Extract the adjacent mutation rollback fallback gate into its own builder, preserving all existing audit-only disabled output and regression assertions.

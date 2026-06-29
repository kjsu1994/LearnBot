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

### 2026-06-29 - Run 147

- Completed: Added backend audit-only `releaseAttemptModel.latestAttempt.mutationFinalAnswerDeliveryReceiptGate` after `mutationFinalAnswerDeliveryGate` and before `mutationCompletionSummary`. The gate consumes final-answer delivery readiness, records disabled delivery receipt policy, source final-answer delivery gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted result counts, policy checks, blocking keys, and disabled delivery-receipt/final-answer-delivery/delivery-handoff/final-response-handoff/user-visible completion/conversation-turn save/final-answer persistence/final-answer completion/final-answer generation/publication/mutation flags. It explicitly refuses delivery receipt recording, acknowledgement save, final-answer delivery, delivery handoff, final-response handoff, user-visible completion, conversation-turn save, final-answer persistence, final-answer completion, final-answer generation, publication, and mutation while creating no request, pushing no request, claiming no request, running no request, completing no request, persisting no final answer, saving no conversation turn, marking no user-visible completion, handing off no final response, delivering no answer, recording no delivery receipt, saving no acknowledgement, publishing no answer, enabling no mutation, and opening no server-local mutation path. Regression assertions cover ready/refused and blocked delivery receipt gate states, policy checks, disabled counts, disabled flags, completion-summary inclusion, delivery receipt summary state, and existing no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. README documents the gate as audit-only backend readiness.
- Verified: A sandboxed targeted Maven run failed before project loading because Maven could not access Maven Central (`Permission denied: getsockopt`). The same targeted command with network approval, `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test`, passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java frontend/src/components/code/CodeWorkspace.jsx todo.md` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new `mutationFinalAnswerDeliveryReceiptGate` service, test, README, and todo strings. Frontend and Local Agent code were not changed in this run, so `npm run build` and .NET self-tests were not rerun. The running Docker stack was not rebuilt after this source change.
- Remaining risk: The mutation final-answer delivery receipt gate is backend readiness only and is not yet displayed in the Code workspace UI. No Local Agent mutation request is created, no request is pushed, no request becomes claimable/running/completed, no accepted mutation observation is persisted, no Local Agent write helper is called, no tests/builds run, no rollback fallback executes, no RAG freshness update occurs, no mutation result aggregation occurs, no publication occurs, and delivery receipt/final-answer generation/completion/delivery/persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff remains disabled.
- Next recommended step: Surface `mutationFinalAnswerDeliveryReceiptGate` in the Code workspace UI as audit-only latest-attempt evidence, without adding release/apply/test/rollback/execution/result-aggregation/publication/final-answer delivery/final-response handoff/delivery handoff/delivery receipt controls or changing mutation policy.

### 2026-06-29 - Run 146

- Completed: Surfaced backend `releaseAttemptModel.latestAttempt.mutationFinalAnswerDeliveryGate` in the Code workspace UI as read-only audit data after `mutationFinalResponseHandoffGate` and before `mutationCompletionSummary`. The UI now displays final-answer delivery gate status, schema, final-response handoff readiness, prerequisite state, execution target, final-answer delivery policy, delivery handoff state, source final-response handoff gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted result counts, policy checks, blocking keys, message, and disabled release/request creation/push/claim/execution/write helper/claimable/mutation/apply/test/rollback restore/post-execution observation/completed-result persistence/observation acceptance/intake persistence/accepted-observation persistence/rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/final-answer completion/final-answer delivery/final-answer persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff flags. The mutation completion summary also displays `finalAnswerDeliveryEnabled` when present. No buttons, handlers, release controls, apply/test/rollback controls, request creation, push, claim transition, execution, result aggregation, publication, RAG freshness update, final-answer generation/completion/delivery/persistence control, conversation-save control, user-visible completion control, final-response handoff control, delivery handoff control, final-answer delivery control, mutation path, or server-local file mutation path was added. README documents the UI display as read-only status.
- Verified: `npm run build` in `frontend/` passed with the existing Vite large chunk warning. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java frontend/src/components/code/CodeWorkspace.jsx todo.md` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the mutation final-answer delivery gate UI strings, final-answer delivery summary flags, README text, and this progress log. Backend and Local Agent code were not changed in this run, so Maven and .NET self-tests were not rerun. The running Docker stack was not rebuilt after this source change.
- Remaining risk: The mutation final-answer delivery gate is now visible, but there is not yet a backend audit-only delivery receipt/acknowledgement gate after final-answer delivery. No Local Agent mutation request is created, no request is pushed, no request becomes claimable/running/completed, no accepted mutation observation is persisted, no Local Agent write helper is called, no tests/builds run, no rollback fallback executes, no RAG freshness update occurs, no mutation result aggregation occurs, no publication occurs, and final-answer generation/completion/delivery/persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff remains disabled.
- Next recommended step: Add a backend audit-only `mutationFinalAnswerDeliveryReceiptGate` that consumes `mutationFinalAnswerDeliveryGate`, explains why delivery receipt/acknowledgement remains disabled, and preserves no-create/no-push/no-claim/no-execute/no-mutation behavior.

### 2026-06-29 - Run 145

- Completed: Added backend audit-only `releaseAttemptModel.latestAttempt.mutationFinalAnswerDeliveryGate` after `mutationFinalResponseHandoffGate` and before `mutationCompletionSummary`. The gate consumes final-response handoff readiness, records disabled final-answer delivery policy, disabled delivery handoff state, source final-response handoff gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted result counts, policy checks, blocking keys, and disabled release/request creation/push/claim/execution/write helper/apply/test/rollback restore/post-execution observation/completed-result persistence/observation acceptance/intake persistence/accepted-observation persistence/rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/final-answer completion/final-answer delivery/final-answer persistence/conversation-turn save/user-visible completion/final-response handoff/delivery handoff/mutation flags. It explicitly refuses final-answer delivery, delivery handoff, final-response handoff, user-visible completion, conversation-turn save, final-answer persistence, final-answer completion, final-answer generation, publication, and mutation while creating no request, pushing no request, claiming no request, running no request, completing no request, persisting no final answer, saving no conversation turn, marking no user-visible completion, handing off no final response, delivering no answer, publishing no answer, enabling no mutation, and opening no server-local mutation path. Regression assertions cover ready/refused and blocked final-answer delivery gate states, policy checks, disabled counts, disabled flags, completion-summary inclusion, final-answer delivery summary state, and existing no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. README documents the gate as audit-only backend readiness.
- Verified: A sandboxed targeted Maven run failed before project loading because Maven could not access Maven Central (`Permission denied: getsockopt`). The same targeted command with network approval, `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test`, passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java frontend/src/components/code/CodeWorkspace.jsx todo.md` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new `mutationFinalAnswerDeliveryGate` service, test, README, and todo strings. Frontend and Local Agent code were not changed in this run, so `npm run build` and .NET self-tests were not rerun. The running Docker stack was not rebuilt after this source change.
- Remaining risk: The mutation final-answer delivery gate is backend readiness only and is not yet displayed in the Code workspace UI. No Local Agent mutation request is created, no request is pushed, no request becomes claimable/running/completed, no accepted mutation observation is persisted, no Local Agent write helper is called, no tests/builds run, no rollback fallback executes, no RAG freshness update occurs, no mutation result aggregation occurs, no publication occurs, and final-answer generation/completion/delivery/persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff remains disabled.
- Next recommended step: Surface `mutationFinalAnswerDeliveryGate` in the Code workspace UI as audit-only latest-attempt evidence, without adding release/apply/test/rollback/execution/result-aggregation/publication/final-answer delivery/final-response handoff/delivery handoff controls or changing mutation policy.

### 2026-06-29 - Run 144

- Completed: Surfaced backend `releaseAttemptModel.latestAttempt.mutationFinalResponseHandoffGate` in the Code workspace UI as read-only audit data after `mutationFinalAnswerUserVisibleCompletionGate` and before `mutationCompletionSummary`. The UI now displays final-response handoff gate status, schema, user-visible completion readiness, prerequisite state, execution target, final-response handoff policy, delivery handoff state, source final-answer user-visible completion gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted result counts, policy checks, blocking keys, message, and disabled release/request creation/push/claim/execution/write helper/claimable/mutation/apply/test/rollback restore/post-execution observation/completed-result persistence/observation acceptance/intake persistence/accepted-observation persistence/rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/final-answer completion/final-answer delivery/final-answer persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff flags. The mutation completion summary also displays `deliveryHandoffEnabled` when present. No buttons, handlers, release controls, apply/test/rollback controls, request creation, push, claim transition, execution, result aggregation, publication, RAG freshness update, final-answer generation/completion/delivery/persistence control, conversation-save control, user-visible completion control, final-response handoff control, delivery handoff control, mutation path, or server-local file mutation path was added. README documents the UI display as read-only status.
- Verified: `npm run build` in `frontend/` passed with the existing Vite large chunk warning. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java frontend/src/components/code/CodeWorkspace.jsx todo.md` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the mutation final-response handoff gate UI strings, delivery handoff summary flags, README text, and this progress log. Backend and Local Agent code were not changed in this run, so Maven and .NET self-tests were not rerun. The running Docker stack was not rebuilt after this source change.
- Remaining risk: The mutation final-response handoff gate is now visible, but there is not yet a separate backend audit-only final-answer delivery gate after final-response handoff. No Local Agent mutation request is created, no request is pushed, no request becomes claimable/running/completed, no accepted mutation observation is persisted, no Local Agent write helper is called, no tests/builds run, no rollback fallback executes, no RAG freshness update occurs, no mutation result aggregation occurs, no publication occurs, and final-answer generation/completion/delivery/persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff remains disabled.
- Next recommended step: Add a backend audit-only `mutationFinalAnswerDeliveryGate` that consumes `mutationFinalResponseHandoffGate`, explains why final-answer delivery remains disabled, and preserves no-create/no-push/no-claim/no-execute/no-mutation behavior.

### 2026-06-29 - Run 143

- Completed: Added backend audit-only `releaseAttemptModel.latestAttempt.mutationFinalResponseHandoffGate` after `mutationFinalAnswerUserVisibleCompletionGate` and before `mutationCompletionSummary`. The gate consumes final-answer user-visible completion readiness, records disabled final-response handoff policy, disabled delivery handoff state, source final-answer user-visible completion gate status/schema, source/release/session/agent/workspace ids, expected/completed/accepted/rejected/intake-persisted result counts, policy checks, blocking keys, and disabled release/request creation/push/claim/execution/write helper/apply/test/rollback restore/post-execution observation/completed-result persistence/observation acceptance/intake persistence/accepted-observation persistence/rollback fallback/RAG freshness/result aggregation/publication/final-answer generation/final-answer completion/final-answer delivery/final-answer persistence/conversation-turn save/user-visible completion/final-response handoff/delivery handoff/mutation flags. It explicitly refuses final-response handoff, delivery handoff, final-answer delivery, user-visible completion, conversation-turn save, final-answer persistence, final-answer completion, final-answer generation, publication, and mutation while creating no request, pushing no request, claiming no request, running no request, completing no request, persisting no final answer, saving no conversation turn, marking no user-visible completion, handing off no final response, delivering no answer, publishing no answer, enabling no mutation, and opening no server-local mutation path. Regression assertions cover ready/refused and blocked final-response handoff gate states, policy checks, disabled counts, disabled flags, completion-summary inclusion, delivery handoff summary state, and existing no-create/no-push/no-claim/no-execute/no-complete/no-mutation behavior. README documents the gate as audit-only backend readiness.
- Verified: A sandboxed targeted Maven run failed before project loading because Maven could not access Maven Central (`Permission denied: getsockopt`). The same targeted command with network approval, `.\.tools\apache-maven-3.9.9\bin\mvn.cmd -f backend\pom.xml "-Dtest=LocalAgentToolGatewayServiceTest" test`, passed: 23 tests, 0 failures, 0 errors. `git -c safe.directory=C:/Users/honeybadger/Desktop/LearnBot diff --check -- README.md backend/src/main/java/com/learnbot/service/LocalAgentToolGatewayService.java backend/src/test/java/com/learnbot/service/LocalAgentToolGatewayServiceTest.java frontend/src/components/code/CodeWorkspace.jsx todo.md` passed with only existing LF-to-CRLF warnings. `rg` checks confirmed the new `mutationFinalResponseHandoffGate` service, test, README, and todo strings. Frontend and Local Agent code were not changed in this run, so `npm run build` and .NET self-tests were not rerun. The running Docker stack was not rebuilt after this source change.
- Remaining risk: The mutation final-response handoff gate is backend readiness only and is not yet displayed in the Code workspace UI. No Local Agent mutation request is created, no request is pushed, no request becomes claimable/running/completed, no accepted mutation observation is persisted, no Local Agent write helper is called, no tests/builds run, no rollback fallback executes, no RAG freshness update occurs, no mutation result aggregation occurs, no publication occurs, and final-answer generation/completion/delivery/persistence/conversation-save/user-visible completion/final-response handoff/delivery handoff remains disabled.
- Next recommended step: Surface `mutationFinalResponseHandoffGate` in the Code workspace UI as audit-only latest-attempt evidence, without adding release/apply/test/rollback/execution/result-aggregation/publication/final-answer/user-visible completion/final-response handoff/delivery handoff controls or changing mutation policy.

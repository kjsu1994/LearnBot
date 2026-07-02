# LearnBot Local Agent

This is the first MVP skeleton for the per-user Local Agent.

It is intentionally safe by default:

- It pairs with the central LearnBot server using a token generated from the web UI.
- It stores local config in `%USERPROFILE%\.learnbot\agent.json`.
- It sends heartbeat with approved local workspace summaries.
- It polls the durable server-side tool queue.
- It writes `agent.log` and `agent-state.json` next to the config file.
- It handles `agent.status`, `agent.doctor`, `workspace.list`, bounded approved-workspace `workspace.tree`, bounded approved-workspace `workspace.search`, path-contained `file.read`, read-only `git.status`, bounded read-only `git.diff`, approved typed `command.runAllowed` test/build requests, dry-run-only `patch.apply` preflight requests, a narrow approved single-file `patch.apply` mutation path, and approved `rollback.restore` requests for Local Agent managed snapshots.
- It advertises the same handled tool set in heartbeat capabilities, including `patch.apply`, `command.runAllowed`, and `rollback.restore`, so server readiness can reason about the connected Local Agent's actual execution surface.
- It rejects path traversal, workspace escape, binary file reads, arbitrary command execution, unknown command ids, multi-file patch mutation, unapproved command/patch/rollback requests, patch mutation without a managed snapshot manifest, and rollback requests that do not reference a managed snapshot manifest.
- Dry-run `patch.apply` responses include hash/context observations plus managed snapshot and rollback observations. After preflight passes, the agent copies target files into `%USERPROFILE%\.learnbot\snapshots\<manifestId>\files\`, writes a manifest, returns `snapshotCreated=true`, and still keeps `mutationApplied=false`.
- `dotnet run --project local-agent -- self-test patch-dry-run-contract` pins that end-to-end dry-run contract: snapshot creation can pass, but the tool result remains `REJECTED`/`UNSAFE_TOOL`, leaves the workspace file unchanged, reports `mutationApplied=false`, and requires separate rollback approval.
- `dotnet run --project local-agent -- self-test workspace-tree-contract` pins the read-only project exploration boundary: `workspace.tree` can enumerate files under an approved workspace with max-entry/max-depth caps, skips large generated directories such as `.git`, `node_modules`, `bin`, `obj`, and `target`, and rejects path escape.
- `dotnet run --project local-agent -- self-test workspace-search-contract` pins the read-only candidate search boundary: `workspace.search` searches text inside approved workspaces with match/file/bytes caps, skips generated directories and binary/oversized files, supports case-insensitive matches, and rejects path escape.
- `dotnet run --project local-agent -- self-test read-only-candidate-selection-contract` pins the audit-only candidate-selection report built from `workspace.tree` plus `workspace.search`: selected files are ranked for the next `file.read` step, mutation remains disabled, and tree-only fallback is explicit when search evidence is unavailable.
- `dotnet run --project local-agent -- self-test multi-file-read-report-contract` pins the audit-only multi-file read report built from candidate selection plus one or more `file.read` responses: selected files are compared with read files, missing selections are explicit, truncation is visible, and mutation remains disabled.
- `dotnet run --project local-agent -- self-test patch-test-retry-decision-contract` pins the audit-only patch/test failure analysis report: successful allowlisted commands require no retry, failed test commands recommend replanning from captured stdout/stderr, unsafe or unapproved commands are blocked, and mutation remains disabled until a new approved patch is produced.
- `dotnet run --project local-agent -- self-test revised-patch-proposal-plan-contract` pins the failed-log-to-proposal boundary: failed `command.runAllowed` stdout/stderr plus known target files can produce a bounded revised-patch proposal plan, but local model generation, mutation, publication, and partial reindex stay disabled until dry-run and user approval.
- `dotnet run --project local-agent -- self-test local-model-revised-patch-request-contract` pins the local-model proposal request boundary: failed command evidence and read target-file snippets are shaped into a capped model input/output contract while the model call, patch queueing, mutation, publication, and partial reindex remain disabled.
- `dotnet run --project local-agent -- self-test local-model-revised-patch-output-contract` pins the local-model proposal output boundary: only a capped unified diff touching the planned target files can advance to `patch.apply` dry-run input, while empty, oversized, or out-of-target diffs are blocked and mutation remains disabled.
- `dotnet run --project local-agent -- self-test validated-revised-patch-dry-run-handoff-contract` pins the disabled dry-run handoff from validated model output to `patch.apply`: the dry-run input is shaped and visible, but durable queueing, claimability, mutation, and approval bypass remain disabled.
- `dotnet run --project local-agent -- self-test patch-test-second-attempt-contract` pins the bounded second-attempt contract: failed test output can produce a revised patch proposal, a validated dry-run handoff can be consumed and matched against the `patch.apply` dry-run response, the flow stops at `APPROVAL_REQUIRED`, and no mutation is executed before a new approval.
- `dotnet run --project local-agent -- self-test revised-patch-approval-request-contract` pins the approval request report for a revised dry-run: target files, diff evidence, snapshot/rollback observations, stale-index disclosure, and next mutation preconditions are explicit, and the next mutation is allowed only after `approvalState=APPROVED`.
- `dotnet run --project local-agent -- self-test revised-patch-approval-gate-contract` pins the server-facing approval-id gate for a revised mutation candidate: a persisted approval request id is required, missing or mismatched ids are blocked, and a matching approved id is required before a second mutation can be queued.
- `dotnet run --project local-agent -- self-test approved-server-queue-second-attempt-contract` pins the same second-attempt boundary through the durable polling queue: an approved first patch can run, an allowlisted test failure is returned, a revised `patch.apply` dry-run is queued and rejected as mutation-disabled after preflight passes, and rollback restores the original file.
- `dotnet run --project local-agent -- self-test approved-execution-flow-contract` pins a narrow Codex-style closed loop in one temporary workspace: read-only `workspace.tree`/`workspace.search`/candidate-selection/multiple `file.read`/multi-file-read/`git.status` observations run before mutation, advertised capabilities include project exploration, candidate search, patch/test-command/rollback tools, approved `patch.apply` mutates one file, approved `command.runAllowed` runs through the typed allowlist, post-patch `git.status` observes the changed workspace, approved `rollback.restore` restores the managed snapshot, and the generated report carries retry-decision, revised-proposal-plan, local-model proposal request/output validation, disabled dry-run handoff, second-attempt, revised-approval-request, final-report, and RAG freshness sections that require partial reindex or stale-index disclosure.
- Patch hunk application and a temp-file rewrite sequence have Local Agent self-test coverage for the future write path, but they are not wired to public patch mutation or release gates yet.
- Approved `patch.apply` mutation currently applies only one file, requires `mutationAllowed=true`, refuses `dryRunOnly=true`, requires a Local Agent managed snapshot manifest, rechecks the target hash immediately before writing, and uses the guarded temp-file rewrite sequence.
- `rollback.restore` can restore only from `%USERPROFILE%\.learnbot\snapshots\<manifestId>\manifest.json` after the request is already approved. It revalidates manifest schema/id, approved workspace id/root, managed snapshot paths, and workspace-contained target paths before copying snapshot files back.
- `command.runAllowed` requires `approvalState=APPROVED`, an approved workspace, and a typed allowlisted `commandId`. The first allowlist includes `dotnet.build`, `dotnet.test`, `npm.run.build`, `npm.test`, `maven.test`, and `maven.backend.test`; `dotnet.version` is retained as a local diagnostic/self-test command. Timeouts and captured output are capped, and arbitrary shell strings are not accepted.
- The first real snapshot creation boundary is specified in `../docs/local-agent-snapshot-implementation-plan.md`; tests remain disabled after snapshot creation.

Example:

```powershell
dotnet run --project local-agent -- pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> --transport polling
dotnet run --project local-agent -- workspace add .
dotnet run --project local-agent -- status
dotnet run --project local-agent -- doctor
dotnet run --project local-agent -- agent status
dotnet run --project local-agent -- agent token
dotnet run --project local-agent -- agent logs --tail 80
dotnet run --project local-agent -- file tree --workspace-id <workspace-id>
dotnet run --project local-agent -- file search --workspace-id <workspace-id> --query App
dotnet run --project local-agent -- file read --workspace-id <workspace-id> --path README.md
dotnet run --project local-agent -- git status --workspace-id <workspace-id>
dotnet run --project local-agent -- git diff --workspace-id <workspace-id> --path README.md
dotnet run --project local-agent -- agent start --once --transport auto
```

Transport modes are scaffolded as `polling`, `websocket`, and `auto`. `websocket` and `auto` try a bounded WebSocket hello/heartbeat first, process pushed read-only `tool.request` messages during the receive window, send `tool.response`, and then keep polling available for durable tool queue fallback. `agent status` includes the configured transport, active transport, consecutive WebSocket failures, and next retry time so fallback behavior is visible.

Internal foreground helper from the repository root:

```powershell
.\scripts\local-agent.ps1 -Action setup -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action setup-plan -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action setup-run-plan -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action browser-pairing-plan -Server http://localhost:8083 -WorkspacePath . -Transport polling
.\scripts\local-agent.ps1 -Action pair-from-web-token-plan -Server http://localhost:8083 -WorkspacePath . -PairingAgentId <agent-id> -PairingToken <pairing-token> -Transport polling
.\scripts\local-agent.ps1 -Action pair-from-web-token -Server http://localhost:8083 -WorkspacePath . -PairingAgentId <agent-id> -PairingToken <pairing-token> -Transport polling
.\scripts\local-agent.ps1 -Action status
.\scripts\local-agent.ps1 -Action token
.\scripts\local-agent.ps1 -Action logs -Tail 80
.\scripts\local-agent.ps1 -Action start
.\scripts\local-agent.ps1 -Action background-start
.\scripts\local-agent.ps1 -Action background-stop
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction status
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction logs -Tail 80
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction background-start
.\scripts\local-agent.ps1 -Action lifecycle-command -LifecycleAction background-stop
.\scripts\local-agent.ps1 -Action lifecycle-status
.\scripts\local-agent.ps1 -Action service-plan
.\scripts\local-agent.ps1 -Action service-command-plan -ServiceAction install
```

`setup-run-plan` returns `learnbot.local-agent.setup-run-plan.v1`, a preview-only guided setup execution boundary. It reuses `setup-plan`, keeps login/pairing/workspace commands disabled, and reports missing inputs before any network calls or local config writes are enabled. The current `setup` helper checks this readiness boundary before prompting for the password or calling the server.

`browser-pairing-plan` returns `learnbot.local-agent.browser-pairing-plan.v1`, a preview-only setup path where the browser owns login and pairing-token creation. It avoids CLI password collection and shows the follow-up `learnbot pair` and workspace registration commands without printing token secrets.

`pair-from-web-token-plan` returns `learnbot.local-agent.pair-from-web-token-plan.v1`, a preview-only contract for pasted web pairing inputs. It validates the agent id, pairing-token presence, and workspace path without collecting a password, printing token secrets, writing local config, registering a workspace, or making network calls.

`pair-from-web-token` runs the guarded browser-token setup path and returns `learnbot.local-agent.pair-from-web-token-result.v1`. It builds the same plan, returns a structured `BLOCKED` result when the plan is not ready, then runs `learnbot pair`, `learnbot workspace add`, and `learnbot agent status` only after readiness passes. The result reports per-step success/failure without collecting a CLI password or printing the pasted token. The underlying `learnbot pair` command still sends its initial heartbeat to the configured server, so the server must be reachable and the token must be valid for the command to complete.

`learnbot pair` validates the initial heartbeat before saving local config. If the server is unreachable or the token is rejected during that first heartbeat, a new config file is not created and an existing config is preserved.

`lifecycle-status` returns `learnbot.local-agent.lifecycle-status.v1`, a machine-readable internal-pilot view of config, run state, process liveness, log presence, recommended lifecycle commands, and explicit service limitations. It does not print pairing token secrets and does not enable Windows Service registration.

`lifecycle-command` returns `learnbot.local-agent.lifecycle-command-result.v1` for `status`, `logs`, `doctor`, `background-start`, or `background-stop`. It is an automation-friendly wrapper around the existing helper commands and reports success/failure, captured output, and safety flags while keeping service command execution disabled.

`service-plan` returns `learnbot.local-agent.service-plan.v1`, a preview-only Windows Service readiness plan. It checks for the installed executable, paired config, approved workspace, and existing service state, then prints planned service commands without executing them.

`service-command-plan` returns `learnbot.local-agent.service-command-plan.v1` for `install`, `start`, `stop`, or `uninstall`. It keeps service command execution disabled and reports the blocking reasons before any future privileged service action is enabled.

Live smoke from the repository root:

```powershell
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath . -ToolName file.read -Path README.md
$env:LEARNBOT_LOCAL_AGENT_WEBSOCKET_ENABLED = "true"
.\scripts\up.ps1 -Build
.\scripts\local-agent-smoke.ps1 -Server http://localhost:8083 -WorkspacePath . -ToolName file.read -Path README.md -Transport websocket
```

The WebSocket smoke requires the backend WebSocket endpoint to be enabled and fails if the request is completed only through polling fallback. The default stack keeps WebSocket disabled, so the non-WebSocket smoke remains the fallback check.

Internal executable publish helper:

```powershell
.\scripts\local-agent-install.ps1 -Action install
.\scripts\local-agent-install.ps1 -Action install -AddToUserPath
.\scripts\local-agent-install.ps1 -Action status
learnbot status
learnbot doctor
learnbot agent status
```

After publishing to the default install directory, `scripts/local-agent.ps1` uses the installed executable automatically. The install helper returns `learnbot.local-agent.install-status.v1`, including the install directory, executable path, PATH visibility, recommended `status`/`doctor`/`start` commands, internal-pilot limitations, and the installed executable's `learnbot status` output when available.

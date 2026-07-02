# LearnBot Local Agent

This is the first MVP skeleton for the per-user Local Agent.

It is intentionally safe by default:

- It pairs with the central LearnBot server using a token generated from the web UI.
- It stores local config in `%USERPROFILE%\.learnbot\agent.json`.
- It sends heartbeat with approved local workspace summaries.
- It polls the durable server-side tool queue.
- It writes `agent.log` and `agent-state.json` next to the config file.
- It handles `agent.status`, `agent.doctor`, `workspace.list`, path-contained `file.read`, read-only `git.status`, bounded read-only `git.diff`, approved typed `command.runAllowed` test/build requests, dry-run-only `patch.apply` preflight requests, a narrow approved single-file `patch.apply` mutation path, and approved `rollback.restore` requests for Local Agent managed snapshots.
- It advertises the same handled tool set in heartbeat capabilities, including `patch.apply`, `command.runAllowed`, and `rollback.restore`, so server readiness can reason about the connected Local Agent's actual execution surface.
- It rejects path traversal, workspace escape, binary file reads, arbitrary command execution, unknown command ids, multi-file patch mutation, unapproved command/patch/rollback requests, patch mutation without a managed snapshot manifest, and rollback requests that do not reference a managed snapshot manifest.
- Dry-run `patch.apply` responses include hash/context observations plus managed snapshot and rollback observations. After preflight passes, the agent copies target files into `%USERPROFILE%\.learnbot\snapshots\<manifestId>\files\`, writes a manifest, returns `snapshotCreated=true`, and still keeps `mutationApplied=false`.
- `dotnet run --project local-agent -- self-test patch-dry-run-contract` pins that end-to-end dry-run contract: snapshot creation can pass, but the tool result remains `REJECTED`/`UNSAFE_TOOL`, leaves the workspace file unchanged, reports `mutationApplied=false`, and requires separate rollback approval.
- `dotnet run --project local-agent -- self-test approved-execution-flow-contract` pins a narrow approved execution flow in one temporary workspace: advertised capabilities include patch/test-command/rollback tools, approved `patch.apply` mutates one file, approved `command.runAllowed` runs through the typed allowlist, `git.status` observes the changed workspace, and approved `rollback.restore` restores the managed snapshot.
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
.\scripts\local-agent.ps1 -Action status
.\scripts\local-agent.ps1 -Action token
.\scripts\local-agent.ps1 -Action logs -Tail 80
.\scripts\local-agent.ps1 -Action start
.\scripts\local-agent.ps1 -Action background-start
.\scripts\local-agent.ps1 -Action background-stop
```

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

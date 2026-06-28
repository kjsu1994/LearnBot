# LearnBot Local Agent

This is the first MVP skeleton for the per-user Local Agent.

It is intentionally safe by default:

- It pairs with the central LearnBot server using a token generated from the web UI.
- It stores local config in `%USERPROFILE%\.learnbot\agent.json`.
- It sends heartbeat with approved local workspace summaries.
- It polls the durable server-side tool queue.
- It writes `agent.log` and `agent-state.json` next to the config file.
- It handles `agent.status`, `agent.doctor`, `workspace.list`, path-contained `file.read`, read-only `git.status`, bounded read-only `git.diff`, and dry-run-only `patch.apply` preflight requests.
- It rejects path traversal, workspace escape, binary file reads, file mutation, arbitrary command execution, patch mutation, test, and rollback tools. `patch.apply` requires `dryRunOnly=true` and refuses any request with `mutationAllowed=true`.
- Dry-run `patch.apply` responses include hash/context observations plus managed snapshot and rollback observations. After preflight passes, the agent copies target files into `%USERPROFILE%\.learnbot\snapshots\<manifestId>\files\`, writes a manifest, returns `snapshotCreated=true`, and still keeps `mutationApplied=false`.
- Patch hunk application and a temp-file rewrite sequence have Local Agent self-test coverage for the future write path, but they are not wired to public filesystem mutation or release gates yet.
- The first real snapshot creation boundary is specified in `../docs/local-agent-snapshot-implementation-plan.md`; patch application, tests, and rollback restore remain disabled after snapshot creation.

Example:

```powershell
dotnet run --project local-agent -- pair --server http://localhost:8083 --agent-id <agent-id> --token <pairing-token> --transport polling
dotnet run --project local-agent -- workspace add .
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
learnbot agent status
```

After publishing to the default install directory, `scripts/local-agent.ps1` uses the installed executable automatically.

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
dotnet run --project local-agent -- m8 status
dotnet run --project local-agent -- m8 doctor
dotnet run --project local-agent -- login --login-id <login-id>
dotnet run --project local-agent -- session status
dotnet run --project local-agent -- session plan
dotnet run --project local-agent -- session create-plan
dotnet run --project local-agent -- session claim-plan --device-code <device-code>
dotnet run --project local-agent -- session claim-result-plan --claim-status <status>
dotnet run --project local-agent -- session artifact-writer-preflight --approved --access-token-present --refresh-token-present --expires-at 2026-07-03T12:00:00Z --refresh-expires-at 2026-07-04T12:00:00Z
dotnet run --project local-agent -- session artifact-writer-test-write --test-only --approved --access-token-present --refresh-token-present --expires-at 2026-07-03T12:00:00Z --refresh-expires-at 2026-07-04T12:00:00Z
dotnet run --project local-agent -- session artifact-reader-test-validate --test-only
dotnet run --project local-agent -- session server-plan-readiness
dotnet run --project local-agent -- agent status
dotnet run --project local-agent -- agent token
dotnet run --project local-agent -- agent logs --tail 80
dotnet run --project local-agent -- file tree --workspace-id <workspace-id>
dotnet run --project local-agent -- file search --workspace-id <workspace-id> --query App
dotnet run --project local-agent -- file read --workspace-id <workspace-id> --path README.md
dotnet run --project local-agent -- git status --workspace-id <workspace-id>
dotnet run --project local-agent -- git diff --workspace-id <workspace-id> --path README.md
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id>
dotnet run --project local-agent -- review --goal "review current changes" --workspace . --repository-id <repository-id>
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id> --observe-read-only
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id> --observe-read-only --read-selected
dotnet run --project local-agent -- fix --goal "repair failing tests" --workspace . --repository-id <repository-id> --server-plan
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
.\scripts\local-agent.ps1 -Action m8-status
.\scripts\local-agent.ps1 -Action m8-doctor
.\scripts\local-agent.ps1 -Action m8-lifecycle-run -Transport auto
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

`learnbot m8 status` returns `learnbot.local-agent.m8-productization-status.v1`, a machine-readable productization readiness view for the Codex-like local experience. It consolidates guided setup, background lifecycle, doctor/log UX, Windows Service preview, Codex-like command availability, signed-installer readiness, and auto-update readiness while keeping M8 work execution, service command execution, installer signing, and auto-update disabled until those product paths are explicitly implemented. The report includes `nextCommands` so unpaired, paired-stopped, and paired-running states show the next safe commands without printing pairing-token secrets.

`learnbot m8 doctor` returns `learnbot.local-agent.m8-doctor.v1`, a read-only M8 diagnosis wrapper around productization status. It groups setup, lifecycle, runtime, logs, service preview, and distribution readiness into sections, repeats the safe next-command sequence, and keeps token printing, service execution, installer signing, and auto-update disabled.

`m8-lifecycle-run` returns `learnbot.local-agent.m8-lifecycle-run-result.v1`, a guarded internal-pilot lifecycle helper. It blocks when the agent is unpaired or has no approved workspace, skips `background-start` when the agent is already running, otherwise starts the background helper and then captures status and logs. It does not execute Windows Service commands, installer signing, auto-update, or token printing.

`learnbot login` returns `learnbot.local-agent.web-login-plan.v1`, a disabled web-login preview. It shows the intended `POST /api/auth/login` handoff, the server-side `POST /api/auth/cli-device-session/plan` bridge, and the next `learnbot session create-plan` discovery step without collecting a password, making a network call, persisting cookies, storing a web session, using the Local Agent pairing token, or printing token secrets. This keeps CLI web-user authentication separate from Local Agent pairing while the device-code or cookie session bridge is still being designed.

The backend `POST /api/auth/cli-device-session/plan` endpoint returns `learnbot.server.auth.cli-device-session-plan.v1`. It is public read-only so an unpaired CLI can discover the future browser/device-code path, but it still does not issue a device code, claim token, access token, refresh token, cookie, or stored session.

The backend `POST /api/auth/cli-device-session/create/plan` endpoint returns `learnbot.server.auth.cli-device-session-create-plan.v1`. It previews the future device-code creation response shape, including verification path, user-code format, expiry, and polling interval, while still not creating a device code, user code, token, cookie, or stored session.

`learnbot session status` returns `learnbot.local-agent.web-session-status.v1`, a read-only view of the future CLI web-session boundary. It reports whether `LEARNBOT_WEB_TOKEN` is present using only a fingerprint, whether that token can be used for `--server-plan`, the future local `web-session.json` path, a nested `learnbot.local-agent.web-session-artifact-validation.v1` validator preview, a nested `learnbot.local-agent.web-session-secret-provider-plan.v1` production secret-store preview, and the server-side `POST /api/auth/cli-device-session/claim/plan` claim/storage preview endpoint. The artifact validator also carries `learnbot.local-agent.web-session-artifact-crypto-preview-requirement.v1`, pointing to the required non-writing `artifact-production-crypto-preview --preview-only` proof before production stored-session loading can be enabled. It does not read or print token secrets, does not poll a claim, does not write a stored web session file, and requires encrypted local session artifact storage before that path can be enabled.

The backend `POST /api/auth/cli-device-session/claim/plan` endpoint returns `learnbot.server.auth.cli-device-session-claim-plan.v1`. It is also public read-only so the CLI can discover the future polling and local session artifact contract, but it does not poll, claim, issue tokens, write local files, accept Local Agent tokens, or persist cookies.

The backend `POST /api/auth/cli-device-session/claim-result/plan` endpoint returns `learnbot.server.auth.cli-device-session-claim-result-plan.v1`. It is public read-only and fixes the future browser-approved claim-result artifact-writer preflight: required claim-result fields, encrypted artifact fields, atomic write requirement, and plaintext-token serialization refusal. It does not accept tokens, write files, persist cookies, refresh tokens, or use the Local Agent pairing token.

`learnbot session plan`, `learnbot session create-plan`, `learnbot session claim-plan`, and `learnbot session claim-result-plan` return `learnbot.local-agent.web-session-plan-fetch-result.v1`. They try the public backend plan endpoints when reachable, never attach the Local Agent pairing token, and fall back to local static disabled plans when the server is offline or `--offline` is passed. The claim-plan fallback includes the future encrypted `web-session.json` artifact body preview using encrypted token placeholders only. The claim-result-plan fallback includes the disabled artifact-writer preflight using encrypted token placeholders only. The fetch result keeps device-code issuance, session claiming, token issuance, cookie persistence, local web-session artifact writes, request creation, and token printing disabled.

`learnbot session artifact-writer-preflight` returns `learnbot.local-agent.web-session-artifact-writer-preflight-result.v1`. It validates a simulated browser-approved claim-result boundary from metadata flags only: approved claim, access-token presence, refresh-token presence, parseable expiry fields, and plaintext-token serialization refusal. It does not accept token values, does not write `web-session.json`, does not probe/decrypt the OS secret store, does not use the Local Agent pairing token, and blocks explicit `--write` requests until the guarded writer is implemented.

`learnbot session artifact-writer-test-write` returns `learnbot.local-agent.web-session-artifact-writer-test-write-result.v1`. It requires explicit `--test-only` plus the same preflight metadata before it writes a local `web-session.json` artifact. The written artifact uses test-only AES-GCM encrypted placeholder token material and atomic replace, never serializes plaintext token material, and never prints secrets. The derived test-only key is not stored in the artifact, so this is a writer/format safety proof, not a usable browser-login session.

`learnbot session artifact-reader-test-validate` returns `learnbot.local-agent.web-session-artifact-reader-test-validate-result.v1`. With explicit `--test-only`, it reads only the test-only artifact provider, parses the artifact schema, verifies the encryption metadata, decrypts the placeholder token material, and returns fingerprints only. Production stored-session loading, DPAPI/OS secret-store decryption, token printing, and Local Agent token use remain disabled.

`learnbot session artifact-production-crypto-preview --preview-only` returns `learnbot.local-agent.web-session-production-artifact-crypto-preview-result.v1`. On Windows it uses the DPAPI current-user provider to encrypt and decrypt placeholder access/refresh token material in memory, then returns only fingerprints and artifact metadata. It does not write `web-session.json`, read stored sessions, load credentials, refresh tokens, use the Local Agent pairing token, or print token material.

`learnbot session artifact-production-writer-preview --preview-only` returns `learnbot.local-agent.web-session-production-artifact-writer-preview-result.v1`. It combines simulated approved claim-result metadata with the production crypto preview proof and prepares the exact future artifact body shape in memory using redacted encrypted-token placeholders. The result also includes `learnbot.local-agent.web-session-production-artifact-atomic-write-plan.v1`, a write-disabled atomic replace plan with the future session path and temp path pattern. It does not output encrypted token blobs, accept real token values, write `web-session.json`, read stored sessions, load credentials, refresh tokens, use the Local Agent pairing token, or print token material.

`learnbot session artifact-production-reader-preview --preview-only` returns `learnbot.local-agent.web-session-production-artifact-reader-preview-result.v1`. It models the future DPAPI-backed reader/decrypt path by checking the crypto proof and required artifact schema/fields, while keeping production file reads, JSON parsing, stored-token decryption, token loading, refresh, stored-session server-plan auth, Local Agent token use, and token printing disabled.

`learnbot session stored-session-auth-readiness` returns `learnbot.local-agent.web-session-stored-session-auth-readiness.v1`. It fixes the future stored-session auth preconditions for Codex-like `fix`/`review --server-plan`: browser-approved claim result, production artifact read/decrypt, access token, refresh token, expiry, refresh expiry, refresh eligibility, and server-plan auth. It still does not read `web-session.json`, parse JSON, decrypt stored tokens, load credentials, refresh tokens, create requests, mutate code, use the Local Agent pairing token, or print token material. `learnbot session status` and `learnbot session server-plan-readiness` embed this readiness report so users can see why environment-token fallback works while stored-session auth remains disabled.

`learnbot session secret-provider-plan` returns `learnbot.local-agent.web-session-secret-provider-plan.v1`. It pins the disabled production secret-store boundary for future Windows DPAPI/current-user or OS secret-store encryption. Automatic provider probing, production encryption/decryption, stored-session loading, plaintext token serialization, token printing, and Local Agent token use all remain disabled; the test-only AES-GCM provider is explicitly not accepted for production.

`learnbot session secret-provider-probe` returns `learnbot.local-agent.web-session-secret-provider-probe-result.v1`. On Windows it runs a DPAPI current-user protect/unprotect round trip with a non-secret sentinel only, proving the local primitive is callable without reading, writing, encrypting, decrypting, loading, or printing web-session token material. Production artifact encryption/decryption and stored-session loading remain disabled after the probe.

`learnbot session server-plan-readiness` returns `learnbot.local-agent.web-session-server-plan-readiness.v1`. It is a read-only bridge between CLI web-session state and Codex-like `fix`/`review --server-plan`: with `LEARNBOT_WEB_TOKEN` present it reports environment-token fallback readiness by fingerprint only; without it, it reports that stored web-session artifact loading is still disabled. It does not read stored token secrets, write a session artifact, use the Local Agent pairing token, create a server request, or enable mutation.

The nested `learnbot.local-agent.web-session-artifact-validation.v1` validator preview fixes the future encrypted `web-session.json` contract. It requires schema `learnbot.local-agent.web-session-artifact.v1`, encrypted access and refresh token fields, expiry fields, and creation time, but it does not read, parse, decrypt, or load token secrets yet.

`learnbot fix` and `learnbot review` return `learnbot.local-agent.codex-command-preview.v1`, the first Codex-like command preview contract. They validate pairing, an approved workspace, and a goal, then include a disabled `learnbot.local-agent.codex-server-submission-plan.v1` for the intended `POST /api/code-agent/loop/submission-plan` handoff. They also include `learnbot.local-agent.codex-one-cycle-preview.v1`, the user-perceived one-cycle contract: goal input, workspace discovery, file discovery, file reads, planning, patch dry-run, approval, apply/test, failure-log retry, final report, and RAG freshness update. That one-cycle preview embeds `learnbot.local-agent.codex-file-discovery-read-plan.v1`, a dry-run-only plan with candidate tools (`file.tree`, `file.search`, `file.read`, `git.status`), bounded path/query hints, planned discovery/read steps, and explicit no-read/no-request/no-mutation flags. The plan also exposes disabled `learnbot.local-agent.codex-read-only-request-envelope-preview.v1` envelopes for the actual Local Agent tool names `workspace.tree`, `workspace.search`, and `git.status`; they are non-claimable and keep request creation, enqueue, execution, file-content reads, mutation, approval, and token printing disabled. With `--observe-read-only`, the CLI returns `learnbot.local-agent.codex-read-only-observation.v1` and executes only `workspace.tree`, `workspace.search`, and `git.status` against a paired approved workspace; search snippets are redacted, then `learnbot.local-agent.codex-read-only-candidate-selection.v1` ranks matched paths into bounded `file.read` candidates while keeping `fileReadExecutionEnabled=false`, `fileContentRead=false`, `requestCreationEnabled=false`, and `mutationAllowed=false`. Adding `--read-selected` explicitly executes only those selected candidates through bounded `file.read`, returns `learnbot.local-agent.codex-selected-file-read.v1`, prepares `learnbot.local-agent.codex-patch-intent-preview.v1` with target files and dry-run intent metadata, exposes `learnbot.local-agent.codex-patch-proposal-preview.v1` as a placeholder proposal boundary, carries disabled `learnbot.local-agent.codex-diff-source-input-preview.v1` for future `local-model`, `server-planner`, `inline`, or `file` diff sources, carries disabled `learnbot.local-agent.codex-planner-diff-output-preview.v1` for future planner output envelopes, carries `learnbot.local-agent.codex-generated-diff-acceptance-preview.v1`, carries `learnbot.local-agent.codex-planner-diff-validation-handoff-preview.v1`, adds `learnbot.local-agent.codex-diff-source-validation-preview.v1`, carries `learnbot.local-agent.codex-patch-dry-run-request-envelope-preview.v1`, carries `learnbot.local-agent.codex-patch-dry-run-preflight-preview.v1`, and carries `learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1`. The diff-source input boundary accepts metadata such as `--diff-source`, `--diff-file`, or `--diff-text` presence but does not read files, accept inline diff bodies, or run planners. When `local-model` or `server-planner` is requested, the planner-output preview fixes the future output envelope shape with target files and unified diff requirement while keeping planner execution and normal diff generation disabled. A bounded in-memory generated diff can be accepted only with `--accept-generated-diff-preview --generated-diff ...`, only from the local-model/server-planner envelope path, and never from a diff file; if accepted, the handoff forwards it to validation preview and reports whether it parses and touches selected target files only. The validation boundary only prepares future `patch.apply` dry-run input when the accepted diff parses and touches selected target files only; the dry-run request envelope preview then fixes the future `patch.apply` request shape with `dryRunOnly=true`, `allowMutation=false`, `USER_LOCAL_AGENT`, and approval required before snapshot-writing dry-run, while request creation, enqueue, claim, snapshot creation, execution, mutation, tests, final-report publication, and partial reindex remain disabled. With `--run-nonwriting-preflight-preview`, a paired approved workspace can run only the existing non-writing context preflight from the accepted generated diff; it reads target files and validates hunks, but still creates no requests, snapshots, file writes, mutation, tests, final-report publication, or partial reindex. When that preflight passes, the approval handoff preview can reach `APPROVAL_HANDOFF_PREPARED` and carries repository id, workspace id, target files, request envelope status, and preflight status for the future snapshot-writing dry-run approval gate, while approval request creation, enqueue, claim, snapshot creation, execution, mutation, tests, final-report publication, and partial reindex remain disabled. Default CLI output still reports no diff source and keeps `diffGenerated=false`, `patchDryRunExecutionEnabled=false`, `requestCreationEnabled=false`, `mutationAllowed=false`, tests, final-report publication, and partial reindex disabled. The read-only discovery/read/plan stages can become ready when pairing, workspace, goal, and repository id are present, while patch/test/final-report/partial-reindex stages stay disabled until authenticated server handoff, approval, and release gates are real. The plan shows repository id, optional space id, instruction, max steps, follow-up runner endpoints, and blockers while keeping network calls, submission, request creation, mutation, test execution, rollback, final publication, partial reindex, and token printing disabled.

Adding `--server-plan` wraps the preview in `learnbot.local-agent.codex-server-plan-fetch-result.v1` and embeds `learnbot.local-agent.web-session-server-plan-readiness.v1`, the same `learnbot.local-agent.codex-one-cycle-preview.v1`, and `learnbot.local-agent.codex-read-only-server-bridge.v1`. The read-only bridge carries the same dry-run file discovery/read plan and ties the CLI cycle to the server loop/runner preview endpoints (`/api/code-agent/loop/runner/preview`, `/select-tool-preview`, and `/enqueue-selected-read-only`) while keeping request creation, file discovery execution, file reads, patch dry-run, mutation, token printing, and stored-session auth disabled. Without `--web-token` or `LEARNBOT_WEB_TOKEN`, it returns `BLOCKED_AUTH_REQUIRED` and does not make a network call. With an explicit web token, it calls the disabled server submission-plan endpoint using `Authorization: Bearer ...`; it never uses or prints the Local Agent pairing token, and the server endpoint still does not create a loop, conversation, Local Agent request, mutation, publication, or reindex job. Adding `--include-approval-handoff-preview` makes the CLI build the same read-only/generated-diff/non-writing-preflight preview used by `--observe-read-only` and include its `patchDryRunApprovalHandoffPreview` payload in the server submission body. The server submission-plan contract can now also accept that optional shape from a completed CLI one-cycle preflight and returns disabled `learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1` and `learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1` contracts that preserve repository, workspace, target-file, envelope, and preflight evidence for browser review without creating approval or release work.

The server-side `POST /api/code-agent/loop/submission-plan` endpoint returns `learnbot.server.code-agent.loop-submission-plan.v1` after normal web authentication and repository-space authorization, but it still does not create a loop, conversation, approval request, Local Agent request, mutation, publication, or reindex job.

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

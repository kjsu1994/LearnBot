# Local Agent WebSocket Transport Design

This document defines the next transport step for LearnBot Local Agent work. It is intentionally scoped to transport only. It must not change the safety boundary: user-owned file mutation, tests, rollback, and command execution remain disabled until their approval, snapshot, and rollback flows are implemented.

## Goals

- Keep the current durable polling protocol as the fallback path.
- Add an outbound WebSocket path from each Local Agent to the central server.
- Reuse the existing token auth, heartbeat, workspace summaries, tool request DTOs, and `local_agent_tool_executions` table.
- Reduce tool latency by pushing queued requests to connected agents instead of waiting for the next poll interval.
- Preserve auditability: every tool request and response must still be stored in `local_agent_tool_executions`.

## Non-Goals

- Do not introduce arbitrary shell execution.
- Do not enable `patch.apply`, `command.runAllowed`, `rollback.restore`, or test/build tools.
- Do not replace the web review/approval surface.
- Do not remove REST polling endpoints.
- Do not require the server to connect to a user PC by LAN IP or localhost.

## Current Boundary

The current implementation has these stable pieces:

- Pairing token: `POST /api/local-agents/pairing-token`.
- Token validation: `LocalAgentAuthService.authenticate`.
- Heartbeat: `POST /api/local-agents/heartbeat`.
- Durable queue claim: `GET /api/local-agents/tools/next`.
- Tool completion: `POST /api/local-agents/tools/{requestId}/response`.
- Queue/audit storage: `local_agent_tool_executions`.
- Agent status cache: `LocalAgentGatewayService`.

WebSocket must sit beside these pieces, not replace them.

## Endpoint

Use one endpoint:

```text
GET /api/local-agents/ws
Header: X-Local-Agent-Token: <local-agent-token>
```

The server authenticates the token during handshake and binds the connection to:

- `userId`
- `agentId`
- token id
- token expiry/revocation state

If the token is missing, expired, revoked, or the handshake cannot bind an agent id, reject the handshake. Polling endpoints continue to work independently.

## Message Envelope

All WebSocket messages use a shared envelope:

```json
{
  "type": "hello|heartbeat|tool.request|tool.response|tool.ack|error|ping|pong",
  "messageId": "uuid",
  "agentId": "uuid",
  "requestId": "uuid-or-null",
  "sentAt": "2026-06-28T00:00:00Z",
  "payload": {}
}
```

Rules:

- `messageId` is unique per sent message.
- `requestId` is present for tool request/response messages.
- `payload` uses the existing DTO shape where possible.
- Unknown message types are rejected with an `error` message and logged.

## Connection Flow

1. Local Agent opens `/api/local-agents/ws` with `X-Local-Agent-Token`.
2. Server authenticates the token and records the connection in a connection registry keyed by `(userId, agentId)`.
3. Agent sends `hello` with version, capabilities, and approved workspace summaries.
4. Server calls the same status registration path as heartbeat.
5. Server sends `tool.request` messages when work is available.
6. Agent handles the typed tool with the same handler currently used by polling.
7. Agent sends `tool.response`.
8. Server persists completion with the same `LocalAgentToolGatewayService.complete` path.

Heartbeat remains active over WebSocket. REST heartbeat may continue during the transition but should not be required once WebSocket is stable.

## Tool Dispatch

The WebSocket path must still create or read durable queue records before dispatch:

```text
service enqueue
-> local_agent_tool_executions row
-> if connected: push tool.request over WebSocket
-> if not connected: leave queued for polling/future reconnect
```

The request is not considered completed until the server persists `tool.response`.

If the WebSocket send fails:

- Keep the queue record.
- Mark or leave status so polling can claim it.
- Do not silently execute server-local tools.

## Fallback Rules

Polling remains the fallback in all cases:

- WebSocket feature flag disabled.
- Handshake fails.
- Connection drops.
- Server restart loses in-memory connections.
- Agent process restarts.
- Reverse proxy does not support upgrade.
- Message parsing fails.

The Local Agent should reconnect with backoff. During reconnect, it may continue using the existing polling loop. This keeps the internal pilot usable while the WebSocket transport matures.

## Feature Flag

Add a backend flag before implementing the endpoint:

```text
learnbot.local-agent.websocket-enabled=false
```

Default is `false` until the endpoint, Local Agent client, tests, and proxy configuration are verified.

Add a Local Agent config option later:

```json
{
  "transport": "auto"
}
```

`auto` means: try WebSocket, fall back to polling.

## Security Requirements

- Authenticate only with Local Agent tokens, never user browser cookies.
- Re-check token active state on handshake and periodically during heartbeat.
- Disconnect if the token becomes revoked or expired.
- Bind every message to the authenticated `(userId, agentId)`.
- Ignore any `userId` supplied by the agent payload.
- Reject tool responses whose request id/user id/agent id do not match the authenticated connection.
- Do not send raw token material back to the client.

## Proxy Requirements

For the Docker/nginx path, WebSocket support must be explicit:

```nginx
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
```

The active Docker stack wires this in `nginx/nginx.conf`; `frontend/nginx.conf` keeps the same rule for the legacy standalone frontend image path.

Keep REST polling available so proxy misconfiguration does not break Local Agent use.

## Implementation Slices

### Slice 1 - Server Skeleton

- Add WebSocket dependency/configuration.
- Add `/api/local-agents/ws` handler behind `learnbot.local-agent.websocket-enabled=false`.
- Authenticate `X-Local-Agent-Token`.
- Accept `hello`, `ping`, and `heartbeat`.
- Update `LocalAgentGatewayService` connection state.
- No tool dispatch yet.

Verification:

- Backend compile/tests pass.
- Handshake rejects missing/invalid token.
- REST polling still passes existing tests.

### Slice 2 - Agent Client Auto Transport

- Add Local Agent transport mode: `polling|websocket|auto`.
- In `auto`, try WebSocket and fall back to existing polling loop.
- Reuse existing local tool handler.
- Log transport transitions.

Verification:

- `agent start --once` still works with polling.
- WebSocket disabled falls back cleanly.
- `agent status/logs/token` unchanged.

### Slice 3 - Push Tool Request

- On enqueue, if the agent is connected, send a `tool.request`.
- Persist every request before sending.
- Persist `tool.response` through the same completion service.
- If send fails, keep polling fallback available.

Verification:

- A queued `file.read` succeeds over WebSocket.
- A queued `file.read` still succeeds through polling when WebSocket is disabled.
- Unsupported tools remain rejected by the Local Agent.

### Slice 4 - Runtime Hardening

- Reconnect backoff.
- Token revocation disconnect.
- Connection metrics/logs.
- Nginx/docker upgrade config.
- UI shows transport state: polling/websocket/stale.

Verification:

- Revoked token disconnects.
- Server restart returns to disconnected/stale and polling fallback works after agent reconnect.
- No server-local mutation fallback appears in the UI.

## Open Questions

- Whether Spring MVC WebSocket or WebFlux WebSocket should be used. The current app already has `spring-boot-starter-webflux`, but most controllers are MVC. Choose the smallest compatible server implementation during Slice 1.
- Whether multi-agent-per-user should be introduced now. Current `LocalAgentGatewayService` stores one agent snapshot per user; WebSocket can initially preserve that behavior and defer multi-agent routing.
- Whether transport state should be persisted. Initial implementation can keep connection state in memory because durable tool state is already in the database.

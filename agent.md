1\. Think Before Coding

Don't assume. Don't hide confusion. Surface tradeoffs.



Before implementing:



State your assumptions explicitly. If uncertain, ask.

If multiple interpretations exist, present them - don't pick silently.

If a simpler approach exists, say so. Push back when warranted.

If something is unclear, stop. Name what's confusing. Ask.

2\. Simplicity First

Minimum code that solves the problem. Nothing speculative.



No features beyond what was asked.

No abstractions for single-use code.

No "flexibility" or "configurability" that wasn't requested.

No error handling for impossible scenarios.

If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.



3\. Surgical Changes

Touch only what you must. Clean up only your own mess.



When editing existing code:



Don't "improve" adjacent code, comments, or formatting.

Don't refactor things that aren't broken.

Match existing style, even if you'd do it differently.

If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:



Remove imports/variables/functions that YOUR changes made unused.

Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

4. Product North Star

LearnBot is not just a chatbot. The long-term goal is a local RAG-powered agent platform similar to a local Codex for small internal teams.

The service should combine:

- A central LearnBot RAG server for shared code/document indexing, retrieval, model calls, permissions, conversations, diagnostics, and agent orchestration.
- A per-user Local Agent process for user workspace access, file reads/writes, allowlisted test execution, git status/diff, rollback snapshots, and local environment operations.

The central server must not be the only place where code changes happen. Server-local patching is useful as a prototype and for shared sandbox work, but the intended product direction is:

User chat goal -> central RAG search/planning -> tool decision -> user Local Agent tool execution -> observation returned to server -> LLM decides next step -> repeat until complete or approval is required.

LearnBot does not need to implement every part of this vision from scratch. Use proven open-source projects aggressively when they provide reliable value for crawling, parsing, indexing, retrieval, embeddings, vector search, agent orchestration, sandboxing, diff application, code intelligence, observability, or UI infrastructure. However, open source must serve the LearnBot product direction, not redefine it. Do not bend the service into an awkward shape just to fit a library or framework. Prefer integrating, wrapping, or replacing open-source components behind clear local interfaces so the product can keep its own quality, safety, privacy, and workflow requirements.

The normal user experience should remain web-first: users access the LearnBot conversation UI through the central website. For local file changes, each user should run a separate Local Agent on their own PC. The Local Agent should be packaged in stages:

- Early internal pilot: a PowerShell installer that downloads a lightweight agent executable, creates configuration, pairs it with the user's LearnBot account, registers approved workspace roots, and starts the agent.
- Practical internal use: the same agent executable registered as a Windows Service or equivalent background process with logs, restart behavior, configuration, and update support.
- Mature distribution: a signed MSI or EXE installer with uninstall, auto-update, proxy/internal-network support, and optional GUI settings.

The preferred network model is outbound connection from the Local Agent to the central server, usually over WebSocket or a similarly controlled channel. The central server should not depend on directly reaching a user's PC by LAN IP or localhost. The server plans and orchestrates; the Local Agent executes approved local tools and returns observations.

The Local Agent should be small, safe, and tool-oriented. It may be implemented with a deployment-friendly runtime such as Go or .NET even if the central server is Java/Spring. The important requirement is not language uniformity; it is reliable local execution, clear tool schemas, easy installation, low resource use, and strong safety boundaries.

Server-side direct file mutation must not become the default product path. Server-local apply, test, and rollback code may exist only as a prototype, shared sandbox, migration bridge, or admin/debug capability. User-owned repository changes should ultimately be executed by the user's Local Agent. Keep planning, retrieval, validation, approval, session history, and UI review flows on the central service where useful, but move side-effectful local actions such as file writes, test commands, git operations, and rollback restoration behind the USER_LOCAL_AGENT execution target.

The Local Agent should also provide a simple CLI experience similar in spirit to running `codex` from PowerShell. A user should eventually be able to install LearnBot locally and use a `learnbot` command to manage login, pairing, agent start/stop/status, workspace registration, diagnostics, logs, opening the web UI, and optionally lightweight CLI chat or fix/review commands. The web UI remains the main conversation and review surface, but the CLI should make local setup and day-to-day agent control feel natural for developers.

Design implications:

- Separate tool orchestration from tool execution location.
- Support execution targets such as SERVER_LOCAL and USER_LOCAL_AGENT.
- Keep read-only tools low friction, but require explicit approval for write, test, rollback, git, and other side-effectful tools.
- Never expose arbitrary shell execution from the Local Agent. Use typed tool schemas and command allowlists.
- A Local Agent may access only user-approved workspace roots.
- Every agent action should be tied to a session id, evidence, tool input, tool output, approval state, and rollback state where applicable.
- After file changes, the RAG index must eventually be synchronized through partial reindexing or another explicit freshness mechanism.
- In agent loop automation, do not fake intelligence with server-authored content shortcuts. The LLM must decide the target, diagnosis, edit intent, and replacement content from observed context. The server may select execution targets, bound context, validate schemas, materialize LLM-authored structured edits into diffs, enforce approval gates, run safety checks, and report blockers. The server must not hardcode task-specific code, prose, HTML/CSS, file names, language-specific patches, or canned "fix" content to make a demo pass. If the LLM output is missing, malformed, truncated, unsafe, or insufficient, improve the loop structure, context packaging, retry strategy, validation, or user-facing blocker report instead of silently substituting server-written changes.

Quality remains the core product value: prefer a slower but grounded and recoverable workflow over a fast workflow that produces weak answers or unsafe code changes.
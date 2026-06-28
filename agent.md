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

Design implications:

- Separate tool orchestration from tool execution location.
- Support execution targets such as SERVER_LOCAL and USER_LOCAL_AGENT.
- Keep read-only tools low friction, but require explicit approval for write, test, rollback, git, and other side-effectful tools.
- Never expose arbitrary shell execution from the Local Agent. Use typed tool schemas and command allowlists.
- A Local Agent may access only user-approved workspace roots.
- Every agent action should be tied to a session id, evidence, tool input, tool output, approval state, and rollback state where applicable.
- After file changes, the RAG index must eventually be synchronized through partial reindexing or another explicit freshness mechanism.

Quality remains the core product value: prefer a slower but grounded and recoverable workflow over a fast workflow that produces weak answers or unsafe code changes.

서비스의 궁극적인 목표는 최상의 답변품질을 보유한 RAG기반 agent 서비스야(로컬 codex를 만들고싶어) (속도가 빠르면 좋겠지만 속도만 빠른 쓰레기를 뱉는 서비스는 내가원하는방향이 아니야)

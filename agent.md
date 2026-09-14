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

LearnBot is a web-first Code and Document RAG knowledge service. Preserve indexing, retrieval, Code Intelligence IR, evidence-grounded answers, conversations, authentication and server deployment. Agent execution, local device enrollment, patch generation/application, test execution and rollback are retired. Iterative retrieval and answer verification are RAG functions and remain supported.

5. Generic RAG Quality and Anti-Overfitting

Code RAG is a general repository-question-answering system. Production behavior must remain independent of a benchmark fixture, one repository, one question, one expected answer, one programming language, or one framework.

Production retrieval, graph expansion, evidence ranking, context selection, answer generation, and validation must not contain:

- fixture or case IDs;
- benchmark question text or expected-answer text;
- repository or project names added to make a quality case pass;
- expected file paths, class names, method names, symbols, routes, or citations from a development case;
- language- or framework-specific keyword lists, patches, score bonuses, or fallback answers introduced only for a tested example;
- server-authored claims that substitute for missing retrieval evidence or missing model reasoning.

Evaluation fixtures may contain questions, expected files, symbols, claims, and answer modes because they are test specifications. Production code must never read, import, reference, or reproduce those expectations to alter an answer. A passing test is evidence only when the production behavior is derived from repository data and general contracts.

Allowed improvements must be reusable and evidence-derived, such as:

- normalized structural identities and language-neutral graph relations;
- repository-observed endpoint, symbol, path, line, chunk, call, reference, and provenance metadata;
- bounded retrieval and context policies based on evidence authority, relevance, coverage, and diversity;
- typed operation contracts, executable handles, validation, retry, and explicit insufficiency behavior;
- generic prompt and evaluator corrections that apply to unseen repositories and equivalent question types.

Do not keep a generic-looking rule merely because it contains no literal fixture name. If it promotes unrelated evidence, consumes bounded context slots, weakens another cohort, or improves only the tuned cases, treat it as overfitting or a regression and remove or redesign it.

Use development sets to find and diagnose common failure classes, not to prove generality. After the development gate passes, freeze production retrieval, ranking, prompts, validation, and evaluator criteria, then verify them on previously unused Java and C# repositories with non-duplicate holdout questions. If holdout results are used to tune production behavior, that repository becomes development data and a new untouched holdout is required.

Report fresh measured results honestly. Do not claim an accuracy target, regression recovery, or generic quality improvement from unit tests, stale captures, offline rescoring alone, or an unmeasured deployment.

import assert from 'node:assert/strict';
import { buildMutationRagFreshnessGateView } from './mutationRagFreshnessGate.js';

const view = buildMutationRagFreshnessGateView({
  schema: 'learnbot.local-agent.mutation-rag-freshness-gate.v1',
  status: 'REFUSED_RAG_FRESHNESS_DISABLED',
  rollbackFallbackReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  ragFreshnessPolicy: 'DISABLED_AUDIT_ONLY',
  sourceRollbackFallbackGateStatus: 'REFUSED_ROLLBACK_FALLBACK_DISABLED',
  sourceRequestId: 'request-123',
  releaseAttemptId: 'attempt-1234567890',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  expectedResultCount: 4,
  completedResultCount: 0,
  acceptedResultCount: 0,
  rejectedResultCount: 0,
  intakePersistedResultCount: 0,
  ragFreshnessUpdateEnabled: false,
  ragFreshnessUpdateInvocationEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  rollbackFallbackExecutionEnabled: false,
  intakePersistenceEnabled: false,
  acceptedObservationPersistenceEnabled: false,
  postExecutionObservationEnabled: false,
  completedResultPersistenceEnabled: false,
  observationAcceptanceEnabled: false,
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  executionEnabled: false,
  writeHelperEnabled: false,
  claimable: false,
  mutationAllowed: false,
  applyEnabled: false,
  testEnabled: false,
  rollbackRestoreEnabled: false,
  policyChecks: [
    {
      key: 'mutationRollbackFallbackGate',
      status: 'REFUSED_ROLLBACK_FALLBACK_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'A disabled rollback fallback gate must refuse rollback execution before RAG freshness can be considered.',
    },
    {
      key: 'ragFreshnessPolicy',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'RAG freshness updates are disabled.',
    },
  ],
  blockingKeys: [
    'ragFreshnessPolicy',
    'ragFreshnessUpdate',
    'resultAggregation',
    'publication',
    'finalAnswerGeneration',
    'ragFreshnessUpdateEnabled',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation RAG freshness is explicitly refused: no freshness update, aggregation, publication, or final answer is enabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation RAG freshness gate: REFUSED_RAG_FRESHNESS_DISABLED / learnbot.local-agent.mutation-rag-freshness-gate.v1 / rollback fallback ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / rollback fallback status REFUSED_ROLLBACK_FALLBACK_DISABLED'
);
assert.equal(
  view.idsText,
  'mutation RAG freshness ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation RAG freshness counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation RAG freshness disabled: rag freshness false / freshness invocation false / result aggregation false / publication false / final answer false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'RAG freshness policy mutationRollbackFallbackGate: REFUSED_ROLLBACK_FALLBACK_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / A disabled rollback fallback gate must refuse rollback execution before RAG freshness can be considered.',
  'RAG freshness policy ragFreshnessPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / RAG freshness updates are disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation RAG freshness blocking keys: ragFreshnessPolicy, ragFreshnessUpdate, resultAggregation, publication, finalAnswerGeneration, ragFreshnessUpdateEnabled, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const hidden = buildMutationRagFreshnessGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

import assert from 'node:assert/strict';
import { buildMutationResultAggregationGateView } from './mutationResultAggregationGate.js';

const view = buildMutationResultAggregationGateView({
  schema: 'learnbot.local-agent.mutation-result-aggregation-gate.v1',
  status: 'REFUSED_RESULT_AGGREGATION_DISABLED',
  ragFreshnessReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  resultAggregationPolicy: 'DISABLED_AUDIT_ONLY',
  sourceRagFreshnessGateStatus: 'REFUSED_RAG_FRESHNESS_DISABLED',
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
  mutationResultAggregationEnabled: false,
  resultAggregationInvocationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  ragFreshnessUpdateEnabled: false,
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
      key: 'mutationRagFreshnessGate',
      status: 'REFUSED_RAG_FRESHNESS_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'A disabled RAG freshness gate must refuse freshness updates before result aggregation can be considered.',
    },
    {
      key: 'resultAggregationPolicy',
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
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Mutation result aggregation is disabled.',
    },
  ],
  blockingKeys: [
    'resultAggregationPolicy',
    'resultAggregation',
    'publication',
    'finalAnswerGeneration',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation result aggregation is explicitly refused: no aggregation, publication, or final answer is enabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation result aggregation gate: REFUSED_RESULT_AGGREGATION_DISABLED / learnbot.local-agent.mutation-result-aggregation-gate.v1 / RAG freshness ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / RAG freshness status REFUSED_RAG_FRESHNESS_DISABLED'
);
assert.equal(
  view.idsText,
  'mutation result aggregation ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation result aggregation counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation result aggregation disabled: result aggregation false / aggregation invocation false / publication false / final answer false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'result aggregation policy mutationRagFreshnessGate: REFUSED_RAG_FRESHNESS_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / result aggregation false / publication false / final answer false / A disabled RAG freshness gate must refuse freshness updates before result aggregation can be considered.',
  'result aggregation policy resultAggregationPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / result aggregation false / publication false / final answer false / Mutation result aggregation is disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation result aggregation blocking keys: resultAggregationPolicy, resultAggregation, publication, finalAnswerGeneration, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const hidden = buildMutationResultAggregationGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

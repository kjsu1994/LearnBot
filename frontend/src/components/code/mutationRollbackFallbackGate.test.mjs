import assert from 'node:assert/strict';
import { buildMutationRollbackFallbackGateView } from './mutationRollbackFallbackGate.js';

const view = buildMutationRollbackFallbackGateView({
  schema: 'learnbot.local-agent.mutation-rollback-fallback-gate.v1',
  status: 'REFUSED_ROLLBACK_FALLBACK_DISABLED',
  intakePersistenceReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  rollbackFallbackPolicy: 'DISABLED_AUDIT_ONLY',
  sourceResultIntakePersistenceGateStatus: 'REFUSED_INTAKE_PERSISTENCE_DISABLED',
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
  rollbackFallbackExecutionEnabled: false,
  rollbackFallbackInvocationEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
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
      key: 'mutationResultIntakePersistenceGate',
      status: 'REFUSED_INTAKE_PERSISTENCE_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'A disabled intake persistence gate must refuse accepted-observation persistence before rollback fallback can be considered.',
    },
    {
      key: 'rollbackFallbackPolicy',
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
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Rollback fallback execution is disabled.',
    },
    {
      key: 'rollbackFallbackExecution',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'No rollback fallback may execute while the rollback fallback gate is disabled.',
    },
    {
      key: 'ragFreshnessUpdate',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'RAG freshness updates remain disabled until rollback fallback outcomes are modeled.',
    },
    {
      key: 'resultAggregation',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Mutation result aggregation remains disabled until rollback fallback outcomes are modeled.',
    },
    {
      key: 'publication',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Final answer publication remains disabled until rollback fallback outcomes are modeled.',
    },
    {
      key: 'finalAnswerGeneration',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Final-answer generation remains disabled until rollback fallback outcomes are modeled.',
    },
  ],
  blockingKeys: [
    'rollbackFallbackPolicy',
    'rollbackFallbackExecution',
    'ragFreshnessUpdate',
    'resultAggregation',
    'publication',
    'finalAnswerGeneration',
    'rollbackFallbackExecutionEnabled',
    'ragFreshnessUpdateEnabled',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation rollback fallback is explicitly refused: no rollback fallback execution, RAG freshness update, aggregation, publication, or final answer is enabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation rollback fallback gate: REFUSED_ROLLBACK_FALLBACK_DISABLED / learnbot.local-agent.mutation-rollback-fallback-gate.v1 / intake persistence ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / intake status REFUSED_INTAKE_PERSISTENCE_DISABLED'
);
assert.equal(
  view.idsText,
  'mutation rollback fallback ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation rollback fallback counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation rollback fallback disabled: rollback fallback false / rollback invocation false / rag freshness false / result aggregation false / publication false / final answer false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'rollback fallback policy mutationResultIntakePersistenceGate: REFUSED_INTAKE_PERSISTENCE_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / A disabled intake persistence gate must refuse accepted-observation persistence before rollback fallback can be considered.',
  'rollback fallback policy rollbackFallbackPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Rollback fallback execution is disabled.',
  'rollback fallback policy rollbackFallbackExecution: DISABLED / passed false / blocking true / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / No rollback fallback may execute while the rollback fallback gate is disabled.',
  'rollback fallback policy ragFreshnessUpdate: DISABLED / passed false / blocking true / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / RAG freshness updates remain disabled until rollback fallback outcomes are modeled.',
  'rollback fallback policy resultAggregation: DISABLED / passed false / blocking true / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Mutation result aggregation remains disabled until rollback fallback outcomes are modeled.',
  'rollback fallback policy publication: DISABLED / passed false / blocking true / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Final answer publication remains disabled until rollback fallback outcomes are modeled.',
  'rollback fallback policy finalAnswerGeneration: DISABLED / passed false / blocking true / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Final-answer generation remains disabled until rollback fallback outcomes are modeled.',
]);
assert.equal(
  view.blockingText,
  'mutation rollback fallback blocking keys: rollbackFallbackPolicy, rollbackFallbackExecution, ragFreshnessUpdate, resultAggregation, publication, finalAnswerGeneration, rollbackFallbackExecutionEnabled, ragFreshnessUpdateEnabled, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const blocked = buildMutationRollbackFallbackGateView({
  status: 'BLOCKED_ROLLBACK_FALLBACK_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blocked.headerText, 'mutation rollback fallback gate: BLOCKED_ROLLBACK_FALLBACK_DISABLED');
assert.equal(blocked.countsText, 'mutation rollback fallback counts: expected 0 / intake persisted 0');
assert.deepEqual(blocked.policyLines, [
  'rollback fallback policy unknownPolicy: UNKNOWN',
]);

const hidden = buildMutationRollbackFallbackGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

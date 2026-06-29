import assert from 'node:assert/strict';
import { buildMutationResultIntakePersistenceGateView } from './mutationResultIntakePersistenceGate.js';

const view = buildMutationResultIntakePersistenceGateView({
  schema: 'learnbot.local-agent.mutation-result-intake-persistence-gate.v1',
  status: 'REFUSED_INTAKE_PERSISTENCE_DISABLED',
  observationAcceptanceReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  intakePersistencePolicy: 'DISABLED_AUDIT_ONLY',
  sourceObservationAcceptanceGateStatus: 'REFUSED_OBSERVATION_ACCEPTANCE_DISABLED',
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
  intakePersistenceEnabled: false,
  acceptedObservationPersistenceEnabled: false,
  rollbackFallbackExecutionEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
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
      key: 'mutationObservationAcceptanceGate',
      status: 'REFUSED_OBSERVATION_ACCEPTANCE_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'A disabled observation acceptance gate must refuse accepted observation intake before persistence can be considered.',
    },
    {
      key: 'intakePersistencePolicy',
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
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Accepted mutation result intake persistence is disabled.',
    },
    {
      key: 'acceptedObservationPersistence',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Accepted mutation observations must not be persisted while intake persistence is disabled.',
    },
    {
      key: 'rollbackFallbackExecution',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Rollback fallback execution remains disabled until accepted mutation observations are persisted.',
    },
    {
      key: 'ragFreshnessUpdate',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'RAG freshness updates remain disabled until accepted mutation observations are persisted.',
    },
    {
      key: 'resultAggregation',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Mutation result aggregation remains disabled until accepted mutation observations are persisted.',
    },
    {
      key: 'publication',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Final answer publication remains disabled until accepted mutation observations are persisted.',
    },
    {
      key: 'finalAnswerGeneration',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      intakePersistenceEnabled: false,
      acceptedObservationPersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Final-answer generation remains disabled until accepted mutation observations are persisted.',
    },
  ],
  blockingKeys: [
    'intakePersistencePolicy',
    'acceptedObservationPersistence',
    'rollbackFallbackExecution',
    'ragFreshnessUpdate',
    'resultAggregation',
    'publication',
    'finalAnswerGeneration',
    'intakePersistenceEnabled',
    'acceptedObservationPersistenceEnabled',
    'rollbackFallbackExecutionEnabled',
    'ragFreshnessUpdateEnabled',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation result intake persistence is explicitly refused: no accepted observation persistence, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation result intake persistence gate: REFUSED_INTAKE_PERSISTENCE_DISABLED / learnbot.local-agent.mutation-result-intake-persistence-gate.v1 / observation acceptance ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / acceptance status REFUSED_OBSERVATION_ACCEPTANCE_DISABLED'
);
assert.equal(
  view.idsText,
  'mutation result intake persistence ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation result intake persistence counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation result intake persistence disabled: intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'result intake persistence policy mutationObservationAcceptanceGate: REFUSED_OBSERVATION_ACCEPTANCE_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / A disabled observation acceptance gate must refuse accepted observation intake before persistence can be considered.',
  'result intake persistence policy intakePersistencePolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Accepted mutation result intake persistence is disabled.',
  'result intake persistence policy acceptedObservationPersistence: DISABLED / passed false / blocking true / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Accepted mutation observations must not be persisted while intake persistence is disabled.',
  'result intake persistence policy rollbackFallbackExecution: DISABLED / passed false / blocking true / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Rollback fallback execution remains disabled until accepted mutation observations are persisted.',
  'result intake persistence policy ragFreshnessUpdate: DISABLED / passed false / blocking true / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / RAG freshness updates remain disabled until accepted mutation observations are persisted.',
  'result intake persistence policy resultAggregation: DISABLED / passed false / blocking true / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Mutation result aggregation remains disabled until accepted mutation observations are persisted.',
  'result intake persistence policy publication: DISABLED / passed false / blocking true / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Final answer publication remains disabled until accepted mutation observations are persisted.',
  'result intake persistence policy finalAnswerGeneration: DISABLED / passed false / blocking true / intake persistence false / accepted observation persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Final-answer generation remains disabled until accepted mutation observations are persisted.',
]);
assert.equal(
  view.blockingText,
  'mutation result intake persistence blocking keys: intakePersistencePolicy, acceptedObservationPersistence, rollbackFallbackExecution, ragFreshnessUpdate, resultAggregation, publication, finalAnswerGeneration, intakePersistenceEnabled, acceptedObservationPersistenceEnabled, rollbackFallbackExecutionEnabled, ragFreshnessUpdateEnabled, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const blocked = buildMutationResultIntakePersistenceGateView({
  status: 'BLOCKED_INTAKE_PERSISTENCE_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blocked.headerText, 'mutation result intake persistence gate: BLOCKED_INTAKE_PERSISTENCE_DISABLED');
assert.equal(blocked.countsText, 'mutation result intake persistence counts: expected 0 / intake persisted 0');
assert.deepEqual(blocked.policyLines, [
  'result intake persistence policy unknownPolicy: UNKNOWN',
]);

const hidden = buildMutationResultIntakePersistenceGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

import assert from 'node:assert/strict';
import { buildMutationObservationAcceptanceGateView } from './mutationObservationAcceptanceGate.js';

const refusedGate = {
  schema: 'learnbot.local-agent.mutation-observation-acceptance-gate.v1',
  status: 'REFUSED_OBSERVATION_ACCEPTANCE_DISABLED',
  postExecutionObservationReady: true,
  prerequisitesPassed: true,
  releaseAttemptId: '99aabbcc-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  acceptancePolicy: 'DISABLED_AUDIT_ONLY',
  sourcePostExecutionObservationGateStatus: 'REFUSED_POST_EXECUTION_OBSERVATION_DISABLED',
  expectedResultCount: 4,
  completedResultCount: 0,
  acceptedResultCount: 0,
  rejectedResultCount: 0,
  intakePersistedResultCount: 0,
  observationAcceptanceEnabled: false,
  intakePersistenceEnabled: false,
  rollbackFallbackExecutionEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  postExecutionObservationEnabled: false,
  completedResultPersistenceEnabled: false,
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
      key: 'mutationPostExecutionObservationGate',
      status: 'REFUSED_POST_EXECUTION_OBSERVATION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      observationAcceptanceEnabled: false,
      intakePersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'A disabled post-execution observation gate must refuse completed-result capture before acceptance can be considered.',
    },
    {
      key: 'intakePersistence',
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
      observationAcceptanceEnabled: false,
      intakePersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Accepted mutation observation intake must not be persisted while acceptance is disabled.',
    },
    {
      key: 'finalAnswerGeneration',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      observationAcceptanceEnabled: false,
      intakePersistenceEnabled: false,
      rollbackFallbackExecutionEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Final-answer generation remains disabled until accepted observations are persisted.',
    },
  ],
  blockingKeys: [
    'acceptancePolicy',
    'intakePersistence',
    'rollbackFallbackExecution',
    'ragFreshnessUpdate',
    'resultAggregation',
    'publication',
    'finalAnswerGeneration',
    'observationAcceptanceEnabled',
    'intakePersistenceEnabled',
    'rollbackFallbackExecutionEnabled',
    'ragFreshnessUpdateEnabled',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation observation acceptance is explicitly refused: no accepted observation intake, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled.',
};

const refusedView = buildMutationObservationAcceptanceGateView(refusedGate);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation observation acceptance gate: REFUSED_OBSERVATION_ACCEPTANCE_DISABLED / learnbot.local-agent.mutation-observation-acceptance-gate.v1 / post-execution observation ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / observation status REFUSED_POST_EXECUTION_OBSERVATION_DISABLED'
);
assert.equal(
  refusedView.idsText,
  'mutation observation acceptance ids: source request-1 / release 99aabbcc / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.countsText,
  'mutation observation acceptance counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  refusedView.disabledText,
  'mutation observation acceptance disabled: acceptance false / intake persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / post-execution observation false / result persistence false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(refusedView.policyLines, [
  'observation acceptance policy mutationPostExecutionObservationGate: REFUSED_POST_EXECUTION_OBSERVATION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / acceptance false / intake persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / A disabled post-execution observation gate must refuse completed-result capture before acceptance can be considered.',
  'observation acceptance policy intakePersistence: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / acceptance false / intake persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Accepted mutation observation intake must not be persisted while acceptance is disabled.',
  'observation acceptance policy finalAnswerGeneration: DISABLED / passed false / blocking true / acceptance false / intake persistence false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Final-answer generation remains disabled until accepted observations are persisted.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation observation acceptance blocking keys: acceptancePolicy, intakePersistence, rollbackFallbackExecution, ragFreshnessUpdate, resultAggregation, publication, finalAnswerGeneration, observationAcceptanceEnabled, intakePersistenceEnabled, rollbackFallbackExecutionEnabled, ragFreshnessUpdateEnabled, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.equal(refusedView.message, refusedGate.message);

const blockedView = buildMutationObservationAcceptanceGateView({
  status: 'BLOCKED_OBSERVATION_ACCEPTANCE_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation observation acceptance gate: BLOCKED_OBSERVATION_ACCEPTANCE_DISABLED');
assert.equal(blockedView.countsText, 'mutation observation acceptance counts: expected 0 / intake persisted 0');
assert.deepEqual(blockedView.policyLines, [
  'observation acceptance policy unknownPolicy: UNKNOWN',
]);

const hiddenView = buildMutationObservationAcceptanceGateView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.policyLines, []);

console.log('mutationObservationAcceptanceGate view tests passed');

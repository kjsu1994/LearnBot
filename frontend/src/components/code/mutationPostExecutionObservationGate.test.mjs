import assert from 'node:assert/strict';
import { buildMutationPostExecutionObservationGateView } from './mutationPostExecutionObservationGate.js';

const refusedGate = {
  schema: 'learnbot.local-agent.mutation-post-execution-observation-gate.v1',
  status: 'REFUSED_POST_EXECUTION_OBSERVATION_DISABLED',
  executionGateReady: true,
  prerequisitesPassed: true,
  releaseAttemptId: '99aabbcc-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  observationPolicy: 'DISABLED_AUDIT_ONLY',
  sourceExecutionGateStatus: 'REFUSED_EXECUTION_DISABLED',
  expectedResultCount: 4,
  completedResultCount: 0,
  acceptedResultCount: 0,
  rejectedResultCount: 0,
  postExecutionObservationEnabled: false,
  completedResultPersistenceEnabled: false,
  rollbackFallbackExecutionEnabled: false,
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
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  policyChecks: [
    {
      key: 'mutationExecutionGate',
      status: 'REFUSED_EXECUTION_DISABLED',
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
      message: 'A disabled execution gate must refuse Local Agent execution before post-execution observations can be considered.',
    },
    {
      key: 'completedResultPersistence',
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
      message: 'Completed mutation results must not be persisted while observation capture is disabled.',
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
      message: 'Rollback fallback execution remains disabled until mutation observations are accepted.',
    },
  ],
  blockingKeys: [
    'observationPolicy',
    'completedResultPersistence',
    'rollbackFallbackExecution',
    'ragFreshnessUpdate',
    'resultAggregation',
    'publication',
    'postExecutionObservationEnabled',
    'completedResultPersistenceEnabled',
    'rollbackFallbackExecutionEnabled',
    'ragFreshnessUpdateEnabled',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent post-execution mutation observation is explicitly refused: no completed-result capture, rollback fallback, RAG freshness update, aggregation, publication, or final answer is enabled.',
};

const refusedView = buildMutationPostExecutionObservationGateView(refusedGate);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation post-execution observation gate: REFUSED_POST_EXECUTION_OBSERVATION_DISABLED / learnbot.local-agent.mutation-post-execution-observation-gate.v1 / execution gate ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / execution status REFUSED_EXECUTION_DISABLED'
);
assert.equal(
  refusedView.idsText,
  'mutation post-execution observation ids: source request-1 / release 99aabbcc / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.countsText,
  'mutation post-execution observation counts: expected 4 / completed 0 / accepted 0 / rejected 0'
);
assert.equal(
  refusedView.disabledText,
  'mutation post-execution observation disabled: observation false / result persistence false / rollback fallback false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.deepEqual(refusedView.policyLines, [
  'post-execution observation policy mutationExecutionGate: REFUSED_EXECUTION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / A disabled execution gate must refuse Local Agent execution before post-execution observations can be considered.',
  'post-execution observation policy completedResultPersistence: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Completed mutation results must not be persisted while observation capture is disabled.',
  'post-execution observation policy rollbackFallbackExecution: DISABLED / passed false / blocking true / rollback fallback false / rag freshness false / result aggregation false / publication false / final answer false / Rollback fallback execution remains disabled until mutation observations are accepted.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation post-execution observation blocking keys: observationPolicy, completedResultPersistence, rollbackFallbackExecution, ragFreshnessUpdate, resultAggregation, publication, postExecutionObservationEnabled, completedResultPersistenceEnabled, rollbackFallbackExecutionEnabled, ragFreshnessUpdateEnabled, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.equal(refusedView.message, refusedGate.message);

const blockedView = buildMutationPostExecutionObservationGateView({
  status: 'BLOCKED_POST_EXECUTION_OBSERVATION_DISABLED',
  expectedResultCount: 0,
  rejectedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation post-execution observation gate: BLOCKED_POST_EXECUTION_OBSERVATION_DISABLED');
assert.equal(blockedView.countsText, 'mutation post-execution observation counts: expected 0 / rejected 0');
assert.deepEqual(blockedView.policyLines, [
  'post-execution observation policy unknownPolicy: UNKNOWN',
]);

const hiddenView = buildMutationPostExecutionObservationGateView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.policyLines, []);

console.log('mutationPostExecutionObservationGate view tests passed');

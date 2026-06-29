import assert from 'node:assert/strict';
import { buildMutationPublicationGateView } from './mutationPublicationGate.js';

const view = buildMutationPublicationGateView({
  schema: 'learnbot.local-agent.mutation-publication-gate.v1',
  status: 'REFUSED_PUBLICATION_DISABLED',
  resultAggregationReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  publicationPolicy: 'DISABLED_AUDIT_ONLY',
  sourceResultAggregationGateStatus: 'REFUSED_RESULT_AGGREGATION_DISABLED',
  sourceResultAggregationGateSchema: 'learnbot.local-agent.mutation-result-aggregation-gate.v1',
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
  publicationEnabled: false,
  publicationInvocationEnabled: false,
  finalAnswerGenerationEnabled: false,
  mutationResultAggregationEnabled: false,
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
      key: 'mutationResultAggregationGate',
      status: 'REFUSED_RESULT_AGGREGATION_DISABLED',
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
      message: 'A disabled result aggregation gate must refuse aggregation before publication can be considered.',
    },
    {
      key: 'publicationPolicy',
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
      message: 'Mutation publication is disabled.',
    },
    {
      key: 'publication',
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
      message: 'No mutation publication may run while publication is disabled.',
    },
    {
      key: 'finalAnswerGeneration',
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
      message: 'Final-answer generation remains disabled until publication state is modeled.',
    },
  ],
  blockingKeys: [
    'publicationPolicy',
    'publication',
    'finalAnswerGeneration',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation publication is explicitly refused: no publication or final answer is enabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation publication gate: REFUSED_PUBLICATION_DISABLED / learnbot.local-agent.mutation-publication-gate.v1 / result aggregation ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / result aggregation status REFUSED_RESULT_AGGREGATION_DISABLED / learnbot.local-agent.mutation-result-aggregation-gate.v1'
);
assert.equal(
  view.idsText,
  'mutation publication ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation publication counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation publication disabled: publication false / publication invocation false / final answer false / result aggregation false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'publication policy mutationResultAggregationGate: REFUSED_RESULT_AGGREGATION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / result aggregation false / publication false / final answer false / A disabled result aggregation gate must refuse aggregation before publication can be considered.',
  'publication policy publicationPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / result aggregation false / publication false / final answer false / Mutation publication is disabled.',
  'publication policy publication: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / result aggregation false / publication false / final answer false / No mutation publication may run while publication is disabled.',
  'publication policy finalAnswerGeneration: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / result aggregation false / publication false / final answer false / Final-answer generation remains disabled until publication state is modeled.',
]);
assert.equal(
  view.blockingText,
  'mutation publication blocking keys: publicationPolicy, publication, finalAnswerGeneration, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const blocked = buildMutationPublicationGateView({
  status: 'BLOCKED_PUBLICATION_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blocked.headerText, 'mutation publication gate: BLOCKED_PUBLICATION_DISABLED');
assert.equal(blocked.countsText, 'mutation publication counts: expected 0 / intake persisted 0');
assert.deepEqual(blocked.policyLines, [
  'publication policy unknownPolicy: UNKNOWN',
]);

const hidden = buildMutationPublicationGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

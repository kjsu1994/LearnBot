import assert from 'node:assert/strict';
import { buildMutationFinalAnswerCompletionGateView } from './mutationFinalAnswerCompletionGate.js';

const view = buildMutationFinalAnswerCompletionGateView({
  schema: 'learnbot.local-agent.mutation-final-answer-completion-gate.v1',
  status: 'REFUSED_FINAL_ANSWER_COMPLETION_DISABLED',
  finalAnswerGenerationReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  finalAnswerCompletionPolicy: 'DISABLED_AUDIT_ONLY',
  finalAnswerDeliveryEnabled: false,
  sourceFinalAnswerGenerationGateStatus: 'REFUSED_FINAL_ANSWER_GENERATION_DISABLED',
  sourceFinalAnswerGenerationGateSchema: 'learnbot.local-agent.mutation-final-answer-generation-gate.v1',
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
  finalAnswerCompletionEnabled: false,
  finalAnswerCompletionInvocationEnabled: false,
  finalAnswerGenerationEnabled: false,
  publicationEnabled: false,
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
      key: 'mutationFinalAnswerGenerationGate',
      status: 'REFUSED_FINAL_ANSWER_GENERATION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      message: 'A disabled final-answer generation gate must refuse generation before final-answer completion can be considered.',
    },
    {
      key: 'finalAnswerCompletionPolicy',
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
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      message: 'Mutation final-answer completion and delivery are disabled.',
    },
  ],
  blockingKeys: [
    'finalAnswerCompletionPolicy',
    'finalAnswerCompletion',
    'finalAnswerDelivery',
    'finalAnswerCompletionEnabled',
    'finalAnswerDeliveryEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation final-answer completion is explicitly refused: no final answer is completed or delivered.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation final-answer completion gate: REFUSED_FINAL_ANSWER_COMPLETION_DISABLED / learnbot.local-agent.mutation-final-answer-completion-gate.v1 / final answer generation ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / delivery false / final answer generation status REFUSED_FINAL_ANSWER_GENERATION_DISABLED / learnbot.local-agent.mutation-final-answer-generation-gate.v1'
);
assert.equal(
  view.idsText,
  'mutation final-answer completion ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation final-answer completion counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation final-answer completion disabled: completion false / completion invocation false / delivery false / final answer false / publication false / result aggregation false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'final-answer completion policy mutationFinalAnswerGenerationGate: REFUSED_FINAL_ANSWER_GENERATION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / A disabled final-answer generation gate must refuse generation before final-answer completion can be considered.',
  'final-answer completion policy finalAnswerCompletionPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / Mutation final-answer completion and delivery are disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation final-answer completion blocking keys: finalAnswerCompletionPolicy, finalAnswerCompletion, finalAnswerDelivery, finalAnswerCompletionEnabled, finalAnswerDeliveryEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const hidden = buildMutationFinalAnswerCompletionGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

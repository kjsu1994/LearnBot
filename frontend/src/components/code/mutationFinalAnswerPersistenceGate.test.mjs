import assert from 'node:assert/strict';
import { buildMutationFinalAnswerPersistenceGateView } from './mutationFinalAnswerPersistenceGate.js';

const view = buildMutationFinalAnswerPersistenceGateView({
  schema: 'learnbot.local-agent.mutation-final-answer-persistence-gate.v1',
  status: 'REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED',
  finalAnswerCompletionReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  finalAnswerPersistencePolicy: 'DISABLED_AUDIT_ONLY',
  conversationTurnSaveEnabled: false,
  sourceFinalAnswerCompletionGateStatus: 'REFUSED_FINAL_ANSWER_COMPLETION_DISABLED',
  sourceFinalAnswerCompletionGateSchema: 'learnbot.local-agent.mutation-final-answer-completion-gate.v1',
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
  finalAnswerPersistenceEnabled: false,
  finalAnswerPersistenceInvocationEnabled: false,
  finalAnswerCompletionEnabled: false,
  finalAnswerDeliveryEnabled: false,
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
      key: 'mutationFinalAnswerCompletionGate',
      status: 'REFUSED_FINAL_ANSWER_COMPLETION_DISABLED',
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
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      message: 'A disabled final-answer completion gate must refuse completion before final-answer persistence can be considered.',
    },
    {
      key: 'finalAnswerPersistencePolicy',
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
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      message: 'Mutation final-answer persistence and conversation save are disabled.',
    },
    {
      key: 'finalAnswerPersistence',
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
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      message: 'No final answer may be persisted while final-answer persistence is disabled.',
    },
    {
      key: 'conversationTurnSave',
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
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      message: 'No conversation turn may be saved while final-answer persistence is disabled.',
    },
    {
      key: 'finalAnswerDelivery',
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
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      message: 'No final answer may be delivered while final-answer persistence is disabled.',
    },
  ],
  blockingKeys: [
    'finalAnswerPersistencePolicy',
    'finalAnswerPersistence',
    'conversationTurnSave',
    'finalAnswerDelivery',
    'finalAnswerPersistenceEnabled',
    'conversationTurnSaveEnabled',
    'finalAnswerCompletionEnabled',
    'finalAnswerDeliveryEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation final-answer persistence is explicitly refused: no final answer is persisted and no conversation turn is saved.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation final-answer persistence gate: REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED / learnbot.local-agent.mutation-final-answer-persistence-gate.v1 / final answer completion ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / conversation save false / final answer completion status REFUSED_FINAL_ANSWER_COMPLETION_DISABLED / learnbot.local-agent.mutation-final-answer-completion-gate.v1'
);
assert.equal(
  view.idsText,
  'mutation final-answer persistence ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation final-answer persistence counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation final-answer persistence disabled: persistence false / persistence invocation false / conversation save false / completion false / delivery false / final answer false / publication false / result aggregation false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'final-answer persistence policy mutationFinalAnswerCompletionGate: REFUSED_FINAL_ANSWER_COMPLETION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / A disabled final-answer completion gate must refuse completion before final-answer persistence can be considered.',
  'final-answer persistence policy finalAnswerPersistencePolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / Mutation final-answer persistence and conversation save are disabled.',
  'final-answer persistence policy finalAnswerPersistence: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / No final answer may be persisted while final-answer persistence is disabled.',
  'final-answer persistence policy conversationTurnSave: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / No conversation turn may be saved while final-answer persistence is disabled.',
  'final-answer persistence policy finalAnswerDelivery: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / No final answer may be delivered while final-answer persistence is disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation final-answer persistence blocking keys: finalAnswerPersistencePolicy, finalAnswerPersistence, conversationTurnSave, finalAnswerDelivery, finalAnswerPersistenceEnabled, conversationTurnSaveEnabled, finalAnswerCompletionEnabled, finalAnswerDeliveryEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const blocked = buildMutationFinalAnswerPersistenceGateView({
  status: 'BLOCKED_FINAL_ANSWER_PERSISTENCE_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blocked.headerText, 'mutation final-answer persistence gate: BLOCKED_FINAL_ANSWER_PERSISTENCE_DISABLED');
assert.equal(blocked.countsText, 'mutation final-answer persistence counts: expected 0 / intake persisted 0');
assert.deepEqual(blocked.policyLines, [
  'final-answer persistence policy unknownPolicy: UNKNOWN',
]);

const hidden = buildMutationFinalAnswerPersistenceGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

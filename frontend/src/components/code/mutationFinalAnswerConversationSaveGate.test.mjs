import assert from 'node:assert/strict';
import { buildMutationFinalAnswerConversationSaveGateView } from './mutationFinalAnswerConversationSaveGate.js';

const view = buildMutationFinalAnswerConversationSaveGateView({
  schema: 'learnbot.local-agent.mutation-final-answer-conversation-save-gate.v1',
  status: 'REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED',
  finalAnswerPersistenceReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  finalAnswerConversationSavePolicy: 'DISABLED_AUDIT_ONLY',
  userVisibleCompletionEnabled: false,
  sourceFinalAnswerPersistenceGateStatus: 'REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED',
  sourceFinalAnswerPersistenceGateSchema: 'learnbot.local-agent.mutation-final-answer-persistence-gate.v1',
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
  conversationTurnSaveEnabled: false,
  conversationTurnSaveInvocationEnabled: false,
  finalAnswerPersistenceEnabled: false,
  finalAnswerDeliveryEnabled: false,
  finalAnswerCompletionEnabled: false,
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
      key: 'mutationFinalAnswerPersistenceGate',
      status: 'REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED',
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
      userVisibleCompletionEnabled: false,
      message: 'A disabled final-answer persistence gate must refuse persistence before conversation save can be considered.',
    },
    {
      key: 'finalAnswerConversationSavePolicy',
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
      userVisibleCompletionEnabled: false,
      message: 'Mutation final-answer conversation save and user-visible completion are disabled.',
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
      userVisibleCompletionEnabled: false,
      message: 'No conversation turn may be saved while conversation save is disabled.',
    },
    {
      key: 'userVisibleCompletion',
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
      userVisibleCompletionEnabled: false,
      message: 'No user-visible completion may be marked while conversation save is disabled.',
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
      userVisibleCompletionEnabled: false,
      message: 'No final answer may be delivered while conversation save is disabled.',
    },
  ],
  blockingKeys: [
    'finalAnswerConversationSavePolicy',
    'conversationTurnSave',
    'userVisibleCompletion',
    'finalAnswerDelivery',
    'conversationTurnSaveEnabled',
    'userVisibleCompletionEnabled',
    'finalAnswerPersistenceEnabled',
    'finalAnswerDeliveryEnabled',
    'finalAnswerCompletionEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation final-answer conversation save is explicitly refused: no conversation turn is saved and no user-visible completion is marked.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation final-answer conversation-save gate: REFUSED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED / learnbot.local-agent.mutation-final-answer-conversation-save-gate.v1 / final answer persistence ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / user-visible completion false / final answer persistence status REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED / learnbot.local-agent.mutation-final-answer-persistence-gate.v1'
);
assert.equal(
  view.idsText,
  'mutation final-answer conversation-save ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation final-answer conversation-save counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation final-answer conversation-save disabled: conversation save false / conversation save invocation false / user-visible completion false / persistence false / delivery false / completion false / final answer false / publication false / result aggregation false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'final-answer conversation-save policy mutationFinalAnswerPersistenceGate: REFUSED_FINAL_ANSWER_PERSISTENCE_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / A disabled final-answer persistence gate must refuse persistence before conversation save can be considered.',
  'final-answer conversation-save policy finalAnswerConversationSavePolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / Mutation final-answer conversation save and user-visible completion are disabled.',
  'final-answer conversation-save policy conversationTurnSave: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / No conversation turn may be saved while conversation save is disabled.',
  'final-answer conversation-save policy userVisibleCompletion: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / No user-visible completion may be marked while conversation save is disabled.',
  'final-answer conversation-save policy finalAnswerDelivery: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / No final answer may be delivered while conversation save is disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation final-answer conversation-save blocking keys: finalAnswerConversationSavePolicy, conversationTurnSave, userVisibleCompletion, finalAnswerDelivery, conversationTurnSaveEnabled, userVisibleCompletionEnabled, finalAnswerPersistenceEnabled, finalAnswerDeliveryEnabled, finalAnswerCompletionEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const hidden = buildMutationFinalAnswerConversationSaveGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

const blocked = buildMutationFinalAnswerConversationSaveGateView({
  policyChecks: null,
  blockingKeys: null,
  message: 'Local Agent mutation final-answer conversation save is blocked because the disabled final-answer persistence gate is incomplete.',
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.headerText,
  'mutation final-answer conversation-save gate: BLOCKED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED'
);
assert.deepEqual(blocked.policyLines, []);
assert.equal(blocked.blockingText, '');
assert.match(blocked.message, /blocked/);

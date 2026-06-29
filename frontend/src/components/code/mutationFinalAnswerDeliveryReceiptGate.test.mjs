import assert from 'node:assert/strict';
import { buildMutationFinalAnswerDeliveryReceiptGateView } from './mutationFinalAnswerDeliveryReceiptGate.js';

const view = buildMutationFinalAnswerDeliveryReceiptGateView({
  schema: 'learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1',
  status: 'REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED',
  finalAnswerDeliveryReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  deliveryReceiptPolicy: 'DISABLED_AUDIT_ONLY',
  sourceFinalAnswerDeliveryGateStatus: 'REFUSED_FINAL_ANSWER_DELIVERY_DISABLED',
  sourceFinalAnswerDeliveryGateSchema: 'learnbot.local-agent.mutation-final-answer-delivery-gate.v1',
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
  deliveryReceiptEnabled: false,
  finalAnswerDeliveryEnabled: false,
  deliveryHandoffEnabled: false,
  finalResponseHandoffEnabled: false,
  userVisibleCompletionEnabled: false,
  conversationTurnSaveEnabled: false,
  finalAnswerPersistenceEnabled: false,
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
      key: 'mutationFinalAnswerDeliveryGate',
      status: 'REFUSED_FINAL_ANSWER_DELIVERY_DISABLED',
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
      deliveryReceiptEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      userVisibleCompletionEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryHandoffEnabled: false,
      message: 'A disabled final-answer delivery gate must refuse delivery before delivery receipt can be considered.',
    },
    {
      key: 'deliveryReceiptPolicy',
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
      deliveryReceiptEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      userVisibleCompletionEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryHandoffEnabled: false,
      message: 'Mutation final-answer delivery receipt and acknowledgement are disabled.',
    },
  ],
  blockingKeys: [
    'deliveryReceiptPolicy',
    'deliveryReceipt',
    'finalAnswerDelivery',
    'deliveryHandoff',
    'deliveryReceiptEnabled',
    'finalAnswerDeliveryEnabled',
    'deliveryHandoffEnabled',
    'finalResponseHandoffEnabled',
    'userVisibleCompletionEnabled',
    'conversationTurnSaveEnabled',
    'finalAnswerPersistenceEnabled',
    'finalAnswerCompletionEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation final-answer delivery receipt is explicitly refused: no delivery receipt is recorded and no acknowledgement is saved.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation final-answer delivery receipt gate: REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED / learnbot.local-agent.mutation-final-answer-delivery-receipt-gate.v1 / final-answer delivery ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / final-answer delivery status REFUSED_FINAL_ANSWER_DELIVERY_DISABLED / learnbot.local-agent.mutation-final-answer-delivery-gate.v1'
);
assert.equal(
  view.idsText,
  'mutation final-answer delivery receipt ids: source request-123 / release attempt- / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation final-answer delivery receipt counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation final-answer delivery receipt disabled: receipt false / delivery false / delivery handoff false / final response handoff false / user-visible completion false / conversation save false / persistence false / completion false / final answer false / publication false / result aggregation false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'final-answer delivery receipt policy mutationFinalAnswerDeliveryGate: REFUSED_FINAL_ANSWER_DELIVERY_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / receipt false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / A disabled final-answer delivery gate must refuse delivery before delivery receipt can be considered.',
  'final-answer delivery receipt policy deliveryReceiptPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / receipt false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / Mutation final-answer delivery receipt and acknowledgement are disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation final-answer delivery receipt blocking keys: deliveryReceiptPolicy, deliveryReceipt, finalAnswerDelivery, deliveryHandoff, deliveryReceiptEnabled, finalAnswerDeliveryEnabled, deliveryHandoffEnabled, finalResponseHandoffEnabled, userVisibleCompletionEnabled, conversationTurnSaveEnabled, finalAnswerPersistenceEnabled, finalAnswerCompletionEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const hidden = buildMutationFinalAnswerDeliveryReceiptGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

import assert from 'node:assert/strict';
import { buildMutationCompletionSummaryView } from './mutationCompletionSummary.js';

const view = buildMutationCompletionSummaryView({
  schema: 'learnbot.local-agent.mutation-completion-summary.v1',
  status: 'READY_COMPLETION_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
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
  finalAnswerCompletionEnabled: false,
  finalAnswerDeliveryEnabled: false,
  finalAnswerPersistenceEnabled: false,
  conversationTurnSaveEnabled: false,
  userVisibleCompletionEnabled: false,
  finalResponseHandoffEnabled: false,
  deliveryHandoffEnabled: false,
  deliveryReceiptEnabled: false,
  items: [
    {
      key: 'releaseAttemptReadiness',
      status: 'READY',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      userVisibleCompletionEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      message: 'Latest release attempt must be fresh, complete, and based on passing patch preconditions.',
    },
    {
      key: 'mutationFinalAnswerDeliveryGate',
      status: 'REFUSED_FINAL_ANSWER_DELIVERY_DISABLED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      userVisibleCompletionEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      message: 'Future mutation final-answer delivery must pass through a disabled delivery gate that refuses final-answer delivery and delivery handoff.',
    },
    {
      key: 'mutationFinalAnswerDeliveryReceiptGate',
      status: 'REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      finalAnswerDeliveryEnabled: false,
      finalAnswerPersistenceEnabled: false,
      conversationTurnSaveEnabled: false,
      userVisibleCompletionEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      message: 'Future mutation final-answer delivery receipt must pass through a disabled receipt gate that refuses delivery receipt and acknowledgement.',
    },
  ],
  blockingKeys: [],
  message: 'Local Agent mutation completion prerequisites are modeled, but execution, aggregation, publication, and final-answer generation remain disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation completion summary: READY_COMPLETION_DISABLED / learnbot.local-agent.mutation-completion-summary.v1 / prerequisites true / USER_LOCAL_AGENT'
);
assert.equal(
  view.disabledText,
  'mutation completion disabled: release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / receipt false'
);
assert.deepEqual(view.itemLines, [
  'releaseAttemptReadiness: READY / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / receipt false / Latest release attempt must be fresh, complete, and based on passing patch preconditions.',
  'mutationFinalAnswerDeliveryGate: REFUSED_FINAL_ANSWER_DELIVERY_DISABLED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / receipt false / Future mutation final-answer delivery must pass through a disabled delivery gate that refuses final-answer delivery and delivery handoff.',
  'mutationFinalAnswerDeliveryReceiptGate: REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / receipt false / Future mutation final-answer delivery receipt must pass through a disabled receipt gate that refuses delivery receipt and acknowledgement.',
]);
assert.equal(view.blockingText, '');
assert.match(view.message, /remain disabled/);

const blocked = buildMutationCompletionSummaryView({
  status: 'BLOCKED_COMPLETION_DISABLED',
  finalAnswerDeliveryEnabled: false,
  deliveryHandoffEnabled: false,
  deliveryReceiptEnabled: false,
  blockingKeys: ['mutationCompletionSummary'],
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.disabledText,
  'mutation completion disabled: delivery false / delivery handoff false / receipt false'
);
assert.equal(blocked.blockingText, 'mutation completion blocking keys: mutationCompletionSummary');

const hidden = buildMutationCompletionSummaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.itemLines, []);

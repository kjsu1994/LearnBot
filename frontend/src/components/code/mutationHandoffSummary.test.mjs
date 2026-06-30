import assert from 'node:assert/strict';
import { buildMutationHandoffSummaryView } from './mutationHandoffSummary.js';

const view = buildMutationHandoffSummaryView({
  schema: 'learnbot.local-agent.mutation-handoff-summary.v1',
  status: 'READY_HANDOFF_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  sourceCompletionSummaryStatus: 'READY_COMPLETION_DISABLED',
  disabledControls: {
    releaseGateEnabled: false,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    writeHelperEnabled: false,
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
    acknowledgementSaveEnabled: false,
    claimable: false,
    mutationAllowed: false,
  },
  handoffStages: [
    {
      key: 'dispatchDecision',
      sourceGateKey: 'mutationDispatchDecisionModel',
      status: 'MODELED_DISABLED',
      passed: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      resultIntakeEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
    {
      key: 'finalResponse',
      sourceGateKey: 'mutationFinalResponseHandoffGate',
      status: 'MODELED_DISABLED',
      passed: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      resultIntakeEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
    {
      key: 'deliveryReceipt',
      sourceGateKey: 'mutationFinalAnswerDeliveryReceiptGate',
      status: 'MODELED_DISABLED',
      passed: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      resultIntakeEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
  ],
  blockingKeys: ['releaseGateEnabled', 'requestCreationEnabled', 'pushEnabled', 'claimEnabled', 'mutationAllowed'],
  message: 'Local Agent mutation handoff prerequisites are modeled, but all handoff controls remain disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation handoff summary: READY_HANDOFF_DISABLED / learnbot.local-agent.mutation-handoff-summary.v1 / prerequisites true / USER_LOCAL_AGENT / completion READY_COMPLETION_DISABLED'
);
assert.equal(
  view.disabledText,
  'mutation handoff disabled: release gate false / request creation false / push false / claim false / write helper false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / receipt false / acknowledgement save false / claimable false / mutation false'
);
assert.deepEqual(view.stageLines, [
  'handoff dispatchDecision: MODELED_DISABLED / source mutationDispatchDecisionModel / passed true / request creation false / push false / claim false / execution false / result intake false / final response false / receipt false / acknowledgement save false / claimable false / mutation false',
  'handoff finalResponse: MODELED_DISABLED / source mutationFinalResponseHandoffGate / passed true / request creation false / push false / claim false / execution false / result intake false / final response false / receipt false / acknowledgement save false / claimable false / mutation false',
  'handoff deliveryReceipt: MODELED_DISABLED / source mutationFinalAnswerDeliveryReceiptGate / passed true / request creation false / push false / claim false / execution false / result intake false / final response false / receipt false / acknowledgement save false / claimable false / mutation false',
]);
assert.equal(
  view.blockingText,
  'mutation handoff blocking keys: releaseGateEnabled, requestCreationEnabled, pushEnabled, claimEnabled, mutationAllowed'
);
assert.match(view.message, /handoff controls remain disabled/);

const hidden = buildMutationHandoffSummaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.stageLines, []);

const blocked = buildMutationHandoffSummaryView({
  status: 'BLOCKED_HANDOFF_DISABLED',
  disabledControls: {
    finalResponseHandoffEnabled: false,
    deliveryHandoffEnabled: false,
    deliveryReceiptEnabled: false,
    acknowledgementSaveEnabled: false,
  },
  blockingKeys: ['mutationCompletionSummary'],
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.disabledText,
  'mutation handoff disabled: final response handoff false / delivery handoff false / receipt false / acknowledgement save false'
);
assert.equal(blocked.blockingText, 'mutation handoff blocking keys: mutationCompletionSummary');

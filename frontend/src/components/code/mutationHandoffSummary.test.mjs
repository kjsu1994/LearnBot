import assert from 'node:assert/strict';
import { buildMutationHandoffSummaryView } from './mutationHandoffSummary.js';

const view = buildMutationHandoffSummaryView({
  schema: 'learnbot.local-agent.mutation-handoff-summary.v1',
  status: 'READY_HANDOFF_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  sessionId: 'session-1',
  userId: 'user-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  sourceCompletionSummaryStatus: 'READY_COMPLETION_DISABLED',
  sourceCompletionSummaryDeliveryReceiptGateStatus: 'REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED',
  sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSavePolicy: 'DISABLED_AUDIT_ONLY',
  sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSaveEnabled: false,
  sourceCompletionSummaryDeliveryReceiptGatePublicationGateStatus: 'REFUSED_PUBLICATION_DISABLED',
  sourceCompletionSummaryDeliveryReceiptGatePublicationGateSchema: 'learnbot.local-agent.mutation-publication-gate.v1',
  sourceCompletionSummaryDeliveryReceiptGatePublicationGateSessionId: 'session-1',
  sourceCompletionSummaryDeliveryReceiptGatePublicationGateUserId: 'user-1',
  sourceCompletionSummaryDeliveryReceiptGatePublicationGateAgentId: 'agent-1',
  sourceCompletionSummaryDeliveryReceiptGatePublicationGateWorkspaceId: 'workspace-1',
  sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryStatus: 'READY_PUBLICATION_DISABLED',
  sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryDraftStatus: 'READY_DRAFT_DISABLED',
  sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationCount: 2,
  sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationAcceptedCount: 2,
  sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationRejectedCount: 0,
  sourceCompletionSummaryDeliveryReceiptGateMissingMutationResultRiskVisible: false,
  sourceCompletionSummaryDeliveryReceiptGateStaleIndexRiskVisible: true,
  sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationCount: 2,
  sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount: 2,
  sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount: 0,
  sourceCompletionSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible: false,
  sourceCompletionSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible: true,
  sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus: 'ACCEPTED',
  sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName: 'patch.apply',
  sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus: 'PASSED',
  sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount: 2,
  sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount: 2,
  sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount: 0,
  sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible: false,
  sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible: true,
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
    {
      key: 'acknowledgementSave',
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
  'mutation handoff summary: READY_HANDOFF_DISABLED / learnbot.local-agent.mutation-handoff-summary.v1 / prerequisites true / USER_LOCAL_AGENT / completion READY_COMPLETION_DISABLED / receipt REFUSED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED / acknowledgement DISABLED_AUDIT_ONLY / acknowledgement save false'
);
assert.equal(
  view.idsText,
  'mutation handoff summary ids: session session-1 / user user-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.sourceContextText,
  'mutation handoff source context: publication gate REFUSED_PUBLICATION_DISABLED / publication schema learnbot.local-agent.mutation-publication-gate.v1 / publication session session-1 / publication user user-1 / publication agent agent-1 / publication workspace workspace-1 / publication READY_PUBLICATION_DISABLED / draft READY_DRAFT_DISABLED / observations 2 / accepted 2 / rejected 0 / missing result risk false / stale index risk true / publication observations OBSERVED / publication count 2 / publication accepted 2 / publication rejected 0 / publication missing result risk false / publication stale index risk true / publication latest ACCEPTED / publication tool patch.apply / publication verification PASSED / publication rollback summary observations OBSERVED / publication rollback summary count 2 / publication rollback summary accepted 2 / publication rollback summary rejected 0 / publication rollback summary missing result risk false / publication rollback summary stale index risk true'
);
assert.equal(
  view.disabledText,
  'mutation handoff disabled: release gate false / request creation false / push false / claim false / write helper false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false / completion false / delivery false / persistence false / conversation save false / user-visible completion false / final response handoff false / delivery handoff false / receipt false / acknowledgement save false / claimable false / mutation false'
);
assert.deepEqual(view.stageLines, [
  'handoff dispatchDecision: MODELED_DISABLED / source mutationDispatchDecisionModel / passed true / request creation false / push false / claim false / execution false / result intake false / final response false / receipt false / acknowledgement save false / claimable false / mutation false',
  'handoff finalResponse: MODELED_DISABLED / source mutationFinalResponseHandoffGate / passed true / request creation false / push false / claim false / execution false / result intake false / final response false / receipt false / acknowledgement save false / claimable false / mutation false',
  'handoff deliveryReceipt: MODELED_DISABLED / source mutationFinalAnswerDeliveryReceiptGate / passed true / request creation false / push false / claim false / execution false / result intake false / final response false / receipt false / acknowledgement save false / claimable false / mutation false',
  'handoff acknowledgementSave: MODELED_DISABLED / source mutationFinalAnswerDeliveryReceiptGate / passed true / request creation false / push false / claim false / execution false / result intake false / final response false / receipt false / acknowledgement save false / claimable false / mutation false',
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

import assert from 'node:assert/strict';
import { buildMutationExecutionReadinessBoundaryView } from './mutationExecutionReadinessBoundary.js';

const view = buildMutationExecutionReadinessBoundaryView({
  schema: 'learnbot.local-agent.mutation-execution-readiness-boundary.v1',
  status: 'REFUSED_EXECUTION_READINESS_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  sessionId: 'session-1',
  userId: 'user-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  sourceHandoffSummaryStatus: 'READY_HANDOFF_DISABLED',
  sourceHandoffSummaryDeliveryReceiptGatePublicationGateStatus: 'REFUSED_PUBLICATION_DISABLED',
  sourceHandoffSummaryDeliveryReceiptGatePublicationGateSchema: 'learnbot.local-agent.mutation-publication-gate.v1',
  sourceHandoffSummaryDeliveryReceiptGatePublicationGateSessionId: 'session-1',
  sourceHandoffSummaryDeliveryReceiptGatePublicationGateUserId: 'user-1',
  sourceHandoffSummaryDeliveryReceiptGatePublicationGateAgentId: 'agent-1',
  sourceHandoffSummaryDeliveryReceiptGatePublicationGateWorkspaceId: 'workspace-1',
  sourceHandoffSummaryDeliveryReceiptGatePublicationBoundaryStatus: 'READY_PUBLICATION_DISABLED',
  sourceHandoffSummaryDeliveryReceiptGatePublicationBoundaryDraftStatus: 'READY_DRAFT_DISABLED',
  sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationCount: 2,
  sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationAcceptedCount: 2,
  sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationRejectedCount: 0,
  sourceHandoffSummaryDeliveryReceiptGateMissingMutationResultRiskVisible: false,
  sourceHandoffSummaryDeliveryReceiptGateStaleIndexRiskVisible: true,
  sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationCount: 2,
  sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount: 2,
  sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount: 0,
  sourceHandoffSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible: false,
  sourceHandoffSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible: true,
  sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus: 'ACCEPTED',
  sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName: 'patch.apply',
  sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus: 'PASSED',
  sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount: 2,
  sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount: 2,
  sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount: 0,
  sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible: false,
  sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible: true,
  sourceExecutionGateStatus: 'REFUSED_EXECUTION_DISABLED',
  sourceWriteHelperSafetyGateStatus: 'REFUSED_WRITE_HELPER_DISABLED',
  expectedRequestCount: 4,
  completedRequestCount: 0,
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  executionEnabled: false,
  toolRunnerEnabled: false,
  writeHelperEnabled: false,
  applyEnabled: false,
  testEnabled: false,
  rollbackRestoreEnabled: false,
  resultIntakeEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  finalResponseHandoffEnabled: false,
  deliveryReceiptEnabled: false,
  acknowledgementSaveEnabled: false,
  claimable: false,
  mutationAllowed: false,
  readinessChecks: [
    {
      key: 'mutationHandoffSummary',
      status: 'READY_HANDOFF_DISABLED',
      passed: true,
      blocking: false,
      executionEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'mutationExecutionGate',
      status: 'REFUSED_EXECUTION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
    },
    {
      key: 'mutationWriteHelperSafetyGate',
      status: 'REFUSED_WRITE_HELPER_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
    },
    {
      key: 'runtimeExecutionSwitch',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      executionEnabled: false,
      toolRunnerEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'sideEffectTransport',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
    },
  ],
  blockingKeys: [
    'runtimeExecutionSwitch',
    'sideEffectTransport',
    'releaseGateEnabled',
    'requestCreationEnabled',
    'pushEnabled',
    'claimEnabled',
    'executionEnabled',
    'writeHelperEnabled',
    'applyEnabled',
    'testEnabled',
    'rollbackRestoreEnabled',
    'resultIntakeEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation execution inputs are modeled, but runtime execution remains disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation execution readiness: REFUSED_EXECUTION_READINESS_DISABLED / learnbot.local-agent.mutation-execution-readiness-boundary.v1 / prerequisites true / USER_LOCAL_AGENT / expected 4 / completed 0'
);
assert.equal(
  view.idsText,
  'mutation execution readiness ids: session session-1 / user user-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.sourceText,
  'mutation execution readiness sources: handoff READY_HANDOFF_DISABLED / execution gate REFUSED_EXECUTION_DISABLED / write helper REFUSED_WRITE_HELPER_DISABLED'
);
assert.equal(
  view.sourceContextText,
  'mutation execution readiness source context: publication gate REFUSED_PUBLICATION_DISABLED / publication schema learnbot.local-agent.mutation-publication-gate.v1 / publication session session-1 / publication user user-1 / publication agent agent-1 / publication workspace workspace-1 / publication READY_PUBLICATION_DISABLED / draft READY_DRAFT_DISABLED / observations 2 / accepted 2 / rejected 0 / missing result risk false / stale index risk true / publication observations OBSERVED / publication count 2 / publication accepted 2 / publication rejected 0 / publication missing result risk false / publication stale index risk true / publication latest ACCEPTED / publication tool patch.apply / publication verification PASSED / publication rollback summary observations OBSERVED / publication rollback summary count 2 / publication rollback summary accepted 2 / publication rollback summary rejected 0 / publication rollback summary missing result risk false / publication rollback summary stale index risk true'
);
assert.equal(
  view.disabledText,
  'mutation execution readiness disabled: release gate false / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / rag freshness false / result aggregation false / publication false / final answer false / final response handoff false / receipt false / acknowledgement save false / claimable false / mutation false'
);
assert.deepEqual(view.checkLines, [
  'execution readiness mutationHandoffSummary: READY_HANDOFF_DISABLED / passed true / blocking false / execution false / mutation false',
  'execution readiness mutationExecutionGate: REFUSED_EXECUTION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / acknowledgement save false / claimable false / mutation false',
  'execution readiness mutationWriteHelperSafetyGate: REFUSED_WRITE_HELPER_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / acknowledgement save false / claimable false / mutation false',
  'execution readiness runtimeExecutionSwitch: DISABLED / passed false / blocking true / execution false / tool runner false / mutation false',
  'execution readiness sideEffectTransport: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / acknowledgement save false / claimable false / mutation false',
]);
assert.equal(
  view.blockingText,
  'mutation execution readiness blocking keys: runtimeExecutionSwitch, sideEffectTransport, releaseGateEnabled, requestCreationEnabled, pushEnabled, claimEnabled, executionEnabled, writeHelperEnabled, applyEnabled, testEnabled, rollbackRestoreEnabled, resultIntakeEnabled, mutationAllowed'
);
assert.match(view.message, /runtime execution remains disabled/);

const hidden = buildMutationExecutionReadinessBoundaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.checkLines, []);

const blocked = buildMutationExecutionReadinessBoundaryView({
  status: 'BLOCKED_EXECUTION_READINESS_DISABLED',
  readinessChecks: null,
  blockingKeys: null,
  message: 'Local Agent mutation execution readiness is blocked by incomplete disabled handoff, execution, or write-helper inputs.',
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.headerText,
  'mutation execution readiness: BLOCKED_EXECUTION_READINESS_DISABLED'
);
assert.deepEqual(blocked.checkLines, []);
assert.equal(blocked.blockingText, '');
assert.match(blocked.message, /blocked/);

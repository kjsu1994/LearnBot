import assert from 'node:assert/strict';
import { buildMutationResultCompletionBoundaryView } from './mutationResultCompletionBoundary.js';

const view = buildMutationResultCompletionBoundaryView({
  schema: 'learnbot.local-agent.mutation-result-completion-boundary.v1',
  status: 'REFUSED_RESULT_COMPLETION_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  sourceToolRunnerBoundaryStatus: 'REFUSED_TOOL_RUNNER_DISABLED',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateStatus: 'REFUSED_PUBLICATION_DISABLED',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSchema: 'learnbot.local-agent.mutation-publication-gate.v1',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateSessionId: 'session-1',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateUserId: 'user-1',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateAgentId: 'agent-1',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationGateWorkspaceId: 'workspace-1',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryStatus: 'READY_PUBLICATION_DISABLED',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationBoundaryDraftStatus: 'READY_DRAFT_DISABLED',
  sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationCount: 2,
  sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationAcceptedCount: 2,
  sourceToolRunnerBoundaryDeliveryReceiptGateAcceptedObservationRejectedCount: 0,
  sourceToolRunnerBoundaryDeliveryReceiptGateMissingMutationResultRiskVisible: false,
  sourceToolRunnerBoundaryDeliveryReceiptGateStaleIndexRiskVisible: true,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount: 2,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount: 2,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount: 0,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible: false,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible: true,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus: 'ACCEPTED',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName: 'patch.apply',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus: 'PASSED',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount: 2,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount: 2,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount: 0,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible: false,
  sourceToolRunnerBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible: true,
  sourcePostExecutionObservationGateStatus: 'REFUSED_POST_EXECUTION_OBSERVATION_DISABLED',
  completionPolicy: 'DISABLED_AUDIT_ONLY',
  expectedResultCount: 4,
  completedResultCount: 0,
  acceptedResultCount: 0,
  rejectedResultCount: 0,
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  runningTransitionEnabled: false,
  executionEnabled: false,
  toolRunnerEnabled: false,
  writeHelperEnabled: false,
  applyEnabled: false,
  testEnabled: false,
  rollbackRestoreEnabled: false,
  completedResultTransitionEnabled: false,
  completedResultPersistenceEnabled: false,
  postExecutionObservationEnabled: false,
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
  resultChecks: [
    {
      key: 'mutationToolRunnerBoundary',
      status: 'REFUSED_TOOL_RUNNER_DISABLED',
      passed: true,
      blocking: false,
      toolRunnerEnabled: false,
      acknowledgementSaveEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'mutationPostExecutionObservationGate',
      status: 'REFUSED_POST_EXECUTION_OBSERVATION_DISABLED',
      passed: true,
      blocking: false,
      toolRunnerEnabled: false,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      postExecutionObservationEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
    {
      key: 'completedResultTransition',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      acknowledgementSaveEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'resultEnvelopePersistence',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      toolRunnerEnabled: false,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      postExecutionObservationEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
    {
      key: 'observationCapture',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      toolRunnerEnabled: false,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      postExecutionObservationEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
  ],
  blockingKeys: [
    'completedResultTransition',
    'resultEnvelopePersistence',
    'observationCapture',
    'toolRunnerEnabled',
    'completedResultTransitionEnabled',
    'completedResultPersistenceEnabled',
    'postExecutionObservationEnabled',
    'resultIntakeEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation result completion is modeled, but completed-result transitions remain disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation result completion boundary: REFUSED_RESULT_COMPLETION_DISABLED / learnbot.local-agent.mutation-result-completion-boundary.v1 / prerequisites true / USER_LOCAL_AGENT / expected 4 / completed 0 / accepted 0 / rejected 0'
);
assert.equal(
  view.sourceText,
  'mutation result completion sources: tool runner REFUSED_TOOL_RUNNER_DISABLED / observation REFUSED_POST_EXECUTION_OBSERVATION_DISABLED / completion policy DISABLED_AUDIT_ONLY'
);
assert.equal(
  view.sourceContextText,
  'mutation result completion source context: publication gate REFUSED_PUBLICATION_DISABLED / publication schema learnbot.local-agent.mutation-publication-gate.v1 / publication session session-1 / publication user user-1 / publication agent agent-1 / publication workspace workspace-1 / publication READY_PUBLICATION_DISABLED / draft READY_DRAFT_DISABLED / observations 2 / accepted 2 / rejected 0 / missing result risk false / stale index risk true / publication observations OBSERVED / publication count 2 / publication accepted 2 / publication rejected 0 / publication missing result risk false / publication stale index risk true / publication latest ACCEPTED / publication tool patch.apply / publication verification PASSED / publication rollback summary observations OBSERVED / publication rollback summary count 2 / publication rollback summary accepted 2 / publication rollback summary rejected 0 / publication rollback summary missing result risk false / publication rollback summary stale index risk true'
);
assert.equal(
  view.disabledText,
  'mutation result completion disabled: release gate false / request creation false / push false / claim false / running transition false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / completed transition false / result persistence false / observation capture false / result intake false / rag freshness false / result aggregation false / publication false / final answer false / final response handoff false / receipt false / acknowledgement save false / claimable false / mutation false'
);
assert.deepEqual(view.checkLines, [
  'result completion mutationToolRunnerBoundary: REFUSED_TOOL_RUNNER_DISABLED / passed true / blocking false / tool runner false / acknowledgement save false / mutation false',
  'result completion mutationPostExecutionObservationGate: REFUSED_POST_EXECUTION_OBSERVATION_DISABLED / passed true / blocking false / tool runner false / completed transition false / result persistence false / observation capture false / result intake false / acknowledgement save false / claimable false / mutation false',
  'result completion completedResultTransition: DISABLED / passed false / blocking true / completed transition false / result persistence false / acknowledgement save false / mutation false',
  'result completion resultEnvelopePersistence: DISABLED / passed false / blocking true / tool runner false / completed transition false / result persistence false / observation capture false / result intake false / acknowledgement save false / claimable false / mutation false',
  'result completion observationCapture: DISABLED / passed false / blocking true / tool runner false / completed transition false / result persistence false / observation capture false / result intake false / acknowledgement save false / claimable false / mutation false',
]);
assert.equal(
  view.blockingText,
  'mutation result completion blocking keys: completedResultTransition, resultEnvelopePersistence, observationCapture, toolRunnerEnabled, completedResultTransitionEnabled, completedResultPersistenceEnabled, postExecutionObservationEnabled, resultIntakeEnabled, mutationAllowed'
);
assert.match(view.message, /completed-result transitions/);

const hidden = buildMutationResultCompletionBoundaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.checkLines, []);

const blocked = buildMutationResultCompletionBoundaryView({
  status: 'BLOCKED_RESULT_COMPLETION_DISABLED',
  resultChecks: null,
  blockingKeys: null,
  message: 'Local Agent mutation result completion is blocked by incomplete disabled tool-runner or post-execution observation inputs.',
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.headerText,
  'mutation result completion boundary: BLOCKED_RESULT_COMPLETION_DISABLED'
);
assert.deepEqual(blocked.checkLines, []);
assert.equal(blocked.blockingText, '');
assert.match(blocked.message, /blocked/);

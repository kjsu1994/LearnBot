import assert from 'node:assert/strict';
import { buildMutationFinalAnswerCompletionGateView } from './mutationFinalAnswerCompletionGate.js';

const view = buildMutationFinalAnswerCompletionGateView({
  schema: 'learnbot.local-agent.mutation-final-answer-completion-gate.v1',
  status: 'REFUSED_FINAL_ANSWER_COMPLETION_DISABLED',
  finalAnswerGenerationReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  finalAnswerCompletionPolicy: 'DISABLED_AUDIT_ONLY',
  sourceFinalAnswerGenerationGateStatus: 'REFUSED_FINAL_ANSWER_GENERATION_DISABLED',
  sourceFinalAnswerGenerationGateSchema: 'learnbot.local-agent.mutation-final-answer-generation-gate.v1',
  sourceFinalAnswerGenerationGatePublicationGateStatus: 'REFUSED_PUBLICATION_DISABLED',
  sourceFinalAnswerGenerationGatePublicationGateSchema: 'learnbot.local-agent.mutation-publication-gate.v1',
  sourceFinalAnswerGenerationGatePublicationGateSessionId: 'session-1',
  sourceFinalAnswerGenerationGatePublicationGateUserId: 'user-1',
  sourceFinalAnswerGenerationGatePublicationGateAgentId: 'agent-1',
  sourceFinalAnswerGenerationGatePublicationGateWorkspaceId: 'workspace-1',
  sourceFinalAnswerGenerationGatePublicationBoundaryStatus: 'READY_PUBLICATION_DISABLED',
  sourceFinalAnswerGenerationGatePublicationBoundaryPrerequisitesPassed: true,
  sourceFinalAnswerGenerationGatePublicationBoundaryDraftStatus: 'READY_DRAFT_DISABLED',
  sourceFinalAnswerGenerationGatePublicationBoundaryDraftSections: ['changedFiles', 'verificationOutcome', 'rollbackState', 'ragFreshnessState'],
  sourceFinalAnswerGenerationGateAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceFinalAnswerGenerationGateAcceptedObservationCount: 2,
  sourceFinalAnswerGenerationGateAcceptedObservationAcceptedCount: 2,
  sourceFinalAnswerGenerationGateAcceptedObservationRejectedCount: 0,
  sourceFinalAnswerGenerationGateMissingMutationResultRiskVisible: false,
  sourceFinalAnswerGenerationGateStaleIndexRiskVisible: true,
  sourceFinalAnswerGenerationGatePublicationAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceFinalAnswerGenerationGatePublicationAcceptedObservationCount: 2,
  sourceFinalAnswerGenerationGatePublicationAcceptedObservationAcceptedCount: 2,
  sourceFinalAnswerGenerationGatePublicationAcceptedObservationRejectedCount: 0,
  sourceFinalAnswerGenerationGatePublicationMissingMutationResultRiskVisible: false,
  sourceFinalAnswerGenerationGatePublicationStaleIndexRiskVisible: true,
  sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationStatus: 'ACCEPTED',
  sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationToolName: 'patch.apply',
  sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationVerificationStatus: 'PASSED',
  sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryObservationCount: 2,
  sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryAcceptedCount: 2,
  sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryRejectedCount: 0,
  sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible: false,
  sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible: true,
  sourceRequestId: 'request-123',
  releaseAttemptId: 'attempt-1234567890',
  sessionId: 'session-1',
  userId: 'user-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  expectedResultCount: 4,
  completedResultCount: 0,
  acceptedResultCount: 0,
  rejectedResultCount: 0,
  intakePersistedResultCount: 0,
  finalAnswerCompletionEnabled: false,
  finalAnswerCompletionInvocationEnabled: false,
  finalAnswerDeliveryEnabled: false,
  finalResponseHandoffEnabled: false,
  deliveryReceiptEnabled: false,
  acknowledgementSaveEnabled: false,
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
      finalAnswerDeliveryEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
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
      finalAnswerDeliveryEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'Mutation final-answer completion and delivery are disabled.',
    },
    {
      key: 'finalAnswerCompletion',
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
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'No final answer may be completed while final-answer completion is disabled.',
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
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'No final answer may be delivered while final-answer delivery is disabled.',
    },
  ],
  blockingKeys: [
    'finalAnswerCompletionPolicy',
    'finalAnswerCompletion',
    'finalAnswerDelivery',
    'finalAnswerCompletionEnabled',
    'finalAnswerDeliveryEnabled',
    'finalResponseHandoffEnabled',
    'deliveryReceiptEnabled',
    'acknowledgementSaveEnabled',
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
  'mutation final-answer completion ids: source request-123 / release attempt- / session session-1 / user user-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation final-answer completion counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.generationContextText,
  'mutation final-answer completion generation context: publication boundary READY_PUBLICATION_DISABLED / publication prerequisites true / draft READY_DRAFT_DISABLED / sections changedFiles, verificationOutcome, rollbackState, ragFreshnessState / observations OBSERVED / observed 2 / accepted 2 / rejected 0 / missing result risk false / stale index risk true'
);
assert.equal(
  view.sourceContextText,
  'mutation final-answer completion source context: publication gate REFUSED_PUBLICATION_DISABLED / publication schema learnbot.local-agent.mutation-publication-gate.v1 / publication session session-1 / publication user user-1 / publication agent agent-1 / publication workspace workspace-1 / publication observations OBSERVED / count 2 / accepted 2 / rejected 0 / missing result risk false / stale index risk true / latest ACCEPTED / tool patch.apply / verification PASSED / rollback summary observations OBSERVED / rollback summary count 2 / rollback summary accepted 2 / rollback summary rejected 0 / rollback summary missing result risk false / rollback summary stale index risk true'
);
assert.equal(
  view.disabledText,
  'mutation final-answer completion disabled: completion false / completion invocation false / delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / final answer false / publication false / result aggregation false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'final-answer completion policy mutationFinalAnswerGenerationGate: REFUSED_FINAL_ANSWER_GENERATION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / A disabled final-answer generation gate must refuse generation before final-answer completion can be considered.',
  'final-answer completion policy finalAnswerCompletionPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / Mutation final-answer completion and delivery are disabled.',
  'final-answer completion policy finalAnswerCompletion: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / No final answer may be completed while final-answer completion is disabled.',
  'final-answer completion policy finalAnswerDelivery: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / completion false / delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / No final answer may be delivered while final-answer delivery is disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation final-answer completion blocking keys: finalAnswerCompletionPolicy, finalAnswerCompletion, finalAnswerDelivery, finalAnswerCompletionEnabled, finalAnswerDeliveryEnabled, finalResponseHandoffEnabled, deliveryReceiptEnabled, acknowledgementSaveEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const blocked = buildMutationFinalAnswerCompletionGateView({
  status: 'BLOCKED_FINAL_ANSWER_COMPLETION_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blocked.headerText, 'mutation final-answer completion gate: BLOCKED_FINAL_ANSWER_COMPLETION_DISABLED');
assert.equal(blocked.countsText, 'mutation final-answer completion counts: expected 0 / intake persisted 0');
assert.deepEqual(blocked.policyLines, [
  'final-answer completion policy unknownPolicy: UNKNOWN',
]);

const hidden = buildMutationFinalAnswerCompletionGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);
assert.equal(hidden.sourceContextText, '');

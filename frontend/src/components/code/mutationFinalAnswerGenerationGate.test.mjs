import assert from 'node:assert/strict';
import { buildMutationFinalAnswerGenerationGateView } from './mutationFinalAnswerGenerationGate.js';

const view = buildMutationFinalAnswerGenerationGateView({
  schema: 'learnbot.local-agent.mutation-final-answer-generation-gate.v1',
  status: 'REFUSED_FINAL_ANSWER_GENERATION_DISABLED',
  publicationReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  finalAnswerGenerationPolicy: 'DISABLED_AUDIT_ONLY',
  sourcePublicationGateStatus: 'REFUSED_PUBLICATION_DISABLED',
  sourcePublicationGateSchema: 'learnbot.local-agent.mutation-publication-gate.v1',
  sourcePublicationGateSessionId: 'session-1',
  sourcePublicationGateUserId: 'user-1',
  sourcePublicationGateAgentId: 'agent-1',
  sourcePublicationGateWorkspaceId: 'workspace-1',
  sourcePublicationGateAcceptedObservationSummaryStatus: 'OBSERVED',
  sourcePublicationGateAcceptedObservationCount: 2,
  sourcePublicationGateAcceptedObservationAcceptedCount: 2,
  sourcePublicationGateAcceptedObservationRejectedCount: 0,
  sourcePublicationGateMissingMutationResultRiskVisible: false,
  sourcePublicationGateStaleIndexRiskVisible: true,
  sourcePublicationGateLatestAcceptedObservationStatus: 'ACCEPTED',
  sourcePublicationGateLatestAcceptedObservationToolName: 'patch.apply',
  sourcePublicationGateLatestAcceptedObservationVerificationStatus: 'PASSED',
  sourcePublicationGateRollbackAcceptedObservationSummaryStatus: 'OBSERVED',
  sourcePublicationGateRollbackAcceptedObservationSummaryObservationCount: 2,
  sourcePublicationGateRollbackAcceptedObservationSummaryAcceptedCount: 2,
  sourcePublicationGateRollbackAcceptedObservationSummaryRejectedCount: 0,
  sourcePublicationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible: false,
  sourcePublicationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible: true,
  sourceFinalAnswerPublicationBoundaryStatus: 'READY_PUBLICATION_DISABLED',
  sourceFinalAnswerPublicationBoundaryPrerequisitesPassed: true,
  sourceFinalAnswerPublicationBoundaryDraftStatus: 'READY_DRAFT_DISABLED',
  sourceFinalAnswerPublicationBoundaryDraftSections: ['changedFiles', 'verificationOutcome', 'rollbackState', 'ragFreshnessState'],
  sourceFinalAnswerPublicationBoundaryAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceFinalAnswerPublicationBoundaryAcceptedObservationCount: 2,
  sourceFinalAnswerPublicationBoundaryAcceptedObservationAcceptedCount: 2,
  sourceFinalAnswerPublicationBoundaryAcceptedObservationRejectedCount: 0,
  sourceFinalAnswerPublicationBoundaryMissingMutationResultRiskVisible: false,
  sourceFinalAnswerPublicationBoundaryStaleIndexRiskVisible: true,
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
  finalAnswerGenerationEnabled: false,
  finalAnswerGenerationInvocationEnabled: false,
  finalAnswerCompletionEnabled: false,
  finalAnswerDeliveryEnabled: false,
  finalResponseHandoffEnabled: false,
  deliveryReceiptEnabled: false,
  acknowledgementSaveEnabled: false,
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
      key: 'mutationPublicationGate',
      status: 'REFUSED_PUBLICATION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      finalAnswerDeliveryEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'A disabled publication gate must refuse publication before final-answer generation can be considered.',
    },
    {
      key: 'finalAnswerGenerationPolicy',
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
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      finalAnswerDeliveryEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'Mutation final-answer generation is disabled.',
    },
    {
      key: 'finalAnswerGeneration',
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
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      finalAnswerCompletionEnabled: false,
      finalAnswerDeliveryEnabled: false,
      finalResponseHandoffEnabled: false,
      deliveryReceiptEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'No final answer may be generated while final-answer generation is disabled.',
    },
  ],
  blockingKeys: [
    'finalAnswerGenerationPolicy',
    'finalAnswerGeneration',
    'finalAnswerGenerationEnabled',
    'finalAnswerCompletionEnabled',
    'finalAnswerDeliveryEnabled',
    'finalResponseHandoffEnabled',
    'deliveryReceiptEnabled',
    'acknowledgementSaveEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation final-answer generation is explicitly refused: no final answer is generated.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation final-answer generation gate: REFUSED_FINAL_ANSWER_GENERATION_DISABLED / learnbot.local-agent.mutation-final-answer-generation-gate.v1 / publication ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / publication status REFUSED_PUBLICATION_DISABLED / learnbot.local-agent.mutation-publication-gate.v1'
);
assert.equal(
  view.idsText,
  'mutation final-answer generation ids: source request-123 / release attempt- / session session-1 / user user-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation final-answer generation counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.disabledText,
  'mutation final-answer generation disabled: final answer false / final answer invocation false / final-answer completion false / final-answer delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / publication false / result aggregation false / rag freshness false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.equal(
  view.publicationContextText,
  'mutation final-answer generation publication context: boundary READY_PUBLICATION_DISABLED / prerequisites true / draft READY_DRAFT_DISABLED / draft sections changedFiles, verificationOutcome, rollbackState, ragFreshnessState / observations OBSERVED / count 2 / accepted 2 / rejected 0 / missing result risk false / stale index risk true'
);
assert.equal(
  view.sourceContextText,
  'mutation final-answer generation source context: publication gate REFUSED_PUBLICATION_DISABLED / publication schema learnbot.local-agent.mutation-publication-gate.v1 / publication session session-1 / publication user user-1 / publication agent agent-1 / publication workspace workspace-1 / publication observations OBSERVED / count 2 / accepted 2 / rejected 0 / missing result risk false / stale index risk true / latest ACCEPTED / tool patch.apply / verification PASSED / rollback summary observations OBSERVED / rollback summary count 2 / rollback summary accepted 2 / rollback summary rejected 0 / rollback summary missing result risk false / rollback summary stale index risk true'
);
assert.deepEqual(view.policyLines, [
  'final-answer generation policy mutationPublicationGate: REFUSED_PUBLICATION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / final-answer completion false / final-answer delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / A disabled publication gate must refuse publication before final-answer generation can be considered.',
  'final-answer generation policy finalAnswerGenerationPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / final-answer completion false / final-answer delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / Mutation final-answer generation is disabled.',
  'final-answer generation policy finalAnswerGeneration: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / publication false / final answer false / final-answer completion false / final-answer delivery false / final-response handoff false / delivery receipt false / acknowledgement save false / No final answer may be generated while final-answer generation is disabled.',
]);
assert.equal(
  view.blockingText,
  'mutation final-answer generation blocking keys: finalAnswerGenerationPolicy, finalAnswerGeneration, finalAnswerGenerationEnabled, finalAnswerCompletionEnabled, finalAnswerDeliveryEnabled, finalResponseHandoffEnabled, deliveryReceiptEnabled, acknowledgementSaveEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const blocked = buildMutationFinalAnswerGenerationGateView({
  status: 'BLOCKED_FINAL_ANSWER_GENERATION_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blocked.headerText, 'mutation final-answer generation gate: BLOCKED_FINAL_ANSWER_GENERATION_DISABLED');
assert.equal(blocked.countsText, 'mutation final-answer generation counts: expected 0 / intake persisted 0');
assert.deepEqual(blocked.policyLines, [
  'final-answer generation policy unknownPolicy: UNKNOWN',
]);

const hidden = buildMutationFinalAnswerGenerationGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);
assert.equal(hidden.publicationContextText, '');
assert.equal(hidden.sourceContextText, '');

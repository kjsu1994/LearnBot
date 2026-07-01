import assert from 'node:assert/strict';
import { buildMutationRagFreshnessGateView } from './mutationRagFreshnessGate.js';

const view = buildMutationRagFreshnessGateView({
  schema: 'learnbot.local-agent.mutation-rag-freshness-gate.v1',
  status: 'REFUSED_RAG_FRESHNESS_DISABLED',
  rollbackFallbackReady: true,
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  ragFreshnessPolicy: 'DISABLED_AUDIT_ONLY',
  sourceRollbackFallbackGateStatus: 'REFUSED_ROLLBACK_FALLBACK_DISABLED',
  sourceRollbackFallbackGatePublicationGateSchema: 'learnbot.local-agent.mutation-publication-gate.v1',
  sourceRollbackFallbackGatePublicationGateStatus: 'REFUSED_PUBLICATION_DISABLED',
  sourceRollbackFallbackGatePublicationGateSessionId: 'session-1',
  sourceRollbackFallbackGatePublicationGateUserId: 'user-1',
  sourceRollbackFallbackGatePublicationGateAgentId: 'agent-1',
  sourceRollbackFallbackGatePublicationGateWorkspaceId: 'workspace-1',
  sourceRollbackFallbackGateLatestAcceptedObservationStatus: 'ACCEPTED',
  sourceRollbackFallbackGateLatestAcceptedObservationAccepted: true,
  sourceRollbackFallbackGateLatestAcceptedObservationRejected: false,
  sourceRollbackFallbackGateLatestAcceptedObservationTerminalFailureAccepted: false,
  sourceRollbackFallbackGateLatestAcceptedObservationToolName: 'patch.apply',
  sourceRollbackFallbackGateLatestAcceptedObservationVerificationStatus: 'PASSED',
  sourceRollbackFallbackGateAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceRollbackFallbackGateAcceptedObservationSummaryObservationCount: 2,
  sourceRollbackFallbackGateAcceptedObservationSummaryAcceptedCount: 2,
  sourceRollbackFallbackGateAcceptedObservationSummaryRejectedCount: 0,
  sourceRollbackFallbackGateAcceptedObservationSummaryMissingMutationResultRiskVisible: false,
  sourceRollbackFallbackGateAcceptedObservationSummaryStaleIndexRiskVisible: true,
  sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStatus: 'OBSERVED',
  sourceRollbackFallbackGateRollbackAcceptedObservationSummaryObservationCount: 2,
  sourceRollbackFallbackGateRollbackAcceptedObservationSummaryAcceptedCount: 2,
  sourceRollbackFallbackGateRollbackAcceptedObservationSummaryRejectedCount: 0,
  sourceRollbackFallbackGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible: false,
  sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible: true,
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
  acceptedMutationObservationSummarySchema: 'learnbot.local-agent.accepted-mutation-observation-summary.v1',
  acceptedMutationObservationSummaryStatus: 'OBSERVED',
  acceptedMutationObservationCount: 2,
  acceptedMutationObservationAcceptedCount: 2,
  acceptedMutationObservationRejectedCount: 0,
  acceptedMutationObservationTerminalFailureAcceptedCount: 0,
  acceptedMutationObservationToolCounts: {
    'patch.apply': 1,
    'command.runAllowed': 1,
  },
  acceptedMutationObservationStatusCounts: {
    ACCEPTED: 2,
  },
  missingMutationResultRiskVisible: false,
  staleIndexRiskVisible: true,
  ragFreshnessUpdateEnabled: false,
  ragFreshnessUpdateInvocationEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  acknowledgementSaveEnabled: false,
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
      key: 'mutationRollbackFallbackGate',
      status: 'REFUSED_ROLLBACK_FALLBACK_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'A disabled rollback fallback gate must refuse rollback execution before RAG freshness can be considered.',
    },
    {
      key: 'ragFreshnessPolicy',
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
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'RAG freshness updates are disabled.',
    },
    {
      key: 'ragFreshnessUpdate',
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
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'No code or document index freshness update may run while RAG freshness is disabled.',
    },
    {
      key: 'resultAggregation',
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
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'Mutation result aggregation remains disabled until RAG freshness state is modeled.',
    },
    {
      key: 'publication',
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
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'Final answer publication remains disabled until RAG freshness state is modeled.',
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
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      acknowledgementSaveEnabled: false,
      message: 'Final-answer generation remains disabled until RAG freshness state is modeled.',
    },
  ],
  blockingKeys: [
    'ragFreshnessPolicy',
    'ragFreshnessUpdate',
    'resultAggregation',
    'publication',
    'finalAnswerGeneration',
    'ragFreshnessUpdateEnabled',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'acknowledgementSaveEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation RAG freshness is explicitly refused: no freshness update, aggregation, publication, or final answer is enabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation RAG freshness gate: REFUSED_RAG_FRESHNESS_DISABLED / learnbot.local-agent.mutation-rag-freshness-gate.v1 / rollback fallback ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / rollback fallback status REFUSED_ROLLBACK_FALLBACK_DISABLED'
);
assert.equal(
  view.idsText,
  'mutation RAG freshness ids: source request-123 / release attempt- / session session-1 / user user-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.countsText,
  'mutation RAG freshness counts: expected 4 / completed 0 / accepted 0 / rejected 0 / intake persisted 0'
);
assert.equal(
  view.observationSummaryText,
  'mutation RAG freshness accepted observations: learnbot.local-agent.accepted-mutation-observation-summary.v1 / OBSERVED / observations 2 / accepted 2 / rejected 0 / terminal failures 0 / tool counts patch.apply=1, command.runAllowed=1 / status counts ACCEPTED=2 / missing result risk false / stale index risk true'
);
assert.equal(
  view.sourceContextText,
  'mutation RAG freshness source context: publication gate REFUSED_PUBLICATION_DISABLED / publication schema learnbot.local-agent.mutation-publication-gate.v1 / publication session session-1 / publication user user-1 / publication agent agent-1 / publication workspace workspace-1 / rollback latest ACCEPTED / accepted true / rejected false / terminal failure accepted false / tool patch.apply / verification PASSED / missing result risk false / stale index risk true / summary observations OBSERVED / summary count 2 / summary accepted 2 / summary rejected 0 / summary missing result risk false / summary stale index risk true / rollback summary observations OBSERVED / rollback summary count 2 / rollback summary accepted 2 / rollback summary rejected 0 / rollback summary missing result risk false / rollback summary stale index risk true'
);
assert.equal(
  view.disabledText,
  'mutation RAG freshness disabled: rag freshness false / freshness invocation false / result aggregation false / publication false / final answer false / acknowledgement save false / rollback fallback false / intake persistence false / accepted observation persistence false / post-execution observation false / result persistence false / acceptance false / release gate false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false'
);
assert.deepEqual(view.policyLines, [
  'RAG freshness policy mutationRollbackFallbackGate: REFUSED_ROLLBACK_FALLBACK_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / acknowledgement save false / A disabled rollback fallback gate must refuse rollback execution before RAG freshness can be considered.',
  'RAG freshness policy ragFreshnessPolicy: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / acknowledgement save false / RAG freshness updates are disabled.',
  'RAG freshness policy ragFreshnessUpdate: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / acknowledgement save false / No code or document index freshness update may run while RAG freshness is disabled.',
  'RAG freshness policy resultAggregation: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / acknowledgement save false / Mutation result aggregation remains disabled until RAG freshness state is modeled.',
  'RAG freshness policy publication: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / acknowledgement save false / Final answer publication remains disabled until RAG freshness state is modeled.',
  'RAG freshness policy finalAnswerGeneration: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / rag freshness false / result aggregation false / publication false / final answer false / acknowledgement save false / Final-answer generation remains disabled until RAG freshness state is modeled.',
]);
assert.equal(
  view.blockingText,
  'mutation RAG freshness blocking keys: ragFreshnessPolicy, ragFreshnessUpdate, resultAggregation, publication, finalAnswerGeneration, ragFreshnessUpdateEnabled, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, acknowledgementSaveEnabled, mutationAllowed'
);
assert.match(view.message, /explicitly refused/);

const blocked = buildMutationRagFreshnessGateView({
  status: 'BLOCKED_RAG_FRESHNESS_DISABLED',
  expectedResultCount: 0,
  intakePersistedResultCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blocked.headerText, 'mutation RAG freshness gate: BLOCKED_RAG_FRESHNESS_DISABLED');
assert.equal(blocked.countsText, 'mutation RAG freshness counts: expected 0 / intake persisted 0');
assert.deepEqual(blocked.policyLines, [
  'RAG freshness policy unknownPolicy: UNKNOWN',
]);

const hidden = buildMutationRagFreshnessGateView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.policyLines, []);

import assert from 'node:assert/strict';
import { buildAgentLoopRunnerHandoffSummaryView } from './agentLoopRunnerHandoffSummary.js';

const handoffSummary = {
  schema: 'learnbot.code-agent.creation-disabled-handoff-summary.v1',
  status: 'READY_HANDOFF_CREATION_DISABLED',
  sourceBoundaryStatus: 'RELEASE_REFUSED_GATE_DISABLED',
  expectedRequestCount: 4,
  durableMutationExecutionRowCount: 0,
  persistedRequestCount: 0,
  pushedRequestCount: 0,
  claimableRequestCount: 0,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
  message: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
};

const previewView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary,
  recommendedAction: {
    schema: 'learnbot.code-agent.runner-recommended-action.v1',
    actionKey: 'CHECK_ENQUEUE_REFUSAL',
    label: 'Check enqueue refusal',
    enabled: true,
    endpoint: '/api/code-agent/loop/runner/enqueue-read-only',
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    reason: 'Confirm the runner will not enqueue mutation work from this handoff state.',
  },
});

assert.equal(previewView.show, true);
assert.equal(
  previewView.headerText,
  'agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED / learnbot.code-agent.creation-disabled-handoff-summary.v1 / runner WAIT_CREATION_GATE_DISABLED / boundary RELEASE_REFUSED_GATE_DISABLED'
);
assert.equal(
  previewView.countsText,
  'agent loop runner handoff counts: expected 4 / durable mutation rows 0 / persisted 0 / pushed 0 / claimable 0'
);
assert.equal(
  previewView.disabledText,
  'agent loop runner handoff disabled: request creation false / enqueue false / push false / claim false / final result false / publication false / acknowledgement false / mutation false'
);
assert.equal(previewView.nestedPreviewText, '');
assert.equal(
  previewView.recommendedActionText,
  'agent loop runner recommended action: action CHECK_ENQUEUE_REFUSAL / label Check enqueue refusal / enabled true / endpoint /api/code-agent/loop/runner/enqueue-read-only / request creation false / push false / claim false / mutation false / reason Confirm the runner will not enqueue mutation work from this handoff state.'
);
assert.match(previewView.message, /request creation is disabled/);

const enqueueView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'NOT_ENQUEUED',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary,
  preview: {
    runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    handoffSummary,
  },
});

assert.equal(
  enqueueView.headerText,
  'agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED / learnbot.code-agent.creation-disabled-handoff-summary.v1 / runner NOT_ENQUEUED / summary runner WAIT_CREATION_GATE_DISABLED / boundary RELEASE_REFUSED_GATE_DISABLED'
);
assert.equal(
  enqueueView.nestedPreviewText,
  'agent loop runner nested preview: WAIT_CREATION_GATE_DISABLED / READY_HANDOFF_CREATION_DISABLED / request creation false / push false / claim false / mutation false'
);

const fallbackView = buildAgentLoopRunnerHandoffSummaryView({
  nextAction: {
    actionKey: 'READY_HANDOFF_CREATION_DISABLED',
    handoffSummary,
    recommendedAction: {
      actionKey: 'CHECK_ENQUEUE_REFUSAL',
      label: 'Check enqueue refusal',
      enabled: true,
      endpoint: '/api/code-agent/loop/runner/enqueue-read-only',
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      mutationEnabled: false,
      reason: 'Confirm the runner will not enqueue mutation work from this handoff state.',
    },
  },
});
assert.equal(fallbackView.show, true);
assert.match(fallbackView.headerText, /READY_HANDOFF_CREATION_DISABLED/);
assert.match(fallbackView.recommendedActionText, /action CHECK_ENQUEUE_REFUSAL/);
assert.match(fallbackView.recommendedActionText, /mutation false/);

const hidden = buildAgentLoopRunnerHandoffSummaryView(null);
assert.equal(hidden.show, false);
assert.equal(hidden.countsText, '');

const selectedReadOnlyView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'QUEUE_READ_ONLY_OBSERVATION',
  runnerDecision: 'ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION',
  reason: 'Queued the model-selected read-only Local Agent git.status observation. Mutation remains disabled.',
  selectedByModel: true,
  requestCreationEnabled: true,
  enqueueEnabled: true,
  pushEnabled: true,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  queuedRequest: {
    requestId: 'request-1',
    request: {
      toolName: 'git.status',
      approvalState: 'NOT_REQUIRED',
      input: {
        mutationAllowed: false,
        freshObservationOnly: true,
      },
    },
  },
  recommendedAction: {
    actionKey: 'QUEUE_SELECTED_READ_ONLY',
    label: 'Queue read-only step',
    enabled: true,
    endpoint: '/api/code-agent/loop/runner/enqueue-selected-read-only',
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    reason: 'Queue only the prepared read-only git.status observation; mutation remains disabled.',
  },
});
assert.equal(selectedReadOnlyView.show, true);
assert.equal(selectedReadOnlyView.badgeText, 'read-only queued');
assert.equal(
  selectedReadOnlyView.headerText,
  'agent loop runner selected read-only: QUEUE_READ_ONLY_OBSERVATION / ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION / model selected'
);
assert.equal(
  selectedReadOnlyView.disabledText,
  'agent loop runner selected read-only controls: request creation true / enqueue true / push true / claim false / final result false / publication false / acknowledgement false / mutation false'
);
assert.equal(
  selectedReadOnlyView.nestedPreviewText,
  'agent loop runner selected read-only tool: git.status / approval NOT_REQUIRED / mutation false / fresh observation true'
);
assert.match(selectedReadOnlyView.recommendedActionText, /action QUEUE_SELECTED_READ_ONLY/);
assert.match(selectedReadOnlyView.recommendedActionText, /mutation false/);

const selectedReadOnlyObservationView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'QUEUE_READ_ONLY_OBSERVATION',
  runnerDecision: 'ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION',
  selectedByModel: true,
  requestCreationEnabled: true,
  enqueueEnabled: true,
  pushEnabled: true,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  queuedRequest: {
    requestId: 'request-1',
    request: {
      toolName: 'git.status',
      approvalState: 'NOT_REQUIRED',
      input: {
        mutationAllowed: false,
        freshObservationOnly: true,
      },
    },
  },
}, {
  requestId: 'request-1',
  executionTarget: 'USER_LOCAL_AGENT',
  toolName: 'git.status',
  approvalState: 'NOT_REQUIRED',
  status: 'SUCCEEDED',
  input: {
    mutationAllowed: false,
    freshObservationOnly: true,
  },
  output: {
    repositoryVerification: {
      status: 'MATCH',
    },
  },
});
assert.equal(
  selectedReadOnlyObservationView.observationText,
  'agent loop runner queued observation: SUCCEEDED / tool git.status / target USER_LOCAL_AGENT / approval NOT_REQUIRED / mutation false / fresh observation true / repository verification MATCH'
);

const releaseGateSummary = {
  schema: 'learnbot.code-agent.release-gate-fresh-observation-handoff.v1',
  status: 'WAIT_FOR_RELEASE_GATE',
  sourceEventType: 'LOCAL_AGENT_APPROVAL_DECISION',
  sourceSequenceNumber: 12,
  sourceRequestId: 'source-request-1',
  approvalState: 'APPROVED',
  approvalRequestHeld: true,
  releaseRequired: true,
  readinessRoute: 'GET /api/local-agents/tools/source-request-1/readiness',
  freshObservationsRoute: 'POST /api/local-agents/tools/source-request-1/fresh-observations',
  releaseBoundaryRoute: 'POST /api/local-agents/tools/source-request-1/release-for-execution',
  runnerAutoEnqueueEnabled: false,
  freshObservationAutoEnqueueEnabled: false,
  sourcePatchRequestCreationEnabled: false,
  sourcePatchPushEnabled: false,
  sourcePatchClaimEnabled: false,
  mutationEnabled: false,
  verificationCommandExecutionEnabled: false,
  rollbackRestoreEnabled: false,
  ragFreshnessUpdateEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  deliveryEnabled: false,
  acknowledgementEnabled: false,
  runnerDecision: 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS',
  message: 'Approved held patch requires fresh Local Agent observations before release.',
};
const releaseGateView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'WAIT_FOR_RELEASE_GATE',
  runnerDecision: 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS',
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: releaseGateSummary,
});
assert.equal(releaseGateView.badgeText, 'release gate');
assert.equal(
  releaseGateView.headerText,
  'agent loop runner handoff: WAIT_FOR_RELEASE_GATE / learnbot.code-agent.release-gate-fresh-observation-handoff.v1 / runner WAIT_RELEASE_GATE_FRESH_OBSERVATIONS'
);
assert.equal(
  releaseGateView.sourceText,
  'agent loop runner release handoff source: source request source-request-1 / source event LOCAL_AGENT_APPROVAL_DECISION / sequence 12 / approval APPROVED / held true / release required true'
);
assert.equal(
  releaseGateView.routeText,
  'agent loop runner release handoff routes: readiness GET /api/local-agents/tools/source-request-1/readiness / fresh observations POST /api/local-agents/tools/source-request-1/fresh-observations / release boundary POST /api/local-agents/tools/source-request-1/release-for-execution'
);
assert.match(releaseGateView.disabledText, /runner auto-enqueue false/);
assert.match(releaseGateView.disabledText, /fresh observation auto-enqueue false/);
assert.match(releaseGateView.disabledText, /source patch request creation false/);
assert.match(releaseGateView.disabledText, /source patch push false/);
assert.match(releaseGateView.disabledText, /source patch claim false/);
assert.match(releaseGateView.disabledText, /verification command execution false/);
assert.match(releaseGateView.disabledText, /rollback restore false/);
assert.match(releaseGateView.disabledText, /RAG freshness update false/);
assert.match(releaseGateView.disabledText, /final answer generation false/);
assert.match(releaseGateView.disabledText, /delivery false/);

const freshEvidenceCompleteSummary = {
  schema: 'learnbot.code-agent.release-gate-fresh-observation-complete-state.v1',
  status: 'FRESH_EVIDENCE_COMPLETE_RELEASE_GATED',
  sourceEventType: 'LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED',
  sourceSequenceNumber: 18,
  sourceRequestId: 'source-request-1',
  releaseAttemptId: 'release-attempt-1',
  evidenceComplete: true,
  requiredCount: 2,
  linkedCount: 2,
  missingCount: 0,
  sourceOnlyFallbackCount: 0,
  blockingCount: 0,
  linkedKeys: ['repositoryVerification', 'patchDryRun'],
  blockingKeys: [],
  freshObservationEvidenceCompleteness: 'COMPLETE',
  freshObservationEvidenceStatus: 'READY_FOR_RELEASE_REVIEW',
  runnerAutoEnqueueEnabled: false,
  freshObservationAutoEnqueueEnabled: false,
  sourcePatchRequestCreationEnabled: false,
  sourcePatchPushEnabled: false,
  sourcePatchClaimEnabled: false,
  mutationEnabled: false,
  verificationCommandExecutionEnabled: false,
  rollbackRestoreEnabled: false,
  ragFreshnessUpdateEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  deliveryEnabled: false,
  acknowledgementEnabled: false,
  runnerDecision: 'WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE',
  message: 'Fresh Local Agent observations are complete; release remains gated.',
};
const freshEvidenceCompleteView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'FRESH_EVIDENCE_COMPLETE_RELEASE_GATED',
  runnerDecision: 'WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE',
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: freshEvidenceCompleteSummary,
});
assert.equal(freshEvidenceCompleteView.badgeText, 'fresh evidence');
assert.equal(
  freshEvidenceCompleteView.headerText,
  'agent loop runner handoff: FRESH_EVIDENCE_COMPLETE_RELEASE_GATED / learnbot.code-agent.release-gate-fresh-observation-complete-state.v1 / runner WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE'
);
assert.equal(
  freshEvidenceCompleteView.sourceText,
  'agent loop runner release handoff source: source request source-request-1 / release attempt release-attempt-1 / source event LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED / sequence 18'
);
assert.equal(
  freshEvidenceCompleteView.freshObservationText,
  'agent loop runner release fresh observations: evidence complete true / required count 2 / linked 2 / missing 0 / source-only fallback 0 / blocking 0 / completeness COMPLETE / status READY_FOR_RELEASE_REVIEW / linked keys repositoryVerification, patchDryRun'
);
assert.match(freshEvidenceCompleteView.disabledText, /source patch claim false/);
assert.match(freshEvidenceCompleteView.disabledText, /verification command execution false/);
assert.match(freshEvidenceCompleteView.disabledText, /RAG freshness update false/);
assert.match(freshEvidenceCompleteView.disabledText, /final answer generation false/);
assert.match(freshEvidenceCompleteView.disabledText, /acknowledgement false/);

const releaseReadinessRefreshSummary = {
  schema: 'learnbot.code-agent.release-readiness-refresh-state.v1',
  status: 'RELEASE_READINESS_REFRESHED_RELEASE_GATED',
  sourceEventType: 'LOCAL_AGENT_RELEASE_READINESS_REFRESHED',
  sourceSequenceNumber: 21,
  sourceRequestId: 'source-request-1',
  releaseAttemptId: 'release-attempt-1',
  readyToRelease: false,
  readinessMessage: 'Held patch request is not ready for Local Agent execution.',
  warningCount: 1,
  checkCount: 18,
  failedCheckKeys: ['releaseGateEnabled'],
  patchReleaseStatus: 'BLOCKED_RELEASE_DISABLED',
  patchReleasePreconditionsPassed: false,
  patchExecutionGateStatus: 'BLOCKED_RELEASE_DISABLED',
  patchExecutionPreconditionsPassed: false,
  releaseAttemptReady: false,
  freshObservationEvidenceComplete: true,
  runnerAutoEnqueueEnabled: false,
  freshObservationAutoEnqueueEnabled: false,
  sourcePatchRequestCreationEnabled: false,
  sourcePatchPushEnabled: false,
  sourcePatchClaimEnabled: false,
  claimEnabled: false,
  claimable: false,
  mutationEnabled: false,
  verificationCommandExecutionEnabled: false,
  rollbackRestoreEnabled: false,
  ragFreshnessUpdateEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  deliveryEnabled: false,
  acknowledgementEnabled: false,
  runnerDecision: 'WAIT_RELEASE_GATE_READINESS_REFRESHED',
  message: 'Release readiness was refreshed from fresh evidence, but release and mutation remain disabled.',
};
const releaseReadinessRefreshView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'RELEASE_READINESS_REFRESHED_RELEASE_GATED',
  runnerDecision: 'WAIT_RELEASE_GATE_READINESS_REFRESHED',
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: releaseReadinessRefreshSummary,
});
assert.equal(releaseReadinessRefreshView.badgeText, 'readiness');
assert.equal(
  releaseReadinessRefreshView.headerText,
  'agent loop runner handoff: RELEASE_READINESS_REFRESHED_RELEASE_GATED / learnbot.code-agent.release-readiness-refresh-state.v1 / runner WAIT_RELEASE_GATE_READINESS_REFRESHED'
);
assert.equal(
  releaseReadinessRefreshView.sourceText,
  'agent loop runner release handoff source: source request source-request-1 / release attempt release-attempt-1 / source event LOCAL_AGENT_RELEASE_READINESS_REFRESHED / sequence 21'
);
assert.equal(
  releaseReadinessRefreshView.readinessText,
  'agent loop runner release readiness: ready to release false / readiness message Held patch request is not ready for Local Agent execution. / warnings 1 / checks 18 / failed checks releaseGateEnabled / patch release BLOCKED_RELEASE_DISABLED / patch release preconditions false / patch execution gate BLOCKED_RELEASE_DISABLED / patch execution preconditions false / release attempt ready false / fresh evidence complete true'
);
assert.match(releaseReadinessRefreshView.disabledText, /source patch claim false/);
assert.match(releaseReadinessRefreshView.disabledText, /claim false/);
assert.match(releaseReadinessRefreshView.disabledText, /verification command execution false/);
assert.match(releaseReadinessRefreshView.disabledText, /rollback restore false/);
assert.match(releaseReadinessRefreshView.disabledText, /RAG freshness update false/);
assert.match(releaseReadinessRefreshView.disabledText, /final answer generation false/);
assert.match(releaseReadinessRefreshView.disabledText, /delivery false/);
assert.match(releaseReadinessRefreshView.disabledText, /acknowledgement false/);

const releaseReviewView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RELEASE_READINESS_REFRESHED_RELEASE_GATED',
  actionKey: 'RELEASE_READINESS_REFRESHED_RELEASE_GATED',
  runnerDecision: 'RELEASE_REVIEW_REFUSED_GATE_DISABLED',
  reason: 'Release review recorded the disabled release boundary.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: releaseReadinessRefreshSummary,
  boundary: {
    status: 'RELEASE_REFUSED_GATE_DISABLED',
    actionMode: 'REFUSAL_ONLY',
    releaseGateEnabled: false,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    writeHelperEnabled: false,
    applyEnabled: false,
    testEnabled: false,
    rollbackRestoreEnabled: false,
    ragFreshnessUpdateEnabled: false,
    mutationAllowed: false,
    blockingReasons: ['release gate is disabled', 'held patch request remains non-claimable'],
  },
});
assert.equal(releaseReviewView.badgeText, 'release review');
assert.match(
  releaseReviewView.headerText,
  /agent loop runner handoff: RELEASE_READINESS_REFRESHED_RELEASE_GATED \/ learnbot\.code-agent\.release-readiness-refresh-state\.v1 \/ runner RELEASE_REVIEW_REFUSED_GATE_DISABLED \/ summary runner WAIT_RELEASE_GATE_READINESS_REFRESHED \/ review boundary RELEASE_REFUSED_GATE_DISABLED/
);
assert.match(
  releaseReviewView.boundaryText,
  /agent loop runner release review boundary: status RELEASE_REFUSED_GATE_DISABLED \/ action REFUSAL_ONLY \/ release gate false \/ request creation false \/ push false \/ claim false \/ claimable false \/ write helper false \/ apply false \/ test false \/ rollback restore false \/ RAG freshness update false \/ mutation false \/ blocking release gate is disabled, held patch request remains non-claimable/
);

const releaseRefusalStopSummary = {
  schema: 'learnbot.code-agent.release-boundary-refusal-summary.v1',
  status: 'RELEASE_REVIEW_REFUSED_GATE_DISABLED',
  sourceEventType: 'LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED',
  sourceSequenceNumber: 24,
  sourceRequestId: 'source-request-1',
  releaseAttemptId: 'release-attempt-1',
  boundaryStatus: 'RELEASE_REFUSED_GATE_DISABLED',
  actionMode: 'REFUSAL_ONLY',
  blockingReasons: ['release gate is disabled', 'held patch request remains non-claimable'],
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  claimable: false,
  mutationEnabled: false,
  verificationCommandExecutionEnabled: false,
  rollbackRestoreEnabled: false,
  ragFreshnessUpdateEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  deliveryEnabled: false,
  acknowledgementEnabled: false,
  runnerDecision: 'NO_REQUEST_PREPARED',
  message: 'Report that release was refused and mutation remains disabled.',
};
const releaseRefusalStopView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'RECORDED',
  actionKey: 'STOP_WITH_REASON',
  runnerDecision: 'NO_REQUEST_PREPARED',
  reason: 'Report that release was refused and mutation remains disabled.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: releaseRefusalStopSummary,
});
assert.equal(releaseRefusalStopView.badgeText, 'release refused');
assert.equal(
  releaseRefusalStopView.headerText,
  'agent loop runner handoff: RELEASE_REVIEW_REFUSED_GATE_DISABLED / learnbot.code-agent.release-boundary-refusal-summary.v1 / runner NO_REQUEST_PREPARED'
);
assert.equal(
  releaseRefusalStopView.sourceText,
  'agent loop runner release handoff source: source request source-request-1 / release attempt release-attempt-1 / source event LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED / sequence 24'
);
assert.match(
  releaseRefusalStopView.boundaryText,
  /agent loop runner release refusal stop: status RELEASE_REVIEW_REFUSED_GATE_DISABLED \/ action REFUSAL_ONLY \/ boundary RELEASE_REFUSED_GATE_DISABLED \/ release gate false \/ request creation false \/ push false \/ claim false \/ claimable false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false \/ blocking release gate is disabled, held patch request remains non-claimable/
);
assert.match(
  releaseRefusalStopView.disabledText,
  /request creation false \/ enqueue false \/ push false \/ claim false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false/
);
assert.equal(releaseRefusalStopView.message, 'Report that release was refused and mutation remains disabled.');

const completedFlowSummary = {
  schema: 'learnbot.code-agent.approved-execution-flow-completed-handoff.v1',
  status: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
  runnerDecision: 'READY_FINAL_RESULT_DISABLED',
  sourceEventType: 'LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED',
  sourceSequenceNumber: 31,
  sourceRequestId: 'source-request-1',
  releaseAttemptId: 'release-attempt-1',
  requestIdSource: 'durableCompletedRows',
  stepCount: 4,
  ordered: true,
  identityConsistent: true,
  releaseAttemptLinked: true,
  allTerminal: true,
  allSucceeded: true,
  finalMutationReportSummaryStatus: 'READY_SUMMARY_AUDIT_ONLY',
  ragFreshnessMarkerStatus: 'STALE_INDEX_WARNING_REQUIRED',
  finalAnswerPublicationHandoffStatus: 'READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED',
  acknowledgementSaveHandoffStatus: 'READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED',
  finalResultHandoff: {
    schema: 'learnbot.code-agent.approved-execution-flow-final-result-handoff.v1',
    status: 'READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED',
    finalMutationReportSummaryStatus: 'READY_SUMMARY_AUDIT_ONLY',
    ragFreshnessMarkerStatus: 'STALE_INDEX_WARNING_REQUIRED',
    finalAnswerPublicationHandoffStatus: 'READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED',
    acknowledgementSaveHandoffStatus: 'READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED',
    staleIndexDisclosureModeled: true,
    finalAnswerDeliveryEnabled: false,
    finalAnswerGenerationEnabled: false,
    publicationEnabled: false,
    acknowledgementSaveEnabled: false,
    ragFreshnessUpdateEnabled: false,
    partialReindexEnabled: false,
    followUpMutationEnabled: false,
    mutationEnabled: false,
  },
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  ragFreshnessUpdateEnabled: false,
  followUpMutationEnabled: false,
  mutationEnabled: false,
  message: 'Approved Local Agent execution flow is complete and visible for final-result handoff, but final publication remains disabled.',
};
const completedFlowView = buildAgentLoopRunnerHandoffSummaryView({
  status: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
  actionKey: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
  runnerDecision: 'READY_FINAL_RESULT_DISABLED',
  reason: 'Approved Local Agent execution flow completed, but final result publication remains disabled.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: completedFlowSummary,
});
assert.equal(completedFlowView.badgeText, 'flow complete');
assert.equal(
  completedFlowView.headerText,
  'agent loop runner handoff: APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED / learnbot.code-agent.approved-execution-flow-completed-handoff.v1 / runner READY_FINAL_RESULT_DISABLED'
);
assert.equal(
  completedFlowView.sourceText,
  'agent loop runner release handoff source: source request source-request-1 / release attempt release-attempt-1 / source event LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED / sequence 31'
);
assert.match(
  completedFlowView.boundaryText,
  /agent loop runner approved execution flow complete: status APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED \/ request id source durableCompletedRows \/ steps 4 \/ ordered true \/ identity consistent true \/ release linked true \/ terminal true \/ succeeded true \/ final report summary READY_SUMMARY_AUDIT_ONLY \/ RAG marker STALE_INDEX_WARNING_REQUIRED \/ publication handoff READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED \/ acknowledgement handoff READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED \/ final result false \/ publication false \/ RAG freshness update false \/ acknowledgement false \/ follow-up mutation false \/ mutation false/
);
assert.match(
  completedFlowView.finalResultText,
  /agent loop runner final-result handoff: schema learnbot\.code-agent\.approved-execution-flow-final-result-handoff\.v1 \/ status READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED \/ final report summary READY_SUMMARY_AUDIT_ONLY \/ RAG marker STALE_INDEX_WARNING_REQUIRED \/ publication handoff READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED \/ acknowledgement handoff READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED \/ stale disclosure modeled true \/ delivery false \/ final answer generation false \/ publication false \/ acknowledgement save false \/ RAG freshness update false \/ partial reindex false \/ follow-up mutation false \/ mutation false/
);
assert.match(
  completedFlowView.disabledText,
  /request creation false \/ enqueue false \/ push false \/ claim false \/ RAG freshness update false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
);
assert.equal(completedFlowView.message, completedFlowSummary.message);

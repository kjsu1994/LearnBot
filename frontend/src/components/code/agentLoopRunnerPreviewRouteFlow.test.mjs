import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';
import { previewAgentLoopRunner } from '../../features/code/agentLoopRunnerPreviewClient.js';
import { enqueueAgentLoopRunnerReadOnly } from '../../features/code/agentLoopRunnerReadOnlyEnqueueClient.js';
import { reviewAgentLoopRunnerReleaseGate } from '../../features/code/agentLoopRunnerReleaseReviewClient.js';
import { previewAgentLoopRunnerFinalResultPublication } from '../../features/code/runner/agentLoopRunnerFinalResultPublicationPreviewClient.js';
import { previewAgentLoopRunnerM8EntryReadiness } from '../../features/code/runner/agentLoopRunnerM8EntryReadinessClient.js';
import { buildCodeWorkspaceReadinessSmokeProps } from './codeWorkspaceReadinessSmokeHarness.mjs';
import { assertNoForbiddenTrueFlags } from './mutationDisabledFlagGuard.js';

const latestAttempt = {
  mutationRequestCreationGate: {
    status: 'REFUSED_CREATION_DISABLED',
    expectedRequestCount: 4,
    durableMutationExecutionRowCount: 0,
    persistedRequestCount: 0,
    pushedRequestCount: 0,
    claimableRequestCount: 0,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
  },
};

const runnerPreviewResponse = {
  status: 'RECORDED',
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
  reason: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
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
  handoffSummary: {
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
  },
};

const runnerEnqueueResponse = {
  status: 'RECORDED',
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'NOT_ENQUEUED',
  reason: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  queuedRequest: null,
  handoffSummary: {
    schema: 'learnbot.code-agent.creation-disabled-handoff-summary.v1',
    status: 'READY_HANDOFF_CREATION_DISABLED',
    sourceBoundaryStatus: 'RELEASE_REFUSED_GATE_DISABLED',
    expectedRequestCount: 4,
    durableMutationExecutionRowCount: 0,
    persistedRequestCount: 0,
    pushedRequestCount: 0,
    claimableRequestCount: 0,
    requestCreationEnabled: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    finalResultEnabled: false,
    publicationEnabled: false,
    acknowledgementEnabled: false,
    runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
    message: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
  },
  preview: {
    runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    handoffSummary: {
      status: 'READY_HANDOFF_CREATION_DISABLED',
    },
  },
};

const validatedDryRunIntentRunnerPreviewResponse = {
  status: 'APPROVAL_REQUIRED',
  actionKey: 'WAIT_FOR_APPROVAL',
  runnerDecision: 'WAIT_FOR_APPROVAL',
  reason: 'Review the persisted validated dry-run intent before any future claimable non-mutating dry-run.',
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  nextAction: {
    actionKey: 'WAIT_FOR_APPROVAL',
    handoffSummary: {
      schema: 'learnbot.code-agent.validated-dry-run-intent-review-handoff.v1',
      status: 'VALIDATED_DRY_RUN_INTENT_REVIEW',
      sourceEventType: 'LOCAL_AGENT_APPROVAL_REQUEST_CREATED',
      sourceSequenceNumber: 13,
      sourceRequestId: 'dry-run-intent-1',
      approvalState: 'REQUIRED',
      validatedDryRunIntent: true,
      dryRunIntentPersisted: true,
      reviewSurface: 'CODE_WORKSPACE_LOOP_REVIEW',
      requestPersisted: true,
      eligibilityRoute: 'GET /api/code-agent/local-patch-request/dry-run-intent/dry-run-intent-1/eligibility',
      requestCreationEnabled: false,
      queueEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      dryRunOnly: true,
      mutationAllowed: false,
      approvalBypassAllowed: false,
      message: 'Review the persisted validated dry-run intent eligibility before any future claimable non-mutating dry-run.',
    },
  },
};

const validatedDryRunIntentEligibilityResponse = {
  schema: 'learnbot.server.validated-revised-patch-dry-run-eligibility.v1',
  status: 'READY_DRY_RUN_RELEASE_DISABLED',
  requestId: 'dry-run-intent-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  toolName: 'patch.apply',
  executionTarget: 'USER_LOCAL_AGENT',
  approvalState: 'REQUIRED',
  requestStatus: 'APPROVAL_REQUIRED',
  validatedDryRunIntent: true,
  dryRunIntentPersisted: true,
  requestPersisted: true,
  requestCreationEnabled: false,
  queueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  claimable: false,
  dryRunOnly: true,
  mutationAllowed: false,
  approvalBypassAllowed: false,
  prerequisitesPassed: true,
  targetFiles: ['README.md'],
  blockingKeys: [],
  checks: [
    { key: 'patchApplyTool', passed: true, message: 'Persisted intent must be a patch.apply Local Agent request.' },
    { key: 'mutationDisabled', passed: true, message: 'Mutation must remain disabled for this dry-run intent.' },
  ],
  futureDryRunReleaseGate: {
    schema: 'learnbot.server.validated-revised-patch-dry-run-release-gate.v1',
    status: 'READY_RELEASE_DISABLED',
    prerequisitesPassed: true,
    requestCreationEnabled: false,
    queueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    dryRunOnly: true,
    mutationAllowed: false,
    approvalBypassAllowed: false,
  },
  message: 'This is a disabled eligibility read model only; it creates no request, pushes nothing, and makes no Local Agent work claimable.',
};

const validatedDryRunIntentTransitionPreviewResponse = {
  schema: 'learnbot.server.validated-revised-patch-dry-run-transition-preview.v1',
  status: 'READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED',
  sourceRequestId: 'dry-run-intent-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  prerequisitesPassed: true,
  requestPersisted: false,
  requestCreationEnabled: false,
  queueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  claimable: false,
  dryRunOnly: true,
  mutationAllowed: false,
  approvalBypassAllowed: false,
  transitionGate: {
    schema: 'learnbot.server.validated-revised-patch-dry-run-transition-gate.v1',
    status: 'READY_TRANSITION_DISABLED',
    prerequisitesPassed: true,
    requestCreationEnabled: false,
    queueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    dryRunOnly: true,
    mutationAllowed: false,
    approvalBypassAllowed: false,
  },
  eligibility: validatedDryRunIntentEligibilityResponse,
  wouldBeClaimableDryRunRequest: {
    schema: 'learnbot.server.validated-revised-patch-claimable-dry-run-request-preview.v1',
    status: 'READY_REQUEST_PREVIEW_ONLY',
    sourceRequestId: 'dry-run-intent-1',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalState: 'NOT_REQUIRED',
    requestPersisted: false,
    requestCreationEnabled: false,
    queueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    dryRunOnly: true,
    mutationAllowed: false,
    approvalBypassAllowed: false,
    input: {
      targetFiles: ['README.md'],
    },
  },
  message: 'This is a disabled transition preview only; it creates no request, pushes nothing, and makes no Local Agent work claimable.',
};

const completedLocalDryRunRequest = {
  requestId: 'local-dry-run-request-1',
  request: {
    toolName: 'patch.apply',
    approvalState: 'NOT_REQUIRED',
    input: {
      sourceRequestId: 'request-route-flow-1',
      dryRunOnly: true,
      mutationAllowed: false,
    },
  },
};

const completedLocalDryRunResult = {
  requestId: 'local-dry-run-request-1',
  status: 'REJECTED',
  toolName: 'patch.apply',
  approvalState: 'NOT_REQUIRED',
  failureCode: 'UNSAFE_TOOL',
  error: 'Patch dry-run passed and a local snapshot was created, but file mutation is disabled until approval.',
  input: {
    sourceRequestId: 'request-route-flow-1',
    dryRunOnly: true,
    mutationAllowed: false,
  },
  output: {
    dryRun: true,
    preflightPassed: true,
    snapshotCreated: true,
    mutationApplied: false,
    files: [
      {
        path: 'README.md',
        contextMatched: true,
      },
    ],
  },
  responseWarnings: ['dry-run completed without mutation'],
};

const failedLocalDryRunResult = {
  requestId: 'local-dry-run-request-2',
  status: 'FAILED',
  toolName: 'patch.apply',
  approvalState: 'NOT_REQUIRED',
  failureCode: 'CONTEXT_MISMATCH',
  error: 'Patch context did not match the current workspace file.',
  input: {
    sourceRequestId: 'request-route-flow-1',
    dryRunOnly: true,
    mutationAllowed: false,
  },
  output: {
    dryRun: true,
    preflightPassed: false,
    snapshotCreated: false,
    mutationApplied: false,
  },
  responseWarnings: ['dry-run failed without mutation'],
};

const releaseGateRunnerPreviewResponse = {
  status: 'RECORDED',
  actionKey: 'WAIT_FOR_RELEASE_GATE',
  runnerDecision: 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS',
  reason: 'Approved held patch requires fresh Local Agent observations before release.',
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
  handoffSummary: {
    schema: 'learnbot.code-agent.release-gate-fresh-observation-handoff.v1',
    status: 'WAIT_FOR_RELEASE_GATE',
    sourceEventType: 'LOCAL_AGENT_APPROVAL_DECISION',
    sourceSequenceNumber: 12,
    sourceRequestId: 'source-request-route-1',
    approvalState: 'APPROVED',
    approvalRequestHeld: true,
    releaseRequired: true,
    readinessRoute: 'GET /api/local-agents/tools/source-request-route-1/readiness',
    freshObservationsRoute: 'POST /api/local-agents/tools/source-request-route-1/fresh-observations',
    releaseBoundaryRoute: 'POST /api/local-agents/tools/source-request-route-1/release-for-execution',
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
  },
};

const freshEvidenceCompleteRunnerPreviewResponse = {
  status: 'RECORDED',
  actionKey: 'FRESH_EVIDENCE_COMPLETE_RELEASE_GATED',
  runnerDecision: 'WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE',
  reason: 'Fresh Local Agent observations are complete; release remains gated.',
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
  handoffSummary: {
    schema: 'learnbot.code-agent.release-gate-fresh-observation-complete-state.v1',
    status: 'FRESH_EVIDENCE_COMPLETE_RELEASE_GATED',
    sourceEventType: 'LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED',
    sourceSequenceNumber: 18,
    sourceRequestId: 'source-request-route-1',
    releaseAttemptId: 'release-attempt-route-1',
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
  },
};

const releaseReadinessRefreshRunnerPreviewResponse = {
  status: 'RECORDED',
  actionKey: 'RELEASE_READINESS_REFRESHED_RELEASE_GATED',
  runnerDecision: 'WAIT_RELEASE_GATE_READINESS_REFRESHED',
  reason: 'Release readiness was refreshed from fresh evidence; release, claim, and mutation remain disabled.',
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
  handoffSummary: {
    schema: 'learnbot.code-agent.release-readiness-refresh-state.v1',
    status: 'RELEASE_READINESS_REFRESHED_RELEASE_GATED',
    sourceEventType: 'LOCAL_AGENT_RELEASE_READINESS_REFRESHED',
    sourceSequenceNumber: 21,
    sourceRequestId: 'source-request-route-1',
    releaseAttemptId: 'release-attempt-route-1',
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
  },
};

const releaseReviewResponse = {
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
  handoffSummary: releaseReadinessRefreshRunnerPreviewResponse.handoffSummary,
  preview: releaseReadinessRefreshRunnerPreviewResponse,
  boundary: {
    requestId: 'source-request-route-1',
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
    message: 'Release gate is disabled.',
  },
};

const releaseRefusalStopRunnerPreviewResponse = {
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
  handoffSummary: {
    schema: 'learnbot.code-agent.release-boundary-refusal-summary.v1',
    status: 'RELEASE_REVIEW_REFUSED_GATE_DISABLED',
    sourceEventType: 'LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED',
    sourceSequenceNumber: 24,
    sourceRequestId: 'source-request-route-1',
    releaseAttemptId: 'release-attempt-route-1',
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
  },
  nextAction: {
    status: 'RECORDED',
    actionKey: 'STOP_WITH_REASON',
    reason: 'Report that release was refused and mutation remains disabled.',
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    finalResultEnabled: false,
    publicationEnabled: false,
    acknowledgementEnabled: false,
    sourceEventType: 'LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED',
    handoffSummary: {
      schema: 'learnbot.code-agent.release-boundary-refusal-summary.v1',
      status: 'RELEASE_REVIEW_REFUSED_GATE_DISABLED',
      sourceRequestId: 'source-request-route-1',
      releaseAttemptId: 'release-attempt-route-1',
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
    },
  },
};

const completedFlowRunnerPreviewResponse = {
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
  handoffSummary: {
    schema: 'learnbot.code-agent.approved-execution-flow-completed-handoff.v1',
    status: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
    runnerDecision: 'READY_FINAL_RESULT_DISABLED',
    sourceEventType: 'LOCAL_AGENT_APPROVED_EXECUTION_FLOW_COMPLETED',
    sourceSequenceNumber: 31,
    sourceRequestId: 'source-request-route-1',
    releaseAttemptId: 'release-attempt-route-1',
    requestIdSource: 'durableCompletedRows',
    stepCount: 4,
    ordered: true,
    identityConsistent: true,
    releaseAttemptLinked: true,
    approvalRequestLinked: true,
    allTerminal: true,
    allSucceeded: true,
    postRetryVerificationPassed: true,
    postRetryVerificationPartialReindexMarkerRequired: true,
    finalMutationReportSummaryStatus: 'READY_SUMMARY_AUDIT_ONLY',
    ragFreshnessMarkerStatus: 'STALE_INDEX_WARNING_REQUIRED',
    partialReindexPlanStatus: 'PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED',
    partialReindexEnqueueBoundaryStatus: 'READY_ENQUEUE_DISABLED',
    partialReindexEnqueueReady: true,
    finalAnswerPublicationHandoffStatus: 'READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED',
    acknowledgementSaveHandoffStatus: 'READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED',
    finalResultHandoff: {
      schema: 'learnbot.code-agent.approved-execution-flow-final-result-handoff.v1',
      status: 'READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED',
      finalMutationReportSummaryStatus: 'READY_SUMMARY_AUDIT_ONLY',
      postRetryVerificationPassed: true,
      postRetryVerificationApprovalLinked: true,
      postRetryVerificationReleaseLinked: true,
      postRetryVerificationPartialReindexMarkerRequired: true,
      ragFreshnessMarkerStatus: 'STALE_INDEX_WARNING_REQUIRED',
      partialReindexPlanStatus: 'PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED',
      partialReindexPlanFreshnessAction: 'PARTIAL_REINDEX_TARGET_FILES_AFTER_APPROVED_RETRY',
      partialReindexPlanTargetFiles: ['README.md'],
      partialReindexEnqueueBoundaryStatus: 'READY_ENQUEUE_DISABLED',
      partialReindexEnqueueReady: true,
      partialReindexRepositoryId: 'repo-1',
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
    message: 'Approved execution completed and audit-only final-result handoff context is modeled, but publication, final answer delivery, acknowledgement save, RAG freshness update, and follow-up mutation remain disabled.',
  },
  recommendedAction: {
    schema: 'learnbot.code-agent.runner-recommended-action.v1',
    actionKey: 'STOP_AND_REPORT',
    label: 'Stop and report',
    enabled: false,
    endpoint: '',
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    reason: 'Stop the loop and report the blocking state without creating Local Agent work.',
  },
};

const finalResultPublicationPreviewResponse = {
  loopId: 'loop-preview-1',
  repositoryId: 'repo-1',
  status: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
  actionKey: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
  publicationDecision: 'READY_FINAL_RESULT_PUBLICATION_DISABLED',
  reason: 'Final result handoff is ready for audit-only reporting, but publication remains disabled.',
  finalResultReady: true,
  finalResultEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  finalAnswerDeliveryEnabled: false,
  acknowledgementEnabled: false,
  acknowledgementSaveEnabled: false,
  ragFreshnessUpdateEnabled: false,
  partialReindexEnabled: false,
  followUpMutationEnabled: false,
  mutationEnabled: false,
  handoffSummary: completedFlowRunnerPreviewResponse.handoffSummary,
  finalResultHandoff: completedFlowRunnerPreviewResponse.handoffSummary.finalResultHandoff,
  runnerPreview: completedFlowRunnerPreviewResponse,
};

const m8EntryReadinessResponse = {
  loopId: 'loop-preview-1',
  repositoryId: 'repo-1',
  status: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
  actionKey: 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED',
  m7ClosureDecision: 'M7_CLOSURE_READY',
  m8EntryDecision: 'M8_ENTRY_READY',
  reason: 'M8 productization can start, but no M8 execution or delivery controls are enabled.',
  m7ClosureReady: true,
  m8EntryReady: true,
  finalResultHandoffReady: true,
  finalResultPublicationPreviewReady: true,
  m8WorkEnabled: false,
  cliPackagingEnabled: false,
  installerEnabled: false,
  publicationEnabled: false,
  finalAnswerDeliveryEnabled: false,
  acknowledgementSaveEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationEnabled: false,
  blockingReasons: [],
  handoffSummary: completedFlowRunnerPreviewResponse.handoffSummary,
  finalResultHandoff: completedFlowRunnerPreviewResponse.handoffSummary.finalResultHandoff,
  finalResultPublicationPreview: finalResultPublicationPreviewResponse,
};

const submissionPlanReviewPreviewResponse = {
  schema: 'learnbot.server.code-agent.loop-submission-plan.v1',
  repositoryId: 'repo-1',
  spaceId: 'space-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  instruction: 'test',
  maxSteps: 6,
  readyForDisabledPlan: true,
  enabled: false,
  requestCreationEnabled: false,
  mutationEnabled: false,
  patchDryRunApprovalHandoffPlan: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-handoff-plan.v1',
    status: 'READY_APPROVAL_REQUEST_PREVIEW_DISABLED',
    requestCreationEnabled: false,
    approvalRequestCreationEnabled: false,
    enqueueEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
  },
  patchDryRunApprovalReviewPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1',
    status: 'READY_BROWSER_REVIEW_DISABLED',
    reviewSurface: 'CODE_WORKSPACE_LOOP_REVIEW',
    sourcePlanStatus: 'READY_APPROVAL_REQUEST_PREVIEW_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'AWAITING_USER_REVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    requestCreationEnabled: false,
    approvalRequestCreationEnabled: false,
    approvalPersistenceEnabled: false,
    enqueueEnabled: false,
    claimEnabled: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    reason: 'CLI dry-run approval evidence is ready for browser review, but all execution remains disabled.',
  },
  patchDryRunApprovalIntentPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1',
    status: 'READY_APPROVAL_INTENT_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    reviewProvided: true,
    approvalIntentPrepared: true,
    sourceReviewStatus: 'READY_BROWSER_REVIEW_DISABLED',
    sourceReviewSurface: 'CODE_WORKSPACE_LOOP_REVIEW',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'USER_REVIEW_REQUIRED',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalIntentCreationEnabled: false,
    approvalPersistenceEnabled: false,
    requestCreationEnabled: false,
    approvalRequestCreationEnabled: false,
    enqueueEnabled: false,
    claimEnabled: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Browser review evidence is ready to shape an approval intent, but all execution remains disabled.',
  },
  patchDryRunApprovalRequestCreationPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1',
    status: 'READY_APPROVAL_REQUEST_CREATION_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    approvalIntentProvided: true,
    approvalRequestCreationPrepared: true,
    approvalPersistencePrepared: true,
    sourceIntentStatus: 'READY_APPROVAL_INTENT_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVAL_REQUIRED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    localAgentToolRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Approval intent is ready to model browser approval persistence and approval-request creation, but all execution remains disabled.',
  },
  patchDryRunApprovalDecisionPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1',
    status: 'READY_APPROVAL_DECISION_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    approvalRequestCreationProvided: true,
    approvalDecisionPrepared: true,
    sourceRequestCreationStatus: 'READY_APPROVAL_REQUEST_CREATION_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'AWAITING_BROWSER_DECISION_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    decisionOptions: [
      {
        action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN',
        prepared: true,
        enabled: false,
        approvalDecisionPersistenceEnabled: false,
        requestCreationEnabled: false,
        mutationEnabled: false,
      },
      {
        action: 'DENY_SNAPSHOT_WRITING_DRY_RUN',
        prepared: true,
        enabled: false,
        approvalDecisionPersistenceEnabled: false,
        requestCreationEnabled: false,
        mutationEnabled: false,
      },
    ],
    heldRequestReview: {
      schema: 'learnbot.server.code-agent.patch-dry-run-held-request-review-preview.v1',
      status: 'READY_HELD_REQUEST_REVIEW_DISABLED',
      heldRequestReviewPrepared: true,
      heldRequestCreated: false,
      approvalDecisionRecorded: false,
      requestCreationEnabled: false,
      claimable: false,
      mutationEnabled: false,
    },
    approvalDecisionEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-decision-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    localAgentToolRequestCreated: false,
    heldRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Browser approval decision can be modeled for approve or deny, but all execution remains disabled.',
  },
  patchDryRunApprovalDecisionPersistencePreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1',
    status: 'READY_APPROVAL_DECISION_PERSISTENCE_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    approvalDecisionProvided: true,
    approvalDecisionPersistencePrepared: true,
    heldRequestReviewPrepared: true,
    sourceDecisionStatus: 'READY_APPROVAL_DECISION_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVAL_DECISION_HELD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalDecisionEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-decision-preview',
    approvalDecisionPersistenceEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-decision-persistence-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    localAgentToolRequestCreated: false,
    heldRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Approval decision persistence and held-request review can be modeled, but all execution remains disabled.',
  },
  patchDryRunHeldRequestReviewPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1',
    status: 'READY_HELD_REQUEST_REVIEW_ACTION_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    approvalDecisionPersistenceProvided: true,
    heldRequestReviewActionPrepared: true,
    heldRequestReviewPrepared: true,
    sourceDecisionPersistenceStatus: 'READY_APPROVAL_DECISION_PERSISTENCE_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'HELD_REQUEST_BROWSER_REVIEW_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    reviewActions: [
      { action: 'REVIEW_HELD_APPROVAL', prepared: true, enabled: false },
      { action: 'APPROVE_HELD_APPROVAL', prepared: true, enabled: false },
      { action: 'DENY_HELD_APPROVAL', prepared: true, enabled: false },
    ],
    approvalDecisionPersistenceEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-decision-persistence-preview',
    heldRequestReviewEndpoint: '/api/code-agent/loop/runner/patch-dry-run-held-request-review-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    localAgentToolRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Held request review actions can be displayed for browser review, but all execution remains disabled.',
  },
  patchDryRunApprovalActionPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1',
    status: 'READY_APPROVAL_ACTION_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    heldRequestReviewProvided: true,
    approvalActionPrepared: true,
    heldRequestReviewPrepared: true,
    sourceHeldRequestReviewStatus: 'READY_HELD_REQUEST_REVIEW_ACTION_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'AWAITING_APPROVE_OR_DENY_ACTION_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    heldRequestReviewEndpoint: '/api/code-agent/loop/runner/patch-dry-run-held-request-review-preview',
    approvalActionEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-action-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Approve and deny actions can be displayed for browser review, but all execution remains disabled.',
  },
  patchDryRunApprovalActionPersistencePreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1',
    status: 'READY_APPROVAL_ACTION_PERSISTENCE_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    approvalActionProvided: true,
    approvalActionPersistencePrepared: true,
    heldRequestReviewPrepared: true,
    sourceApprovalActionStatus: 'READY_APPROVAL_ACTION_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVAL_ACTION_PERSISTENCE_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    approvalActionEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-action-preview',
    approvalActionPersistenceEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-action-persistence-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Approval action persistence can be modeled, but all execution remains disabled.',
  },
  patchDryRunApprovalRecordPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1',
    status: 'READY_APPROVAL_RECORD_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    approvalActionPersistenceProvided: true,
    approvalRecordPrepared: true,
    localAgentRequestCreationPrepared: true,
    sourceApprovalActionPersistenceStatus: 'READY_APPROVAL_ACTION_PERSISTENCE_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVAL_RECORD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    approvalActionPersistenceEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-action-persistence-preview',
    approvalRecordEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-record-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Approval record and Local Agent request creation can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentRequestEnvelopePreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1',
    status: 'READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    approvalRecordProvided: true,
    localAgentRequestEnvelopePrepared: true,
    localAgentRequestCreationPrepared: true,
    sourceApprovalRecordStatus: 'READY_APPROVAL_RECORD_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    approvalRecordEndpoint: '/api/code-agent/loop/runner/patch-dry-run-approval-record-preview',
    localAgentRequestEnvelopeEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-envelope-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'The future Local Agent patch.apply dry-run request envelope can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentRequestCreationPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1',
    status: 'READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    localAgentRequestEnvelopeProvided: true,
    localAgentRequestEnvelopePrepared: true,
    localAgentRequestCreationPrepared: true,
    queueHandoffPrepared: true,
    sourceLocalAgentRequestEnvelopeStatus: 'READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    localAgentRequestEnvelopeEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-envelope-preview',
    localAgentRequestCreationEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-creation-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    durableLocalAgentRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Durable Local Agent patch.apply dry-run request creation can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentQueuePreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1',
    status: 'READY_LOCAL_AGENT_QUEUE_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    localAgentRequestCreationProvided: true,
    localAgentRequestCreationPrepared: true,
    queueHandoffPrepared: true,
    pushHandoffPrepared: true,
    claimHandoffPrepared: true,
    sourceLocalAgentRequestCreationStatus: 'READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    localAgentRequestCreationEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-creation-preview',
    localAgentQueueEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-queue-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    durableLocalAgentRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Local Agent queue, push, and claim handoff can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentClaimReadinessPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1',
    status: 'READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    localAgentQueueProvided: true,
    queueHandoffPrepared: true,
    pushHandoffPrepared: true,
    claimHandoffPrepared: true,
    snapshotDryRunReadinessPrepared: true,
    sourceLocalAgentQueueStatus: 'READY_LOCAL_AGENT_QUEUE_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    localAgentQueueEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-queue-preview',
    localAgentClaimReadinessEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-claim-readiness-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    durableLocalAgentRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Claim and snapshot-writing dry-run readiness can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentSnapshotDryRunPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1',
    status: 'READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    localAgentClaimReadinessProvided: true,
    queueHandoffPrepared: true,
    pushHandoffPrepared: true,
    claimHandoffPrepared: true,
    snapshotDryRunReadinessPrepared: true,
    patchDryRunExecutionObservationPrepared: true,
    sourceLocalAgentClaimReadinessStatus: 'READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    diffValidationPassed: true,
    requestEnvelopePrepared: true,
    nonWritingPreflightPassed: true,
    browserReviewReady: true,
    userApprovalRequired: true,
    approvalActions: [
      { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
      { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', prepared: true, enabled: false },
    ],
    localAgentClaimReadinessEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-claim-readiness-preview',
    localAgentSnapshotDryRunEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-snapshot-dry-run-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    durableLocalAgentRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    patchDryRunExecuted: false,
    patchDryRunObservationRecorded: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Snapshot-writing dry-run observation can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentDryRunResultPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1',
    status: 'READY_DRY_RUN_RESULT_ANALYSIS_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    localAgentSnapshotDryRunProvided: true,
    snapshotDryRunReadinessPrepared: true,
    patchDryRunExecutionObservationPrepared: true,
    dryRunResultAnalysisPrepared: true,
    failureLogAnalysisPrepared: true,
    retryDecisionPrepared: true,
    sourceLocalAgentSnapshotDryRunStatus: 'READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    dryRunResultStatus: 'NOT_EXECUTED_PREVIEW',
    dryRunFailureCode: 'NOT_EXECUTED',
    dryRunSucceeded: false,
    dryRunFailed: false,
    contextMismatchDetected: false,
    unsafePatchDetected: false,
    retryRecommended: true,
    retryDecision: 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT',
    replanRequired: false,
    userReviewRequired: true,
    localAgentSnapshotDryRunEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-snapshot-dry-run-preview',
    localAgentDryRunResultEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-dry-run-result-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    durableLocalAgentRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    patchDryRunExecuted: false,
    patchDryRunObservationRecorded: false,
    dryRunResultRecorded: false,
    failureLogAnalysisRecorded: false,
    retryDecisionRecorded: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Dry-run result and retry decision can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentRetryInputPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1',
    status: 'READY_RETRY_INPUT_REPLAN_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    localAgentDryRunResultProvided: true,
    retryInputPrepared: true,
    boundedRetryPatchInputPrepared: true,
    replanDecisionPrepared: true,
    sourceLocalAgentDryRunResultStatus: 'READY_DRY_RUN_RESULT_ANALYSIS_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    dryRunResultStatus: 'NOT_EXECUTED_PREVIEW',
    dryRunFailureCode: 'NOT_EXECUTED',
    contextMismatchDetected: false,
    unsafePatchDetected: false,
    retryRecommended: true,
    sourceRetryDecision: 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT',
    retryInputDecision: 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT',
    replanRequired: false,
    userVisibleDecision: 'WAIT_FOR_DRY_RUN_RESULT_BEFORE_RETRY_OR_REPLAN',
    localAgentDryRunResultEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-dry-run-result-preview',
    localAgentRetryInputEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-input-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    durableLocalAgentRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    patchDryRunExecuted: false,
    patchDryRunObservationRecorded: false,
    dryRunResultRecorded: false,
    failureLogAnalysisRecorded: false,
    retryDecisionRecorded: false,
    retryPatchGenerated: false,
    retryRequestCreationEnabled: false,
    retryExecutionEnabled: false,
    replanExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Retry input and replan decisions can be modeled, but all execution remains disabled.',
  },
  patchDryRunLocalAgentRetryProposalPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-retry-proposal-preview.v1',
    status: 'READY_RETRY_PROPOSAL_FINAL_STOP_DISABLED',
    repositoryId: 'repo-1',
    spaceId: 'space-1',
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    localAgentRetryInputProvided: true,
    retryProposalPrepared: true,
    boundedRetryPatchProposalPrepared: true,
    finalStopDecisionPrepared: true,
    sourceLocalAgentRetryInputStatus: 'READY_RETRY_INPUT_REPLAN_DISABLED',
    toolName: 'patch.apply',
    executionTarget: 'USER_LOCAL_AGENT',
    approvalKind: 'SNAPSHOT_WRITING_DRY_RUN',
    approvalState: 'APPROVED_HELD_PREVIEW',
    targetFiles: ['README.md'],
    dryRunResultStatus: 'NOT_EXECUTED_PREVIEW',
    dryRunFailureCode: 'NOT_EXECUTED',
    contextMismatchDetected: false,
    unsafePatchDetected: false,
    retryRecommended: true,
    sourceRetryInputDecision: 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT',
    replanRequired: false,
    userVisibleDecision: 'WAIT_FOR_RETRY_PATCH_PROPOSAL',
    finalStopDecision: 'WAIT_FOR_RETRY_PATCH_PROPOSAL',
    localAgentRetryInputEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-input-preview',
    localAgentRetryProposalEndpoint: '/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-proposal-preview',
    approvalRequestEndpoint: '/api/code-agent/loop/runner/validated-patch-approval-request',
    releaseReviewEndpoint: '/api/code-agent/loop/runner/release-review',
    approvalRecordCreationEnabled: false,
    approvalActionPersistenceEnabled: false,
    approvalActionEnabled: false,
    heldRequestReviewEnabled: false,
    heldRequestCreated: false,
    approvalDecisionPersistenceEnabled: false,
    approvalPersistenceEnabled: false,
    approvalRequestCreationEnabled: false,
    requestCreationEnabled: false,
    serverApprovalRecordCreated: false,
    approvalDecisionRecorded: false,
    approvalDecisionPersisted: false,
    approvalActionRecorded: false,
    approvalActionPersisted: false,
    localAgentToolRequestCreated: false,
    durableLocalAgentRequestCreated: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    snapshotCreationEnabled: false,
    patchDryRunExecutionEnabled: false,
    patchDryRunExecuted: false,
    patchDryRunObservationRecorded: false,
    dryRunResultRecorded: false,
    failureLogAnalysisRecorded: false,
    retryDecisionRecorded: false,
    retryPatchGenerated: false,
    retryPatchProposalGenerated: false,
    retryRequestCreationEnabled: false,
    retryExecutionEnabled: false,
    replanExecutionEnabled: false,
    mutationEnabled: false,
    testExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    finalPublicationEnabled: false,
    partialReindexEnabled: false,
    approvalBypassAllowed: false,
    reason: 'Retry proposal or final-stop decision can be modeled, but all execution remains disabled.',
  },
};

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
});

try {
  const { CodeWorkspace } = await vite.ssrLoadModule('/src/components/code/CodeWorkspace.jsx');
  const requests = [];
  const enqueueRequests = [];
  const releaseReviewRequests = [];
  const finalResultPublicationRequests = [];
  const m8EntryReadinessRequests = [];
  const validatedDryRunIntentTransitionRequests = [];
  let runnerPreview = null;
  let enqueueResult = 'stale';
  let releaseReviewResult = 'stale';
  let finalResultPublicationPreview = 'stale';
  let m8EntryReadiness = 'stale';

  const props = {
    ...buildCodeWorkspaceReadinessSmokeProps({
      requestId: 'request-route-flow-1',
      latestAttempt,
    }),
    codeAgentLoopRunnerPreview: null,
    codeAgentLoopSubmissionPlan: submissionPlanReviewPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
    localAgentStatus: {
      state: 'CONNECTED',
      agentId: 'agent-1',
      message: 'Local Agent connected.',
      workspaces: [
        {
          workspaceId: 'workspace-1',
          approved: true,
          name: 'learnbot',
          path: 'C:/work/learnbot',
        },
      ],
    },
    loading: () => false,
    previewCodeAgentLoopRunner: async (loopPreview) => {
      runnerPreview = await previewAgentLoopRunner({
        request: async (path, options) => {
          requests.push({ path, options });
          return runnerPreviewResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-preview');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setPreview: (value) => {
          runnerPreview = value;
        },
        setEnqueueResult: (value) => {
          enqueueResult = value;
        },
      });
      return runnerPreview;
    },
    enqueueCodeAgentLoopRunnerReadOnly: async (loopPreview) => {
      enqueueResult = await enqueueAgentLoopRunnerReadOnly({
        request: async (path, options) => {
          enqueueRequests.push({ path, options });
          return runnerEnqueueResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-enqueue-read-only');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setEnqueueResult: (value) => {
          enqueueResult = value;
        },
      });
      return enqueueResult;
    },
    reviewCodeAgentLoopRunnerReleaseGate: async (loopPreview) => {
      releaseReviewResult = await reviewAgentLoopRunnerReleaseGate({
        request: async (path, options) => {
          releaseReviewRequests.push({ path, options });
          return releaseReviewResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-release-review');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setReleaseReviewResult: (value) => {
          releaseReviewResult = value;
        },
      });
      return releaseReviewResult;
    },
    previewCodeAgentLoopRunnerFinalResultPublication: async (loopPreview) => {
      finalResultPublicationPreview = await previewAgentLoopRunnerFinalResultPublication({
        request: async (path, options) => {
          finalResultPublicationRequests.push({ path, options });
          return finalResultPublicationPreviewResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-final-result-publication-preview');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setFinalResultPublicationPreview: (value) => {
          finalResultPublicationPreview = value;
        },
      });
      return finalResultPublicationPreview;
    },
    previewCodeAgentLoopRunnerM8EntryReadiness: async (loopPreview) => {
      m8EntryReadiness = await previewAgentLoopRunnerM8EntryReadiness({
        request: async (path, options) => {
          m8EntryReadinessRequests.push({ path, options });
          return m8EntryReadinessResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-m8-entry-readiness');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setM8EntryReadiness: (value) => {
          m8EntryReadiness = value;
        },
      });
      return m8EntryReadiness;
    },
    previewCodeAgentValidatedDryRunIntentTransition: async ({ requestId, eligibilityRoute, transitionRoute } = {}) => {
      validatedDryRunIntentTransitionRequests.push({ requestId, eligibilityRoute, transitionRoute });
      return validatedDryRunIntentTransitionPreviewResponse;
    },
  };

  assert.equal(assertNoForbiddenTrueFlags(runnerPreviewResponse, 'runnerPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(runnerEnqueueResponse, 'runnerEnqueueResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(releaseGateRunnerPreviewResponse, 'releaseGateRunnerPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(freshEvidenceCompleteRunnerPreviewResponse, 'freshEvidenceCompleteRunnerPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(releaseReadinessRefreshRunnerPreviewResponse, 'releaseReadinessRefreshRunnerPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(releaseReviewResponse, 'releaseReviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(releaseRefusalStopRunnerPreviewResponse, 'releaseRefusalStopRunnerPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(completedFlowRunnerPreviewResponse, 'completedFlowRunnerPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(finalResultPublicationPreviewResponse, 'finalResultPublicationPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(m8EntryReadinessResponse, 'm8EntryReadinessResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(submissionPlanReviewPreviewResponse, 'submissionPlanReviewPreviewResponse'), true);
  const initialMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, props));
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]*disabled)[^>]*>(?:(?!<\/button>)[\s\S])*Preview runner state(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    initialMarkup,
    /agent loop server approval review: READY_BROWSER_REVIEW_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-review-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop server approval review evidence: tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state AWAITING_USER_REVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop server approval review readiness: source READY_APPROVAL_REQUEST_PREVIEW_DISABLED \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop server approval review disabled: request creation false \/ approval request creation false \/ approval persistence false \/ enqueue false \/ claim false \/ snapshot false \/ patch dry-run false \/ test false \/ publication false \/ partial reindex false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval intent preview: READY_APPROVAL_INTENT_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-intent-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop approval intent evidence: source READY_BROWSER_REVIEW_DISABLED \/ surface CODE_WORKSPACE_LOOP_REVIEW \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state USER_REVIEW_REQUIRED \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop approval intent readiness: review provided true \/ prepared true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop approval intent disabled: intent creation false \/ approval persistence false \/ request creation false \/ approval request creation false \/ enqueue false \/ claim false \/ snapshot false \/ patch dry-run false \/ test false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval request creation preview: READY_APPROVAL_REQUEST_CREATION_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-request-creation-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop approval request creation evidence: source READY_APPROVAL_INTENT_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVAL_REQUIRED_HELD_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop approval request creation readiness: intent provided true \/ request prepared true \/ persistence prepared true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop approval request creation disabled: approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ tool request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision preview: READY_APPROVAL_DECISION_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-decision-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision evidence: source READY_APPROVAL_REQUEST_CREATION_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state AWAITING_BROWSER_DECISION_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision readiness: request creation provided true \/ decision prepared true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision options: APPROVE_SNAPSHOT_WRITING_DRY_RUN:false, DENY_SNAPSHOT_WRITING_DRY_RUN:false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision disabled: decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ tool request false \/ held request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision persistence preview: READY_APPROVAL_DECISION_PERSISTENCE_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-decision-persistence-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision persistence evidence: source READY_APPROVAL_DECISION_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVAL_DECISION_HELD_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision persistence readiness: decision provided true \/ persistence prepared true \/ held review true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop approval decision persistence disabled: decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ tool request false \/ held request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop held request review preview: READY_HELD_REQUEST_REVIEW_ACTION_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-held-request-review-action-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop held request review evidence: source READY_APPROVAL_DECISION_PERSISTENCE_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state HELD_REQUEST_BROWSER_REVIEW_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop held request review readiness: decision persistence provided true \/ action prepared true \/ held review true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop held request review actions: REVIEW_HELD_APPROVAL:false, APPROVE_HELD_APPROVAL:false, DENY_HELD_APPROVAL:false/
  );
  assert.match(
    initialMarkup,
    /agent loop held request review disabled: held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ tool request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action preview: READY_APPROVAL_ACTION_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-action-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action evidence: source READY_HELD_REQUEST_REVIEW_ACTION_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state AWAITING_APPROVE_OR_DENY_ACTION_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action readiness: held review provided true \/ action prepared true \/ held review true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action options: APPROVE_SNAPSHOT_WRITING_DRY_RUN:false, DENY_SNAPSHOT_WRITING_DRY_RUN:false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action disabled: action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action persistence preview: READY_APPROVAL_ACTION_PERSISTENCE_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-action-persistence-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action persistence evidence: source READY_APPROVAL_ACTION_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVAL_ACTION_PERSISTENCE_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action persistence readiness: action provided true \/ persistence prepared true \/ held review true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action persistence options: APPROVE_SNAPSHOT_WRITING_DRY_RUN:false, DENY_SNAPSHOT_WRITING_DRY_RUN:false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval action persistence disabled: action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval record preview: READY_APPROVAL_RECORD_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-approval-record-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop approval record evidence: source READY_APPROVAL_ACTION_PERSISTENCE_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVAL_RECORD_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop approval record readiness: action persistence provided true \/ record prepared true \/ request prepared true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop approval record options: APPROVE_SNAPSHOT_WRITING_DRY_RUN:false, DENY_SNAPSHOT_WRITING_DRY_RUN:false/
  );
  assert.match(
    initialMarkup,
    /agent loop approval record disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request envelope preview: READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-request-envelope-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request envelope evidence: source READY_APPROVAL_RECORD_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVED_HELD_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request envelope readiness: approval record provided true \/ envelope prepared true \/ request prepared true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request envelope disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request creation preview: READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-request-creation-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request creation evidence: source READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVED_HELD_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request creation readiness: request envelope provided true \/ envelope prepared true \/ request creation prepared true \/ queue handoff true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent request creation disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ durable request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent queue preview: READY_LOCAL_AGENT_QUEUE_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-queue-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent queue evidence: source READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVED_HELD_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent queue readiness: request creation provided true \/ request creation prepared true \/ queue handoff true \/ push handoff true \/ claim handoff true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent queue disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ durable request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent claim readiness preview: READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-claim-readiness-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent claim readiness evidence: source READY_LOCAL_AGENT_QUEUE_DISABLED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ approval SNAPSHOT_WRITING_DRY_RUN \/ state APPROVED_HELD_PREVIEW \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent claim readiness: queue provided true \/ queue handoff true \/ push handoff true \/ claim handoff true \/ snapshot dry-run true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent claim readiness disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ durable request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent snapshot dry-run preview: READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-snapshot-dry-run-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent snapshot dry-run readiness: claim readiness provided true \/ queue handoff true \/ push handoff true \/ claim handoff true \/ snapshot dry-run true \/ observation true \/ diff true \/ envelope true \/ preflight true \/ browser review true \/ user approval true/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent dry-run result preview: READY_DRY_RUN_RESULT_ANALYSIS_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-dry-run-result-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent dry-run result evidence: source READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED \/ result NOT_EXECUTED_PREVIEW \/ failure NOT_EXECUTED \/ tool patch\.apply \/ target USER_LOCAL_AGENT \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent dry-run result analysis: snapshot provided true \/ result analysis true \/ failure-log true \/ retry decision true \/ succeeded false \/ failed false \/ context mismatch false \/ unsafe patch false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent dry-run retry decision: recommended true \/ decision WAIT_FOR_ACTUAL_DRY_RUN_RESULT \/ replan false \/ user review true/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent dry-run result disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ durable request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ dry-run executed false \/ observation recorded false \/ result recorded false \/ failure-log recorded false \/ retry recorded false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry input preview: READY_RETRY_INPUT_REPLAN_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-retry-input-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry input evidence: source READY_DRY_RUN_RESULT_ANALYSIS_DISABLED \/ result NOT_EXECUTED_PREVIEW \/ failure NOT_EXECUTED \/ retry WAIT_FOR_ACTUAL_DRY_RUN_RESULT \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry input decision: dry-run result provided true \/ retry input true \/ bounded patch input true \/ replan decision true \/ context mismatch false \/ unsafe patch false \/ retry recommended true \/ decision WAIT_FOR_ACTUAL_DRY_RUN_RESULT \/ replan false \/ user visible WAIT_FOR_DRY_RUN_RESULT_BEFORE_RETRY_OR_REPLAN/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry input disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ durable request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ dry-run executed false \/ observation recorded false \/ result recorded false \/ failure-log recorded false \/ retry decision recorded false \/ retry patch false \/ retry request false \/ retry execution false \/ replan execution false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry proposal preview: READY_RETRY_PROPOSAL_FINAL_STOP_DISABLED \/ learnbot\.server\.code-agent\.patch-dry-run-local-agent-retry-proposal-preview\.v1/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry proposal evidence: source READY_RETRY_INPUT_REPLAN_DISABLED \/ result NOT_EXECUTED_PREVIEW \/ failure NOT_EXECUTED \/ retry input WAIT_FOR_ACTUAL_DRY_RUN_RESULT \/ files README\.md/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry proposal decision: retry input provided true \/ proposal true \/ bounded proposal true \/ final stop true \/ context mismatch false \/ unsafe patch false \/ retry recommended true \/ replan false \/ user visible WAIT_FOR_RETRY_PATCH_PROPOSAL \/ stop WAIT_FOR_RETRY_PATCH_PROPOSAL/
  );
  assert.match(
    initialMarkup,
    /agent loop local agent retry proposal disabled: record creation false \/ action persistence false \/ action false \/ held review false \/ held request false \/ decision persistence false \/ approval persistence false \/ approval request creation false \/ request creation false \/ approval record false \/ decision recorded false \/ decision persisted false \/ action recorded false \/ action persisted false \/ tool request false \/ durable request false \/ enqueue false \/ push false \/ claim false \/ claimable false \/ snapshot false \/ patch dry-run false \/ dry-run executed false \/ observation recorded false \/ result recorded false \/ failure-log recorded false \/ retry decision recorded false \/ retry patch false \/ retry proposal false \/ retry request false \/ retry execution false \/ replan execution false \/ test false \/ rollback false \/ publication false \/ partial reindex false \/ bypass false \/ mutation false/
  );
  assert.doesNotMatch(initialMarkup, /agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED/);

  await props.previewCodeAgentLoopRunner(props.codeAgentLoopPreview);

  assert.deepEqual(requests, [
    {
      path: '/api/code-agent/loop/runner/preview',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(requests.some((call) => call.path.includes('enqueue-read-only')), false);
  assert.equal(enqueueResult, null);
  assert.equal(runnerPreview.runnerDecision, 'WAIT_CREATION_GATE_DISABLED');
  assert.equal(runnerPreview.requestCreationEnabled, false);
  assert.equal(runnerPreview.enqueueEnabled, false);
  assert.equal(runnerPreview.pushEnabled, false);
  assert.equal(runnerPreview.claimEnabled, false);
  assert.equal(runnerPreview.mutationEnabled, false);

  const updatedMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreview,
    codeAgentLoopRunnerEnqueueResult: null,
  }));
  assert.match(
    updatedMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    updatedMarkup,
    /agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED \/ learnbot\.code-agent\.creation-disabled-handoff-summary\.v1 \/ runner WAIT_CREATION_GATE_DISABLED \/ boundary RELEASE_REFUSED_GATE_DISABLED/
  );
  assert.match(
    updatedMarkup,
    /agent loop runner handoff counts: expected 4 \/ durable mutation rows 0 \/ persisted 0 \/ pushed 0 \/ claimable 0/
  );
  assert.match(
    updatedMarkup,
    /agent loop runner handoff disabled: request creation false \/ enqueue false \/ push false \/ claim false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );
  assert.match(
    updatedMarkup,
    /agent loop runner recommended action: action CHECK_ENQUEUE_REFUSAL \/ label Check enqueue refusal \/ enabled true \/ endpoint \/api\/code-agent\/loop\/runner\/enqueue-read-only \/ request creation false \/ push false \/ claim false \/ mutation false/
  );
  assert.match(
    updatedMarkup,
    /Mutation handoff is ready, but Local Agent mutation request creation is disabled/
  );

  const validatedDryRunIntentMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: validatedDryRunIntentRunnerPreviewResponse,
    codeAgentValidatedDryRunIntentEligibility: validatedDryRunIntentEligibilityResponse,
    codeAgentValidatedDryRunIntentTransitionPreview: validatedDryRunIntentTransitionPreviewResponse,
  }));
  assert.match(validatedDryRunIntentMarkup, /dry-run review/);
  assert.match(
    validatedDryRunIntentMarkup,
    /agent loop one-cycle: dry-run transition previewed/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /agent loop one-cycle patch: plan pending \/ patch valid \/ target files README\.md/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /agent loop one-cycle dry-run: eligibility READY_DRY_RUN_RELEASE_DISABLED \/ transition READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED \/ request dry-run-intent-1 \/ result pending \/ claimable false \/ mutation false/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /agent loop one-cycle decision: ask for approval \/ action WAIT_FOR_APPROVAL \/ runner WAIT_FOR_APPROVAL \/ approval true \/ replan false \/ report false/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /agent loop runner release handoff routes: eligibility GET \/api\/code-agent\/local-patch-request\/dry-run-intent\/dry-run-intent-1\/eligibility/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /<button\b(?=[^>]*class="ghost-button compact-action")(?![^>]*disabled)[^>]*>(?:(?!<\/button>)[\s\S])*Inspect dry-run eligibility(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /<button\b(?=[^>]*class="ghost-button compact-action")(?![^>]*\sdisabled=)[^>]*>(?:(?!<\/button>)[\s\S])*Preview claimable dry-run transition(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /validated dry-run intent eligibility: READY_DRY_RUN_RELEASE_DISABLED \/ learnbot\.server\.validated-revised-patch-dry-run-eligibility\.v1/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /validated dry-run controls disabled: request creation false \/ queue false \/ push false \/ claim false \/ claimable false \/ dry-run only true \/ mutation false \/ approval bypass false/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /future dry-run release gate: READY_RELEASE_DISABLED \/ prerequisites true \/ request creation false \/ queue false \/ push false \/ claim false \/ claimable false \/ dry-run only true \/ mutation false \/ approval bypass false/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /validated dry-run transition preview: READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED \/ learnbot\.server\.validated-revised-patch-dry-run-transition-preview\.v1/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /validated dry-run transition controls disabled: request creation false \/ request persisted false \/ queue false \/ push false \/ claim false \/ claimable false \/ dry-run only true \/ mutation false \/ approval bypass false/
  );
  assert.match(
    validatedDryRunIntentMarkup,
    /would-be claimable dry-run request: READY_REQUEST_PREVIEW_ONLY \/ learnbot\.server\.validated-revised-patch-claimable-dry-run-request-preview\.v1 \/ source intent dry-run-intent-1 \/ tool patch\.apply \/ approval NOT_REQUIRED \/ target USER_LOCAL_AGENT/
  );

  await props.previewCodeAgentValidatedDryRunIntentTransition({
    requestId: 'dry-run-intent-1',
    eligibilityRoute: 'GET /api/code-agent/local-patch-request/dry-run-intent/dry-run-intent-1/eligibility',
    transitionRoute: 'GET /api/code-agent/local-patch-request/dry-run-intent/dry-run-intent-1/claimable-dry-run-preview',
  });
  assert.deepEqual(validatedDryRunIntentTransitionRequests, [
    {
      requestId: 'dry-run-intent-1',
      eligibilityRoute: 'GET /api/code-agent/local-patch-request/dry-run-intent/dry-run-intent-1/eligibility',
      transitionRoute: 'GET /api/code-agent/local-patch-request/dry-run-intent/dry-run-intent-1/claimable-dry-run-preview',
    },
  ]);

  const completedLocalDryRunMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLocalPatchDryRunRequest: completedLocalDryRunRequest,
    codeAgentLocalPatchDryRunResult: completedLocalDryRunResult,
  }));
  assert.match(
    completedLocalDryRunMarkup,
    /agent loop one-cycle: dry-run observation returned/
  );
  assert.match(
    completedLocalDryRunMarkup,
    /agent loop one-cycle tool: patch\.apply \/ request local-dry-run-request-1 \/ approval NOT_REQUIRED/
  );
  assert.match(
    completedLocalDryRunMarkup,
    /agent loop one-cycle dry-run: eligibility pending \/ transition pending \/ request local-dry-run-request-1 \/ result REJECTED \/ claimable false \/ mutation false/
  );
  assert.match(
    completedLocalDryRunMarkup,
    /agent loop one-cycle dry-run observation: dry-run true \/ preflight true \/ snapshot true \/ mutation applied false \/ expected refusal true/
  );
  assert.match(
    completedLocalDryRunMarkup,
    /agent loop one-cycle safety: request creation true \/ enqueue true \/ push true \/ claim true \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );
  assert.match(
    completedLocalDryRunMarkup,
    /agent loop one-cycle decision: ask for approval \/ action pending \/ runner pending \/ approval true \/ replan false \/ report false/
  );
  assert.match(
    completedLocalDryRunMarkup,
    /agent loop one-cycle next: dry-run observation recorded; refresh readiness or runner next-action before any release; mutation remains disabled/
  );
  assert.match(
    completedLocalDryRunMarkup,
    /Dry-run completed; mutation refused as expected/
  );

  const failedLocalDryRunMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLocalPatchDryRunRequest: {
      ...completedLocalDryRunRequest,
      requestId: 'local-dry-run-request-2',
    },
    codeAgentLocalPatchDryRunResult: failedLocalDryRunResult,
  }));
  assert.match(
    failedLocalDryRunMarkup,
    /agent loop one-cycle: dry-run observation returned/
  );
  assert.match(
    failedLocalDryRunMarkup,
    /agent loop one-cycle dry-run observation: dry-run true \/ preflight false \/ snapshot false \/ mutation applied false \/ expected refusal false/
  );
  assert.match(
    failedLocalDryRunMarkup,
    /agent loop one-cycle decision: replan from failure logs \/ action pending \/ runner pending \/ approval false \/ replan true \/ report false/
  );

  await props.enqueueCodeAgentLoopRunnerReadOnly(props.codeAgentLoopPreview);

  assert.deepEqual(enqueueRequests, [
    {
      path: '/api/code-agent/loop/runner/enqueue-read-only',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(enqueueRequests.some((call) => call.path.includes('/preview')), false);
  assert.equal(enqueueResult.runnerDecision, 'NOT_ENQUEUED');
  assert.equal(enqueueResult.queuedRequest, null);
  assert.equal(enqueueResult.requestCreationEnabled, false);
  assert.equal(enqueueResult.enqueueEnabled, false);
  assert.equal(enqueueResult.pushEnabled, false);
  assert.equal(enqueueResult.claimEnabled, false);
  assert.equal(enqueueResult.mutationEnabled, false);

  const enqueueMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreview,
    codeAgentLoopRunnerEnqueueResult: enqueueResult,
  }));
  assert.match(
    enqueueMarkup,
    /agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED \/ learnbot\.code-agent\.creation-disabled-handoff-summary\.v1 \/ runner NOT_ENQUEUED \/ summary runner WAIT_CREATION_GATE_DISABLED \/ boundary RELEASE_REFUSED_GATE_DISABLED/
  );
  assert.match(
    enqueueMarkup,
    /agent loop runner nested preview: WAIT_CREATION_GATE_DISABLED \/ READY_HANDOFF_CREATION_DISABLED \/ request creation false \/ push false \/ claim false \/ mutation false/
  );
  assert.match(
    enqueueMarkup,
    /agent loop runner handoff disabled: request creation false \/ enqueue false \/ push false \/ claim false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );

  const releaseGateMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: releaseGateRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
  }));
  assert.match(
    releaseGateMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner handoff: WAIT_FOR_RELEASE_GATE \/ learnbot\.code-agent\.release-gate-fresh-observation-handoff\.v1 \/ runner WAIT_RELEASE_GATE_FRESH_OBSERVATIONS/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner release handoff source: source request source-request-route-1 \/ source event LOCAL_AGENT_APPROVAL_DECISION \/ sequence 12 \/ approval APPROVED \/ held true \/ release required true/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner release handoff routes: readiness GET \/api\/local-agents\/tools\/source-request-route-1\/readiness \/ fresh observations POST \/api\/local-agents\/tools\/source-request-route-1\/fresh-observations \/ release boundary POST \/api\/local-agents\/tools\/source-request-route-1\/release/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner handoff disabled: runner auto-enqueue false \/ fresh observation auto-enqueue false \/ source patch request creation false \/ source patch push false \/ source patch claim false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false/
  );

  const freshEvidenceCompleteMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: freshEvidenceCompleteRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
  }));
  assert.match(
    freshEvidenceCompleteMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    freshEvidenceCompleteMarkup,
    /agent loop runner handoff: FRESH_EVIDENCE_COMPLETE_RELEASE_GATED \/ learnbot\.code-agent\.release-gate-fresh-observation-complete-state\.v1 \/ runner WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE/
  );
  assert.match(
    freshEvidenceCompleteMarkup,
    /agent loop runner release handoff source: source request source-request-route-1 \/ release attempt release-attempt-route-1 \/ source event LOCAL_AGENT_RELEASE_FRESH_OBSERVATIONS_ENQUEUED \/ sequence 18/
  );
  assert.match(
    freshEvidenceCompleteMarkup,
    /agent loop runner release fresh observations: evidence complete true \/ required count 2 \/ linked 2 \/ missing 0 \/ source-only fallback 0 \/ blocking 0 \/ completeness COMPLETE \/ status READY_FOR_RELEASE_REVIEW \/ linked keys repositoryVerification, patchDryRun/
  );
  assert.match(
    freshEvidenceCompleteMarkup,
    /agent loop runner handoff disabled: runner auto-enqueue false \/ fresh observation auto-enqueue false \/ source patch request creation false \/ source patch push false \/ source patch claim false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false/
  );

  const releaseReadinessRefreshMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: releaseReadinessRefreshRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
    codeAgentLoopRunnerReleaseReviewResult: null,
  }));
  assert.match(
    releaseReadinessRefreshMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    releaseReadinessRefreshMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Review release refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    releaseReadinessRefreshMarkup,
    /agent loop runner handoff: RELEASE_READINESS_REFRESHED_RELEASE_GATED \/ learnbot\.code-agent\.release-readiness-refresh-state\.v1 \/ runner WAIT_RELEASE_GATE_READINESS_REFRESHED/
  );
  assert.match(
    releaseReadinessRefreshMarkup,
    /agent loop runner release handoff source: source request source-request-route-1 \/ release attempt release-attempt-route-1 \/ source event LOCAL_AGENT_RELEASE_READINESS_REFRESHED \/ sequence 21/
  );
  assert.match(
    releaseReadinessRefreshMarkup,
    /agent loop runner release readiness: ready to release false \/ readiness message Held patch request is not ready for Local Agent execution\. \/ warnings 1 \/ checks 18 \/ failed checks releaseGateEnabled \/ patch release BLOCKED_RELEASE_DISABLED \/ patch release preconditions false \/ patch execution gate BLOCKED_RELEASE_DISABLED \/ patch execution preconditions false \/ release attempt ready false \/ fresh evidence complete true/
  );
  assert.match(
    releaseReadinessRefreshMarkup,
    /agent loop runner handoff disabled: runner auto-enqueue false \/ fresh observation auto-enqueue false \/ source patch request creation false \/ source patch push false \/ source patch claim false \/ claim false \/ claimable false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false/
  );

  await props.reviewCodeAgentLoopRunnerReleaseGate(props.codeAgentLoopPreview);

  assert.deepEqual(releaseReviewRequests, [
    {
      path: '/api/code-agent/loop/runner/release-review',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(releaseReviewResult.runnerDecision, 'RELEASE_REVIEW_REFUSED_GATE_DISABLED');
  assert.equal(releaseReviewResult.boundary.releaseGateEnabled, false);
  assert.equal(releaseReviewResult.claimEnabled, false);
  assert.equal(releaseReviewResult.mutationEnabled, false);

  const releaseReviewMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: releaseReadinessRefreshRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
    codeAgentLoopRunnerReleaseReviewResult: releaseReviewResult,
  }));
  assert.match(
    releaseReviewMarkup,
    /agent loop runner handoff: RELEASE_READINESS_REFRESHED_RELEASE_GATED \/ learnbot\.code-agent\.release-readiness-refresh-state\.v1 \/ runner RELEASE_REVIEW_REFUSED_GATE_DISABLED \/ summary runner WAIT_RELEASE_GATE_READINESS_REFRESHED \/ review boundary RELEASE_REFUSED_GATE_DISABLED/
  );
  assert.match(
    releaseReviewMarkup,
    /agent loop runner release review boundary: status RELEASE_REFUSED_GATE_DISABLED \/ action REFUSAL_ONLY \/ release gate false \/ request creation false \/ push false \/ claim false \/ claimable false \/ write helper false \/ apply false \/ test false \/ rollback restore false \/ RAG freshness update false \/ mutation false \/ blocking release gate is disabled, held patch request remains non-claimable/
  );

  const releaseRefusalStopMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: releaseRefusalStopRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
    codeAgentLoopRunnerReleaseReviewResult: null,
  }));
  assert.match(
    releaseRefusalStopMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    releaseRefusalStopMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Review release refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    releaseRefusalStopMarkup,
    /agent loop runner handoff: RELEASE_REVIEW_REFUSED_GATE_DISABLED \/ learnbot\.code-agent\.release-boundary-refusal-summary\.v1 \/ runner NO_REQUEST_PREPARED/
  );
  assert.match(
    releaseRefusalStopMarkup,
    /agent loop runner release handoff source: source request source-request-route-1 \/ release attempt release-attempt-route-1 \/ source event LOCAL_AGENT_RELEASE_BOUNDARY_REFUSED \/ sequence 24/
  );
  assert.match(
    releaseRefusalStopMarkup,
    /agent loop runner release refusal stop: status RELEASE_REVIEW_REFUSED_GATE_DISABLED \/ action REFUSAL_ONLY \/ boundary RELEASE_REFUSED_GATE_DISABLED \/ release gate false \/ request creation false \/ push false \/ claim false \/ claimable false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false \/ blocking release gate is disabled, held patch request remains non-claimable/
  );

  const completedFlowMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: completedFlowRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
    codeAgentLoopRunnerReleaseReviewResult: null,
  }));
  assert.match(
    completedFlowMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    completedFlowMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Preview final result handoff(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    completedFlowMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check M8 entry(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    completedFlowMarkup,
    /agent loop runner handoff: APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED \/ learnbot\.code-agent\.approved-execution-flow-completed-handoff\.v1 \/ runner READY_FINAL_RESULT_DISABLED/
  );
  assert.match(
    completedFlowMarkup,
    /agent loop runner approved execution flow complete: status APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED \/ request id source durableCompletedRows \/ steps 4 \/ ordered true \/ identity consistent true \/ release linked true \/ approval linked true \/ terminal true \/ succeeded true \/ post-retry verification passed true \/ partial reindex marker required true \/ final report summary READY_SUMMARY_AUDIT_ONLY \/ RAG marker STALE_INDEX_WARNING_REQUIRED \/ partial reindex plan PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED \/ partial reindex enqueue READY_ENQUEUE_DISABLED \/ partial reindex ready true \/ publication handoff READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED \/ acknowledgement handoff READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED \/ final result false \/ publication false \/ RAG freshness update false \/ acknowledgement false \/ follow-up mutation false \/ mutation false/
  );
  assert.match(
    completedFlowMarkup,
    /agent loop runner final-result handoff: schema learnbot\.code-agent\.approved-execution-flow-final-result-handoff\.v1 \/ status READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED \/ final report summary READY_SUMMARY_AUDIT_ONLY \/ post-retry verification passed true \/ post-retry approval linked true \/ post-retry release linked true \/ partial reindex marker required true \/ RAG marker STALE_INDEX_WARNING_REQUIRED \/ partial reindex plan PARTIAL_REINDEX_MARKER_REQUIRED_DISABLED \/ partial reindex action PARTIAL_REINDEX_TARGET_FILES_AFTER_APPROVED_RETRY \/ partial reindex files README\.md \/ partial reindex enqueue READY_ENQUEUE_DISABLED \/ partial reindex ready true \/ partial reindex repository repo-1 \/ publication handoff READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED \/ acknowledgement handoff READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED \/ stale disclosure modeled true \/ delivery false \/ final answer generation false \/ publication false \/ acknowledgement save false \/ RAG freshness update false \/ partial reindex false \/ follow-up mutation false \/ mutation false/
  );

  await props.previewCodeAgentLoopRunnerFinalResultPublication(props.codeAgentLoopPreview);

  assert.deepEqual(finalResultPublicationRequests, [
    {
      path: '/api/code-agent/loop/runner/final-result-publication-preview',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(finalResultPublicationRequests.some((call) => call.path.includes('release-review')), false);
  assert.equal(finalResultPublicationPreview.finalResultReady, true);
  assert.equal(finalResultPublicationPreview.publicationEnabled, false);
  assert.equal(finalResultPublicationPreview.acknowledgementSaveEnabled, false);
  assert.equal(finalResultPublicationPreview.mutationEnabled, false);

  const finalResultPublicationMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: completedFlowRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
    codeAgentLoopRunnerReleaseReviewResult: null,
    codeAgentLoopRunnerFinalResultPublicationPreview: finalResultPublicationPreview,
  }));
  assert.match(
    finalResultPublicationMarkup,
    /agent loop runner final-result publication preview: READY_FINAL_RESULT_PUBLICATION_DISABLED \/ final result ready true \/ publication false \/ acknowledgement save false \/ mutation false/
  );
  assert.match(
    finalResultPublicationMarkup,
    /agent loop one-cycle: final report previewed/
  );
  assert.match(
    finalResultPublicationMarkup,
    /agent loop one-cycle final report: APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED \/ ready true \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );
  assert.match(
    finalResultPublicationMarkup,
    /agent loop one-cycle decision: stop with report \/ action STOP_AND_REPORT \/ runner READY_FINAL_RESULT_DISABLED \/ approval false \/ replan false \/ report true/
  );
  assert.match(
    finalResultPublicationMarkup,
    /agent loop one-cycle next: final report preview is ready for review; publication, acknowledgement, and mutation remain disabled/
  );
  assert.match(
    finalResultPublicationMarkup,
    /agent loop runner handoff: APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED \/ learnbot\.code-agent\.approved-execution-flow-completed-handoff\.v1 \/ summary runner READY_FINAL_RESULT_DISABLED/
  );

  await props.previewCodeAgentLoopRunnerM8EntryReadiness(props.codeAgentLoopPreview);

  assert.deepEqual(m8EntryReadinessRequests, [
    {
      path: '/api/code-agent/loop/runner/m8-entry-readiness',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(m8EntryReadiness.m8EntryReady, true);
  assert.equal(m8EntryReadiness.m8WorkEnabled, false);
  assert.equal(m8EntryReadiness.publicationEnabled, false);
  assert.equal(m8EntryReadiness.acknowledgementSaveEnabled, false);
  assert.equal(m8EntryReadiness.mutationEnabled, false);

  const m8EntryReadinessMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: completedFlowRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
    codeAgentLoopRunnerReleaseReviewResult: null,
    codeAgentLoopRunnerFinalResultPublicationPreview: finalResultPublicationPreview,
    codeAgentLoopRunnerM8EntryReadiness: m8EntryReadiness,
  }));
  assert.match(
    m8EntryReadinessMarkup,
    /agent loop runner M8 entry readiness: M8_ENTRY_READY \/ M7 closure M7_CLOSURE_READY \/ M8 entry ready true \/ M8 work false \/ publication false \/ acknowledgement save false \/ mutation false/
  );

  const reloadPreviewRequests = [];
  const reloadPreviews = [];
  const reloadEnqueueResults = ['stale-terminal-enqueue'];
  const reloadedStopPreview = await previewAgentLoopRunner({
    request: async (path, options) => {
      reloadPreviewRequests.push({ path, options });
      return releaseRefusalStopRunnerPreviewResponse;
    },
    run: async (label, task) => {
      assert.equal(label, 'code-agent-loop-runner-preview');
      return await task();
    },
    repositoryId: props.selectedRepositoryId,
    loopId: props.codeAgentLoopPreview.loopId,
    agentId: 'agent-1',
    workspaceId: 'workspace-1',
    setPreview: (value) => {
      reloadPreviews.push(value);
    },
    setEnqueueResult: (value) => {
      reloadEnqueueResults.push(value);
    },
  });
  assert.deepEqual(reloadPreviewRequests, [
    {
      path: '/api/code-agent/loop/runner/preview',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(reloadedStopPreview.runnerDecision, 'NO_REQUEST_PREPARED');
  assert.equal(reloadedStopPreview.nextAction.actionKey, 'STOP_WITH_REASON');
  assert.equal(reloadPreviews.at(-1), releaseRefusalStopRunnerPreviewResponse);
  assert.equal(reloadEnqueueResults.at(-1), null);

  const reloadedStopMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: reloadedStopPreview,
    codeAgentLoopRunnerEnqueueResult: reloadEnqueueResults.at(-1),
    codeAgentLoopRunnerReleaseReviewResult: null,
  }));
  assert.match(
    reloadedStopMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    reloadedStopMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Review release refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    reloadedStopMarkup,
    /agent loop runner handoff: RELEASE_REVIEW_REFUSED_GATE_DISABLED \/ learnbot\.code-agent\.release-boundary-refusal-summary\.v1 \/ runner NO_REQUEST_PREPARED/
  );
  assert.match(
    reloadedStopMarkup,
    /agent loop runner release refusal stop: status RELEASE_REVIEW_REFUSED_GATE_DISABLED \/ action REFUSAL_ONLY \/ boundary RELEASE_REFUSED_GATE_DISABLED \/ release gate false \/ request creation false \/ push false \/ claim false \/ claimable false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false \/ blocking release gate is disabled, held patch request remains non-claimable/
  );
} finally {
  await vite.close();
}

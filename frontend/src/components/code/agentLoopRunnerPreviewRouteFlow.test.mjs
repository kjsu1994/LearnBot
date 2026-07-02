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
  const initialMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, props));
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]*disabled)[^>]*>(?:(?!<\/button>)[\s\S])*Preview runner state(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
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
    /agent loop runner handoff disabled: runner auto-enqueue false \/ fresh observation auto-enqueue false \/ source patch request creation false \/ source patch push false \/ source patch claim false \/ claim false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false/
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
    /agent loop runner approved execution flow complete: status APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED \/ request id source durableCompletedRows \/ steps 4 \/ ordered true \/ identity consistent true \/ release linked true \/ terminal true \/ succeeded true \/ final report summary READY_SUMMARY_AUDIT_ONLY \/ RAG marker STALE_INDEX_WARNING_REQUIRED \/ publication handoff READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED \/ acknowledgement handoff READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED \/ final result false \/ publication false \/ RAG freshness update false \/ acknowledgement false \/ follow-up mutation false \/ mutation false/
  );
  assert.match(
    completedFlowMarkup,
    /agent loop runner final-result handoff: schema learnbot\.code-agent\.approved-execution-flow-final-result-handoff\.v1 \/ status READY_FINAL_RESULT_AUDIT_ONLY_PUBLICATION_DISABLED \/ final report summary READY_SUMMARY_AUDIT_ONLY \/ RAG marker STALE_INDEX_WARNING_REQUIRED \/ publication handoff READY_HANDOFF_AUDIT_ONLY_PUBLICATION_DISABLED \/ acknowledgement handoff READY_ACKNOWLEDGEMENT_AUDIT_ONLY_SAVE_DISABLED \/ stale disclosure modeled true \/ delivery false \/ final answer generation false \/ publication false \/ acknowledgement save false \/ RAG freshness update false \/ partial reindex false \/ follow-up mutation false \/ mutation false/
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

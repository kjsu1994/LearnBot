import assert from 'node:assert/strict';
import { buildCodeWorkspaceReadinessSmokeProps } from './codeWorkspaceReadinessSmokeHarness.mjs';
import { assertNoForbiddenTrueFlags } from './mutationDisabledFlagGuard.js';

const latestAttempt = {
  mutationFinalAnswerDeliveryGate: {
    status: 'REFUSED_FINAL_ANSWER_DELIVERY_DISABLED',
    finalAnswerDeliveryEnabled: false,
    mutationAllowed: false,
  },
  freshObservationRequestPlan: [
    {
      key: 'freshRepositoryVerification',
      status: 'PLANNED_DISABLED',
      enqueueEnabled: false,
      claimableAfterEnqueue: false,
      mutationAllowed: false,
    },
  ],
  freshObservationEvidenceStatus: [
    {
      key: 'freshRepositoryVerification',
      status: 'RELEASE_ATTEMPT_LINKED',
      linked: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
  ],
  freshObservationEvidenceCompleteness: {
    status: 'ALL_LINKED_RELEASE_DISABLED',
    complete: true,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimable: false,
    mutationAllowed: false,
  },
  evidence: {
    freshObservationEnqueueBoundary: {
      status: 'REFUSED_ENQUEUE_DISABLED',
      requestCreationEnabled: false,
      pushEnabled: false,
      enqueueEnabled: false,
      claimableAfterEnqueue: false,
      mutationAllowed: false,
    },
  },
};

const props = buildCodeWorkspaceReadinessSmokeProps({
  requestId: 'request-1',
  latestAttempt,
});

assert.equal(props.selectedRepositoryId, 'repo-1');
assert.equal(props.codeAgentLoopPreview.status, 'PREVIEW_ONLY');
assert.equal(props.codeAgentLoopPreview.mutationEnabled, false);
assert.equal(props.codeAgentLoopPreview.timelinePersistenceEnabled, true);
assert.equal(props.codeAgentLoopPreview.cancellationEnabled, false);
assert.equal(props.codeAgentLoopTimelines[0].mutationEnabled, false);
assert.equal(props.codeAgentLoopTimelines[0].events[1].mayMutate, false);
assert.equal(props.codeAgentLoopTimelines[0].events[2].eventType, 'TIMEOUT_POLICY_REGISTERED');
assert.equal(props.codeAgentLoopTimelines[0].events[2].details.timeoutSeconds, 120);
assert.equal(props.codeAgentLoopTimelines[0].events[3].eventType, 'CANCELLATION_POLICY_REGISTERED');
assert.equal(props.codeAgentLoopTimelines[0].events[3].details.cancellationEnabled, false);
assert.equal(props.codeAgentLoopTimelines[0].events[4].eventType, 'FINAL_RESULT_POLICY_REGISTERED');
assert.equal(props.codeAgentLoopTimelines[0].events[4].details.finalResultEnabled, false);
assert.equal(props.codeAgentLoopTimelines[0].events[5].eventType, 'STOP_OUTCOME_POLICY_REGISTERED');
assert.equal(props.codeAgentLoopTimelines[0].events[5].details.stopKey, 'WEAK_EVIDENCE');
assert.equal(props.codeAgentLoopTimelines[0].events[6].details.stopKey, 'AGENT_UNAVAILABLE');
assert.equal(props.codeAgentLoopTimelines[0].events[7].details.stopKey, 'TOOL_FAILED');
assert.equal(props.codeAgentLoopTimelines[0].events[8].details.stopKey, 'APPROVAL_DENIED');
assert.equal(props.codeAgentLoopTimelines[0].events[9].eventType, 'LOCAL_AGENT_OBSERVATION_RESULT');
assert.equal(props.codeAgentLoopTimelines[0].events[9].details.mutationApplied, false);
assert.equal(props.codeAgentLoopTimelines[0].events[10].eventType, 'LOCAL_AGENT_APPROVAL_DECISION');
assert.equal(props.codeAgentLoopTimelines[0].events[10].mayMutate, false);
assert.equal(props.codeAgentLocalPatchRequest.requestId, 'request-1');
assert.equal(props.codeAgentLocalPatchRequest.status, 'APPROVED_HELD');
assert.equal(props.codeAgentLocalPatchRequest.toolName, 'patch.apply');
assert.equal(props.codeAgentLocalPatchRequest.approvalState, 'APPROVED');
assert.equal(props.codeAgentLocalPatchReadiness.requestId, 'request-1');
assert.equal(props.codeAgentLocalPatchReadiness.readyToRelease, false);
assert.equal(props.codeAgentLocalPatchReadiness.patchExecutionGate.status, 'BLOCKED_RELEASE_DISABLED');
assert.equal(props.codeAgentLocalPatchReadiness.patchExecutionGate.claimEnabled, false);
assert.equal(props.codeAgentLocalPatchReadiness.patchExecutionGate.releaseGateEnabled, false);
assert.equal(props.codeAgentLocalPatchReadiness.releaseAttemptModel.status, 'READY_RELEASE_ATTEMPT_DISABLED');
assert.equal(props.codeAgentLocalPatchReadiness.releaseAttemptModel.latestAttempt, latestAttempt);
assert.equal(
  props.codeAgentLocalPatchReadiness.releaseAttemptModel.latestAttempt.freshObservationRequestPlan[0].claimableAfterEnqueue,
  false
);
assert.equal(
  props.codeAgentLocalPatchReadiness.releaseAttemptModel.latestAttempt.freshObservationEvidenceStatus[0].status,
  'RELEASE_ATTEMPT_LINKED'
);
assert.equal(
  props.codeAgentLocalPatchReadiness.releaseAttemptModel.latestAttempt.freshObservationEvidenceStatus[0].requestCreationEnabled,
  false
);
assert.equal(
  props.codeAgentLocalPatchReadiness.releaseAttemptModel.latestAttempt.freshObservationEvidenceCompleteness.status,
  'ALL_LINKED_RELEASE_DISABLED'
);
assert.equal(
  props.codeAgentLocalPatchReadiness.releaseAttemptModel.latestAttempt.evidence.freshObservationEnqueueBoundary.enqueueEnabled,
  false
);
assert.equal(assertNoForbiddenTrueFlags(props, 'props'), true);

const invalidDryRunResult = {
  requestId: 'dry-run-invalid-1',
  status: 'SUCCEEDED',
  output: {
    dryRun: true,
    preflightPassed: true,
    mutationApplied: true,
    snapshotCreated: true,
  },
};

const invalidSnapshotProps = buildCodeWorkspaceReadinessSmokeProps({
  requestId: 'request-2',
  latestAttempt,
  dryRunResult: invalidDryRunResult,
  readinessOverrides: {
    snapshotReadiness: {
      status: 'INVALID',
      mutationApplied: true,
      snapshotCreated: true,
      message: 'Snapshot readiness requires a non-mutating Local Agent dry-run observation with mutationApplied=false.',
    },
    rollbackReadiness: {
      status: 'INVALID',
      blocking: true,
      message: 'Rollback validation requires a dry-run observation with mutationApplied=false.',
    },
  },
});

assert.deepEqual(invalidSnapshotProps.codeAgentLocalPatchDryRunRequest, {
  requestId: 'dry-run-invalid-1',
});
assert.equal(invalidSnapshotProps.codeAgentLocalPatchDryRunResult, invalidDryRunResult);
assert.equal(invalidSnapshotProps.codeAgentLocalPatchReadiness.snapshotReadiness.status, 'INVALID');
assert.equal(invalidSnapshotProps.codeAgentLocalPatchReadiness.snapshotReadiness.mutationApplied, true);
assert.equal(invalidSnapshotProps.codeAgentLocalPatchReadiness.rollbackReadiness.status, 'INVALID');
assert.equal(invalidSnapshotProps.codeAgentLocalPatchReadiness.rollbackReadiness.blocking, true);
assert.equal(assertNoForbiddenTrueFlags(invalidSnapshotProps, 'invalidSnapshotProps'), true);

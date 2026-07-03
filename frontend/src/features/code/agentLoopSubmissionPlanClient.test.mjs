import assert from 'node:assert/strict';
import { previewAgentLoopSubmissionPlan } from './agentLoopSubmissionPlanClient.js';

const calls = [];
const plans = [];
const handoff = {
  schema: 'learnbot.local-agent.codex-patch-dry-run-approval-handoff-preview.v1',
  status: 'APPROVAL_HANDOFF_PREPARED',
  approvalHandoffPrepared: true,
};

const response = {
  schema: 'learnbot.server.code-agent.loop-submission-plan.v1',
  patchDryRunApprovalReviewPreview: {
    schema: 'learnbot.server.code-agent.patch-dry-run-approval-review-preview.v1',
    status: 'READY_BROWSER_REVIEW_DISABLED',
    requestCreationEnabled: false,
    approvalRequestCreationEnabled: false,
    approvalPersistenceEnabled: false,
    enqueueEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
  },
};

const intentResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-approval-intent-preview.v1',
  status: 'READY_APPROVAL_INTENT_DISABLED',
  approvalIntentPrepared: true,
  approvalIntentCreationEnabled: false,
  approvalPersistenceEnabled: false,
  requestCreationEnabled: false,
  approvalRequestCreationEnabled: false,
  enqueueEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
};

const requestCreationResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-approval-request-creation-preview.v1',
  status: 'READY_APPROVAL_REQUEST_CREATION_DISABLED',
  approvalRequestCreationPrepared: true,
  approvalPersistencePrepared: true,
  approvalPersistenceEnabled: false,
  approvalRequestCreationEnabled: false,
  requestCreationEnabled: false,
  serverApprovalRecordCreated: false,
  localAgentToolRequestCreated: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  claimable: false,
  mutationEnabled: false,
};

const decisionResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-approval-decision-preview.v1',
  status: 'READY_APPROVAL_DECISION_DISABLED',
  approvalDecisionPrepared: true,
  decisionOptions: [
    { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', enabled: false },
    { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', enabled: false },
  ],
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
  mutationEnabled: false,
};

const decisionPersistenceResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-approval-decision-persistence-preview.v1',
  status: 'READY_APPROVAL_DECISION_PERSISTENCE_DISABLED',
  approvalDecisionProvided: true,
  approvalDecisionPersistencePrepared: true,
  heldRequestReviewPrepared: true,
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
  mutationEnabled: false,
};

const heldRequestReviewResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-held-request-review-action-preview.v1',
  status: 'READY_HELD_REQUEST_REVIEW_ACTION_DISABLED',
  approvalDecisionPersistenceProvided: true,
  heldRequestReviewActionPrepared: true,
  heldRequestReviewPrepared: true,
  reviewActions: [
    { action: 'REVIEW_HELD_APPROVAL', enabled: false },
    { action: 'APPROVE_HELD_APPROVAL', enabled: false },
    { action: 'DENY_HELD_APPROVAL', enabled: false },
  ],
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
  mutationEnabled: false,
};

const approvalActionResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-approval-action-preview.v1',
  status: 'READY_APPROVAL_ACTION_DISABLED',
  heldRequestReviewProvided: true,
  approvalActionPrepared: true,
  heldRequestReviewPrepared: true,
  approvalActions: [
    { action: 'APPROVE_SNAPSHOT_WRITING_DRY_RUN', enabled: false },
    { action: 'DENY_SNAPSHOT_WRITING_DRY_RUN', enabled: false },
  ],
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
  mutationEnabled: false,
};

const approvalActionPersistenceResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-approval-action-persistence-preview.v1',
  status: 'READY_APPROVAL_ACTION_PERSISTENCE_DISABLED',
  approvalActionProvided: true,
  approvalActionPersistencePrepared: true,
  heldRequestReviewPrepared: true,
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
  mutationEnabled: false,
};

const approvalRecordResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-approval-record-preview.v1',
  status: 'READY_APPROVAL_RECORD_DISABLED',
  approvalActionPersistenceProvided: true,
  approvalRecordPrepared: true,
  localAgentRequestCreationPrepared: true,
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
  mutationEnabled: false,
};

const localAgentRequestEnvelopeResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-request-envelope-preview.v1',
  status: 'READY_LOCAL_AGENT_REQUEST_ENVELOPE_DISABLED',
  approvalRecordProvided: true,
  localAgentRequestEnvelopePrepared: true,
  localAgentRequestCreationPrepared: true,
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
};

const localAgentRequestCreationResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-request-creation-preview.v1',
  status: 'READY_LOCAL_AGENT_REQUEST_CREATION_DISABLED',
  localAgentRequestEnvelopeProvided: true,
  localAgentRequestEnvelopePrepared: true,
  localAgentRequestCreationPrepared: true,
  queueHandoffPrepared: true,
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
};

const localAgentQueueResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-queue-preview.v1',
  status: 'READY_LOCAL_AGENT_QUEUE_DISABLED',
  localAgentRequestCreationProvided: true,
  localAgentRequestCreationPrepared: true,
  queueHandoffPrepared: true,
  pushHandoffPrepared: true,
  claimHandoffPrepared: true,
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
};

const localAgentClaimReadinessResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-claim-readiness-preview.v1',
  status: 'READY_CLAIM_SNAPSHOT_DRY_RUN_DISABLED',
  localAgentQueueProvided: true,
  queueHandoffPrepared: true,
  pushHandoffPrepared: true,
  claimHandoffPrepared: true,
  snapshotDryRunReadinessPrepared: true,
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
};

const localAgentSnapshotDryRunResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-snapshot-dry-run-preview.v1',
  status: 'READY_SNAPSHOT_DRY_RUN_OBSERVATION_DISABLED',
  localAgentClaimReadinessProvided: true,
  queueHandoffPrepared: true,
  pushHandoffPrepared: true,
  claimHandoffPrepared: true,
  snapshotDryRunReadinessPrepared: true,
  patchDryRunExecutionObservationPrepared: true,
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
};

const localAgentDryRunResultResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-dry-run-result-preview.v1',
  status: 'READY_DRY_RUN_RESULT_ANALYSIS_DISABLED',
  localAgentSnapshotDryRunProvided: true,
  snapshotDryRunReadinessPrepared: true,
  patchDryRunExecutionObservationPrepared: true,
  dryRunResultAnalysisPrepared: true,
  failureLogAnalysisPrepared: true,
  retryDecisionPrepared: true,
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
  requestCreationEnabled: false,
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
};

const localAgentRetryInputResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-retry-input-preview.v1',
  status: 'READY_RETRY_INPUT_REPLAN_DISABLED',
  localAgentDryRunResultProvided: true,
  retryInputPrepared: true,
  boundedRetryPatchInputPrepared: true,
  replanDecisionPrepared: true,
  dryRunResultStatus: 'NOT_EXECUTED_PREVIEW',
  dryRunFailureCode: 'NOT_EXECUTED',
  contextMismatchDetected: false,
  unsafePatchDetected: false,
  retryRecommended: true,
  sourceRetryDecision: 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT',
  retryInputDecision: 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT',
  replanRequired: false,
  userVisibleDecision: 'WAIT_FOR_DRY_RUN_RESULT_BEFORE_RETRY_OR_REPLAN',
  requestCreationEnabled: false,
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
};

const localAgentRetryProposalResponse = {
  schema: 'learnbot.server.code-agent.patch-dry-run-local-agent-retry-proposal-preview.v1',
  status: 'READY_RETRY_PROPOSAL_FINAL_STOP_DISABLED',
  localAgentRetryInputProvided: true,
  retryProposalPrepared: true,
  boundedRetryPatchProposalPrepared: true,
  finalStopDecisionPrepared: true,
  dryRunResultStatus: 'NOT_EXECUTED_PREVIEW',
  dryRunFailureCode: 'NOT_EXECUTED',
  contextMismatchDetected: false,
  unsafePatchDetected: false,
  retryRecommended: true,
  sourceRetryInputDecision: 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT',
  replanRequired: false,
  userVisibleDecision: 'WAIT_FOR_RETRY_PATCH_PROPOSAL',
  finalStopDecision: 'WAIT_FOR_RETRY_PATCH_PROPOSAL',
  requestCreationEnabled: false,
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
};

const result = await previewAgentLoopSubmissionPlan({
  request: async (path, options) => {
    calls.push({ path, options });
    if (path === '/api/code-agent/loop/runner/patch-dry-run-approval-intent-preview') {
      return intentResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-approval-request-creation-preview') {
      return requestCreationResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-approval-decision-preview') {
      return decisionResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-approval-decision-persistence-preview') {
      return decisionPersistenceResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-held-request-review-preview') {
      return heldRequestReviewResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-approval-action-preview') {
      return approvalActionResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-approval-action-persistence-preview') {
      return approvalActionPersistenceResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-approval-record-preview') {
      return approvalRecordResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-envelope-preview') {
      return localAgentRequestEnvelopeResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-creation-preview') {
      return localAgentRequestCreationResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-queue-preview') {
      return localAgentQueueResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-claim-readiness-preview') {
      return localAgentClaimReadinessResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-snapshot-dry-run-preview') {
      return localAgentSnapshotDryRunResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-dry-run-result-preview') {
      return localAgentDryRunResultResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-input-preview') {
      return localAgentRetryInputResponse;
    }
    if (path === '/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-proposal-preview') {
      return localAgentRetryProposalResponse;
    }
    return response;
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-submission-plan');
    return await task();
  },
  repositoryId: 'repo-1',
  spaceId: 'space-1',
  instruction: '  repair failing tests  ',
  maxSteps: 6,
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  patchDryRunApprovalHandoffPreview: handoff,
  setSubmissionPlan: (value) => plans.push(value),
});

assert.deepEqual(result, {
  ...response,
  patchDryRunApprovalIntentPreview: intentResponse,
  patchDryRunApprovalRequestCreationPreview: requestCreationResponse,
  patchDryRunApprovalDecisionPreview: decisionResponse,
  patchDryRunApprovalDecisionPersistencePreview: decisionPersistenceResponse,
  patchDryRunHeldRequestReviewPreview: heldRequestReviewResponse,
  patchDryRunApprovalActionPreview: approvalActionResponse,
  patchDryRunApprovalActionPersistencePreview: approvalActionPersistenceResponse,
  patchDryRunApprovalRecordPreview: approvalRecordResponse,
  patchDryRunLocalAgentRequestEnvelopePreview: localAgentRequestEnvelopeResponse,
  patchDryRunLocalAgentRequestCreationPreview: localAgentRequestCreationResponse,
  patchDryRunLocalAgentQueuePreview: localAgentQueueResponse,
  patchDryRunLocalAgentClaimReadinessPreview: localAgentClaimReadinessResponse,
  patchDryRunLocalAgentSnapshotDryRunPreview: localAgentSnapshotDryRunResponse,
  patchDryRunLocalAgentDryRunResultPreview: localAgentDryRunResultResponse,
  patchDryRunLocalAgentRetryInputPreview: localAgentRetryInputResponse,
  patchDryRunLocalAgentRetryProposalPreview: localAgentRetryProposalResponse,
});
assert.deepEqual(plans, [{
  ...response,
  patchDryRunApprovalIntentPreview: intentResponse,
  patchDryRunApprovalRequestCreationPreview: requestCreationResponse,
  patchDryRunApprovalDecisionPreview: decisionResponse,
  patchDryRunApprovalDecisionPersistencePreview: decisionPersistenceResponse,
  patchDryRunHeldRequestReviewPreview: heldRequestReviewResponse,
  patchDryRunApprovalActionPreview: approvalActionResponse,
  patchDryRunApprovalActionPersistencePreview: approvalActionPersistenceResponse,
  patchDryRunApprovalRecordPreview: approvalRecordResponse,
  patchDryRunLocalAgentRequestEnvelopePreview: localAgentRequestEnvelopeResponse,
  patchDryRunLocalAgentRequestCreationPreview: localAgentRequestCreationResponse,
  patchDryRunLocalAgentQueuePreview: localAgentQueueResponse,
  patchDryRunLocalAgentClaimReadinessPreview: localAgentClaimReadinessResponse,
  patchDryRunLocalAgentSnapshotDryRunPreview: localAgentSnapshotDryRunResponse,
  patchDryRunLocalAgentDryRunResultPreview: localAgentDryRunResultResponse,
  patchDryRunLocalAgentRetryInputPreview: localAgentRetryInputResponse,
  patchDryRunLocalAgentRetryProposalPreview: localAgentRetryProposalResponse,
}]);
assert.deepEqual(calls, [
  {
    path: '/api/code-agent/loop/submission-plan',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        instruction: 'repair failing tests',
        maxSteps: 6,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalHandoffPreview: handoff,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-approval-intent-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalReviewPreview: response.patchDryRunApprovalReviewPreview,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-approval-request-creation-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalIntentPreview: intentResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-approval-decision-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalRequestCreationPreview: requestCreationResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-approval-decision-persistence-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalDecisionPreview: decisionResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-held-request-review-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalDecisionPersistencePreview: decisionPersistenceResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-approval-action-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunHeldRequestReviewPreview: heldRequestReviewResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-approval-action-persistence-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalActionPreview: approvalActionResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-approval-record-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalActionPersistencePreview: approvalActionPersistenceResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-envelope-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunApprovalRecordPreview: approvalRecordResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-request-creation-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunLocalAgentRequestEnvelopePreview: localAgentRequestEnvelopeResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-queue-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunLocalAgentRequestCreationPreview: localAgentRequestCreationResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-claim-readiness-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunLocalAgentQueuePreview: localAgentQueueResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-snapshot-dry-run-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunLocalAgentClaimReadinessPreview: localAgentClaimReadinessResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-dry-run-result-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunLocalAgentSnapshotDryRunPreview: localAgentSnapshotDryRunResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-input-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunLocalAgentDryRunResultPreview: localAgentDryRunResultResponse,
      },
    },
  },
  {
    path: '/api/code-agent/loop/runner/patch-dry-run-local-agent-retry-proposal-preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        spaceId: 'space-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        patchDryRunLocalAgentRetryInputPreview: localAgentRetryInputResponse,
      },
    },
  },
]);
assert.equal(response.patchDryRunApprovalReviewPreview.requestCreationEnabled, false);
assert.equal(response.patchDryRunApprovalReviewPreview.approvalRequestCreationEnabled, false);
assert.equal(response.patchDryRunApprovalReviewPreview.approvalPersistenceEnabled, false);
assert.equal(response.patchDryRunApprovalReviewPreview.enqueueEnabled, false);
assert.equal(response.patchDryRunApprovalReviewPreview.claimEnabled, false);
assert.equal(response.patchDryRunApprovalReviewPreview.mutationEnabled, false);
assert.equal(result.patchDryRunApprovalIntentPreview.approvalIntentCreationEnabled, false);
assert.equal(result.patchDryRunApprovalIntentPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalIntentPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalIntentPreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalIntentPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunApprovalIntentPreview.claimEnabled, false);
assert.equal(result.patchDryRunApprovalIntentPreview.mutationEnabled, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.pushEnabled, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.claimEnabled, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.claimable, false);
assert.equal(result.patchDryRunApprovalRequestCreationPreview.mutationEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.approvalDecisionPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.approvalDecisionRecorded, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.heldRequestCreated, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.pushEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.claimEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.claimable, false);
assert.equal(result.patchDryRunApprovalDecisionPreview.mutationEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.approvalDecisionPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.approvalDecisionRecorded, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.approvalDecisionPersisted, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.heldRequestCreated, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.enqueueEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.pushEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.claimEnabled, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.claimable, false);
assert.equal(result.patchDryRunApprovalDecisionPersistencePreview.mutationEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.heldRequestReviewEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.heldRequestCreated, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.approvalDecisionPersistenceEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.approvalDecisionRecorded, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.approvalDecisionPersisted, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.pushEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.claimEnabled, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.claimable, false);
assert.equal(result.patchDryRunHeldRequestReviewPreview.mutationEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.approvalActionEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.heldRequestReviewEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.heldRequestCreated, false);
assert.equal(result.patchDryRunApprovalActionPreview.approvalDecisionPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunApprovalActionPreview.approvalDecisionRecorded, false);
assert.equal(result.patchDryRunApprovalActionPreview.approvalDecisionPersisted, false);
assert.equal(result.patchDryRunApprovalActionPreview.approvalActionRecorded, false);
assert.equal(result.patchDryRunApprovalActionPreview.approvalActionPersisted, false);
assert.equal(result.patchDryRunApprovalActionPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunApprovalActionPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.pushEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.claimEnabled, false);
assert.equal(result.patchDryRunApprovalActionPreview.claimable, false);
assert.equal(result.patchDryRunApprovalActionPreview.mutationEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalActionPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalActionEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.heldRequestReviewEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.heldRequestCreated, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalDecisionPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalDecisionRecorded, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalDecisionPersisted, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalActionRecorded, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.approvalActionPersisted, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.enqueueEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.pushEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.claimEnabled, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.claimable, false);
assert.equal(result.patchDryRunApprovalActionPersistencePreview.mutationEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.approvalRecordCreationEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.approvalActionPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.approvalActionEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunApprovalRecordPreview.approvalActionRecorded, false);
assert.equal(result.patchDryRunApprovalRecordPreview.approvalActionPersisted, false);
assert.equal(result.patchDryRunApprovalRecordPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunApprovalRecordPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.pushEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.claimEnabled, false);
assert.equal(result.patchDryRunApprovalRecordPreview.claimable, false);
assert.equal(result.patchDryRunApprovalRecordPreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.localAgentRequestEnvelopePrepared, true);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.approvalRecordCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.approvalActionPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.approvalActionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.approvalActionRecorded, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.approvalActionPersisted, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestEnvelopePreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.localAgentRequestCreationPrepared, true);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.queueHandoffPrepared, true);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.approvalRecordCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.approvalActionPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.approvalActionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.approvalActionRecorded, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.approvalActionPersisted, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.durableLocalAgentRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRequestCreationPreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.queueHandoffPrepared, true);
assert.equal(result.patchDryRunLocalAgentQueuePreview.pushHandoffPrepared, true);
assert.equal(result.patchDryRunLocalAgentQueuePreview.claimHandoffPrepared, true);
assert.equal(result.patchDryRunLocalAgentQueuePreview.approvalRecordCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.approvalActionPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.approvalActionEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.approvalActionRecorded, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.approvalActionPersisted, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.durableLocalAgentRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentQueuePreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.snapshotDryRunReadinessPrepared, true);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.queueHandoffPrepared, true);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.pushHandoffPrepared, true);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.claimHandoffPrepared, true);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.approvalRecordCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.approvalActionPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.approvalActionEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.approvalPersistenceEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.approvalRequestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.serverApprovalRecordCreated, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.approvalActionRecorded, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.approvalActionPersisted, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.durableLocalAgentRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentClaimReadinessPreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.patchDryRunExecutionObservationPrepared, true);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.snapshotDryRunReadinessPrepared, true);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.durableLocalAgentRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.patchDryRunExecuted, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.patchDryRunObservationRecorded, false);
assert.equal(result.patchDryRunLocalAgentSnapshotDryRunPreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.dryRunResultAnalysisPrepared, true);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.failureLogAnalysisPrepared, true);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.retryDecisionPrepared, true);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.dryRunResultStatus, 'NOT_EXECUTED_PREVIEW');
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.retryRecommended, true);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.durableLocalAgentRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.patchDryRunExecuted, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.patchDryRunObservationRecorded, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.dryRunResultRecorded, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.failureLogAnalysisRecorded, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.retryDecisionRecorded, false);
assert.equal(result.patchDryRunLocalAgentDryRunResultPreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.retryInputPrepared, true);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.boundedRetryPatchInputPrepared, true);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.replanDecisionPrepared, true);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.retryInputDecision, 'WAIT_FOR_ACTUAL_DRY_RUN_RESULT');
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.durableLocalAgentRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.patchDryRunExecuted, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.dryRunResultRecorded, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.failureLogAnalysisRecorded, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.retryDecisionRecorded, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.retryPatchGenerated, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.retryRequestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.retryExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.replanExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryInputPreview.mutationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.retryProposalPrepared, true);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.boundedRetryPatchProposalPrepared, true);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.finalStopDecisionPrepared, true);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.finalStopDecision, 'WAIT_FOR_RETRY_PATCH_PROPOSAL');
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.requestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.localAgentToolRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.durableLocalAgentRequestCreated, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.enqueueEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.pushEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.claimEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.claimable, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.snapshotCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.patchDryRunExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.patchDryRunExecuted, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.dryRunResultRecorded, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.failureLogAnalysisRecorded, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.retryDecisionRecorded, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.retryPatchGenerated, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.retryPatchProposalGenerated, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.retryRequestCreationEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.retryExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.replanExecutionEnabled, false);
assert.equal(result.patchDryRunLocalAgentRetryProposalPreview.mutationEnabled, false);

const skippedPlans = ['stale'];
const skipped = await previewAgentLoopSubmissionPlan({
  request: async () => {
    throw new Error('request should not run without a repository id');
  },
  run: async (_label, task) => await task(),
  repositoryId: '',
  instruction: 'repair failing tests',
  setSubmissionPlan: (value) => skippedPlans.push(value),
});
assert.equal(skipped, null);
assert.deepEqual(skippedPlans, ['stale', null]);

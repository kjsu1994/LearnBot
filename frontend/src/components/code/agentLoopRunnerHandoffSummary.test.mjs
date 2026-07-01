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
  },
});
assert.equal(fallbackView.show, true);
assert.match(fallbackView.headerText, /READY_HANDOFF_CREATION_DISABLED/);

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
  releaseBoundaryRoute: 'POST /api/local-agents/tools/source-request-1/release',
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
  'agent loop runner release handoff routes: readiness GET /api/local-agents/tools/source-request-1/readiness / fresh observations POST /api/local-agents/tools/source-request-1/fresh-observations / release boundary POST /api/local-agents/tools/source-request-1/release'
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

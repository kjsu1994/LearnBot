import assert from 'node:assert/strict';
import { buildMutationDispatchEnvelopeContractView } from './mutationDispatchEnvelopeContract.js';

const view = buildMutationDispatchEnvelopeContractView({
  schema: 'learnbot.local-agent.mutation-dispatch-envelope.v1',
  status: 'READY_DISPATCH_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  dispatchMode: 'LOCAL_AGENT_TOOL_SEQUENCE',
  sourceRequestId: 'request-123',
  releaseAttemptId: 'attempt-123',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  writeHelperEnabled: false,
  claimable: false,
  mutationAllowed: false,
  applyEnabled: false,
  testEnabled: false,
  rollbackRestoreEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  expectedOutcomeKeys: [
    'patchApplyOutcome',
    'allowlistedVerificationOutcome',
    'postWriteRepositoryObservation',
    'rollbackFallbackOutcome',
    'ragFreshnessMarker',
  ],
  orderedToolSequence: [
    {
      order: 1,
      key: 'patchApply',
      toolName: 'patch.apply',
      approvalState: 'APPROVED',
      sideEffectful: true,
      rollbackFallback: false,
    },
    {
      order: 4,
      key: 'rollbackFallback',
      toolName: 'rollback.restore',
      approvalState: 'REQUIRED',
      sideEffectful: true,
      rollbackFallback: true,
    },
  ],
  requiredApprovals: [
    {
      key: 'patchApply',
      toolName: 'patch.apply',
      approvalState: 'APPROVED',
      sideEffectful: true,
    },
    {
      key: 'postWriteObservation',
      toolName: 'git.status',
      approvalState: 'NOT_REQUIRED',
      sideEffectful: false,
    },
  ],
  rollbackObligation: {
    status: 'RESTORE_VALIDATED',
    toolName: 'rollback.restore',
    required: true,
    rollbackRestoreEnabled: false,
  },
  ragFreshnessObligation: {
    status: 'MODELED_UPDATE_DISABLED',
    required: true,
    ragFreshnessUpdateEnabled: false,
    message: 'Local file changes must produce a partial reindex marker or explicit stale-index warning before final reporting.',
  },
  blockingKeys: [],
  message: 'Local Agent mutation dispatch envelope is modeled, but request creation, push, claim, and mutation remain disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation dispatch envelope contract: READY_DISPATCH_DISABLED / learnbot.local-agent.mutation-dispatch-envelope.v1 / prerequisites true / USER_LOCAL_AGENT / LOCAL_AGENT_TOOL_SEQUENCE'
);
assert.equal(
  view.idsText,
  'mutation dispatch ids: source request-123 / release attempt-123 / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.disabledText,
  'mutation dispatch disabled: release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.equal(
  view.expectedOutcomesText,
  'mutation dispatch expected outcomes: patchApplyOutcome, allowlistedVerificationOutcome, postWriteRepositoryObservation, rollbackFallbackOutcome, ragFreshnessMarker'
);
assert.deepEqual(view.toolLines, [
  '1. patchApply: patch.apply / approval APPROVED / side-effect true / rollback fallback false',
  '4. rollbackFallback: rollback.restore / approval REQUIRED / side-effect true / rollback fallback true',
]);
assert.deepEqual(view.approvalLines, [
  'approval patchApply: APPROVED / patch.apply / side-effect true',
  'approval postWriteObservation: NOT_REQUIRED / git.status / side-effect false',
]);
assert.equal(
  view.rollbackText,
  'rollback obligation: RESTORE_VALIDATED / rollback.restore / required true / rollback restore false'
);
assert.equal(
  view.ragFreshnessText,
  'RAG freshness obligation: MODELED_UPDATE_DISABLED / required true / rag freshness false / Local file changes must produce a partial reindex marker or explicit stale-index warning before final reporting.'
);
assert.equal(view.blockingText, '');
assert.match(view.message, /remain disabled/);

const blocked = buildMutationDispatchEnvelopeContractView({
  status: 'BLOCKED_DISPATCH_DISABLED',
  blockingKeys: ['rollbackReadiness', 'ragFreshnessRequirement'],
});
assert.equal(blocked.blockingText, 'mutation dispatch blocking keys: rollbackReadiness, ragFreshnessRequirement');

const hidden = buildMutationDispatchEnvelopeContractView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.toolLines, []);

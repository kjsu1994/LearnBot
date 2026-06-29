import assert from 'node:assert/strict';
import { buildMutationRequestBlueprintView } from './mutationRequestBlueprint.js';

const blueprint = {
  schema: 'learnbot.local-agent.mutation-request-blueprint.v1',
  status: 'REFUSED_REQUEST_CREATION_DISABLED',
  prerequisitesPassed: true,
  releaseAttemptId: 'abcdef12-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  sourceDecisionStatus: 'REFUSED_DISPATCH_DISABLED',
  sourceEnvelopeStatus: 'READY_DISPATCH_DISABLED',
  requestCreationMode: 'BLUEPRINT_ONLY_DISABLED',
  expectedInputKeys: [
    'sourceRequestId',
    'releaseAttemptId',
    'sessionId',
    'userId',
    'agentId',
    'workspaceId',
    'toolName',
    'approvalState',
    'input',
  ],
  expectedOutputKeys: [
    'patchApplyOutcome',
    'allowlistedVerificationOutcome',
    'postWriteRepositoryObservation',
    'rollbackFallbackOutcome',
    'ragFreshnessMarker',
  ],
  orderedToolRequests: [
    {
      order: 1,
      key: 'patchApply',
      status: 'REQUEST_BLUEPRINT_DISABLED',
      toolName: 'patch.apply',
      approvalState: 'APPROVED_HELD',
      sideEffectful: true,
      rollbackFallback: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      mutationAllowed: false,
      expectedOutputKeys: ['patchApplyOutcome'],
    },
    {
      order: 4,
      key: 'rollbackFallback',
      status: 'REQUEST_BLUEPRINT_DISABLED',
      toolName: 'rollback.restore',
      approvalState: 'APPROVAL_REQUIRED',
      sideEffectful: true,
      rollbackFallback: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      mutationAllowed: false,
      expectedOutputKeys: ['rollbackFallbackOutcome'],
    },
  ],
  approvalStates: [
    {
      key: 'patchApply',
      toolName: 'patch.apply',
      approvalState: 'APPROVED_HELD',
    },
    {
      key: 'rollbackFallback',
      toolName: 'rollback.restore',
      approvalState: 'APPROVAL_REQUIRED',
    },
  ],
  releaseGateEnabled: false,
  dispatchDecisionEnabled: false,
  requestBlueprintEnabled: false,
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
  blockingKeys: ['releaseGateEnabled', 'dispatchDecisionEnabled', 'requestCreationEnabled', 'pushEnabled', 'claimEnabled', 'mutationAllowed'],
  message: 'Local Agent mutation request blueprint is derived from the dispatch refusal, but request creation, push, claim, and mutation remain disabled.',
};

const view = buildMutationRequestBlueprintView(blueprint);

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation request blueprint: REFUSED_REQUEST_CREATION_DISABLED / learnbot.local-agent.mutation-request-blueprint.v1 / prerequisites true / USER_LOCAL_AGENT / BLUEPRINT_ONLY_DISABLED / decision REFUSED_DISPATCH_DISABLED / envelope READY_DISPATCH_DISABLED'
);
assert.equal(
  view.idsText,
  'mutation request blueprint ids: source request-1 / release abcdef12 / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  view.disabledText,
  'mutation request blueprint disabled: request blueprint false / dispatch decision false / release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.equal(
  view.expectedInputsText,
  'mutation request expected inputs: sourceRequestId, releaseAttemptId, sessionId, userId, agentId, workspaceId, toolName, approvalState, input'
);
assert.equal(
  view.expectedOutputsText,
  'mutation request expected outputs: patchApplyOutcome, allowlistedVerificationOutcome, postWriteRepositoryObservation, rollbackFallbackOutcome, ragFreshnessMarker'
);
assert.deepEqual(view.toolLines, [
  '1. patchApply: patch.apply / REQUEST_BLUEPRINT_DISABLED / approval APPROVED_HELD / side-effect true / rollback fallback false / request creation false / push false / claim false / claimable false / mutation false / outputs patchApplyOutcome',
  '4. rollbackFallback: rollback.restore / REQUEST_BLUEPRINT_DISABLED / approval APPROVAL_REQUIRED / side-effect true / rollback fallback true / request creation false / push false / claim false / claimable false / mutation false / outputs rollbackFallbackOutcome',
]);
assert.deepEqual(view.approvalLines, [
  'blueprint approval patchApply: APPROVED_HELD / patch.apply',
  'blueprint approval rollbackFallback: APPROVAL_REQUIRED / rollback.restore',
]);
assert.equal(
  view.blockingText,
  'mutation request blueprint blocking keys: releaseGateEnabled, dispatchDecisionEnabled, requestCreationEnabled, pushEnabled, claimEnabled, mutationAllowed'
);
assert.equal(view.message, blueprint.message);

const blockedView = buildMutationRequestBlueprintView({
  status: 'BLOCKED_REQUEST_BLUEPRINT_DISABLED',
  orderedToolRequests: [
    {
      key: 'pendingTool',
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
  ],
  approvalStates: [
    {
      key: 'pendingTool',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation request blueprint: BLOCKED_REQUEST_BLUEPRINT_DISABLED');
assert.deepEqual(blockedView.toolLines, [
  'pendingTool: tool pending / request creation false / push false / claim false / claimable false / mutation false',
]);
assert.deepEqual(blockedView.approvalLines, [
  'blueprint approval pendingTool: UNKNOWN',
]);

const hiddenView = buildMutationRequestBlueprintView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.toolLines, []);
assert.deepEqual(hiddenView.approvalLines, []);

console.log('mutationRequestBlueprint view tests passed');

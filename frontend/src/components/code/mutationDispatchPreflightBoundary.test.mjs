import assert from 'node:assert/strict';
import { buildMutationDispatchPreflightBoundaryView } from './mutationDispatchPreflightBoundary.js';

const readyBoundary = {
  schema: 'learnbot.local-agent.mutation-dispatch-preflight-boundary.v1',
  status: 'READY_PREFLIGHT_DISABLED',
  prerequisitesPassed: true,
  sourceRequestId: 'request-1',
  releaseAttemptId: 'release-1',
  requestedAgentId: 'agent-1',
  connectedAgentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  connectionState: 'CONNECTED',
  agentConnected: true,
  agentMatches: true,
  agentVersion: '0.1.0',
  approvedWorkspaceReady: true,
  workspaceName: 'learnbot',
  workspaceApproved: true,
  workspaceIdentityStatus: 'MATCH',
  workspaceIdentityVerified: true,
  requiredCapabilities: ['patch.apply', 'command.runAllowed', 'git.status', 'rollback.restore'],
  advertisedCapabilities: ['command.runAllowed', 'git.status', 'patch.apply', 'rollback.restore'],
  capabilityChecks: [
    {
      toolName: 'patch.apply',
      available: true,
      passed: true,
      blocking: false,
      sideEffectful: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
    {
      toolName: 'git.status',
      available: true,
      passed: true,
      blocking: false,
      sideEffectful: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
  ],
  missingCapabilities: [],
  dispatchEnvelopeStatus: 'READY_DISPATCH_DISABLED',
  dispatchEnvelopePrerequisitesPassed: true,
  dispatchPreflightEnabled: false,
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
  blockingKeys: [],
  message: 'Local Agent mutation dispatch preflight prerequisites are visible, but dispatch, request creation, push, claim, and mutation remain disabled.',
};

const readyView = buildMutationDispatchPreflightBoundaryView(readyBoundary);

assert.equal(readyView.show, true);
assert.equal(
  readyView.headerText,
  'mutation dispatch preflight boundary: READY_PREFLIGHT_DISABLED / learnbot.local-agent.mutation-dispatch-preflight-boundary.v1 / prerequisites true / USER_LOCAL_AGENT / envelope READY_DISPATCH_DISABLED / envelope prerequisites true'
);
assert.equal(
  readyView.agentText,
  'mutation dispatch preflight agent: CONNECTED / connected true / matches true / requested agent-1 / connected id agent-1 / version 0.1.0'
);
assert.equal(
  readyView.workspaceText,
  'mutation dispatch preflight workspace: workspace-1 / approved ready true / approved true / learnbot / identity MATCH / verified true'
);
assert.equal(
  readyView.requiredCapabilitiesText,
  'mutation dispatch required capabilities: patch.apply, command.runAllowed, git.status, rollback.restore'
);
assert.equal(
  readyView.advertisedCapabilitiesText,
  'mutation dispatch advertised capabilities: command.runAllowed, git.status, patch.apply, rollback.restore'
);
assert.deepEqual(readyView.capabilityLines, [
  'capability patch.apply: available true / passed true / blocking false / side-effect true / request creation false / push false / claimable false / mutation false',
  'capability git.status: available true / passed true / blocking false / side-effect false / request creation false / push false / claimable false / mutation false',
]);
assert.equal(readyView.missingCapabilitiesText, '');
assert.equal(
  readyView.disabledText,
  'mutation dispatch preflight disabled: dispatch preflight false / release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.equal(readyView.blockingText, '');
assert.equal(readyView.message, readyBoundary.message);

const blockedView = buildMutationDispatchPreflightBoundaryView({
  status: 'BLOCKED_PREFLIGHT_DISABLED',
  connectionState: 'DISCONNECTED',
  agentConnected: false,
  agentMatches: false,
  missingCapabilities: ['rollback.restore'],
  blockingKeys: ['agentConnected', 'requiredToolCapabilities'],
  capabilityChecks: [
    {
      toolName: 'rollback.restore',
      available: false,
      passed: false,
      blocking: true,
      sideEffectful: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
  ],
});

assert.equal(blockedView.show, true);
assert.equal(blockedView.headerText, 'mutation dispatch preflight boundary: BLOCKED_PREFLIGHT_DISABLED');
assert.equal(blockedView.agentText, 'mutation dispatch preflight agent: DISCONNECTED / connected false / matches false');
assert.deepEqual(blockedView.capabilityLines, [
  'capability rollback.restore: available false / passed false / blocking true / side-effect true / request creation false / push false / claimable false / mutation false',
]);
assert.equal(blockedView.missingCapabilitiesText, 'mutation dispatch missing capabilities: rollback.restore');
assert.equal(blockedView.blockingText, 'mutation dispatch preflight blocking keys: agentConnected, requiredToolCapabilities');

const hiddenView = buildMutationDispatchPreflightBoundaryView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.capabilityLines, []);

console.log('mutationDispatchPreflightBoundary view tests passed');

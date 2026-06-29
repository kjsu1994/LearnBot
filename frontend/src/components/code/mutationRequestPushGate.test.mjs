import assert from 'node:assert/strict';
import { buildMutationRequestPushGateView } from './mutationRequestPushGate.js';

const refusedGate = {
  schema: 'learnbot.local-agent.mutation-request-push-gate.v1',
  status: 'REFUSED_PUSH_DISABLED',
  creationGateReady: true,
  prerequisitesPassed: true,
  releaseAttemptId: '55667788-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  transportPushPolicy: 'DISABLED_AUDIT_ONLY',
  pusherInvocationEnabled: false,
  sourceCreationGateStatus: 'REFUSED_CREATION_DISABLED',
  expectedRequestCount: 4,
  persistedRequestCount: 0,
  pushedRequestCount: 0,
  claimableRequestCount: 0,
  pushGateEnabled: false,
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
  policyChecks: [
    {
      key: 'mutationRequestCreationGate',
      status: 'REFUSED_CREATION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      mutationAllowed: false,
      message: 'A disabled creation gate must refuse persistence before push can be considered.',
    },
    {
      key: 'pusherInvocation',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      mutationAllowed: false,
      message: 'LocalAgentToolPusher must not be called for disabled mutation requests.',
    },
  ],
  blockingKeys: ['transportPushPolicy', 'pusherInvocation', 'claimableTransition', 'pushEnabled', 'requestCreationEnabled', 'claimEnabled', 'mutationAllowed'],
  message: 'Local Agent mutation request push is explicitly refused: no transport push, claim transition, or mutation is enabled.',
};

const refusedView = buildMutationRequestPushGateView(refusedGate);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation request push gate: REFUSED_PUSH_DISABLED / learnbot.local-agent.mutation-request-push-gate.v1 / creation gate ready true / prerequisites true / USER_LOCAL_AGENT / transport DISABLED_AUDIT_ONLY / pusher false / creation status REFUSED_CREATION_DISABLED'
);
assert.equal(
  refusedView.idsText,
  'mutation request push gate ids: source request-1 / release 55667788 / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.countsText,
  'mutation request push counts: expected 4 / persisted 0 / pushed 0 / claimable 0'
);
assert.equal(
  refusedView.disabledText,
  'mutation request push disabled: push gate false / release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.deepEqual(refusedView.policyLines, [
  'push policy mutationRequestCreationGate: REFUSED_CREATION_DISABLED / passed true / blocking false / request creation false / push false / claim false / claimable false / mutation false / A disabled creation gate must refuse persistence before push can be considered.',
  'push policy pusherInvocation: DISABLED / passed false / blocking true / request creation false / push false / claim false / claimable false / mutation false / LocalAgentToolPusher must not be called for disabled mutation requests.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation request push blocking keys: transportPushPolicy, pusherInvocation, claimableTransition, pushEnabled, requestCreationEnabled, claimEnabled, mutationAllowed'
);
assert.equal(refusedView.message, refusedGate.message);

const blockedView = buildMutationRequestPushGateView({
  status: 'BLOCKED_PUSH_DISABLED',
  expectedRequestCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation request push gate: BLOCKED_PUSH_DISABLED');
assert.equal(blockedView.countsText, 'mutation request push counts: expected 0');
assert.deepEqual(blockedView.policyLines, [
  'push policy unknownPolicy: UNKNOWN',
]);

const hiddenView = buildMutationRequestPushGateView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.policyLines, []);

console.log('mutationRequestPushGate view tests passed');

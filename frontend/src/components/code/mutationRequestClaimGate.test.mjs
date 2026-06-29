import assert from 'node:assert/strict';
import { buildMutationRequestClaimGateView } from './mutationRequestClaimGate.js';

const refusedGate = {
  schema: 'learnbot.local-agent.mutation-request-claim-gate.v1',
  status: 'REFUSED_CLAIM_DISABLED',
  pushGateReady: true,
  prerequisitesPassed: true,
  releaseAttemptId: '99aabbcc-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  claimPolicy: 'DISABLED_AUDIT_ONLY',
  claimNextInvocationEnabled: false,
  sourcePushGateStatus: 'REFUSED_PUSH_DISABLED',
  expectedRequestCount: 4,
  persistedRequestCount: 0,
  pushedRequestCount: 0,
  claimableRequestCount: 0,
  runningRequestCount: 0,
  claimGateEnabled: false,
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
      key: 'mutationRequestPushGate',
      status: 'REFUSED_PUSH_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      running: false,
      mutationAllowed: false,
      message: 'A disabled push gate must refuse transport push before claim can be considered.',
    },
    {
      key: 'runningTransition',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      running: false,
      mutationAllowed: false,
      message: 'No mutation request can move to RUNNING while claim is disabled.',
    },
  ],
  blockingKeys: ['claimPolicy', 'claimNextInvocation', 'runningTransition', 'claimEnabled', 'pushEnabled', 'requestCreationEnabled', 'mutationAllowed'],
  message: 'Local Agent mutation request claim is explicitly refused: no claimNext call, claimable transition, running transition, or mutation is enabled.',
};

const refusedView = buildMutationRequestClaimGateView(refusedGate);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation request claim gate: REFUSED_CLAIM_DISABLED / learnbot.local-agent.mutation-request-claim-gate.v1 / push gate ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / claimNext false / push status REFUSED_PUSH_DISABLED'
);
assert.equal(
  refusedView.idsText,
  'mutation request claim gate ids: source request-1 / release 99aabbcc / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.countsText,
  'mutation request claim counts: expected 4 / persisted 0 / pushed 0 / claimable 0 / running 0'
);
assert.equal(
  refusedView.disabledText,
  'mutation request claim disabled: claim gate false / release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.deepEqual(refusedView.policyLines, [
  'claim policy mutationRequestPushGate: REFUSED_PUSH_DISABLED / passed true / blocking false / request creation false / push false / claim false / claimable false / running false / mutation false / A disabled push gate must refuse transport push before claim can be considered.',
  'claim policy runningTransition: DISABLED / passed false / blocking true / request creation false / push false / claim false / claimable false / running false / mutation false / No mutation request can move to RUNNING while claim is disabled.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation request claim blocking keys: claimPolicy, claimNextInvocation, runningTransition, claimEnabled, pushEnabled, requestCreationEnabled, mutationAllowed'
);
assert.equal(refusedView.message, refusedGate.message);

const blockedView = buildMutationRequestClaimGateView({
  status: 'BLOCKED_CLAIM_DISABLED',
  expectedRequestCount: 0,
  runningRequestCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation request claim gate: BLOCKED_CLAIM_DISABLED');
assert.equal(blockedView.countsText, 'mutation request claim counts: expected 0 / running 0');
assert.deepEqual(blockedView.policyLines, [
  'claim policy unknownPolicy: UNKNOWN',
]);

const hiddenView = buildMutationRequestClaimGateView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.policyLines, []);

console.log('mutationRequestClaimGate view tests passed');

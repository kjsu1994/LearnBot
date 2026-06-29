import assert from 'node:assert/strict';
import { buildMutationRequestCreationGateView } from './mutationRequestCreationGate.js';

const refusedGate = {
  schema: 'learnbot.local-agent.mutation-request-creation-gate.v1',
  status: 'REFUSED_CREATION_DISABLED',
  blueprintReady: true,
  prerequisitesPassed: true,
  releaseAttemptId: '11223344-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  releaseGateState: 'DISABLED',
  requestCreationPolicy: 'DISABLED_AUDIT_ONLY',
  expectedRequestCount: 4,
  persistedRequestCount: 0,
  pushedRequestCount: 0,
  claimableRequestCount: 0,
  requestCreationGateEnabled: false,
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
      key: 'mutationRequestBlueprint',
      status: 'REFUSED_REQUEST_CREATION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      mutationAllowed: false,
      message: 'A disabled request blueprint must be present before creation can be considered.',
    },
    {
      key: 'requestPersistence',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      claimable: false,
      mutationAllowed: false,
      message: 'No Local Agent mutation tool execution row may be inserted while this gate is disabled.',
    },
  ],
  blockingKeys: ['releaseGateEnabled', 'requestCreationPolicy', 'requestPersistence', 'requestCreationEnabled', 'pushEnabled', 'claimEnabled', 'mutationAllowed'],
  message: 'Local Agent mutation request creation is explicitly refused: no execution rows are created, pushed, or made claimable while creation is disabled.',
};

const refusedView = buildMutationRequestCreationGateView(refusedGate);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation request creation gate: REFUSED_CREATION_DISABLED / learnbot.local-agent.mutation-request-creation-gate.v1 / blueprint ready true / prerequisites true / USER_LOCAL_AGENT / release gate DISABLED / policy DISABLED_AUDIT_ONLY'
);
assert.equal(
  refusedView.idsText,
  'mutation request creation gate ids: source request-1 / release 11223344 / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.countsText,
  'mutation request creation counts: expected 4 / persisted 0 / pushed 0 / claimable 0'
);
assert.equal(
  refusedView.disabledText,
  'mutation request creation disabled: creation gate false / release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.deepEqual(refusedView.policyLines, [
  'creation policy mutationRequestBlueprint: REFUSED_REQUEST_CREATION_DISABLED / passed true / blocking false / request creation false / push false / claim false / claimable false / mutation false / A disabled request blueprint must be present before creation can be considered.',
  'creation policy requestPersistence: DISABLED / passed false / blocking true / request creation false / push false / claim false / claimable false / mutation false / No Local Agent mutation tool execution row may be inserted while this gate is disabled.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation request creation blocking keys: releaseGateEnabled, requestCreationPolicy, requestPersistence, requestCreationEnabled, pushEnabled, claimEnabled, mutationAllowed'
);
assert.equal(refusedView.message, refusedGate.message);

const blockedView = buildMutationRequestCreationGateView({
  status: 'BLOCKED_CREATION_DISABLED',
  expectedRequestCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation request creation gate: BLOCKED_CREATION_DISABLED');
assert.equal(blockedView.countsText, 'mutation request creation counts: expected 0');
assert.deepEqual(blockedView.policyLines, [
  'creation policy unknownPolicy: UNKNOWN',
]);

const hiddenView = buildMutationRequestCreationGateView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.policyLines, []);

console.log('mutationRequestCreationGate view tests passed');

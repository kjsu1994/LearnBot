import assert from 'node:assert/strict';
import { buildMutationDispatchDecisionModelView } from './mutationDispatchDecisionModel.js';

const refusedModel = {
  schema: 'learnbot.local-agent.mutation-dispatch-decision.v1',
  status: 'REFUSED_DISPATCH_DISABLED',
  decision: 'REFUSE_DISPATCH',
  readinessInputsPassed: true,
  releaseAttemptId: '12345678-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  envelopeStatus: 'READY_DISPATCH_DISABLED',
  preflightStatus: 'READY_PREFLIGHT_DISABLED',
  dispatchDecisionEnabled: false,
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
  readinessInputs: [
    {
      key: 'mutationDispatchEnvelopeContract',
      status: 'READY_DISPATCH_DISABLED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      dispatchDecisionEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      message: 'The future dispatch envelope must define ordered tools, approvals, rollback, and freshness obligations.',
    },
    {
      key: 'releaseGateEnabled',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      releaseGateEnabled: false,
      dispatchDecisionEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      message: 'The backend release gate is still disabled, so no held patch can become claimable.',
    },
  ],
  blockingKeys: ['releaseGateEnabled', 'dispatchDecisionEnabled'],
  userVisibleRefusalMessage: 'Local Agent mutation dispatch is modeled and preflight-ready, but execution is disabled until the release gate and dispatch decision switch are explicitly enabled.',
  message: 'Dispatch decision refuses mutation dispatch by policy: release gate, request creation, push, claim, and mutation remain disabled.',
};

const refusedView = buildMutationDispatchDecisionModelView(refusedModel);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation dispatch decision model: REFUSED_DISPATCH_DISABLED / learnbot.local-agent.mutation-dispatch-decision.v1 / decision REFUSE_DISPATCH / readiness inputs true / USER_LOCAL_AGENT / envelope READY_DISPATCH_DISABLED / preflight READY_PREFLIGHT_DISABLED'
);
assert.equal(
  refusedView.idsText,
  'mutation dispatch decision ids: source request-1 / release 12345678 / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.disabledText,
  'mutation dispatch decision disabled: dispatch decision false / release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.deepEqual(refusedView.inputLines, [
  'decision input mutationDispatchEnvelopeContract: READY_DISPATCH_DISABLED / passed true / blocking false / release gate false / dispatch decision false / request creation false / push false / claimable false / mutation false / The future dispatch envelope must define ordered tools, approvals, rollback, and freshness obligations.',
  'decision input releaseGateEnabled: DISABLED / passed false / blocking true / release gate false / dispatch decision false / request creation false / push false / claimable false / mutation false / The backend release gate is still disabled, so no held patch can become claimable.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation dispatch decision blocking keys: releaseGateEnabled, dispatchDecisionEnabled'
);
assert.equal(refusedView.refusalText, `dispatch refusal: ${refusedModel.userVisibleRefusalMessage}`);
assert.equal(refusedView.message, refusedModel.message);

const legacyStatusView = buildMutationDispatchDecisionModelView({
  status: 'BLOCKED_DISPATCH_DISABLED',
  dispatchEnvelopeStatus: 'BLOCKED_DISPATCH_DISABLED',
  dispatchPreflightStatus: 'BLOCKED_PREFLIGHT_DISABLED',
});

assert.equal(
  legacyStatusView.headerText,
  'mutation dispatch decision model: BLOCKED_DISPATCH_DISABLED / envelope BLOCKED_DISPATCH_DISABLED / preflight BLOCKED_PREFLIGHT_DISABLED'
);

const hiddenView = buildMutationDispatchDecisionModelView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.inputLines, []);

console.log('mutationDispatchDecisionModel view tests passed');

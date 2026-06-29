import assert from 'node:assert/strict';
import { buildMutationExecutionGateView } from './mutationExecutionGate.js';

const refusedGate = {
  schema: 'learnbot.local-agent.mutation-execution-gate.v1',
  status: 'REFUSED_EXECUTION_DISABLED',
  claimGateReady: true,
  prerequisitesPassed: true,
  releaseAttemptId: '99aabbcc-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  executionPolicy: 'DISABLED_AUDIT_ONLY',
  toolRunnerInvocationEnabled: false,
  writeHelperInvocationEnabled: false,
  sourceClaimGateStatus: 'REFUSED_CLAIM_DISABLED',
  expectedRequestCount: 4,
  persistedRequestCount: 0,
  pushedRequestCount: 0,
  claimableRequestCount: 0,
  runningRequestCount: 0,
  completedRequestCount: 0,
  executionGateEnabled: false,
  executionEnabled: false,
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
      key: 'mutationRequestClaimGate',
      status: 'REFUSED_CLAIM_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      running: false,
      completed: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'A disabled claim gate must refuse claim and running transitions before execution can be considered.',
    },
    {
      key: 'toolRunnerInvocation',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      running: false,
      completed: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      ragFreshnessUpdateEnabled: false,
      mutationResultAggregationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'No Local Agent tool runner may be invoked for disabled mutation execution.',
    },
  ],
  blockingKeys: [
    'executionPolicy',
    'toolRunnerInvocation',
    'writeHelperInvocation',
    'completionTransition',
    'executionEnabled',
    'writeHelperEnabled',
    'applyEnabled',
    'testEnabled',
    'rollbackRestoreEnabled',
    'ragFreshnessUpdateEnabled',
    'mutationResultAggregationEnabled',
    'publicationEnabled',
    'finalAnswerGenerationEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation execution is explicitly refused: no tool runner, write helper, apply, test, rollback restore, RAG freshness update, aggregation, publication, or final answer is enabled.',
};

const refusedView = buildMutationExecutionGateView(refusedGate);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation execution gate: REFUSED_EXECUTION_DISABLED / learnbot.local-agent.mutation-execution-gate.v1 / claim gate ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / tool runner false / write helper invocation false / claim status REFUSED_CLAIM_DISABLED'
);
assert.equal(
  refusedView.idsText,
  'mutation execution gate ids: source request-1 / release 99aabbcc / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.countsText,
  'mutation execution counts: expected 4 / persisted 0 / pushed 0 / claimable 0 / running 0 / completed 0'
);
assert.equal(
  refusedView.disabledText,
  'mutation execution disabled: execution gate false / execution false / release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.deepEqual(refusedView.policyLines, [
  'execution policy mutationRequestClaimGate: REFUSED_CLAIM_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / running false / completed false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false / A disabled claim gate must refuse claim and running transitions before execution can be considered.',
  'execution policy toolRunnerInvocation: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / running false / completed false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / publication false / final answer false / No Local Agent tool runner may be invoked for disabled mutation execution.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation execution blocking keys: executionPolicy, toolRunnerInvocation, writeHelperInvocation, completionTransition, executionEnabled, writeHelperEnabled, applyEnabled, testEnabled, rollbackRestoreEnabled, ragFreshnessUpdateEnabled, mutationResultAggregationEnabled, publicationEnabled, finalAnswerGenerationEnabled, mutationAllowed'
);
assert.equal(refusedView.message, refusedGate.message);

const blockedView = buildMutationExecutionGateView({
  status: 'BLOCKED_EXECUTION_DISABLED',
  expectedRequestCount: 0,
  completedRequestCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation execution gate: BLOCKED_EXECUTION_DISABLED');
assert.equal(blockedView.countsText, 'mutation execution counts: expected 0 / completed 0');
assert.deepEqual(blockedView.policyLines, [
  'execution policy unknownPolicy: UNKNOWN',
]);

const hiddenView = buildMutationExecutionGateView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.policyLines, []);

console.log('mutationExecutionGate view tests passed');

import assert from 'node:assert/strict';
import { buildMutationWriteHelperSafetyGateView } from './mutationWriteHelperSafetyGate.js';

const refusedGate = {
  schema: 'learnbot.local-agent.mutation-write-helper-safety-gate.v1',
  status: 'REFUSED_WRITE_HELPER_DISABLED',
  executionGateReady: true,
  prerequisitesPassed: true,
  releaseAttemptId: '99aabbcc-1234-1234-1234-123456789abc',
  sourceRequestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  executionTarget: 'USER_LOCAL_AGENT',
  writeHelperPolicy: 'DISABLED_AUDIT_ONLY',
  sourceExecutionGateStatus: 'REFUSED_EXECUTION_DISABLED',
  expectedRequestCount: 4,
  writeHelperEnabled: false,
  applyEnabled: false,
  mutationAllowed: false,
  rollbackRestoreEnabled: false,
  executionEnabled: false,
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  claimable: false,
  testEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  policyChecks: [
    {
      key: 'mutationExecutionGate',
      status: 'REFUSED_EXECUTION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      message: 'A disabled execution gate must refuse mutation execution before write-helper safety can be considered.',
    },
    {
      key: 'workspaceContainment',
      status: 'REQUIRED_DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      message: 'A future write helper must re-check approved workspace containment immediately before every write.',
    },
    {
      key: 'snapshotManifest',
      status: 'REQUIRED_DISABLED',
      passed: false,
      blocking: true,
      message: 'A future write helper must require a fresh managed snapshot manifest before mutation.',
    },
    {
      key: 'hashRecheck',
      status: 'REQUIRED_DISABLED',
      passed: false,
      blocking: true,
      message: 'A future write helper must re-check expected hashes after snapshot creation and before rewriting files.',
    },
    {
      key: 'atomicRewrite',
      status: 'REQUIRED_DISABLED',
      passed: false,
      blocking: true,
      message: 'A future write helper must use the guarded temp-file rewrite sequence and report before/after hashes.',
    },
    {
      key: 'rollbackContract',
      status: 'REQUIRED_DISABLED',
      passed: false,
      blocking: true,
      message: 'A future write helper must keep rollback restore approval and manifest validation available before writes.',
    },
  ],
  blockingKeys: [
    'writeHelperPolicy',
    'workspaceContainment',
    'snapshotManifest',
    'hashRecheck',
    'atomicRewrite',
    'rollbackContract',
    'writeHelperEnabled',
    'applyEnabled',
    'mutationAllowed',
    'rollbackRestoreEnabled',
    'requestCreationEnabled',
    'pushEnabled',
    'claimEnabled',
  ],
  message: 'Local Agent write-helper safety is explicitly refused: write helper, apply, mutation, and rollback restore remain disabled until guarded write preconditions are implemented end to end.',
};

const refusedView = buildMutationWriteHelperSafetyGateView(refusedGate);

assert.equal(refusedView.show, true);
assert.equal(
  refusedView.headerText,
  'mutation write-helper safety gate: REFUSED_WRITE_HELPER_DISABLED / learnbot.local-agent.mutation-write-helper-safety-gate.v1 / execution gate ready true / prerequisites true / USER_LOCAL_AGENT / policy DISABLED_AUDIT_ONLY / execution status REFUSED_EXECUTION_DISABLED'
);
assert.equal(
  refusedView.idsText,
  'mutation write-helper safety ids: source request-1 / release 99aabbcc / session session-1 / agent agent-1 / workspace workspace-1'
);
assert.equal(
  refusedView.countsText,
  'mutation write-helper safety counts: expected 4'
);
assert.equal(
  refusedView.disabledText,
  'mutation write-helper safety disabled: write helper false / apply false / mutation false / rollback restore false / execution false / release gate false / request creation false / push false / claim false / claimable false / test false / rag freshness false / result aggregation false / publication false / final answer false'
);
assert.deepEqual(refusedView.policyLines.slice(0, 2), [
  'write-helper safety policy mutationExecutionGate: REFUSED_EXECUTION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / A disabled execution gate must refuse mutation execution before write-helper safety can be considered.',
  'write-helper safety policy workspaceContainment: REQUIRED_DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / A future write helper must re-check approved workspace containment immediately before every write.',
]);
assert.deepEqual(refusedView.policyLines.slice(2), [
  'write-helper safety policy snapshotManifest: REQUIRED_DISABLED / passed false / blocking true / A future write helper must require a fresh managed snapshot manifest before mutation.',
  'write-helper safety policy hashRecheck: REQUIRED_DISABLED / passed false / blocking true / A future write helper must re-check expected hashes after snapshot creation and before rewriting files.',
  'write-helper safety policy atomicRewrite: REQUIRED_DISABLED / passed false / blocking true / A future write helper must use the guarded temp-file rewrite sequence and report before/after hashes.',
  'write-helper safety policy rollbackContract: REQUIRED_DISABLED / passed false / blocking true / A future write helper must keep rollback restore approval and manifest validation available before writes.',
]);
assert.equal(
  refusedView.blockingText,
  'mutation write-helper safety blocking keys: writeHelperPolicy, workspaceContainment, snapshotManifest, hashRecheck, atomicRewrite, rollbackContract, writeHelperEnabled, applyEnabled, mutationAllowed, rollbackRestoreEnabled, requestCreationEnabled, pushEnabled, claimEnabled'
);
assert.equal(refusedView.message, refusedGate.message);

const blockedView = buildMutationWriteHelperSafetyGateView({
  status: 'BLOCKED_WRITE_HELPER_DISABLED',
  expectedRequestCount: 0,
  policyChecks: [
    {
      key: 'unknownPolicy',
    },
  ],
});

assert.equal(blockedView.headerText, 'mutation write-helper safety gate: BLOCKED_WRITE_HELPER_DISABLED');
assert.equal(blockedView.countsText, 'mutation write-helper safety counts: expected 0');
assert.deepEqual(blockedView.policyLines, [
  'write-helper safety policy unknownPolicy: UNKNOWN',
]);

const hiddenView = buildMutationWriteHelperSafetyGateView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.policyLines, []);

console.log('mutationWriteHelperSafetyGate view tests passed');

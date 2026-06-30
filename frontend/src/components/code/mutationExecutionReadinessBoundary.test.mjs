import assert from 'node:assert/strict';
import { buildMutationExecutionReadinessBoundaryView } from './mutationExecutionReadinessBoundary.js';

const view = buildMutationExecutionReadinessBoundaryView({
  schema: 'learnbot.local-agent.mutation-execution-readiness-boundary.v1',
  status: 'REFUSED_EXECUTION_READINESS_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  sourceHandoffSummaryStatus: 'READY_HANDOFF_DISABLED',
  sourceExecutionGateStatus: 'REFUSED_EXECUTION_DISABLED',
  sourceWriteHelperSafetyGateStatus: 'REFUSED_WRITE_HELPER_DISABLED',
  expectedRequestCount: 4,
  completedRequestCount: 0,
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  executionEnabled: false,
  toolRunnerEnabled: false,
  writeHelperEnabled: false,
  applyEnabled: false,
  testEnabled: false,
  rollbackRestoreEnabled: false,
  resultIntakeEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  finalResponseHandoffEnabled: false,
  deliveryReceiptEnabled: false,
  acknowledgementSaveEnabled: false,
  claimable: false,
  mutationAllowed: false,
  readinessChecks: [
    {
      key: 'mutationHandoffSummary',
      status: 'READY_HANDOFF_DISABLED',
      passed: true,
      blocking: false,
      executionEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'mutationExecutionGate',
      status: 'REFUSED_EXECUTION_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
    },
    {
      key: 'mutationWriteHelperSafetyGate',
      status: 'REFUSED_WRITE_HELPER_DISABLED',
      passed: true,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
    },
    {
      key: 'runtimeExecutionSwitch',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      executionEnabled: false,
      toolRunnerEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'sideEffectTransport',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
      acknowledgementSaveEnabled: false,
    },
  ],
  blockingKeys: [
    'runtimeExecutionSwitch',
    'sideEffectTransport',
    'releaseGateEnabled',
    'requestCreationEnabled',
    'pushEnabled',
    'claimEnabled',
    'executionEnabled',
    'writeHelperEnabled',
    'applyEnabled',
    'testEnabled',
    'rollbackRestoreEnabled',
    'resultIntakeEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation execution inputs are modeled, but runtime execution remains disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation execution readiness: REFUSED_EXECUTION_READINESS_DISABLED / learnbot.local-agent.mutation-execution-readiness-boundary.v1 / prerequisites true / USER_LOCAL_AGENT / expected 4 / completed 0'
);
assert.equal(
  view.sourceText,
  'mutation execution readiness sources: handoff READY_HANDOFF_DISABLED / execution gate REFUSED_EXECUTION_DISABLED / write helper REFUSED_WRITE_HELPER_DISABLED'
);
assert.equal(
  view.disabledText,
  'mutation execution readiness disabled: release gate false / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / rag freshness false / result aggregation false / publication false / final answer false / final response handoff false / receipt false / acknowledgement save false / claimable false / mutation false'
);
assert.deepEqual(view.checkLines, [
  'execution readiness mutationHandoffSummary: READY_HANDOFF_DISABLED / passed true / blocking false / execution false / mutation false',
  'execution readiness mutationExecutionGate: REFUSED_EXECUTION_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / acknowledgement save false / claimable false / mutation false',
  'execution readiness mutationWriteHelperSafetyGate: REFUSED_WRITE_HELPER_DISABLED / passed true / blocking false / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / acknowledgement save false / claimable false / mutation false',
  'execution readiness runtimeExecutionSwitch: DISABLED / passed false / blocking true / execution false / tool runner false / mutation false',
  'execution readiness sideEffectTransport: DISABLED / passed false / blocking true / request creation false / push false / claim false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / acknowledgement save false / claimable false / mutation false',
]);
assert.equal(
  view.blockingText,
  'mutation execution readiness blocking keys: runtimeExecutionSwitch, sideEffectTransport, releaseGateEnabled, requestCreationEnabled, pushEnabled, claimEnabled, executionEnabled, writeHelperEnabled, applyEnabled, testEnabled, rollbackRestoreEnabled, resultIntakeEnabled, mutationAllowed'
);
assert.match(view.message, /runtime execution remains disabled/);

const hidden = buildMutationExecutionReadinessBoundaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.checkLines, []);

const blocked = buildMutationExecutionReadinessBoundaryView({
  status: 'BLOCKED_EXECUTION_READINESS_DISABLED',
  readinessChecks: null,
  blockingKeys: null,
  message: 'Local Agent mutation execution readiness is blocked by incomplete disabled handoff, execution, or write-helper inputs.',
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.headerText,
  'mutation execution readiness: BLOCKED_EXECUTION_READINESS_DISABLED'
);
assert.deepEqual(blocked.checkLines, []);
assert.equal(blocked.blockingText, '');
assert.match(blocked.message, /blocked/);

import assert from 'node:assert/strict';
import { buildMutationToolRunnerBoundaryView } from './mutationToolRunnerBoundary.js';

const view = buildMutationToolRunnerBoundaryView({
  schema: 'learnbot.local-agent.mutation-tool-runner-boundary.v1',
  status: 'REFUSED_TOOL_RUNNER_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  sourceExecutionReadinessBoundaryStatus: 'REFUSED_EXECUTION_READINESS_DISABLED',
  sourceExecutionGateStatus: 'REFUSED_EXECUTION_DISABLED',
  toolRunnerPolicy: 'DISABLED_AUDIT_ONLY',
  expectedRequestCount: 4,
  runningRequestCount: 0,
  completedRequestCount: 0,
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  runningTransitionEnabled: false,
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
  claimable: false,
  mutationAllowed: false,
  runnerChecks: [
    {
      key: 'mutationExecutionReadinessBoundary',
      status: 'REFUSED_EXECUTION_READINESS_DISABLED',
      passed: true,
      blocking: false,
      toolRunnerEnabled: false,
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
      runningTransitionEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
    },
    {
      key: 'toolRunnerPolicy',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      runningTransitionEnabled: false,
      toolRunnerEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'requestRunningTransition',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      runningTransitionEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
    },
    {
      key: 'resultCompletionTransition',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      runningTransitionEnabled: false,
      executionEnabled: false,
      toolRunnerEnabled: false,
      writeHelperEnabled: false,
      claimable: false,
      mutationAllowed: false,
      applyEnabled: false,
      testEnabled: false,
      rollbackRestoreEnabled: false,
      resultIntakeEnabled: false,
    },
  ],
  blockingKeys: [
    'toolRunnerPolicy',
    'requestRunningTransition',
    'resultCompletionTransition',
    'requestCreationEnabled',
    'pushEnabled',
    'claimEnabled',
    'runningTransitionEnabled',
    'toolRunnerEnabled',
    'writeHelperEnabled',
    'applyEnabled',
    'testEnabled',
    'rollbackRestoreEnabled',
    'resultIntakeEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation tool-runner inputs are modeled, but runner invocation remains disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation tool runner boundary: REFUSED_TOOL_RUNNER_DISABLED / learnbot.local-agent.mutation-tool-runner-boundary.v1 / prerequisites true / USER_LOCAL_AGENT / expected 4 / running 0 / completed 0'
);
assert.equal(
  view.sourceText,
  'mutation tool runner sources: execution readiness REFUSED_EXECUTION_READINESS_DISABLED / execution gate REFUSED_EXECUTION_DISABLED / runner policy DISABLED_AUDIT_ONLY'
);
assert.equal(
  view.disabledText,
  'mutation tool runner disabled: release gate false / request creation false / push false / claim false / running transition false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / rag freshness false / result aggregation false / publication false / final answer false / final response handoff false / receipt false / claimable false / mutation false'
);
assert.deepEqual(view.checkLines, [
  'tool runner mutationExecutionReadinessBoundary: REFUSED_EXECUTION_READINESS_DISABLED / passed true / blocking false / tool runner false / mutation false',
  'tool runner mutationExecutionGate: REFUSED_EXECUTION_DISABLED / passed true / blocking false / request creation false / push false / claim false / running transition false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / claimable false / mutation false',
  'tool runner toolRunnerPolicy: DISABLED / passed false / blocking true / running transition false / tool runner false / mutation false',
  'tool runner requestRunningTransition: DISABLED / passed false / blocking true / request creation false / push false / claim false / running transition false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / claimable false / mutation false',
  'tool runner resultCompletionTransition: DISABLED / passed false / blocking true / request creation false / push false / claim false / running transition false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / result intake false / claimable false / mutation false',
]);
assert.equal(
  view.blockingText,
  'mutation tool runner blocking keys: toolRunnerPolicy, requestRunningTransition, resultCompletionTransition, requestCreationEnabled, pushEnabled, claimEnabled, runningTransitionEnabled, toolRunnerEnabled, writeHelperEnabled, applyEnabled, testEnabled, rollbackRestoreEnabled, resultIntakeEnabled, mutationAllowed'
);
assert.match(view.message, /runner invocation remains disabled/);

const hidden = buildMutationToolRunnerBoundaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.checkLines, []);

const blocked = buildMutationToolRunnerBoundaryView({
  status: 'BLOCKED_TOOL_RUNNER_DISABLED',
  runnerChecks: null,
  blockingKeys: null,
  message: 'Local Agent mutation tool-runner boundary is blocked by incomplete disabled execution readiness or execution gate inputs.',
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.headerText,
  'mutation tool runner boundary: BLOCKED_TOOL_RUNNER_DISABLED'
);
assert.deepEqual(blocked.checkLines, []);
assert.equal(blocked.blockingText, '');
assert.match(blocked.message, /blocked/);

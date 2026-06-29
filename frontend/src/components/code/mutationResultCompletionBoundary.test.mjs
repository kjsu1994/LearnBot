import assert from 'node:assert/strict';
import { buildMutationResultCompletionBoundaryView } from './mutationResultCompletionBoundary.js';

const view = buildMutationResultCompletionBoundaryView({
  schema: 'learnbot.local-agent.mutation-result-completion-boundary.v1',
  status: 'REFUSED_RESULT_COMPLETION_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  sourceToolRunnerBoundaryStatus: 'REFUSED_TOOL_RUNNER_DISABLED',
  sourcePostExecutionObservationGateStatus: 'REFUSED_POST_EXECUTION_OBSERVATION_DISABLED',
  completionPolicy: 'DISABLED_AUDIT_ONLY',
  expectedResultCount: 4,
  completedResultCount: 0,
  acceptedResultCount: 0,
  rejectedResultCount: 0,
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
  completedResultTransitionEnabled: false,
  completedResultPersistenceEnabled: false,
  postExecutionObservationEnabled: false,
  resultIntakeEnabled: false,
  ragFreshnessUpdateEnabled: false,
  mutationResultAggregationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  finalResponseHandoffEnabled: false,
  deliveryReceiptEnabled: false,
  claimable: false,
  mutationAllowed: false,
  resultChecks: [
    {
      key: 'mutationToolRunnerBoundary',
      status: 'REFUSED_TOOL_RUNNER_DISABLED',
      passed: true,
      blocking: false,
      toolRunnerEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'mutationPostExecutionObservationGate',
      status: 'REFUSED_POST_EXECUTION_OBSERVATION_DISABLED',
      passed: true,
      blocking: false,
      toolRunnerEnabled: false,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      postExecutionObservationEnabled: false,
      resultIntakeEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
    {
      key: 'completedResultTransition',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      mutationAllowed: false,
    },
    {
      key: 'resultEnvelopePersistence',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      toolRunnerEnabled: false,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      postExecutionObservationEnabled: false,
      resultIntakeEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
    {
      key: 'observationCapture',
      status: 'DISABLED',
      passed: false,
      blocking: true,
      toolRunnerEnabled: false,
      completedResultTransitionEnabled: false,
      completedResultPersistenceEnabled: false,
      postExecutionObservationEnabled: false,
      resultIntakeEnabled: false,
      claimable: false,
      mutationAllowed: false,
    },
  ],
  blockingKeys: [
    'completedResultTransition',
    'resultEnvelopePersistence',
    'observationCapture',
    'toolRunnerEnabled',
    'completedResultTransitionEnabled',
    'completedResultPersistenceEnabled',
    'postExecutionObservationEnabled',
    'resultIntakeEnabled',
    'mutationAllowed',
  ],
  message: 'Local Agent mutation result completion is modeled, but completed-result transitions remain disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation result completion boundary: REFUSED_RESULT_COMPLETION_DISABLED / learnbot.local-agent.mutation-result-completion-boundary.v1 / prerequisites true / USER_LOCAL_AGENT / expected 4 / completed 0 / accepted 0 / rejected 0'
);
assert.equal(
  view.sourceText,
  'mutation result completion sources: tool runner REFUSED_TOOL_RUNNER_DISABLED / observation REFUSED_POST_EXECUTION_OBSERVATION_DISABLED / completion policy DISABLED_AUDIT_ONLY'
);
assert.equal(
  view.disabledText,
  'mutation result completion disabled: release gate false / request creation false / push false / claim false / running transition false / execution false / tool runner false / write helper false / apply false / test false / rollback restore false / completed transition false / result persistence false / observation capture false / result intake false / rag freshness false / result aggregation false / publication false / final answer false / final response handoff false / receipt false / claimable false / mutation false'
);
assert.deepEqual(view.checkLines, [
  'result completion mutationToolRunnerBoundary: REFUSED_TOOL_RUNNER_DISABLED / passed true / blocking false / tool runner false / mutation false',
  'result completion mutationPostExecutionObservationGate: REFUSED_POST_EXECUTION_OBSERVATION_DISABLED / passed true / blocking false / tool runner false / completed transition false / result persistence false / observation capture false / result intake false / claimable false / mutation false',
  'result completion completedResultTransition: DISABLED / passed false / blocking true / completed transition false / result persistence false / mutation false',
  'result completion resultEnvelopePersistence: DISABLED / passed false / blocking true / tool runner false / completed transition false / result persistence false / observation capture false / result intake false / claimable false / mutation false',
  'result completion observationCapture: DISABLED / passed false / blocking true / tool runner false / completed transition false / result persistence false / observation capture false / result intake false / claimable false / mutation false',
]);
assert.equal(
  view.blockingText,
  'mutation result completion blocking keys: completedResultTransition, resultEnvelopePersistence, observationCapture, toolRunnerEnabled, completedResultTransitionEnabled, completedResultPersistenceEnabled, postExecutionObservationEnabled, resultIntakeEnabled, mutationAllowed'
);
assert.match(view.message, /completed-result transitions/);

const hidden = buildMutationResultCompletionBoundaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.checkLines, []);

const blocked = buildMutationResultCompletionBoundaryView({
  status: 'BLOCKED_RESULT_COMPLETION_DISABLED',
  resultChecks: null,
  blockingKeys: null,
  message: 'Local Agent mutation result completion is blocked by incomplete disabled tool-runner or post-execution observation inputs.',
});
assert.equal(blocked.show, true);
assert.equal(
  blocked.headerText,
  'mutation result completion boundary: BLOCKED_RESULT_COMPLETION_DISABLED'
);
assert.deepEqual(blocked.checkLines, []);
assert.equal(blocked.blockingText, '');
assert.match(blocked.message, /blocked/);

import assert from 'node:assert/strict';
import { buildMutationResultIntakeBoundaryView } from './mutationResultIntakeBoundary.js';

const view = buildMutationResultIntakeBoundaryView({
  schema: 'learnbot.local-agent.mutation-result-intake-boundary.v1',
  status: 'READY_INTAKE_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  postMutationResultSchema: 'learnbot.local-agent.post-mutation-result.v1',
  requiredOutcomeKeys: [
    'patchApplyOutcome',
    'allowlistedVerificationOutcome',
    'postWriteRepositoryObservation',
    'rollbackFallbackOutcome',
    'ragFreshnessMarker',
  ],
  acceptedTerminalStatuses: [
    'SUCCEEDED',
    'FAILED',
    'REJECTED',
    'TIMED_OUT',
    'DISCONNECTED',
  ],
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
  finalAnswerGenerationEnabled: false,
  requirements: [
    {
      key: 'sourceRequestLink',
      status: 'REQUIRED_DISABLED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Future mutation result envelopes must include the approved-held source request id.',
    },
    {
      key: 'mutationAppliedProof',
      status: 'PATCH_APPLY_RESULT_REQUIRED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Final completion must require patch.apply output with mutationApplied=true before claiming files changed.',
    },
  ],
  blockingKeys: [],
  message: 'Future Local Agent mutation result intake requirements are modeled, but result aggregation and final-answer generation remain disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'mutation result intake boundary: READY_INTAKE_DISABLED / learnbot.local-agent.mutation-result-intake-boundary.v1 / prerequisites true / USER_LOCAL_AGENT / source learnbot.local-agent.post-mutation-result.v1'
);
assert.equal(
  view.disabledText,
  'mutation result intake disabled: release gate false / request creation false / push false / claim false / write helper false / claimable false / mutation false / apply false / test false / rollback restore false / rag freshness false / result aggregation false / final answer false'
);
assert.equal(
  view.requiredOutcomeText,
  'mutation result required outcomes: patchApplyOutcome, allowlistedVerificationOutcome, postWriteRepositoryObservation, rollbackFallbackOutcome, ragFreshnessMarker'
);
assert.equal(
  view.acceptedStatusesText,
  'mutation result accepted terminal statuses: SUCCEEDED, FAILED, REJECTED, TIMED_OUT, DISCONNECTED'
);
assert.deepEqual(view.requirementLines, [
  'sourceRequestLink: REQUIRED_DISABLED / passed true / blocking false / release gate false / claimable false / mutation false / result aggregation false / final answer false / Future mutation result envelopes must include the approved-held source request id.',
  'mutationAppliedProof: PATCH_APPLY_RESULT_REQUIRED / passed true / blocking false / release gate false / claimable false / mutation false / result aggregation false / final answer false / Final completion must require patch.apply output with mutationApplied=true before claiming files changed.',
]);
assert.equal(view.blockingText, '');
assert.match(view.message, /result aggregation and final-answer generation remain disabled/);

const hidden = buildMutationResultIntakeBoundaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.requirementLines, []);

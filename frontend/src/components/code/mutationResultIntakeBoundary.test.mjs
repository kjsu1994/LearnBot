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
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Future mutation result envelopes must include the approved-held source request id.',
    },
    {
      key: 'releaseAttemptLink',
      status: 'REQUIRED_DISABLED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Future mutation result envelopes must include the release attempt id that made the held request claimable.',
    },
    {
      key: 'expectedOutcomeKeys',
      status: 'READY_RESULT_CONTRACT_DISABLED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Future mutation result envelopes must cover patch apply, verification, post-write observation, rollback fallback, and RAG freshness.',
    },
    {
      key: 'mutationAppliedProof',
      status: 'PATCH_APPLY_RESULT_REQUIRED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Final completion must require patch.apply output with mutationApplied=true before claiming files changed.',
    },
    {
      key: 'verificationAndRollbackDisclosure',
      status: 'VERIFICATION_AND_ROLLBACK_REQUIRED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Verification failure, skipped verification, rollback execution, and rollback refusal must remain visible in final reporting.',
    },
    {
      key: 'ragFreshnessDisclosure',
      status: 'RAG_FRESHNESS_REQUIRED',
      passed: true,
      blocking: false,
      releaseGateEnabled: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      mutationResultAggregationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Local file changes must produce an explicit RAG freshness marker or stale-index warning before final reporting.',
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
  'sourceRequestLink: REQUIRED_DISABLED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / final answer false / Future mutation result envelopes must include the approved-held source request id.',
  'releaseAttemptLink: REQUIRED_DISABLED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / final answer false / Future mutation result envelopes must include the release attempt id that made the held request claimable.',
  'expectedOutcomeKeys: READY_RESULT_CONTRACT_DISABLED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / final answer false / Future mutation result envelopes must cover patch apply, verification, post-write observation, rollback fallback, and RAG freshness.',
  'mutationAppliedProof: PATCH_APPLY_RESULT_REQUIRED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / final answer false / Final completion must require patch.apply output with mutationApplied=true before claiming files changed.',
  'verificationAndRollbackDisclosure: VERIFICATION_AND_ROLLBACK_REQUIRED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / final answer false / Verification failure, skipped verification, rollback execution, and rollback refusal must remain visible in final reporting.',
  'ragFreshnessDisclosure: RAG_FRESHNESS_REQUIRED / passed true / blocking false / release gate false / request creation false / push false / claimable false / mutation false / result aggregation false / final answer false / Local file changes must produce an explicit RAG freshness marker or stale-index warning before final reporting.',
]);
assert.equal(view.blockingText, '');
assert.match(view.message, /result aggregation and final-answer generation remain disabled/);

const blocked = buildMutationResultIntakeBoundaryView({
  status: 'BLOCKED_INTAKE_DISABLED',
  prerequisitesPassed: false,
  requiredOutcomeKeys: [],
  acceptedTerminalStatuses: [],
  requirements: [
    {
      key: 'expectedOutcomeKeys',
      status: 'BLOCKED_RESULT_CONTRACT_DISABLED',
      passed: false,
      blocking: true,
    },
    {
      key: 'unknownRequirement',
    },
  ],
  blockingKeys: ['expectedOutcomeKeys'],
  message: 'Future Local Agent mutation result intake requirements are incomplete, and result aggregation remains disabled.',
});

assert.equal(
  blocked.headerText,
  'mutation result intake boundary: BLOCKED_INTAKE_DISABLED / prerequisites false'
);
assert.equal(blocked.requiredOutcomeText, '');
assert.equal(blocked.acceptedStatusesText, '');
assert.deepEqual(blocked.requirementLines, [
  'expectedOutcomeKeys: BLOCKED_RESULT_CONTRACT_DISABLED / passed false / blocking true',
  'unknownRequirement: UNKNOWN',
]);
assert.equal(
  blocked.blockingText,
  'mutation result intake blocking keys: expectedOutcomeKeys'
);
assert.match(blocked.message, /requirements are incomplete/);

const hidden = buildMutationResultIntakeBoundaryView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.requirementLines, []);

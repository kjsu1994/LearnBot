import assert from 'node:assert/strict';
import { buildReleaseAttemptModelSummaryView } from './releaseAttemptModelSummary.js';

const view = buildReleaseAttemptModelSummaryView({
  preReleaseRevalidation: {
    status: 'REQUIRED_BEFORE_RELEASE',
    passed: false,
    requiresFreshDryRunAfterReleaseAttempt: true,
    requiresFreshRepositoryVerificationAfterReleaseAttempt: true,
  },
  releaseAttemptModel: {
    status: 'READY_RELEASE_ATTEMPT_DISABLED',
    schema: 'learnbot.local-agent.patch-release-attempt.v1',
    staleWindowSeconds: 120,
    requiredEvidence: [
      'releaseAttemptId',
      'sourceRequestId',
      'freshRepositoryVerification',
      'freshPatchDryRun',
      'createdSnapshotManifest',
      'rollbackManifestValidation',
      'explicitUserReleaseApproval',
    ],
  },
});

assert.equal(view.showPreReleaseRevalidation, true);
assert.equal(
  view.preReleaseRevalidationText,
  'pre-release revalidation: REQUIRED_BEFORE_RELEASE / passed: false / fresh dry-run: true / fresh repo check: true'
);
assert.equal(view.showReleaseAttemptModel, true);
assert.equal(
  view.releaseAttemptModelText,
  'release attempt model: READY_RELEASE_ATTEMPT_DISABLED / learnbot.local-agent.patch-release-attempt.v1 / stale window 120s / evidence 7'
);

const fallbackView = buildReleaseAttemptModelSummaryView({
  preReleaseRevalidation: {},
  releaseAttemptModel: {},
});

assert.equal(fallbackView.preReleaseRevalidationText, 'pre-release revalidation: UNKNOWN');
assert.equal(fallbackView.releaseAttemptModelText, 'release attempt model: UNKNOWN');

const hiddenView = buildReleaseAttemptModelSummaryView();
assert.equal(hiddenView.showPreReleaseRevalidation, false);
assert.equal(hiddenView.preReleaseRevalidationText, '');
assert.equal(hiddenView.showReleaseAttemptModel, false);
assert.equal(hiddenView.releaseAttemptModelText, '');

console.log('releaseAttemptModelSummary view tests passed');

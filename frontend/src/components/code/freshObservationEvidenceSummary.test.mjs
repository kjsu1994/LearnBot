import assert from 'node:assert/strict';
import { buildFreshObservationEvidenceSummaryView } from './freshObservationEvidenceSummary.js';

const releaseAttemptId = '12345678-1234-1234-1234-123456789abc';

const view = buildFreshObservationEvidenceSummaryView({
  evidenceStatus: [
    {
      key: 'freshRepositoryVerification',
      status: 'RELEASE_ATTEMPT_LINKED',
      linked: true,
      sourceOnlyFallback: false,
      blocking: false,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      releaseAttemptId,
    },
    {
      key: 'freshPatchDryRun',
      status: 'SOURCE_ONLY_FALLBACK',
      linked: false,
      sourceOnlyFallback: true,
      blocking: true,
      requestCreationEnabled: false,
      pushEnabled: false,
      claimable: false,
      mutationAllowed: false,
      releaseAttemptId,
    },
  ],
  evidenceCompleteness: {
    status: 'BLOCKED_RELEASE_DISABLED',
    complete: false,
    linkedCount: 1,
    missingCount: 0,
    sourceOnlyFallbackCount: 1,
    blockingCount: 1,
    releaseGateEnabled: false,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimable: false,
    mutationAllowed: false,
    blockingKeys: ['freshPatchDryRun'],
    missingKeys: [],
    sourceOnlyFallbackKeys: ['freshPatchDryRun'],
    message: 'Fresh observation evidence is incomplete for release attempt linkage.',
  },
});

assert.equal(view.showStatus, true);
assert.equal(view.statusHeaderText, 'fresh observation evidence status: audit-only / no request creation / no push');
assert.deepEqual(view.statusLines, [
  'freshRepositoryVerification: RELEASE_ATTEMPT_LINKED / linked true / fallback false / blocking false / request creation false / push false / claimable false / mutation false / attempt 12345678',
  'freshPatchDryRun: SOURCE_ONLY_FALLBACK / linked false / fallback true / blocking true / request creation false / push false / claimable false / mutation false / attempt 12345678',
]);
assert.equal(view.showCompleteness, true);
assert.equal(
  view.completenessText,
  'fresh observation evidence completeness: BLOCKED_RELEASE_DISABLED / complete false / linked 1 / missing 0 / fallback 1 / blocking 1'
);
assert.equal(view.releaseGateText, 'release gate: false / request creation false / push false / claimable false / mutation false');
assert.equal(view.blockingText, 'blocking evidence: freshPatchDryRun');
assert.equal(view.missingText, '');
assert.equal(view.fallbackText, 'fallback-only evidence: freshPatchDryRun');
assert.equal(view.message, 'Fresh observation evidence is incomplete for release attempt linkage.');

const hiddenView = buildFreshObservationEvidenceSummaryView();
assert.equal(hiddenView.showStatus, false);
assert.equal(hiddenView.statusHeaderText, '');
assert.deepEqual(hiddenView.statusLines, []);
assert.equal(hiddenView.showCompleteness, false);
assert.equal(hiddenView.completenessText, '');
assert.equal(hiddenView.releaseGateText, '');

console.log('freshObservationEvidenceSummary view tests passed');

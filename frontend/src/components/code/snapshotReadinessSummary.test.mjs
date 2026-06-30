import assert from 'node:assert/strict';
import { buildSnapshotReadinessSummaryView } from './snapshotReadinessSummary.js';

const snapshot = {
  status: 'INVALID',
  message: 'Snapshot readiness requires a non-mutating Local Agent dry-run observation with mutationApplied=false.',
  snapshotCreated: true,
  manifestCreated: true,
  writesPlanned: true,
  writesCompleted: true,
  manifestId: 'snap-invalid-mutated',
  relativeManifestPath: 'snap-invalid-mutated/manifest.json',
  fileCount: 1,
  observationLinkage: {
    status: 'RELEASE_ATTEMPT_LINKED',
    releaseAttemptLinked: true,
    sourceOnlyFallback: false,
    releaseAttemptId: '12345678-1234-1234-1234-123456789abc',
    sourceRequestId: 'abcdef12-1234-1234-1234-123456789abc',
  },
};

const view = buildSnapshotReadinessSummaryView({
  snapshot,
  snapshotManifestCheck: {
    key: 'snapshotManifestPreview',
    passed: false,
    message: 'Latest Local Agent dry-run must provide non-mutating managed snapshot evidence.',
  },
  rollbackPreconditionsCheck: {
    key: 'rollbackRestorePreconditions',
    passed: true,
    message: 'Rollback restore preconditions are visible.',
  },
  dryRunSnapshotObservation: {
    manifestPreview: {
      id: 'snap-invalid-mutated',
      relativeManifestPath: 'snap-invalid-mutated/manifest.json',
      created: true,
      writesPlanned: true,
      writesCompleted: true,
    },
  },
});

assert.equal(view.show, true);
assert.equal(view.headerText, 'Snapshot readiness: INVALID');
assert.equal(view.message, snapshot.message);
assert.equal(
  view.linkageText,
  'observation linkage: RELEASE_ATTEMPT_LINKED / release-attempt linked: true / source-only fallback: false / attempt 12345678 / source abcdef12'
);
assert.equal(
  view.stateText,
  'snapshot created: true / manifest created: true / writes planned: true / writes completed: true'
);
assert.equal(
  view.manifestText,
  'manifest: snap-invalid-mutated / snap-invalid-mutated/manifest.json / files 1'
);
assert.deepEqual(view.checkLines, [
  'blocked / snapshotManifestPreview: Latest Local Agent dry-run must provide non-mutating managed snapshot evidence.',
  'pass / rollbackRestorePreconditions: Rollback restore preconditions are visible.',
]);
assert.equal(
  view.latestManifestText,
  'latest dry-run manifest: snap-invalid-mutated / snap-invalid-mutated/manifest.json / manifest created: true / writes planned: true / writes completed: true'
);
assert.equal(view.emptyText, '');

const fallbackView = buildSnapshotReadinessSummaryView({
  snapshotManifestCheck: {
    key: 'snapshotManifestPreview',
    passed: true,
    message: 'Snapshot manifest preview exists.',
  },
  rollbackPreconditionsCheck: {
    key: 'rollbackRestorePreconditions',
    passed: true,
    message: 'Rollback restore preconditions exist.',
  },
});
assert.equal(fallbackView.show, true);
assert.equal(fallbackView.headerText, 'Snapshot readiness: observed');
assert.equal(fallbackView.emptyText, 'Queue and refresh a Local Agent dry-run to provide snapshot manifest evidence.');

const blockedFallbackView = buildSnapshotReadinessSummaryView({
  snapshotManifestCheck: {
    key: 'snapshotManifestPreview',
    passed: false,
    message: 'Missing snapshot manifest.',
  },
});
assert.equal(blockedFallbackView.headerText, 'Snapshot readiness: blocked');

const hiddenView = buildSnapshotReadinessSummaryView();
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.checkLines, []);

console.log('snapshotReadinessSummary view tests passed');

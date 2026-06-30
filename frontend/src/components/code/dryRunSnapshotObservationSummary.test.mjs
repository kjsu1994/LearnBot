import assert from 'node:assert/strict';
import { buildDryRunSnapshotObservationSummaryView } from './dryRunSnapshotObservationSummary.js';

const fullView = buildDryRunSnapshotObservationSummaryView({
  wouldCreate: true,
  created: true,
  scope: 'managed-snapshot',
  location: '.learnbot/snapshots/snap-1',
  manifestPreview: {
    id: 'snap-1',
    relativeManifestPath: '.learnbot/snapshots/snap-1/manifest.json',
    created: true,
    writesPlanned: true,
    writesCompleted: false,
  },
  files: [
    {
      path: 'src/App.jsx',
      hashMatches: true,
      contextMatches: true,
    },
    {
      path: '',
      hashMatches: false,
      contextMatches: false,
    },
  ],
});

assert.equal(fullView.show, true);
assert.equal(
  fullView.observationText,
  'snapshot would create: true / created: true / managed-snapshot / .learnbot/snapshots/snap-1'
);
assert.equal(
  fullView.manifestText,
  'snapshot manifest: snap-1 / .learnbot/snapshots/snap-1/manifest.json / manifest created: true / writes planned: true / writes completed: false'
);
assert.equal(
  fullView.filesText,
  'snapshot files: src/App.jsx:hash-ok/context-ok, (unknown):hash-check/context-blocked'
);

const minimalView = buildDryRunSnapshotObservationSummaryView({
  wouldCreate: false,
});

assert.equal(minimalView.show, true);
assert.equal(minimalView.observationText, 'snapshot would create: false');
assert.equal(minimalView.manifestText, '');
assert.equal(minimalView.filesText, '');

const fallbackView = buildDryRunSnapshotObservationSummaryView({
  manifestPreview: {},
});

assert.equal(fallbackView.show, true);
assert.equal(fallbackView.observationText, 'snapshot would create: undefined');
assert.equal(fallbackView.manifestText, 'snapshot manifest: (preview)');

const hiddenView = buildDryRunSnapshotObservationSummaryView();

assert.equal(hiddenView.show, false);
assert.equal(hiddenView.observationText, '');
assert.equal(hiddenView.manifestText, '');
assert.equal(hiddenView.filesText, '');

console.log('dryRunSnapshotObservationSummary view tests passed');

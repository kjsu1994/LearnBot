import assert from 'node:assert/strict';
import { buildPatchReleaseReadinessSummaryView } from './patchReleaseReadinessSummary.js';

const readiness = {
  status: 'BLOCKED',
  message: 'Release remains disabled until fresh Local Agent evidence is complete.',
  preconditionsPassed: false,
  releaseGateEnabled: false,
  mutationEnabled: false,
  prerequisites: [
    {
      key: 'repositoryVerification',
      passed: true,
      message: 'Repository verification is fresh.',
    },
    {
      key: 'snapshotReadiness',
      passed: false,
      message: 'Latest Local Agent dry-run must provide a non-mutating snapshot.',
    },
  ],
};

const view = buildPatchReleaseReadinessSummaryView(readiness);

assert.equal(view.show, true);
assert.equal(view.headerText, 'Pre-apply release checklist: BLOCKED');
assert.equal(view.message, readiness.message);
assert.equal(
  view.stateText,
  'preconditions passed: false / release gate: false / mutation enabled: false'
);
assert.deepEqual(view.prerequisiteLines, [
  'pass / repositoryVerification: Repository verification is fresh.',
  'blocked / snapshotReadiness: Latest Local Agent dry-run must provide a non-mutating snapshot.',
]);

const fallbackView = buildPatchReleaseReadinessSummaryView({});
assert.equal(fallbackView.show, true);
assert.equal(fallbackView.headerText, 'Pre-apply release checklist: UNKNOWN');
assert.equal(fallbackView.message, '');
assert.equal(fallbackView.stateText, 'preconditions passed: undefined');
assert.deepEqual(fallbackView.prerequisiteLines, []);

const hiddenView = buildPatchReleaseReadinessSummaryView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.equal(hiddenView.message, '');
assert.equal(hiddenView.stateText, '');
assert.deepEqual(hiddenView.prerequisiteLines, []);

console.log('patchReleaseReadinessSummary view tests passed');

import assert from 'node:assert/strict';
import { buildReadinessChecksSummaryView } from './readinessChecksSummary.js';

const view = buildReadinessChecksSummaryView({
  checks: [
    {
      key: 'agentConnected',
      passed: true,
      message: 'Local Agent is connected.',
    },
    {
      key: 'snapshotManifestPreview',
      passed: false,
      message: 'Latest Local Agent dry-run must provide non-mutating managed snapshot evidence.',
    },
    {
      key: 'rollbackRestorePreconditions',
      passed: false,
      message: 'Latest Local Agent dry-run must provide rollback restore preconditions before release can be considered.',
    },
    {
      key: 'patchRequestSchema',
      passed: false,
      message: 'Patch request schema is not ready.',
    },
  ],
});

assert.equal(view.show, true);
assert.deepEqual(view.checkRows, [
  {
    key: 'agentConnected',
    passed: true,
    headerText: 'pass 쨌 agentConnected',
    message: 'Local Agent is connected.',
  },
  {
    key: 'patchRequestSchema',
    passed: false,
    headerText: 'blocked 쨌 patchRequestSchema',
    message: 'Patch request schema is not ready.',
  },
]);

const emptyView = buildReadinessChecksSummaryView({
  checks: [
    {
      key: 'snapshotManifestPreview',
      passed: true,
      message: 'Snapshot manifest preview exists.',
    },
    {
      key: 'rollbackRestorePreconditions',
      passed: true,
      message: 'Rollback restore preconditions exist.',
    },
  ],
});

assert.equal(emptyView.show, false);
assert.deepEqual(emptyView.checkRows, []);

const hiddenView = buildReadinessChecksSummaryView();
assert.equal(hiddenView.show, false);
assert.deepEqual(hiddenView.checkRows, []);

console.log('readinessChecksSummary view tests passed');

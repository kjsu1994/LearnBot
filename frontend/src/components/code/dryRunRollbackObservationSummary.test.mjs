import assert from 'node:assert/strict';
import { buildDryRunRollbackObservationSummaryView } from './dryRunRollbackObservationSummary.js';

const fullView = buildDryRunRollbackObservationSummaryView({
  wouldRestore: true,
  restored: false,
  tool: 'rollback.restore',
  restoreScope: 'managed-snapshot',
});

assert.equal(fullView.show, true);
assert.equal(
  fullView.text,
  'rollback would restore: true / restored: false / rollback.restore / managed-snapshot'
);

const minimalView = buildDryRunRollbackObservationSummaryView({
  wouldRestore: false,
});

assert.equal(minimalView.show, true);
assert.equal(minimalView.text, 'rollback would restore: false');

const fallbackView = buildDryRunRollbackObservationSummaryView({});

assert.equal(fallbackView.show, true);
assert.equal(fallbackView.text, 'rollback would restore: undefined');

const hiddenView = buildDryRunRollbackObservationSummaryView();

assert.equal(hiddenView.show, false);
assert.equal(hiddenView.text, '');

console.log('dryRunRollbackObservationSummary view tests passed');

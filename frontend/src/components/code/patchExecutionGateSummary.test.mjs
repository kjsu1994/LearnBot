import assert from 'node:assert/strict';
import { buildPatchExecutionGateSummaryView } from './patchExecutionGateSummary.js';

const gate = {
  status: 'BLOCKED_RELEASE_DISABLED',
  message: 'The held patch remains non-claimable while the release gate is disabled.',
  claimEnabled: false,
  writeHelperEnabled: false,
  releaseGateEnabled: false,
  sourceRequestRelationship: 'SOURCE_APPROVED_HELD_NON_CLAIMABLE',
};

const view = buildPatchExecutionGateSummaryView(gate);

assert.equal(view.show, true);
assert.equal(view.headerText, 'Internal patch execution gate: BLOCKED_RELEASE_DISABLED');
assert.equal(view.message, gate.message);
assert.equal(
  view.controlText,
  'claim enabled: false / write helper: false / release gate: false / SOURCE_APPROVED_HELD_NON_CLAIMABLE'
);

const fallbackView = buildPatchExecutionGateSummaryView({});
assert.equal(fallbackView.show, true);
assert.equal(fallbackView.headerText, 'Internal patch execution gate: UNKNOWN');
assert.equal(fallbackView.message, '');
assert.equal(fallbackView.controlText, 'claim enabled: undefined');

const hiddenView = buildPatchExecutionGateSummaryView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.equal(hiddenView.message, '');
assert.equal(hiddenView.controlText, '');

console.log('patchExecutionGateSummary view tests passed');

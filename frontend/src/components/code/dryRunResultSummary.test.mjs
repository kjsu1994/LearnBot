import assert from 'node:assert/strict';
import { buildDryRunResultSummaryView } from './dryRunResultSummary.js';

const expectedRefusalView = buildDryRunResultSummaryView({
  expectedDryRunRefusal: true,
  result: {
    status: 'REJECTED',
    error: 'Mutation is disabled for dry-run.',
    failureCode: 'UNSAFE_TOOL',
    input: {
      releaseAttemptId: '12345678-1234-1234-1234-123456789abc',
      freshObservationOnly: true,
    },
    output: {
      preflightPassed: true,
      mutationApplied: false,
      snapshotCreated: true,
    },
  },
});

assert.equal(expectedRefusalView.show, true);
assert.equal(expectedRefusalView.titleText, 'Dry-run completed; mutation refused as expected');
assert.equal(expectedRefusalView.errorText, 'Mutation is disabled for dry-run.');
assert.equal(expectedRefusalView.failureText, 'safety gate: UNSAFE_TOOL');
assert.equal(expectedRefusalView.releaseEvidenceText, 'linked release evidence: attempt 12345678 / fresh observation only');
assert.equal(expectedRefusalView.preflightText, 'preflight passed: true');
assert.equal(expectedRefusalView.mutationText, 'mutation applied: false');
assert.equal(expectedRefusalView.snapshotText, 'snapshot created: true');

const failedView = buildDryRunResultSummaryView({
  result: {
    status: 'FAILED',
    failureCode: 'CONTEXT_MISMATCH',
    input: {
      releaseAttemptId: 'abcdef12-1234-1234-1234-123456789abc',
    },
    output: {
      preflightPassed: false,
    },
  },
});

assert.equal(failedView.show, true);
assert.equal(failedView.titleText, 'Dry-run status: FAILED');
assert.equal(failedView.failureText, 'failure: CONTEXT_MISMATCH');
assert.equal(failedView.releaseEvidenceText, 'linked release evidence: attempt abcdef12');
assert.equal(failedView.preflightText, 'preflight passed: false');
assert.equal(failedView.mutationText, '');
assert.equal(failedView.snapshotText, '');

const minimalView = buildDryRunResultSummaryView({
  result: {
    status: 'SUCCEEDED',
  },
});

assert.equal(minimalView.show, true);
assert.equal(minimalView.titleText, 'Dry-run status: SUCCEEDED');
assert.equal(minimalView.errorText, '');
assert.equal(minimalView.failureText, '');
assert.equal(minimalView.releaseEvidenceText, '');

const hiddenView = buildDryRunResultSummaryView();

assert.equal(hiddenView.show, false);
assert.equal(hiddenView.titleText, '');

console.log('dryRunResultSummary view tests passed');

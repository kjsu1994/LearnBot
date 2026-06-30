import assert from 'node:assert/strict';
import { buildRepositoryVerificationSummaryView } from './repositoryVerificationSummary.js';

const verification = {
  status: 'MISMATCH',
  message: 'Local repository identity does not match indexed source metadata.',
  observationLinkage: {
    status: 'SOURCE_ONLY_FALLBACK',
    releaseAttemptLinked: false,
    sourceOnlyFallback: true,
    releaseAttemptId: '12345678-1234-1234-1234-123456789abc',
    sourceRequestId: 'abcdef12-1234-1234-1234-123456789abc',
  },
  checks: [
    {
      key: 'headCommit',
      status: 'MISMATCH',
      expected: '0123456789abcdef0123456789abcdef0123456789abcdef-extra',
      actual: 'fedcba9876543210fedcba9876543210fedcba9876543210-extra',
    },
    {
      key: 'remoteUrl',
      status: 'SKIPPED',
      expected: 'https://example.test/repo.git',
      actual: '',
    },
    {
      key: 'branch',
      status: 'MATCH',
      expected: 'main',
      actual: 'main',
    },
  ],
};

const view = buildRepositoryVerificationSummaryView(verification);

assert.equal(view.show, true);
assert.equal(view.headerText, 'Recorded repository verification: MISMATCH');
assert.equal(view.message, verification.message);
assert.equal(
  view.linkageText,
  'observation linkage: SOURCE_ONLY_FALLBACK / release-attempt linked: false / source-only fallback: true / attempt 12345678 / source abcdef12'
);
assert.deepEqual(view.checkLines, [
  'headCommit: MISMATCH / indexed 0123456789abcdef0123456789abcdef0123456789abcdef / local fedcba9876543210fedcba9876543210fedcba9876543210',
  'branch: MATCH / indexed main / local main',
]);

const fallbackView = buildRepositoryVerificationSummaryView({
  checks: [
    {
      key: 'branch',
      status: 'UNKNOWN',
    },
  ],
});

assert.equal(fallbackView.show, true);
assert.equal(fallbackView.headerText, 'Recorded repository verification: UNVERIFIED');
assert.equal(fallbackView.message, '');
assert.equal(fallbackView.linkageText, '');
assert.deepEqual(fallbackView.checkLines, [
  'branch: UNKNOWN / indexed unknown / local unknown',
]);

const hiddenView = buildRepositoryVerificationSummaryView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.equal(hiddenView.message, '');
assert.equal(hiddenView.linkageText, '');
assert.deepEqual(hiddenView.checkLines, []);

console.log('repositoryVerificationSummary view tests passed');

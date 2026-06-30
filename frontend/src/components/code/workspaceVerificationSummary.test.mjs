import assert from 'node:assert/strict';
import { buildWorkspaceVerificationSummaryView } from './workspaceVerificationSummary.js';

const verification = {
  status: 'MISMATCH',
  blocking: true,
  reason: 'Selected workspace does not match the indexed repository identity.',
  source: 'repositoryObservation',
};

const view = buildWorkspaceVerificationSummaryView(verification);

assert.equal(view.show, true);
assert.equal(view.headerText, 'Effective workspace verification: MISMATCH');
assert.equal(view.blockingText, 'blocking release: true');
assert.equal(view.reason, verification.reason);
assert.equal(view.sourceText, 'source: repositoryObservation');

const fallbackView = buildWorkspaceVerificationSummaryView({});
assert.equal(fallbackView.show, true);
assert.equal(fallbackView.headerText, 'Effective workspace verification: UNVERIFIED');
assert.equal(fallbackView.blockingText, '');
assert.equal(fallbackView.reason, '');
assert.equal(fallbackView.sourceText, '');

const nonBlockingView = buildWorkspaceVerificationSummaryView({
  status: 'VERIFIED',
  blocking: false,
});
assert.equal(nonBlockingView.blockingText, 'blocking release: false');

const hiddenView = buildWorkspaceVerificationSummaryView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.equal(hiddenView.blockingText, '');
assert.equal(hiddenView.reason, '');
assert.equal(hiddenView.sourceText, '');

console.log('workspaceVerificationSummary view tests passed');

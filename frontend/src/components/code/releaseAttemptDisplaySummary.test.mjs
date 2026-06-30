import assert from 'node:assert/strict';
import { buildReleaseAttemptDisplaySummaryView } from './releaseAttemptDisplaySummary.js';

const disabledFlags = {
  releaseGateEnabled: false,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  writeHelperEnabled: false,
  applyEnabled: false,
  testEnabled: false,
  rollbackRestoreEnabled: false,
  ragFreshnessUpdateEnabled: false,
  finalAnswerGenerationEnabled: false,
  mutationAllowed: false,
};

const primary = buildReleaseAttemptDisplaySummaryView({
  displaySummary: {
    show: true,
    linkedEvidenceComplete: true,
    releaseReadyButDisabled: true,
    evidenceStatus: 'ALL_LINKED_RELEASE_DISABLED',
    releaseReadinessStatus: 'READY_RELEASE_DISABLED',
    patchPreconditionsPassed: true,
    evidenceComplete: true,
    linkedCount: 2,
    missingCount: 0,
    sourceOnlyFallbackCount: 0,
    disabledFlags,
    blockingReasons: ['release gate is disabled', 'held patch request remains non-claimable'],
    message: 'Linked release evidence is complete and all release controls remain disabled.',
  },
});

assert.equal(primary.show, true);
assert.equal(primary.title, 'Linked release evidence: complete / release: ready but disabled');
assert.equal(primary.evidenceText, 'evidence ALL_LINKED_RELEASE_DISABLED / linked 2 / missing 0 / fallback 0');
assert.equal(primary.readinessText, 'readiness READY_RELEASE_DISABLED / preconditions true / evidence complete true');
assert.equal(
  primary.disabledGatesText,
  'disabled gates: release false / request creation false / push false / claim false / write helper false / apply false / test false / rollback restore false / RAG freshness false / final answer false / mutation false'
);
assert.equal(primary.whyDisabledText, 'why disabled: release gate is disabled, held patch request remains non-claimable');
assert.match(primary.message, /release controls remain disabled/);

const fallback = buildReleaseAttemptDisplaySummaryView({
  evidenceCompleteness: {
    status: 'ALL_LINKED_RELEASE_DISABLED',
    linkedCount: 2,
    missingCount: 0,
    sourceOnlyFallbackCount: 0,
  },
  finalReadiness: {
    status: 'READY_RELEASE_DISABLED',
    patchPreconditionsPassed: true,
    evidenceComplete: true,
    releaseGateEnabled: false,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    writeHelperEnabled: false,
    applyEnabled: false,
    testEnabled: false,
    rollbackRestoreEnabled: false,
    mutationAllowed: false,
    blockingReasons: ['release gate is disabled', 'held patch request remains non-claimable'],
  },
});

assert.equal(fallback.show, true);
assert.equal(fallback.title, 'Linked release evidence: complete / release: ready but disabled');
assert.equal(fallback.evidenceText, primary.evidenceText);
assert.equal(fallback.readinessText, primary.readinessText);
assert.equal(
  fallback.disabledGatesText,
  'disabled gates: release false / request creation false / push false / claim false / write helper false / apply false / test false / rollback restore false / mutation false'
);
assert.equal(fallback.whyDisabledText, primary.whyDisabledText);

const blockedLinkedEvidence = buildReleaseAttemptDisplaySummaryView({
  displaySummary: {
    show: true,
    linkedEvidenceComplete: true,
    releaseReadyButDisabled: false,
    evidenceStatus: 'ALL_LINKED_RELEASE_DISABLED',
    releaseReadinessStatus: 'BLOCKED_RELEASE_DISABLED',
    patchPreconditionsPassed: false,
    evidenceComplete: true,
    linkedCount: 2,
    missingCount: 0,
    sourceOnlyFallbackCount: 0,
    disabledFlags,
    blockingReasons: [
      'patch release prerequisites are incomplete',
      'release gate is disabled',
      'held patch request remains non-claimable',
    ],
    message: 'The release attempt remains blocked because the dry-run snapshot evidence mutated files.',
  },
});

assert.equal(blockedLinkedEvidence.show, true);
assert.equal(blockedLinkedEvidence.linkedEvidenceComplete, true);
assert.equal(blockedLinkedEvidence.releaseReadyButDisabled, false);
assert.equal(blockedLinkedEvidence.title, 'Linked release evidence: complete / release: blocked');
assert.equal(blockedLinkedEvidence.evidenceText, primary.evidenceText);
assert.equal(blockedLinkedEvidence.readinessText, 'readiness BLOCKED_RELEASE_DISABLED / preconditions false / evidence complete true');
assert.equal(
  blockedLinkedEvidence.disabledGatesText,
  'disabled gates: release false / request creation false / push false / claim false / write helper false / apply false / test false / rollback restore false / RAG freshness false / final answer false / mutation false'
);
assert.equal(
  blockedLinkedEvidence.whyDisabledText,
  'why disabled: patch release prerequisites are incomplete, release gate is disabled, held patch request remains non-claimable'
);
assert.match(blockedLinkedEvidence.message, /dry-run snapshot evidence mutated files/);

const hidden = buildReleaseAttemptDisplaySummaryView({});
assert.equal(hidden.show, false);
assert.equal(hidden.title, 'Linked release evidence: incomplete / release: blocked');

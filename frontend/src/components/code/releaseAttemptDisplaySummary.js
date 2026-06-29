const DISABLED_GATE_LABELS = [
  ['releaseGateEnabled', 'release'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['writeHelperEnabled', 'write helper'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['ragFreshnessUpdateEnabled', 'RAG freshness'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['mutationAllowed', 'mutation'],
];

export function buildReleaseAttemptDisplaySummaryView({
  displaySummary,
  evidenceCompleteness,
  finalReadiness,
} = {}) {
  const disabledFlags = displaySummary?.disabledFlags || {};
  const linkedEvidenceComplete = displaySummary?.linkedEvidenceComplete
    ?? (evidenceCompleteness?.status === 'ALL_LINKED_RELEASE_DISABLED');
  const releaseReadyButDisabled = displaySummary?.releaseReadyButDisabled
    ?? (finalReadiness?.status === 'READY_RELEASE_DISABLED');
  const show = Boolean(displaySummary?.show || linkedEvidenceComplete || releaseReadyButDisabled);

  return {
    show,
    linkedEvidenceComplete,
    releaseReadyButDisabled,
    title: `Linked release evidence: ${linkedEvidenceComplete ? 'complete' : 'incomplete'} / release: ${releaseReadyButDisabled ? 'ready but disabled' : 'blocked'}`,
    evidenceText: releaseEvidenceText(displaySummary, evidenceCompleteness),
    readinessText: releaseReadinessText(displaySummary, finalReadiness),
    disabledGatesText: disabledGatesText(disabledFlags, finalReadiness),
    whyDisabledText: whyDisabledText(displaySummary, finalReadiness),
    message: displaySummary?.message || '',
  };
}

function releaseEvidenceText(displaySummary, evidenceCompleteness) {
  const status = displaySummary?.evidenceStatus || evidenceCompleteness?.status || 'UNKNOWN';
  let text = `evidence ${status}`;
  text += optionalCountText('linked', displaySummary?.linkedCount, evidenceCompleteness?.linkedCount);
  text += optionalCountText('missing', displaySummary?.missingCount, evidenceCompleteness?.missingCount);
  text += optionalCountText('fallback', displaySummary?.sourceOnlyFallbackCount, evidenceCompleteness?.sourceOnlyFallbackCount);
  return text;
}

function releaseReadinessText(displaySummary, finalReadiness) {
  const status = displaySummary?.releaseReadinessStatus || finalReadiness?.status || 'UNKNOWN';
  let text = `readiness ${status}`;
  text += optionalBooleanText('preconditions', displaySummary?.patchPreconditionsPassed, finalReadiness?.patchPreconditionsPassed);
  text += optionalBooleanText('evidence complete', displaySummary?.evidenceComplete, finalReadiness?.evidenceComplete);
  return text;
}

function disabledGatesText(disabledFlags, finalReadiness) {
  const parts = DISABLED_GATE_LABELS
    .map(([key, label]) => {
      const value = disabledFlags[key] ?? finalReadiness?.[key];
      return value === undefined ? null : `${label} ${String(value)}`;
    })
    .filter(Boolean);
  return parts.length ? `disabled gates: ${parts.join(' / ')}` : 'disabled gates:';
}

function whyDisabledText(displaySummary, finalReadiness) {
  const reasons = displaySummary?.blockingReasons || finalReadiness?.blockingReasons || [];
  return reasons.length ? `why disabled: ${reasons.join(', ')}` : '';
}

function optionalCountText(label, primaryValue, fallbackValue) {
  const value = primaryValue ?? fallbackValue;
  return value === undefined ? '' : ` / ${label} ${value}`;
}

function optionalBooleanText(label, primaryValue, fallbackValue) {
  const value = primaryValue ?? fallbackValue;
  return value === undefined ? '' : ` / ${label} ${String(value)}`;
}

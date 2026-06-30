export function buildFreshObservationEvidenceSummaryView({
  evidenceStatus = [],
  evidenceCompleteness = null,
} = {}) {
  const statusItems = Array.isArray(evidenceStatus) ? evidenceStatus : [];

  return {
    showStatus: statusItems.length > 0,
    statusHeaderText: statusItems.length ? 'fresh observation evidence status: audit-only / no request creation / no push' : '',
    statusLines: statusItems.map(freshObservationEvidenceStatusText),
    showCompleteness: Boolean(evidenceCompleteness),
    completenessText: evidenceCompleteness ? freshObservationEvidenceCompletenessText(evidenceCompleteness) : '',
    releaseGateText: evidenceCompleteness ? freshObservationReleaseGateText(evidenceCompleteness) : '',
    blockingText: listText('blocking evidence', evidenceCompleteness?.blockingKeys),
    missingText: listText('missing evidence', evidenceCompleteness?.missingKeys),
    fallbackText: listText('fallback-only evidence', evidenceCompleteness?.sourceOnlyFallbackKeys),
    message: evidenceCompleteness?.message || '',
  };
}

function freshObservationEvidenceStatusText(item) {
  let text = `${item.key}: ${item.status || 'UNKNOWN'}`;
  text += optionalBooleanText('linked', item.linked);
  text += optionalBooleanText('fallback', item.sourceOnlyFallback);
  text += optionalBooleanText('blocking', item.blocking);
  text += optionalBooleanText('request creation', item.requestCreationEnabled);
  text += optionalBooleanText('push', item.pushEnabled);
  text += optionalBooleanText('claimable', item.claimable);
  text += optionalBooleanText('mutation', item.mutationAllowed);
  if (item.releaseAttemptId) {
    text += ` / attempt ${String(item.releaseAttemptId).slice(0, 8)}`;
  }
  return text;
}

function freshObservationEvidenceCompletenessText(completeness) {
  let text = `fresh observation evidence completeness: ${completeness.status || 'UNKNOWN'}`;
  text += optionalBooleanText('complete', completeness.complete);
  text += optionalValueText('linked', completeness.linkedCount);
  text += optionalValueText('missing', completeness.missingCount);
  text += optionalValueText('fallback', completeness.sourceOnlyFallbackCount);
  text += optionalValueText('blocking', completeness.blockingCount);
  return text;
}

function freshObservationReleaseGateText(completeness) {
  let text = `release gate: ${String(completeness.releaseGateEnabled)}`;
  text += optionalBooleanText('request creation', completeness.requestCreationEnabled);
  text += optionalBooleanText('push', completeness.pushEnabled);
  text += optionalBooleanText('claimable', completeness.claimable);
  text += optionalBooleanText('mutation', completeness.mutationAllowed);
  return text;
}

function listText(label, values) {
  return Array.isArray(values) && values.length ? `${label}: ${values.join(', ')}` : '';
}

function optionalBooleanText(label, value) {
  return value === undefined ? '' : ` / ${label} ${String(value)}`;
}

function optionalValueText(label, value) {
  return value === undefined ? '' : ` / ${label} ${value}`;
}

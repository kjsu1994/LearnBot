export function buildAcceptedMutationObservationSummaryText(source = null, label = 'accepted mutation observation summary') {
  if (!source || typeof source !== 'object') {
    return '';
  }

  const schema = firstDefined(source.acceptedMutationObservationSummarySchema, source.schema);
  const status = firstDefined(source.acceptedMutationObservationSummaryStatus, source.status);
  const observationCount = firstDefined(source.acceptedMutationObservationCount, source.observationCount);
  const acceptedCount = firstDefined(source.acceptedMutationObservationAcceptedCount, source.acceptedCount);
  const rejectedCount = firstDefined(source.acceptedMutationObservationRejectedCount, source.rejectedCount);
  const terminalFailureCount = firstDefined(
    source.acceptedMutationObservationTerminalFailureAcceptedCount,
    source.terminalFailureAcceptedCount
  );
  const toolCounts = firstDefined(source.acceptedMutationObservationToolCounts, source.toolObservationCounts);
  const statusCounts = firstDefined(source.acceptedMutationObservationStatusCounts, source.statusObservationCounts);

  const parts = [];
  if (schema || status) {
    parts.push([schema, status].filter(Boolean).join(' / '));
  }
  if (observationCount !== undefined) {
    parts.push(`observations ${String(observationCount)}`);
  }
  if (acceptedCount !== undefined) {
    parts.push(`accepted ${String(acceptedCount)}`);
  }
  if (rejectedCount !== undefined) {
    parts.push(`rejected ${String(rejectedCount)}`);
  }
  if (terminalFailureCount !== undefined) {
    parts.push(`terminal failures ${String(terminalFailureCount)}`);
  }
  const toolCountsText = countsText(toolCounts);
  if (toolCountsText) {
    parts.push(`tool counts ${toolCountsText}`);
  }
  const statusCountsText = countsText(statusCounts);
  if (statusCountsText) {
    parts.push(`status counts ${statusCountsText}`);
  }
  if (source.missingMutationResultRiskVisible !== undefined) {
    parts.push(`missing result risk ${String(source.missingMutationResultRiskVisible)}`);
  }
  if (source.staleIndexRiskVisible !== undefined) {
    parts.push(`stale index risk ${String(source.staleIndexRiskVisible)}`);
  }

  return parts.length ? `${label}: ${parts.join(' / ')}` : '';
}

function firstDefined(...values) {
  return values.find((value) => value !== undefined && value !== null);
}

function countsText(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return '';
  }
  return Object.entries(value)
    .map(([key, count]) => `${key}=${String(count)}`)
    .join(', ');
}

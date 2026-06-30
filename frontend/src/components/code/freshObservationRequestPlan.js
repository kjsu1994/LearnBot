export function buildFreshObservationRequestPlanView(requestPlan = []) {
  const items = Array.isArray(requestPlan) ? requestPlan : [];

  return {
    show: items.length > 0,
    headerText: items.length ? 'fresh observation request plan: audit-only / no enqueue / no claim' : '',
    requestLines: items.map(freshObservationRequestPlanText),
  };
}

function freshObservationRequestPlanText(item) {
  let text = `${item.key}: ${item.status || 'PLANNED_DISABLED'}`;
  if (item.toolName) {
    text += ` / ${item.toolName}`;
  }
  if (item.approvalState) {
    text += ` / approval ${item.approvalState}`;
  }
  text += optionalBooleanText('enqueue', item.enqueueEnabled);
  text += optionalBooleanText('claimable', item.claimableAfterEnqueue);
  text += optionalBooleanText('mutation', item.mutationAllowed);
  text += optionalBooleanText('dry-run', item.dryRunOnly);
  if (item.releaseAttemptId) {
    text += ` / attempt ${String(item.releaseAttemptId).slice(0, 8)}`;
  }
  if (item.sourceRequestId) {
    text += ` / source ${String(item.sourceRequestId).slice(0, 8)}`;
  }
  return text;
}

function optionalBooleanText(label, value) {
  return value === undefined ? '' : ` / ${label} ${String(value)}`;
}

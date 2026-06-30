export function buildFreshObservationEnqueueBoundaryView(boundary = null) {
  if (!boundary) {
    return {
      show: false,
      boundaryText: '',
      plannedRequestLines: [],
      message: '',
    };
  }

  const plannedRequests = Array.isArray(boundary.plannedRequests) ? boundary.plannedRequests : [];

  return {
    show: true,
    boundaryText: freshObservationEnqueueBoundaryText(boundary),
    plannedRequestLines: plannedRequests.map(freshObservationPlannedRequestText),
    message: boundary.message || '',
  };
}

function freshObservationEnqueueBoundaryText(boundary) {
  let text = `fresh observation enqueue boundary: ${boundary.status || 'DISABLED'}`;
  text += optionalBooleanText('request creation', boundary.requestCreationEnabled);
  text += optionalBooleanText('push', boundary.pushEnabled);
  text += optionalBooleanText('enqueue', boundary.enqueueEnabled);
  text += optionalBooleanText('claimable', boundary.claimableAfterEnqueue);
  text += optionalBooleanText('mutation', boundary.mutationAllowed);
  return text;
}

function freshObservationPlannedRequestText(item) {
  let text = `boundary planned ${item.key}: ${item.status || 'TEMPLATE_DISABLED'}`;
  if (item.toolName) {
    text += ` / ${item.toolName}`;
  }
  if (item.approvalState) {
    text += ` / approval ${item.approvalState}`;
  }
  text += optionalBooleanText('enqueue', item.enqueueEnabled);
  text += optionalBooleanText('claimable', item.claimableAfterEnqueue);
  if (item.releaseAttemptId) {
    text += ` / attempt ${String(item.releaseAttemptId).slice(0, 8)}`;
  }
  return text;
}

function optionalBooleanText(label, value) {
  return value === undefined ? '' : ` / ${label} ${String(value)}`;
}

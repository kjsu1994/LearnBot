export function buildAgentLoopTimelineHistoryView(timelines = []) {
  const items = Array.isArray(timelines) ? timelines : [];
  if (!items.length) {
    return null;
  }
  return {
    headerText: `recent agent loop timelines: ${items.length}`,
    timelines: items.map(timelineView),
  };
}

function timelineView(timeline = {}) {
  const events = Array.isArray(timeline.events) ? timeline.events : [];
  return {
    id: timeline.id || '',
    headerText: `loop timeline: ${timeline.status || 'UNKNOWN'} / max steps ${timeline.maxSteps ?? 'UNKNOWN'} / timeout ${timeline.timeoutSeconds ?? 'UNKNOWN'}s`,
    stateText: `timeline state: mutation ${String(timeline.mutationEnabled)} / persistence ${String(timeline.timelinePersistenceEnabled)} / cancellation ${String(timeline.cancellationEnabled)}`,
    instructionText: timeline.instruction ? `instruction: ${timeline.instruction}` : '',
    createdText: timeline.createdAt ? `created: ${timeline.createdAt}` : '',
    eventLines: events.map(eventText),
  };
}

function eventText(event = {}) {
  let text = `${event.sequenceNumber ?? '?'} ${event.eventType || 'UNKNOWN'}`.trim();
  if (event.phase) {
    text += ` / ${event.phase}`;
  }
  if (event.executionTarget) {
    text += ` / ${event.executionTarget}`;
  }
  if (event.toolName) {
    text += ` / ${event.toolName}`;
  }
  text += ` / approval ${String(event.requiresApproval)}`;
  text += ` / may mutate ${String(event.mayMutate)}`;
  text += ` / enabled ${String(event.enabled)}`;
  if (event.details?.status) {
    text += ` / status ${event.details.status}`;
  }
  if (event.details?.approvalState) {
    text += ` / approval state ${event.details.approvalState}`;
  }
  if (event.details?.freshObservationOnly !== undefined) {
    text += ` / fresh observation ${String(event.details.freshObservationOnly)}`;
  }
  if (event.details?.dryRun !== undefined) {
    text += ` / dry-run ${String(event.details.dryRun)}`;
  }
  if (event.details?.mutationApplied !== undefined) {
    text += ` / mutation applied ${String(event.details.mutationApplied)}`;
  }
  if (event.details?.timeoutSeconds !== undefined) {
    text += ` / timeout ${event.details.timeoutSeconds}s`;
  }
  if (event.details?.cancellationEnabled !== undefined) {
    text += ` / cancellation ${String(event.details.cancellationEnabled)}`;
  }
  if (event.details?.finalResultEnabled !== undefined) {
    text += ` / final result ${String(event.details.finalResultEnabled)}`;
  }
  if (event.details?.stopKey) {
    text += ` / stop ${event.details.stopKey}`;
  }
  if (event.details?.outcome) {
    text += ` / outcome ${event.details.outcome}`;
  }
  const recommendedAction = event.details?.recommendedAction;
  if (recommendedAction) {
    text += recommendedActionText(recommendedAction);
  }
  const action = event.details?.action;
  if (action) {
    text += ` / action: ${action}`;
  }
  return text;
}

function recommendedActionText(action = {}) {
  const parts = [
    ['recommended action', action.actionKey],
    ['label', action.label],
    ['enabled', action.enabled],
    ['method', action.method],
    ['endpoint', action.endpoint],
    ['request creation', action.requestCreationEnabled],
    ['push', action.pushEnabled],
    ['claim', action.claimEnabled],
    ['mutation', action.mutationEnabled],
    ['reason', action.reason],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? ` / ${parts.join(' / ')}` : '';
}

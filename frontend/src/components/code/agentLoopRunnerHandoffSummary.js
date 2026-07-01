const DISABLED_CONTROL_LABELS = [
  ['runnerAutoEnqueueEnabled', 'runner auto-enqueue'],
  ['freshObservationAutoEnqueueEnabled', 'fresh observation auto-enqueue'],
  ['sourcePatchRequestCreationEnabled', 'source patch request creation'],
  ['sourcePatchPushEnabled', 'source patch push'],
  ['sourcePatchClaimEnabled', 'source patch claim'],
  ['requestCreationEnabled', 'request creation'],
  ['enqueueEnabled', 'enqueue'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['verificationCommandExecutionEnabled', 'verification command execution'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['ragFreshnessUpdateEnabled', 'RAG freshness update'],
  ['finalResultEnabled', 'final result'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer generation'],
  ['deliveryEnabled', 'delivery'],
  ['acknowledgementEnabled', 'acknowledgement'],
  ['mutationEnabled', 'mutation'],
];

export function buildAgentLoopRunnerHandoffSummaryView(response = null, queuedObservation = null) {
  const summary = response?.handoffSummary
    || response?.preview?.handoffSummary
    || response?.nextAction?.handoffSummary
    || null;
  if (!summary && response?.queuedRequest) {
    return buildSelectedReadOnlyQueueView(response, queuedObservation);
  }
  if (!summary) {
    return {
      show: false,
      badgeText: '',
      headerText: '',
      countsText: '',
      disabledText: '',
      nestedPreviewText: '',
      sourceText: '',
      routeText: '',
      observationText: '',
      message: '',
    };
  }

  return {
    show: true,
    badgeText: runnerHandoffBadgeText(response, summary),
    headerText: runnerHandoffHeaderText(response, summary),
    countsText: runnerHandoffCountsText(summary),
    disabledText: `agent loop runner handoff disabled:${disabledControlSuffix(response, summary)}`,
    nestedPreviewText: runnerNestedPreviewText(response),
    sourceText: runnerHandoffSourceText(summary),
    routeText: runnerHandoffRouteText(summary),
    observationText: '',
    message: summary.message || response?.reason || '',
  };
}

function buildSelectedReadOnlyQueueView(response, queuedObservation) {
  const request = response.queuedRequest?.request || {};
  const input = request.input || {};
  const requestId = response.queuedRequest?.requestId;
  const parts = [
    response.actionKey || 'QUEUE_READ_ONLY_OBSERVATION',
    response.runnerDecision || 'ENQUEUED_READ_ONLY_OBSERVATION',
    response.selectedByModel ? 'model selected' : 'fallback selected',
  ];
  return {
    show: true,
    badgeText: 'read-only queued',
    headerText: `agent loop runner selected read-only: ${parts.filter(Boolean).join(' / ')}`,
    countsText: requestId ? `agent loop runner selected read-only request: ${requestId}` : '',
    disabledText: `agent loop runner selected read-only controls:${disabledControlSuffix(response, {})}`,
    nestedPreviewText: `agent loop runner selected read-only tool: ${request.toolName || 'UNKNOWN'} / approval ${request.approvalState || 'UNKNOWN'} / mutation ${String(input.mutationAllowed)} / fresh observation ${String(input.freshObservationOnly)}`,
    sourceText: '',
    routeText: '',
    observationText: runnerQueuedObservationText(queuedObservation),
    message: response.reason || '',
  };
}

function runnerHandoffBadgeText(response, summary) {
  if (
    summary?.status === 'WAIT_FOR_RELEASE_GATE'
    || summary?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS'
    || response?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS'
  ) {
    return 'release gate';
  }
  return 'creation disabled';
}

function runnerQueuedObservationText(queuedObservation) {
  if (!queuedObservation) {
    return '';
  }
  const input = queuedObservation.input || {};
  const output = queuedObservation.output || {};
  const repositoryVerification = output.repositoryVerification || {};
  const parts = [
    `agent loop runner queued observation: ${queuedObservation.status || 'UNKNOWN'}`,
    `tool ${queuedObservation.toolName || 'UNKNOWN'}`,
    `target ${queuedObservation.executionTarget || 'UNKNOWN'}`,
    `approval ${queuedObservation.approvalState || 'UNKNOWN'}`,
    `mutation ${String(input.mutationAllowed)}`,
  ];
  if (input.freshObservationOnly !== undefined) {
    parts.push(`fresh observation ${String(input.freshObservationOnly)}`);
  }
  if (repositoryVerification.status) {
    parts.push(`repository verification ${repositoryVerification.status}`);
  }
  return parts.join(' / ');
}

function runnerHandoffHeaderText(response, summary) {
  let text = `agent loop runner handoff: ${summary.status || response?.actionKey || 'READY_HANDOFF_CREATION_DISABLED'}`;
  if (summary.schema) {
    text += ` / ${summary.schema}`;
  }
  if (response?.runnerDecision) {
    text += ` / runner ${response.runnerDecision}`;
  }
  if (summary.runnerDecision && summary.runnerDecision !== response?.runnerDecision) {
    text += ` / summary runner ${summary.runnerDecision}`;
  }
  if (summary.sourceBoundaryStatus) {
    text += ` / boundary ${summary.sourceBoundaryStatus}`;
  }
  return text;
}

function runnerHandoffCountsText(summary) {
  const parts = [
    ['expected', summary.expectedRequestCount],
    ['durable mutation rows', summary.durableMutationExecutionRowCount],
    ['persisted', summary.persistedRequestCount],
    ['pushed', summary.pushedRequestCount],
    ['claimable', summary.claimableRequestCount],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner handoff counts: ${parts.join(' / ')}` : '';
}

function runnerHandoffSourceText(summary) {
  const parts = [
    ['source request', summary.sourceRequestId],
    ['source event', summary.sourceEventType],
    ['sequence', summary.sourceSequenceNumber],
    ['approval', summary.approvalState],
    ['held', summary.approvalRequestHeld],
    ['release required', summary.releaseRequired],
  ]
    .map(([label, value]) => value === undefined || value === null ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner release handoff source: ${parts.join(' / ')}` : '';
}

function runnerHandoffRouteText(summary) {
  const parts = [
    ['readiness', summary.readinessRoute],
    ['fresh observations', summary.freshObservationsRoute],
    ['release boundary', summary.releaseBoundaryRoute],
  ]
    .map(([label, value]) => value ? `${label} ${value}` : null)
    .filter(Boolean);
  return parts.length ? `agent loop runner release handoff routes: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(response, summary) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => {
      const value = response?.[key] ?? summary?.[key];
      return value === undefined ? null : ` ${label} ${String(value)}`;
    })
    .filter(Boolean)
    .join(' /');
}

function runnerNestedPreviewText(response) {
  const preview = response?.preview;
  if (!preview?.handoffSummary) {
    return '';
  }
  return `agent loop runner nested preview: ${preview.runnerDecision || 'UNKNOWN'} / ${preview.handoffSummary.status || 'UNKNOWN'} / request creation ${String(preview.requestCreationEnabled)} / push ${String(preview.pushEnabled)} / claim ${String(preview.claimEnabled)} / mutation ${String(preview.mutationEnabled)}`;
}

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
      freshObservationText: '',
      readinessText: '',
      boundaryText: '',
      finalResultText: '',
      observationText: '',
      recommendedActionText: '',
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
    freshObservationText: runnerHandoffFreshObservationText(summary),
    readinessText: runnerHandoffReadinessText(summary),
    boundaryText: runnerReleaseBoundaryText(response?.boundary) || runnerApprovedExecutionFlowCompletedText(summary) || runnerReleaseRefusalStopText(summary),
    finalResultText: runnerApprovedExecutionFinalResultHandoffText(summary),
    observationText: '',
    recommendedActionText: runnerRecommendedActionText(response?.recommendedAction || response?.nextAction?.recommendedAction),
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
    freshObservationText: '',
    readinessText: '',
    boundaryText: '',
    finalResultText: '',
    observationText: runnerQueuedObservationText(queuedObservation),
    recommendedActionText: runnerRecommendedActionText(response.recommendedAction),
    message: response.reason || '',
  };
}

function runnerHandoffBadgeText(response, summary) {
  if (
    summary?.schema === 'learnbot.code-agent.approved-execution-flow-completed-handoff.v1'
    || summary?.status === 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED'
    || response?.runnerDecision === 'READY_FINAL_RESULT_DISABLED'
  ) {
    return 'flow complete';
  }
  if (
    summary?.schema === 'learnbot.code-agent.release-boundary-refusal-summary.v1'
    || summary?.status === 'RELEASE_REVIEW_REFUSED_GATE_DISABLED'
    || response?.actionKey === 'STOP_WITH_REASON'
  ) {
    return 'release refused';
  }
  if (response?.runnerDecision === 'RELEASE_REVIEW_REFUSED_GATE_DISABLED') {
    return 'release review';
  }
  if (
    summary?.status === 'RELEASE_READINESS_REFRESHED_RELEASE_GATED'
    || summary?.runnerDecision === 'WAIT_RELEASE_GATE_READINESS_REFRESHED'
    || response?.runnerDecision === 'WAIT_RELEASE_GATE_READINESS_REFRESHED'
  ) {
    return 'readiness';
  }
  if (
    summary?.status === 'FRESH_EVIDENCE_COMPLETE_RELEASE_GATED'
    || summary?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE'
    || response?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE'
  ) {
    return 'fresh evidence';
  }
  if (
    summary?.status === 'WAIT_FOR_RELEASE_GATE'
    || summary?.status === 'WAIT_FOR_FRESH_OBSERVATION_RESULTS'
    || summary?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS'
    || summary?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATION_RESULTS'
    || response?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS'
    || response?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATION_RESULTS'
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
  if (response?.boundary?.status) {
    text += ` / review boundary ${response.boundary.status}`;
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
    ['release attempt', summary.releaseAttemptId],
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

function runnerHandoffFreshObservationText(summary) {
  const parts = [
    ['queued', summary.queuedRequestCount],
    ['required', summary.observationResultsRequired],
    ['evidence complete', summary.evidenceComplete],
    ['required count', summary.requiredCount],
    ['linked', summary.linkedCount],
    ['missing', summary.missingCount],
    ['source-only fallback', summary.sourceOnlyFallbackCount],
    ['blocking', summary.blockingCount],
    ['completeness', summary.freshObservationEvidenceCompleteness],
    ['status', summary.freshObservationEvidenceStatus],
    ['tools', joinSummaryList(summary.queuedToolNames)],
    ['requests', joinSummaryList(summary.queuedRequestIds)],
    ['approvals', joinSummaryList(summary.queuedApprovalStates)],
    ['linked keys', joinSummaryList(summary.linkedKeys)],
    ['blocking keys', joinSummaryList(summary.blockingKeys)],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner release fresh observations: ${parts.join(' / ')}` : '';
}

function runnerHandoffReadinessText(summary) {
  const parts = [
    ['ready to release', summary.readyToRelease],
    ['readiness message', summary.readinessMessage],
    ['warnings', summary.warningCount],
    ['checks', summary.checkCount],
    ['failed checks', joinSummaryList(summary.failedCheckKeys)],
    ['patch release', summary.patchReleaseStatus],
    ['patch release preconditions', summary.patchReleasePreconditionsPassed],
    ['patch execution gate', summary.patchExecutionGateStatus],
    ['patch execution preconditions', summary.patchExecutionPreconditionsPassed],
    ['release attempt ready', summary.releaseAttemptReady],
    ['fresh evidence complete', summary.freshObservationEvidenceComplete],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner release readiness: ${parts.join(' / ')}` : '';
}

function runnerReleaseBoundaryText(boundary) {
  if (!boundary) {
    return '';
  }
  const parts = [
    ['status', boundary.status],
    ['action', boundary.actionMode],
    ['release gate', boundary.releaseGateEnabled],
    ['request creation', boundary.requestCreationEnabled],
    ['push', boundary.pushEnabled],
    ['claim', boundary.claimEnabled],
    ['claimable', boundary.claimable],
    ['write helper', boundary.writeHelperEnabled],
    ['apply', boundary.applyEnabled],
    ['test', boundary.testEnabled],
    ['rollback restore', boundary.rollbackRestoreEnabled],
    ['RAG freshness update', boundary.ragFreshnessUpdateEnabled],
    ['mutation', boundary.mutationAllowed],
    ['blocking', joinSummaryList(boundary.blockingReasons)],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner release review boundary: ${parts.join(' / ')}` : '';
}

function runnerReleaseRefusalStopText(summary) {
  if (summary?.schema !== 'learnbot.code-agent.release-boundary-refusal-summary.v1') {
    return '';
  }
  const parts = [
    ['status', summary.status],
    ['action', summary.actionMode],
    ['boundary', summary.boundaryStatus],
    ['release gate', summary.releaseGateEnabled],
    ['request creation', summary.requestCreationEnabled],
    ['push', summary.pushEnabled],
    ['claim', summary.claimEnabled],
    ['claimable', summary.claimable],
    ['verification command execution', summary.verificationCommandExecutionEnabled],
    ['rollback restore', summary.rollbackRestoreEnabled],
    ['RAG freshness update', summary.ragFreshnessUpdateEnabled],
    ['final result', summary.finalResultEnabled],
    ['publication', summary.publicationEnabled],
    ['final answer generation', summary.finalAnswerGenerationEnabled],
    ['delivery', summary.deliveryEnabled],
    ['acknowledgement', summary.acknowledgementEnabled],
    ['mutation', summary.mutationEnabled],
    ['blocking', joinSummaryList(summary.blockingReasons)],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner release refusal stop: ${parts.join(' / ')}` : '';
}

function runnerApprovedExecutionFlowCompletedText(summary) {
  if (summary?.schema !== 'learnbot.code-agent.approved-execution-flow-completed-handoff.v1') {
    return '';
  }
  const parts = [
    ['status', summary.status],
    ['request id source', summary.requestIdSource],
    ['steps', summary.stepCount],
    ['ordered', summary.ordered],
    ['identity consistent', summary.identityConsistent],
    ['release linked', summary.releaseAttemptLinked],
    ['terminal', summary.allTerminal],
    ['succeeded', summary.allSucceeded],
    ['final report summary', summary.finalMutationReportSummaryStatus],
    ['RAG marker', summary.ragFreshnessMarkerStatus],
    ['publication handoff', summary.finalAnswerPublicationHandoffStatus],
    ['acknowledgement handoff', summary.acknowledgementSaveHandoffStatus],
    ['final result', summary.finalResultEnabled],
    ['publication', summary.publicationEnabled],
    ['RAG freshness update', summary.ragFreshnessUpdateEnabled],
    ['acknowledgement', summary.acknowledgementEnabled],
    ['follow-up mutation', summary.followUpMutationEnabled],
    ['mutation', summary.mutationEnabled],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner approved execution flow complete: ${parts.join(' / ')}` : '';
}

function runnerApprovedExecutionFinalResultHandoffText(summary) {
  if (summary?.schema !== 'learnbot.code-agent.approved-execution-flow-completed-handoff.v1') {
    return '';
  }
  const handoff = summary.finalResultHandoff || {};
  const parts = [
    ['schema', handoff.schema],
    ['status', handoff.status],
    ['final report summary', handoff.finalMutationReportSummaryStatus],
    ['RAG marker', handoff.ragFreshnessMarkerStatus],
    ['publication handoff', handoff.finalAnswerPublicationHandoffStatus],
    ['acknowledgement handoff', handoff.acknowledgementSaveHandoffStatus],
    ['stale disclosure modeled', handoff.staleIndexDisclosureModeled],
    ['delivery', handoff.finalAnswerDeliveryEnabled],
    ['final answer generation', handoff.finalAnswerGenerationEnabled],
    ['publication', handoff.publicationEnabled],
    ['acknowledgement save', handoff.acknowledgementSaveEnabled],
    ['RAG freshness update', handoff.ragFreshnessUpdateEnabled],
    ['partial reindex', handoff.partialReindexEnabled],
    ['follow-up mutation', handoff.followUpMutationEnabled],
    ['mutation', handoff.mutationEnabled],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner final-result handoff: ${parts.join(' / ')}` : '';
}

function joinSummaryList(value) {
  if (!Array.isArray(value) || !value.length) {
    return '';
  }
  return value.filter((item) => item !== undefined && item !== null && item !== '').map(String).join(', ');
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

function runnerRecommendedActionText(action) {
  if (!action) {
    return '';
  }
  const parts = [
    ['action', action.actionKey],
    ['label', action.label],
    ['enabled', action.enabled],
    ['endpoint', action.endpoint],
    ['request creation', action.requestCreationEnabled],
    ['push', action.pushEnabled],
    ['claim', action.claimEnabled],
    ['mutation', action.mutationEnabled],
    ['reason', action.reason],
  ]
    .map(([label, value]) => value === undefined || value === null || value === '' ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `agent loop runner recommended action: ${parts.join(' / ')}` : '';
}

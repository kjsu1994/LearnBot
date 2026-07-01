const DISABLED_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['executionEnabled', 'execution'],
  ['toolRunnerEnabled', 'tool runner'],
  ['writeHelperEnabled', 'write helper'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['resultIntakeEnabled', 'result intake'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryReceiptEnabled', 'receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
];

const CHECK_CONTROL_LABELS = [
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['executionEnabled', 'execution'],
  ['toolRunnerEnabled', 'tool runner'],
  ['writeHelperEnabled', 'write helper'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['resultIntakeEnabled', 'result intake'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
];

export function buildMutationExecutionReadinessBoundaryView(boundary = null) {
  if (!boundary) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      sourceText: '',
      sourceContextText: '',
      disabledText: '',
      checkLines: [],
      blockingText: '',
      message: '',
    };
  }

  const checks = Array.isArray(boundary.readinessChecks) ? boundary.readinessChecks : [];
  const blockingKeys = Array.isArray(boundary.blockingKeys) ? boundary.blockingKeys : [];

  return {
    show: true,
    headerText: mutationExecutionReadinessHeaderText(boundary),
    idsText: mutationExecutionReadinessIdsText(boundary),
    sourceText: mutationExecutionReadinessSourceText(boundary),
    sourceContextText: mutationExecutionReadinessSourceContextText(boundary),
    disabledText: `mutation execution readiness disabled:${disabledControlSuffix(boundary)}`,
    checkLines: checks.map(mutationExecutionReadinessCheckText),
    blockingText: blockingKeys.length ? `mutation execution readiness blocking keys: ${blockingKeys.join(', ')}` : '',
    message: boundary.message || '',
  };
}

function mutationExecutionReadinessHeaderText(boundary) {
  let text = `mutation execution readiness: ${boundary.status || 'BLOCKED_EXECUTION_READINESS_DISABLED'}`;
  if (boundary.schema) {
    text += ` / ${boundary.schema}`;
  }
  if (boundary.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(boundary.prerequisitesPassed)}`;
  }
  if (boundary.executionTarget) {
    text += ` / ${boundary.executionTarget}`;
  }
  if (boundary.expectedRequestCount !== undefined) {
    text += ` / expected ${String(boundary.expectedRequestCount)}`;
  }
  if (boundary.completedRequestCount !== undefined) {
    text += ` / completed ${String(boundary.completedRequestCount)}`;
  }
  return text;
}

function mutationExecutionReadinessIdsText(boundary) {
  let text = 'mutation execution readiness ids:';
  if (boundary.sessionId) {
    text += ` session ${boundary.sessionId}`;
  }
  if (boundary.userId) {
    text += ` / user ${boundary.userId}`;
  }
  if (boundary.agentId) {
    text += ` / agent ${boundary.agentId}`;
  }
  if (boundary.workspaceId) {
    text += ` / workspace ${boundary.workspaceId}`;
  }
  return text;
}

function mutationExecutionReadinessSourceText(boundary) {
  const parts = [
    ['handoff', boundary.sourceHandoffSummaryStatus],
    ['execution gate', boundary.sourceExecutionGateStatus],
    ['write helper', boundary.sourceWriteHelperSafetyGateStatus],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${value}`)
    .filter(Boolean);
  return parts.length ? `mutation execution readiness sources: ${parts.join(' / ')}` : '';
}

function mutationExecutionReadinessSourceContextText(boundary) {
  const parts = [
    ['publication gate', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationGateStatus],
    ['publication schema', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationGateSchema],
    ['publication session', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationGateSessionId],
    ['publication user', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationGateUserId],
    ['publication agent', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationGateAgentId],
    ['publication workspace', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationGateWorkspaceId],
    ['publication', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationBoundaryStatus],
    ['draft', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationBoundaryDraftStatus],
    ['observations', boundary.sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationCount],
    ['accepted', boundary.sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationAcceptedCount],
    ['rejected', boundary.sourceHandoffSummaryDeliveryReceiptGateAcceptedObservationRejectedCount],
    ['missing result risk', boundary.sourceHandoffSummaryDeliveryReceiptGateMissingMutationResultRiskVisible],
    ['stale index risk', boundary.sourceHandoffSummaryDeliveryReceiptGateStaleIndexRiskVisible],
    ['publication observations', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus],
    ['publication count', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationCount],
    ['publication accepted', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount],
    ['publication rejected', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount],
    ['publication missing result risk', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible],
    ['publication stale index risk', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible],
    ['publication latest', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus],
    ['publication tool', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName],
    ['publication verification', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus],
    ['publication rollback summary observations', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus],
    ['publication rollback summary count', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount],
    ['publication rollback summary accepted', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount],
    ['publication rollback summary rejected', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount],
    ['publication rollback summary missing result risk', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['publication rollback summary stale index risk', boundary.sourceHandoffSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${Array.isArray(value) ? value.join(',') : String(value)}`)
    .filter(Boolean);
  return parts.length ? `mutation execution readiness source context: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(boundary) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => boundary[key] === undefined ? null : ` ${label} ${String(boundary[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationExecutionReadinessCheckText(check) {
  let text = `execution readiness ${check.key}: ${check.status || 'UNKNOWN'}`;
  if (check.passed !== undefined) {
    text += ` / passed ${String(check.passed)}`;
  }
  if (check.blocking !== undefined) {
    text += ` / blocking ${String(check.blocking)}`;
  }
  const suffix = CHECK_CONTROL_LABELS
    .map(([key, label]) => check[key] === undefined ? null : ` / ${label} ${String(check[key])}`)
    .filter(Boolean)
    .join('');
  return text + suffix;
}

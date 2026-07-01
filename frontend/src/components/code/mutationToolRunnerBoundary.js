const DISABLED_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['runningTransitionEnabled', 'running transition'],
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
  ['runningTransitionEnabled', 'running transition'],
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

export function buildMutationToolRunnerBoundaryView(boundary = null) {
  if (!boundary) {
    return {
      show: false,
      headerText: '',
      sourceText: '',
      sourceContextText: '',
      disabledText: '',
      checkLines: [],
      blockingText: '',
      message: '',
    };
  }

  const checks = Array.isArray(boundary.runnerChecks) ? boundary.runnerChecks : [];
  const blockingKeys = Array.isArray(boundary.blockingKeys) ? boundary.blockingKeys : [];

  return {
    show: true,
    headerText: mutationToolRunnerHeaderText(boundary),
    sourceText: mutationToolRunnerSourceText(boundary),
    sourceContextText: mutationToolRunnerSourceContextText(boundary),
    disabledText: `mutation tool runner disabled:${disabledControlSuffix(boundary)}`,
    checkLines: checks.map(mutationToolRunnerCheckText),
    blockingText: blockingKeys.length ? `mutation tool runner blocking keys: ${blockingKeys.join(', ')}` : '',
    message: boundary.message || '',
  };
}

function mutationToolRunnerHeaderText(boundary) {
  let text = `mutation tool runner boundary: ${boundary.status || 'BLOCKED_TOOL_RUNNER_DISABLED'}`;
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
  if (boundary.runningRequestCount !== undefined) {
    text += ` / running ${String(boundary.runningRequestCount)}`;
  }
  if (boundary.completedRequestCount !== undefined) {
    text += ` / completed ${String(boundary.completedRequestCount)}`;
  }
  return text;
}

function mutationToolRunnerSourceText(boundary) {
  const parts = [
    ['execution readiness', boundary.sourceExecutionReadinessBoundaryStatus],
    ['execution gate', boundary.sourceExecutionGateStatus],
    ['runner policy', boundary.toolRunnerPolicy],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${value}`)
    .filter(Boolean);
  return parts.length ? `mutation tool runner sources: ${parts.join(' / ')}` : '';
}

function mutationToolRunnerSourceContextText(boundary) {
  const parts = [
    ['publication gate', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateStatus],
    ['publication schema', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSchema],
    ['publication session', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateSessionId],
    ['publication user', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateUserId],
    ['publication agent', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateAgentId],
    ['publication workspace', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationGateWorkspaceId],
    ['publication', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryStatus],
    ['draft', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationBoundaryDraftStatus],
    ['observations', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationCount],
    ['accepted', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationAcceptedCount],
    ['rejected', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGateAcceptedObservationRejectedCount],
    ['missing result risk', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGateMissingMutationResultRiskVisible],
    ['stale index risk', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGateStaleIndexRiskVisible],
    ['publication observations', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus],
    ['publication count', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationCount],
    ['publication accepted', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount],
    ['publication rejected', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount],
    ['publication missing result risk', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible],
    ['publication stale index risk', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationStaleIndexRiskVisible],
    ['publication latest', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus],
    ['publication tool', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName],
    ['publication verification', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus],
    ['publication rollback summary observations', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus],
    ['publication rollback summary count', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount],
    ['publication rollback summary accepted', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount],
    ['publication rollback summary rejected', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount],
    ['publication rollback summary missing result risk', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['publication rollback summary stale index risk', boundary.sourceExecutionReadinessBoundaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${Array.isArray(value) ? value.join(',') : String(value)}`)
    .filter(Boolean);
  return parts.length ? `mutation tool runner source context: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(boundary) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => boundary[key] === undefined ? null : ` ${label} ${String(boundary[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationToolRunnerCheckText(check) {
  let text = `tool runner ${check.key}: ${check.status || 'UNKNOWN'}`;
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

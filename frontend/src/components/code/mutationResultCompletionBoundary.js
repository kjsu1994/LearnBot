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
  ['completedResultTransitionEnabled', 'completed transition'],
  ['completedResultPersistenceEnabled', 'result persistence'],
  ['postExecutionObservationEnabled', 'observation capture'],
  ['resultIntakeEnabled', 'result intake'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryReceiptEnabled', 'receipt'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
];

const CHECK_CONTROL_LABELS = [
  ['toolRunnerEnabled', 'tool runner'],
  ['completedResultTransitionEnabled', 'completed transition'],
  ['completedResultPersistenceEnabled', 'result persistence'],
  ['postExecutionObservationEnabled', 'observation capture'],
  ['resultIntakeEnabled', 'result intake'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
];

export function buildMutationResultCompletionBoundaryView(boundary = null) {
  if (!boundary) {
    return {
      show: false,
      headerText: '',
      sourceText: '',
      disabledText: '',
      checkLines: [],
      blockingText: '',
      message: '',
    };
  }

  const checks = Array.isArray(boundary.resultChecks) ? boundary.resultChecks : [];
  const blockingKeys = Array.isArray(boundary.blockingKeys) ? boundary.blockingKeys : [];

  return {
    show: true,
    headerText: mutationResultCompletionHeaderText(boundary),
    sourceText: mutationResultCompletionSourceText(boundary),
    disabledText: `mutation result completion disabled:${disabledControlSuffix(boundary)}`,
    checkLines: checks.map(mutationResultCompletionCheckText),
    blockingText: blockingKeys.length ? `mutation result completion blocking keys: ${blockingKeys.join(', ')}` : '',
    message: boundary.message || '',
  };
}

function mutationResultCompletionHeaderText(boundary) {
  let text = `mutation result completion boundary: ${boundary.status || 'BLOCKED_RESULT_COMPLETION_DISABLED'}`;
  if (boundary.schema) {
    text += ` / ${boundary.schema}`;
  }
  if (boundary.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(boundary.prerequisitesPassed)}`;
  }
  if (boundary.executionTarget) {
    text += ` / ${boundary.executionTarget}`;
  }
  if (boundary.expectedResultCount !== undefined) {
    text += ` / expected ${String(boundary.expectedResultCount)}`;
  }
  if (boundary.completedResultCount !== undefined) {
    text += ` / completed ${String(boundary.completedResultCount)}`;
  }
  if (boundary.acceptedResultCount !== undefined) {
    text += ` / accepted ${String(boundary.acceptedResultCount)}`;
  }
  if (boundary.rejectedResultCount !== undefined) {
    text += ` / rejected ${String(boundary.rejectedResultCount)}`;
  }
  return text;
}

function mutationResultCompletionSourceText(boundary) {
  const parts = [
    ['tool runner', boundary.sourceToolRunnerBoundaryStatus],
    ['observation', boundary.sourcePostExecutionObservationGateStatus],
    ['completion policy', boundary.completionPolicy],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${value}`)
    .filter(Boolean);
  return parts.length ? `mutation result completion sources: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(boundary) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => boundary[key] === undefined ? null : ` ${label} ${String(boundary[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationResultCompletionCheckText(check) {
  let text = `result completion ${check.key}: ${check.status || 'UNKNOWN'}`;
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

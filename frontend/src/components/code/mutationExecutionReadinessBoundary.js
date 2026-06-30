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
      sourceText: '',
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
    sourceText: mutationExecutionReadinessSourceText(boundary),
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

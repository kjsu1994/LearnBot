const DISABLED_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['writeHelperEnabled', 'write helper'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalAnswerCompletionEnabled', 'completion'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
  ['deliveryReceiptEnabled', 'receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

const ITEM_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalAnswerCompletionEnabled', 'completion'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
  ['deliveryReceiptEnabled', 'receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationCompletionSummaryView(summary = null) {
  if (!summary) {
    return {
      show: false,
      headerText: '',
      disabledText: '',
      itemLines: [],
      blockingText: '',
      message: '',
    };
  }

  const items = Array.isArray(summary.items) ? summary.items : [];
  const blockingKeys = Array.isArray(summary.blockingKeys) ? summary.blockingKeys : [];

  return {
    show: true,
    headerText: mutationCompletionHeaderText(summary),
    disabledText: `mutation completion disabled:${disabledControlSuffix(summary)}`,
    itemLines: items.map(mutationCompletionItemText),
    blockingText: blockingKeys.length ? `mutation completion blocking keys: ${blockingKeys.join(', ')}` : '',
    message: summary.message || '',
  };
}

function mutationCompletionHeaderText(summary) {
  let text = `mutation completion summary: ${summary.status || 'BLOCKED_COMPLETION_DISABLED'}`;
  if (summary.schema) {
    text += ` / ${summary.schema}`;
  }
  if (summary.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(summary.prerequisitesPassed)}`;
  }
  if (summary.executionTarget) {
    text += ` / ${summary.executionTarget}`;
  }
  return text;
}

function disabledControlSuffix(summary) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => summary[key] === undefined ? null : ` ${label} ${String(summary[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationCompletionItemText(item) {
  let text = `${item.key}: ${item.status || 'UNKNOWN'}`;
  if (item.passed !== undefined) {
    text += ` / passed ${String(item.passed)}`;
  }
  if (item.blocking !== undefined) {
    text += ` / blocking ${String(item.blocking)}`;
  }
  const suffix = ITEM_CONTROL_LABELS
    .map(([key, label]) => item[key] === undefined ? null : ` / ${label} ${String(item[key])}`)
    .filter(Boolean)
    .join('');
  if (item.message) {
    return `${text}${suffix} / ${item.message}`;
  }
  return text + suffix;
}

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
      sourceContextText: '',
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
    sourceContextText: mutationCompletionSourceContextText(summary),
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
  if (summary.sourceFinalAnswerDeliveryReceiptGateStatus) {
    text += ` / receipt ${summary.sourceFinalAnswerDeliveryReceiptGateStatus}`;
  }
  if (summary.sourceFinalAnswerDeliveryReceiptGateAcknowledgementSavePolicy) {
    text += ` / acknowledgement ${summary.sourceFinalAnswerDeliveryReceiptGateAcknowledgementSavePolicy}`;
  }
  if (summary.sourceFinalAnswerDeliveryReceiptGateAcknowledgementSaveEnabled !== undefined) {
    text += ` / acknowledgement save ${String(summary.sourceFinalAnswerDeliveryReceiptGateAcknowledgementSaveEnabled)}`;
  }
  return text;
}

function mutationCompletionSourceContextText(summary) {
  const parts = [
    ['publication gate', summary.sourceFinalAnswerDeliveryReceiptGatePublicationGateStatus],
    ['publication schema', summary.sourceFinalAnswerDeliveryReceiptGatePublicationGateSchema],
    ['publication session', summary.sourceFinalAnswerDeliveryReceiptGatePublicationGateSessionId],
    ['publication user', summary.sourceFinalAnswerDeliveryReceiptGatePublicationGateUserId],
    ['publication agent', summary.sourceFinalAnswerDeliveryReceiptGatePublicationGateAgentId],
    ['publication workspace', summary.sourceFinalAnswerDeliveryReceiptGatePublicationGateWorkspaceId],
    ['publication', summary.sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryStatus],
    ['draft', summary.sourceFinalAnswerDeliveryReceiptGatePublicationBoundaryDraftStatus],
    ['observations', summary.sourceFinalAnswerDeliveryReceiptGateAcceptedObservationCount],
    ['accepted', summary.sourceFinalAnswerDeliveryReceiptGateAcceptedObservationAcceptedCount],
    ['rejected', summary.sourceFinalAnswerDeliveryReceiptGateAcceptedObservationRejectedCount],
    ['missing result risk', summary.sourceFinalAnswerDeliveryReceiptGateMissingMutationResultRiskVisible],
    ['stale index risk', summary.sourceFinalAnswerDeliveryReceiptGateStaleIndexRiskVisible],
    ['publication observations', summary.sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus],
    ['publication count', summary.sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationCount],
    ['publication accepted', summary.sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount],
    ['publication rejected', summary.sourceFinalAnswerDeliveryReceiptGatePublicationAcceptedObservationRejectedCount],
    ['publication missing result risk', summary.sourceFinalAnswerDeliveryReceiptGatePublicationMissingMutationResultRiskVisible],
    ['publication stale index risk', summary.sourceFinalAnswerDeliveryReceiptGatePublicationStaleIndexRiskVisible],
    ['publication latest', summary.sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationStatus],
    ['publication tool', summary.sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationToolName],
    ['publication verification', summary.sourceFinalAnswerDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus],
    ['publication rollback summary observations', summary.sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus],
    ['publication rollback summary count', summary.sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount],
    ['publication rollback summary accepted', summary.sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount],
    ['publication rollback summary rejected', summary.sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount],
    ['publication rollback summary missing result risk', summary.sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['publication rollback summary stale index risk', summary.sourceFinalAnswerDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${Array.isArray(value) ? value.join(',') : String(value)}`)
    .filter(Boolean);
  return parts.length ? `mutation completion source context: ${parts.join(' / ')}` : '';
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

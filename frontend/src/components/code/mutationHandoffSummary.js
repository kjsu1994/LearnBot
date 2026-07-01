const DISABLED_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['writeHelperEnabled', 'write helper'],
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
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
];

const STAGE_CONTROL_LABELS = [
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['executionEnabled', 'execution'],
  ['resultIntakeEnabled', 'result intake'],
  ['finalResponseHandoffEnabled', 'final response'],
  ['deliveryReceiptEnabled', 'receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
];

export function buildMutationHandoffSummaryView(summary = null) {
  if (!summary) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      sourceContextText: '',
      disabledText: '',
      stageLines: [],
      blockingText: '',
      message: '',
    };
  }

  const disabledControls = summary.disabledControls || {};
  const stages = Array.isArray(summary.handoffStages) ? summary.handoffStages : [];
  const blockingKeys = Array.isArray(summary.blockingKeys) ? summary.blockingKeys : [];

  return {
    show: true,
    headerText: mutationHandoffHeaderText(summary),
    idsText: mutationHandoffIdsText(summary),
    sourceContextText: mutationHandoffSourceContextText(summary),
    disabledText: `mutation handoff disabled:${disabledControlSuffix(disabledControls)}`,
    stageLines: stages.map(mutationHandoffStageText),
    blockingText: blockingKeys.length ? `mutation handoff blocking keys: ${blockingKeys.join(', ')}` : '',
    message: summary.message || '',
  };
}

function mutationHandoffHeaderText(summary) {
  let text = `mutation handoff summary: ${summary.status || 'BLOCKED_HANDOFF_DISABLED'}`;
  if (summary.schema) {
    text += ` / ${summary.schema}`;
  }
  if (summary.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(summary.prerequisitesPassed)}`;
  }
  if (summary.executionTarget) {
    text += ` / ${summary.executionTarget}`;
  }
  if (summary.sourceCompletionSummaryStatus) {
    text += ` / completion ${summary.sourceCompletionSummaryStatus}`;
  }
  if (summary.sourceCompletionSummaryDeliveryReceiptGateStatus) {
    text += ` / receipt ${summary.sourceCompletionSummaryDeliveryReceiptGateStatus}`;
  }
  if (summary.sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSavePolicy) {
    text += ` / acknowledgement ${summary.sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSavePolicy}`;
  }
  if (summary.sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSaveEnabled !== undefined) {
    text += ` / acknowledgement save ${String(summary.sourceCompletionSummaryDeliveryReceiptGateAcknowledgementSaveEnabled)}`;
  }
  return text;
}

function mutationHandoffIdsText(summary) {
  let text = 'mutation handoff summary ids:';
  if (summary.sessionId) {
    text += ` session ${summary.sessionId}`;
  }
  if (summary.userId) {
    text += ` / user ${summary.userId}`;
  }
  if (summary.agentId) {
    text += ` / agent ${summary.agentId}`;
  }
  if (summary.workspaceId) {
    text += ` / workspace ${summary.workspaceId}`;
  }
  return text;
}

function mutationHandoffSourceContextText(summary) {
  const parts = [
    ['publication gate', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationGateStatus],
    ['publication schema', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationGateSchema],
    ['publication session', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationGateSessionId],
    ['publication user', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationGateUserId],
    ['publication agent', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationGateAgentId],
    ['publication workspace', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationGateWorkspaceId],
    ['publication', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryStatus],
    ['draft', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationBoundaryDraftStatus],
    ['observations', summary.sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationCount],
    ['accepted', summary.sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationAcceptedCount],
    ['rejected', summary.sourceCompletionSummaryDeliveryReceiptGateAcceptedObservationRejectedCount],
    ['missing result risk', summary.sourceCompletionSummaryDeliveryReceiptGateMissingMutationResultRiskVisible],
    ['stale index risk', summary.sourceCompletionSummaryDeliveryReceiptGateStaleIndexRiskVisible],
    ['publication observations', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationSummaryStatus],
    ['publication count', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationCount],
    ['publication accepted', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationAcceptedCount],
    ['publication rejected', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationAcceptedObservationRejectedCount],
    ['publication missing result risk', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationMissingMutationResultRiskVisible],
    ['publication stale index risk', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationStaleIndexRiskVisible],
    ['publication latest', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationStatus],
    ['publication tool', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationToolName],
    ['publication verification', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationLatestAcceptedObservationVerificationStatus],
    ['publication rollback summary observations', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStatus],
    ['publication rollback summary count', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryObservationCount],
    ['publication rollback summary accepted', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryAcceptedCount],
    ['publication rollback summary rejected', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryRejectedCount],
    ['publication rollback summary missing result risk', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['publication rollback summary stale index risk', summary.sourceCompletionSummaryDeliveryReceiptGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${Array.isArray(value) ? value.join(',') : String(value)}`)
    .filter(Boolean);
  return parts.length ? `mutation handoff source context: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(disabledControls) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => disabledControls[key] === undefined ? null : ` ${label} ${String(disabledControls[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationHandoffStageText(stage) {
  let text = `handoff ${stage.key}: ${stage.status || 'UNKNOWN'}`;
  if (stage.sourceGateKey) {
    text += ` / source ${stage.sourceGateKey}`;
  }
  if (stage.passed !== undefined) {
    text += ` / passed ${String(stage.passed)}`;
  }
  const suffix = STAGE_CONTROL_LABELS
    .map(([key, label]) => stage[key] === undefined ? null : ` / ${label} ${String(stage[key])}`)
    .filter(Boolean)
    .join('');
  return text + suffix;
}

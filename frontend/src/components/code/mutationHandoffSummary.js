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
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
];

export function buildMutationHandoffSummaryView(summary = null) {
  if (!summary) {
    return {
      show: false,
      headerText: '',
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
  return text;
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

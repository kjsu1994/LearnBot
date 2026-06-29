const DISABLED_CONTROL_LABELS = [
  ['dispatchDecisionEnabled', 'dispatch decision'],
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
];

export function buildMutationDispatchDecisionModelView(model = null) {
  if (!model) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      disabledText: '',
      inputLines: [],
      blockingText: '',
      refusalText: '',
      message: '',
    };
  }

  const readinessInputs = Array.isArray(model.readinessInputs) ? model.readinessInputs : [];
  const blockingKeys = Array.isArray(model.blockingKeys) ? model.blockingKeys : [];

  return {
    show: true,
    headerText: mutationDispatchDecisionHeaderText(model),
    idsText: mutationDispatchDecisionIdsText(model),
    disabledText: `mutation dispatch decision disabled:${disabledControlSuffix(model)}`,
    inputLines: readinessInputs.map(mutationDispatchDecisionInputText),
    blockingText: blockingKeys.length ? `mutation dispatch decision blocking keys: ${blockingKeys.join(', ')}` : '',
    refusalText: model.userVisibleRefusalMessage ? `dispatch refusal: ${model.userVisibleRefusalMessage}` : '',
    message: model.message || '',
  };
}

function mutationDispatchDecisionHeaderText(model) {
  let text = `mutation dispatch decision model: ${model.status || 'BLOCKED_DISPATCH_DISABLED'}`;
  if (model.schema) {
    text += ` / ${model.schema}`;
  }
  if (model.decision) {
    text += ` / decision ${model.decision}`;
  }
  if (model.readinessInputsPassed !== undefined) {
    text += ` / readiness inputs ${String(model.readinessInputsPassed)}`;
  }
  if (model.executionTarget) {
    text += ` / ${model.executionTarget}`;
  }

  const envelopeStatus = model.dispatchEnvelopeStatus || model.envelopeStatus;
  const preflightStatus = model.dispatchPreflightStatus || model.preflightStatus;
  if (envelopeStatus) {
    text += ` / envelope ${envelopeStatus}`;
  }
  if (preflightStatus) {
    text += ` / preflight ${preflightStatus}`;
  }
  return text;
}

function mutationDispatchDecisionIdsText(model) {
  let text = 'mutation dispatch decision ids:';
  if (model.sourceRequestId) {
    text += ` source ${model.sourceRequestId}`;
  }
  if (model.releaseAttemptId) {
    text += ` / release ${String(model.releaseAttemptId).slice(0, 8)}`;
  }
  if (model.sessionId) {
    text += ` / session ${model.sessionId}`;
  }
  if (model.agentId) {
    text += ` / agent ${model.agentId}`;
  }
  if (model.workspaceId) {
    text += ` / workspace ${model.workspaceId}`;
  }
  return text;
}

function mutationDispatchDecisionInputText(item) {
  let text = `decision input ${item.key}: ${item.status || 'UNKNOWN'}`;
  if (item.passed !== undefined) {
    text += ` / passed ${String(item.passed)}`;
  }
  if (item.blocking !== undefined) {
    text += ` / blocking ${String(item.blocking)}`;
  }
  if (item.releaseGateEnabled !== undefined) {
    text += ` / release gate ${String(item.releaseGateEnabled)}`;
  }
  if (item.dispatchDecisionEnabled !== undefined) {
    text += ` / dispatch decision ${String(item.dispatchDecisionEnabled)}`;
  }
  if (item.requestCreationEnabled !== undefined) {
    text += ` / request creation ${String(item.requestCreationEnabled)}`;
  }
  if (item.pushEnabled !== undefined) {
    text += ` / push ${String(item.pushEnabled)}`;
  }
  if (item.claimable !== undefined) {
    text += ` / claimable ${String(item.claimable)}`;
  }
  if (item.mutationAllowed !== undefined) {
    text += ` / mutation ${String(item.mutationAllowed)}`;
  }
  if (item.message) {
    text += ` / ${item.message}`;
  }
  return text;
}

function disabledControlSuffix(model) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => model[key] === undefined ? null : ` ${label} ${String(model[key])}`)
    .filter(Boolean)
    .join(' /');
}

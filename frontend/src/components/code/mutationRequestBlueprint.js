const DISABLED_CONTROL_LABELS = [
  ['requestBlueprintEnabled', 'request blueprint'],
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

export function buildMutationRequestBlueprintView(blueprint = null) {
  if (!blueprint) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      disabledText: '',
      expectedInputsText: '',
      expectedOutputsText: '',
      toolLines: [],
      approvalLines: [],
      blockingText: '',
      message: '',
    };
  }

  const expectedInputKeys = Array.isArray(blueprint.expectedInputKeys) ? blueprint.expectedInputKeys : [];
  const expectedOutputKeys = Array.isArray(blueprint.expectedOutputKeys) ? blueprint.expectedOutputKeys : [];
  const orderedToolRequests = Array.isArray(blueprint.orderedToolRequests) ? blueprint.orderedToolRequests : [];
  const approvalStates = Array.isArray(blueprint.approvalStates) ? blueprint.approvalStates : [];
  const blockingKeys = Array.isArray(blueprint.blockingKeys) ? blueprint.blockingKeys : [];

  return {
    show: true,
    headerText: mutationRequestBlueprintHeaderText(blueprint),
    idsText: mutationRequestBlueprintIdsText(blueprint),
    disabledText: `mutation request blueprint disabled:${disabledControlSuffix(blueprint)}`,
    expectedInputsText: expectedInputKeys.length ? `mutation request expected inputs: ${expectedInputKeys.join(', ')}` : '',
    expectedOutputsText: expectedOutputKeys.length ? `mutation request expected outputs: ${expectedOutputKeys.join(', ')}` : '',
    toolLines: orderedToolRequests.map(mutationRequestBlueprintToolText),
    approvalLines: approvalStates.map(mutationRequestBlueprintApprovalText),
    blockingText: blockingKeys.length ? `mutation request blueprint blocking keys: ${blockingKeys.join(', ')}` : '',
    message: blueprint.message || '',
  };
}

function mutationRequestBlueprintHeaderText(blueprint) {
  let text = `mutation request blueprint: ${blueprint.status || 'BLOCKED_REQUEST_BLUEPRINT_DISABLED'}`;
  if (blueprint.schema) {
    text += ` / ${blueprint.schema}`;
  }
  if (blueprint.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(blueprint.prerequisitesPassed)}`;
  }
  if (blueprint.executionTarget) {
    text += ` / ${blueprint.executionTarget}`;
  }
  if (blueprint.requestCreationMode) {
    text += ` / ${blueprint.requestCreationMode}`;
  }
  if (blueprint.sourceDecisionStatus) {
    text += ` / decision ${blueprint.sourceDecisionStatus}`;
  }
  if (blueprint.sourceEnvelopeStatus) {
    text += ` / envelope ${blueprint.sourceEnvelopeStatus}`;
  }
  return text;
}

function mutationRequestBlueprintIdsText(blueprint) {
  let text = 'mutation request blueprint ids:';
  if (blueprint.sourceRequestId) {
    text += ` source ${blueprint.sourceRequestId}`;
  }
  if (blueprint.releaseAttemptId) {
    text += ` / release ${String(blueprint.releaseAttemptId).slice(0, 8)}`;
  }
  if (blueprint.sessionId) {
    text += ` / session ${blueprint.sessionId}`;
  }
  if (blueprint.agentId) {
    text += ` / agent ${blueprint.agentId}`;
  }
  if (blueprint.workspaceId) {
    text += ` / workspace ${blueprint.workspaceId}`;
  }
  return text;
}

function mutationRequestBlueprintToolText(item) {
  let text = `${item.order !== undefined ? `${item.order}. ` : ''}${item.key}: ${item.toolName || 'tool pending'}`;
  if (item.status) {
    text += ` / ${item.status}`;
  }
  if (item.approvalState) {
    text += ` / approval ${item.approvalState}`;
  }
  if (item.sideEffectful !== undefined) {
    text += ` / side-effect ${String(item.sideEffectful)}`;
  }
  if (item.rollbackFallback !== undefined) {
    text += ` / rollback fallback ${String(item.rollbackFallback)}`;
  }
  if (item.requestCreationEnabled !== undefined) {
    text += ` / request creation ${String(item.requestCreationEnabled)}`;
  }
  if (item.pushEnabled !== undefined) {
    text += ` / push ${String(item.pushEnabled)}`;
  }
  if (item.claimEnabled !== undefined) {
    text += ` / claim ${String(item.claimEnabled)}`;
  }
  if (item.claimable !== undefined) {
    text += ` / claimable ${String(item.claimable)}`;
  }
  if (item.mutationAllowed !== undefined) {
    text += ` / mutation ${String(item.mutationAllowed)}`;
  }
  if (item.expectedOutputKeys?.length) {
    text += ` / outputs ${item.expectedOutputKeys.join(', ')}`;
  }
  return text;
}

function mutationRequestBlueprintApprovalText(item) {
  let text = `blueprint approval ${item.key}: ${item.approvalState || 'UNKNOWN'}`;
  if (item.toolName) {
    text += ` / ${item.toolName}`;
  }
  return text;
}

function disabledControlSuffix(blueprint) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => blueprint[key] === undefined ? null : ` ${label} ${String(blueprint[key])}`)
    .filter(Boolean)
    .join(' /');
}

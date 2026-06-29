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
];

export function buildMutationDispatchEnvelopeContractView(contract = null) {
  if (!contract) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      disabledText: '',
      expectedOutcomesText: '',
      toolLines: [],
      approvalLines: [],
      rollbackText: '',
      ragFreshnessText: '',
      blockingText: '',
      message: '',
    };
  }

  const orderedToolSequence = Array.isArray(contract.orderedToolSequence) ? contract.orderedToolSequence : [];
  const requiredApprovals = Array.isArray(contract.requiredApprovals) ? contract.requiredApprovals : [];
  const expectedOutcomeKeys = Array.isArray(contract.expectedOutcomeKeys) ? contract.expectedOutcomeKeys : [];
  const blockingKeys = Array.isArray(contract.blockingKeys) ? contract.blockingKeys : [];

  return {
    show: true,
    headerText: mutationDispatchHeaderText(contract),
    idsText: mutationDispatchIdsText(contract),
    disabledText: `mutation dispatch disabled:${disabledControlSuffix(contract)}`,
    expectedOutcomesText: expectedOutcomeKeys.length ? `mutation dispatch expected outcomes: ${expectedOutcomeKeys.join(', ')}` : '',
    toolLines: orderedToolSequence.map(mutationDispatchToolText),
    approvalLines: requiredApprovals.map(mutationDispatchApprovalText),
    rollbackText: contract.rollbackObligation ? rollbackObligationText(contract.rollbackObligation) : '',
    ragFreshnessText: contract.ragFreshnessObligation ? ragFreshnessObligationText(contract.ragFreshnessObligation) : '',
    blockingText: blockingKeys.length ? `mutation dispatch blocking keys: ${blockingKeys.join(', ')}` : '',
    message: contract.message || '',
  };
}

function mutationDispatchHeaderText(contract) {
  let text = `mutation dispatch envelope contract: ${contract.status || 'BLOCKED_DISPATCH_DISABLED'}`;
  if (contract.schema) {
    text += ` / ${contract.schema}`;
  }
  if (contract.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(contract.prerequisitesPassed)}`;
  }
  if (contract.executionTarget) {
    text += ` / ${contract.executionTarget}`;
  }
  if (contract.dispatchMode) {
    text += ` / ${contract.dispatchMode}`;
  }
  return text;
}

function mutationDispatchIdsText(contract) {
  let text = 'mutation dispatch ids:';
  if (contract.sourceRequestId) {
    text += ` source ${contract.sourceRequestId}`;
  }
  if (contract.releaseAttemptId) {
    text += ` / release ${contract.releaseAttemptId}`;
  }
  if (contract.sessionId) {
    text += ` / session ${contract.sessionId}`;
  }
  if (contract.agentId) {
    text += ` / agent ${contract.agentId}`;
  }
  if (contract.workspaceId) {
    text += ` / workspace ${contract.workspaceId}`;
  }
  return text;
}

function disabledControlSuffix(contract) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => contract[key] === undefined ? null : ` ${label} ${String(contract[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationDispatchToolText(item) {
  let text = `${item.order !== undefined ? `${item.order}. ` : ''}${item.key}: ${item.toolName || 'tool pending'}`;
  if (item.approvalState) {
    text += ` / approval ${item.approvalState}`;
  }
  if (item.sideEffectful !== undefined) {
    text += ` / side-effect ${String(item.sideEffectful)}`;
  }
  if (item.rollbackFallback !== undefined) {
    text += ` / rollback fallback ${String(item.rollbackFallback)}`;
  }
  return text;
}

function mutationDispatchApprovalText(item) {
  let text = `approval ${item.key}: ${item.approvalState || 'UNKNOWN'}`;
  if (item.toolName) {
    text += ` / ${item.toolName}`;
  }
  if (item.sideEffectful !== undefined) {
    text += ` / side-effect ${String(item.sideEffectful)}`;
  }
  return text;
}

function rollbackObligationText(obligation) {
  let text = 'rollback obligation:';
  if (obligation.status) {
    text += ` ${obligation.status}`;
  }
  if (obligation.toolName) {
    text += ` / ${obligation.toolName}`;
  }
  if (obligation.required !== undefined) {
    text += ` / required ${String(obligation.required)}`;
  }
  if (obligation.rollbackRestoreEnabled !== undefined) {
    text += ` / rollback restore ${String(obligation.rollbackRestoreEnabled)}`;
  }
  return text;
}

function ragFreshnessObligationText(obligation) {
  let text = 'RAG freshness obligation:';
  if (obligation.status) {
    text += ` ${obligation.status}`;
  }
  if (obligation.required !== undefined) {
    text += ` / required ${String(obligation.required)}`;
  }
  if (obligation.ragFreshnessUpdateEnabled !== undefined) {
    text += ` / rag freshness ${String(obligation.ragFreshnessUpdateEnabled)}`;
  }
  if (obligation.message) {
    text += ` / ${obligation.message}`;
  }
  return text;
}

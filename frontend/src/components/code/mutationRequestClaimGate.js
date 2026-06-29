const DISABLED_CONTROL_LABELS = [
  ['claimGateEnabled', 'claim gate'],
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

export function buildMutationRequestClaimGateView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      countsText: '',
      disabledText: '',
      policyLines: [],
      blockingText: '',
      message: '',
    };
  }

  const policyChecks = Array.isArray(gate.policyChecks) ? gate.policyChecks : [];
  const blockingKeys = Array.isArray(gate.blockingKeys) ? gate.blockingKeys : [];

  return {
    show: true,
    headerText: mutationRequestClaimHeaderText(gate),
    idsText: mutationRequestClaimIdsText(gate),
    countsText: mutationRequestClaimCountsText(gate),
    disabledText: `mutation request claim disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(mutationRequestClaimPolicyText),
    blockingText: blockingKeys.length ? `mutation request claim blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function mutationRequestClaimHeaderText(gate) {
  let text = `mutation request claim gate: ${gate.status || 'BLOCKED_CLAIM_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.pushGateReady !== undefined) {
    text += ` / push gate ready ${String(gate.pushGateReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.claimPolicy) {
    text += ` / policy ${gate.claimPolicy}`;
  }
  if (gate.claimNextInvocationEnabled !== undefined) {
    text += ` / claimNext ${String(gate.claimNextInvocationEnabled)}`;
  }
  if (gate.sourcePushGateStatus) {
    text += ` / push status ${gate.sourcePushGateStatus}`;
  }
  return text;
}

function mutationRequestClaimIdsText(gate) {
  let text = 'mutation request claim gate ids:';
  if (gate.sourceRequestId) {
    text += ` source ${gate.sourceRequestId}`;
  }
  if (gate.releaseAttemptId) {
    text += ` / release ${String(gate.releaseAttemptId).slice(0, 8)}`;
  }
  if (gate.sessionId) {
    text += ` / session ${gate.sessionId}`;
  }
  if (gate.agentId) {
    text += ` / agent ${gate.agentId}`;
  }
  if (gate.workspaceId) {
    text += ` / workspace ${gate.workspaceId}`;
  }
  return text;
}

function mutationRequestClaimCountsText(gate) {
  let text = 'mutation request claim counts:';
  if (gate.expectedRequestCount !== undefined) {
    text += ` expected ${String(gate.expectedRequestCount)}`;
  }
  if (gate.persistedRequestCount !== undefined) {
    text += ` / persisted ${String(gate.persistedRequestCount)}`;
  }
  if (gate.pushedRequestCount !== undefined) {
    text += ` / pushed ${String(gate.pushedRequestCount)}`;
  }
  if (gate.claimableRequestCount !== undefined) {
    text += ` / claimable ${String(gate.claimableRequestCount)}`;
  }
  if (gate.runningRequestCount !== undefined) {
    text += ` / running ${String(gate.runningRequestCount)}`;
  }
  return text;
}

function mutationRequestClaimPolicyText(item) {
  let text = `claim policy ${item.key}: ${item.status || 'UNKNOWN'}`;
  if (item.passed !== undefined) {
    text += ` / passed ${String(item.passed)}`;
  }
  if (item.blocking !== undefined) {
    text += ` / blocking ${String(item.blocking)}`;
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
  if (item.running !== undefined) {
    text += ` / running ${String(item.running)}`;
  }
  if (item.mutationAllowed !== undefined) {
    text += ` / mutation ${String(item.mutationAllowed)}`;
  }
  if (item.message) {
    text += ` / ${item.message}`;
  }
  return text;
}

function disabledControlSuffix(gate) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => gate[key] === undefined ? null : ` ${label} ${String(gate[key])}`)
    .filter(Boolean)
    .join(' /');
}

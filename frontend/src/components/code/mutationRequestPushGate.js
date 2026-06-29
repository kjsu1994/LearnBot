const DISABLED_CONTROL_LABELS = [
  ['pushGateEnabled', 'push gate'],
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

export function buildMutationRequestPushGateView(gate = null) {
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
    headerText: mutationRequestPushHeaderText(gate),
    idsText: mutationRequestPushIdsText(gate),
    countsText: mutationRequestPushCountsText(gate),
    disabledText: `mutation request push disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(mutationRequestPushPolicyText),
    blockingText: blockingKeys.length ? `mutation request push blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function mutationRequestPushHeaderText(gate) {
  let text = `mutation request push gate: ${gate.status || 'BLOCKED_PUSH_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.creationGateReady !== undefined) {
    text += ` / creation gate ready ${String(gate.creationGateReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.transportPushPolicy) {
    text += ` / transport ${gate.transportPushPolicy}`;
  }
  if (gate.pusherInvocationEnabled !== undefined) {
    text += ` / pusher ${String(gate.pusherInvocationEnabled)}`;
  }
  if (gate.sourceCreationGateStatus) {
    text += ` / creation status ${gate.sourceCreationGateStatus}`;
  }
  return text;
}

function mutationRequestPushIdsText(gate) {
  let text = 'mutation request push gate ids:';
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

function mutationRequestPushCountsText(gate) {
  let text = 'mutation request push counts:';
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
  return text;
}

function mutationRequestPushPolicyText(item) {
  let text = `push policy ${item.key}: ${item.status || 'UNKNOWN'}`;
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

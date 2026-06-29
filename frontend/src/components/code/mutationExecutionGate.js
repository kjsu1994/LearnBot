const DISABLED_CONTROL_LABELS = [
  ['executionGateEnabled', 'execution gate'],
  ['executionEnabled', 'execution'],
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

const POLICY_CONTROL_LABELS = [
  ['passed', 'passed'],
  ['blocking', 'blocking'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['executionEnabled', 'execution'],
  ['writeHelperEnabled', 'write helper'],
  ['claimable', 'claimable'],
  ['running', 'running'],
  ['completed', 'completed'],
  ['mutationAllowed', 'mutation'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

export function buildMutationExecutionGateView(gate = null) {
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
    headerText: mutationExecutionHeaderText(gate),
    idsText: mutationExecutionIdsText(gate),
    countsText: mutationExecutionCountsText(gate),
    disabledText: `mutation execution disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(mutationExecutionPolicyText),
    blockingText: blockingKeys.length ? `mutation execution blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function mutationExecutionHeaderText(gate) {
  let text = `mutation execution gate: ${gate.status || 'BLOCKED_EXECUTION_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.claimGateReady !== undefined) {
    text += ` / claim gate ready ${String(gate.claimGateReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.executionPolicy) {
    text += ` / policy ${gate.executionPolicy}`;
  }
  if (gate.toolRunnerInvocationEnabled !== undefined) {
    text += ` / tool runner ${String(gate.toolRunnerInvocationEnabled)}`;
  }
  if (gate.writeHelperInvocationEnabled !== undefined) {
    text += ` / write helper invocation ${String(gate.writeHelperInvocationEnabled)}`;
  }
  if (gate.sourceClaimGateStatus) {
    text += ` / claim status ${gate.sourceClaimGateStatus}`;
  }
  return text;
}

function mutationExecutionIdsText(gate) {
  let text = 'mutation execution gate ids:';
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

function mutationExecutionCountsText(gate) {
  let text = 'mutation execution counts:';
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
  if (gate.completedRequestCount !== undefined) {
    text += ` / completed ${String(gate.completedRequestCount)}`;
  }
  return text;
}

function mutationExecutionPolicyText(item) {
  let text = `execution policy ${item.key}: ${item.status || 'UNKNOWN'}`;
  text += controlSuffix(item, POLICY_CONTROL_LABELS, ' /');
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

function controlSuffix(source, labels, separator = ' /') {
  return labels
    .map(([key, label]) => source[key] === undefined ? null : `${separator} ${label} ${String(source[key])}`)
    .filter(Boolean)
    .join('');
}

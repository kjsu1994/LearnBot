const DISABLED_CONTROL_LABELS = [
  ['writeHelperEnabled', 'write helper'],
  ['applyEnabled', 'apply'],
  ['mutationAllowed', 'mutation'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['executionEnabled', 'execution'],
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['claimable', 'claimable'],
  ['testEnabled', 'test'],
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
  ['mutationAllowed', 'mutation'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
];

export function buildMutationWriteHelperSafetyGateView(gate = null) {
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
    headerText: writeHelperSafetyHeaderText(gate),
    idsText: writeHelperSafetyIdsText(gate),
    countsText: writeHelperSafetyCountsText(gate),
    disabledText: `mutation write-helper safety disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(writeHelperSafetyPolicyText),
    blockingText: blockingKeys.length ? `mutation write-helper safety blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function writeHelperSafetyHeaderText(gate) {
  let text = `mutation write-helper safety gate: ${gate.status || 'BLOCKED_WRITE_HELPER_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.executionGateReady !== undefined) {
    text += ` / execution gate ready ${String(gate.executionGateReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.writeHelperPolicy) {
    text += ` / policy ${gate.writeHelperPolicy}`;
  }
  if (gate.sourceExecutionGateStatus) {
    text += ` / execution status ${gate.sourceExecutionGateStatus}`;
  }
  return text;
}

function writeHelperSafetyIdsText(gate) {
  let text = 'mutation write-helper safety ids:';
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

function writeHelperSafetyCountsText(gate) {
  let text = 'mutation write-helper safety counts:';
  if (gate.expectedRequestCount !== undefined) {
    text += ` expected ${String(gate.expectedRequestCount)}`;
  }
  return text;
}

function writeHelperSafetyPolicyText(item) {
  let text = `write-helper safety policy ${item.key}: ${item.status || 'UNKNOWN'}`;
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

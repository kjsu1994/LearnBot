const DISABLED_CONTROL_LABELS = [
  ['postExecutionObservationEnabled', 'observation'],
  ['completedResultPersistenceEnabled', 'result persistence'],
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['releaseGateEnabled', 'release gate'],
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
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

export function buildMutationPostExecutionObservationGateView(gate = null) {
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
    headerText: postExecutionObservationHeaderText(gate),
    idsText: postExecutionObservationIdsText(gate),
    countsText: postExecutionObservationCountsText(gate),
    disabledText: `mutation post-execution observation disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(postExecutionObservationPolicyText),
    blockingText: blockingKeys.length ? `mutation post-execution observation blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function postExecutionObservationHeaderText(gate) {
  let text = `mutation post-execution observation gate: ${gate.status || 'BLOCKED_POST_EXECUTION_OBSERVATION_DISABLED'}`;
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
  if (gate.observationPolicy) {
    text += ` / policy ${gate.observationPolicy}`;
  }
  if (gate.sourceExecutionGateStatus) {
    text += ` / execution status ${gate.sourceExecutionGateStatus}`;
  }
  return text;
}

function postExecutionObservationIdsText(gate) {
  let text = 'mutation post-execution observation ids:';
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

function postExecutionObservationCountsText(gate) {
  let text = 'mutation post-execution observation counts:';
  if (gate.expectedResultCount !== undefined) {
    text += ` expected ${String(gate.expectedResultCount)}`;
  }
  if (gate.completedResultCount !== undefined) {
    text += ` / completed ${String(gate.completedResultCount)}`;
  }
  if (gate.acceptedResultCount !== undefined) {
    text += ` / accepted ${String(gate.acceptedResultCount)}`;
  }
  if (gate.rejectedResultCount !== undefined) {
    text += ` / rejected ${String(gate.rejectedResultCount)}`;
  }
  return text;
}

function postExecutionObservationPolicyText(item) {
  let text = `post-execution observation policy ${item.key}: ${item.status || 'UNKNOWN'}`;
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

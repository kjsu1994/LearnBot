const DISABLED_CONTROL_LABELS = [
  ['observationAcceptanceEnabled', 'acceptance'],
  ['intakePersistenceEnabled', 'intake persistence'],
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['postExecutionObservationEnabled', 'post-execution observation'],
  ['completedResultPersistenceEnabled', 'result persistence'],
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
  ['observationAcceptanceEnabled', 'acceptance'],
  ['intakePersistenceEnabled', 'intake persistence'],
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

export function buildMutationObservationAcceptanceGateView(gate = null) {
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
    headerText: observationAcceptanceHeaderText(gate),
    idsText: observationAcceptanceIdsText(gate),
    countsText: observationAcceptanceCountsText(gate),
    disabledText: `mutation observation acceptance disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(observationAcceptancePolicyText),
    blockingText: blockingKeys.length ? `mutation observation acceptance blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function observationAcceptanceHeaderText(gate) {
  let text = `mutation observation acceptance gate: ${gate.status || 'BLOCKED_OBSERVATION_ACCEPTANCE_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.postExecutionObservationReady !== undefined) {
    text += ` / post-execution observation ready ${String(gate.postExecutionObservationReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.acceptancePolicy) {
    text += ` / policy ${gate.acceptancePolicy}`;
  }
  if (gate.sourcePostExecutionObservationGateStatus) {
    text += ` / observation status ${gate.sourcePostExecutionObservationGateStatus}`;
  }
  return text;
}

function observationAcceptanceIdsText(gate) {
  let text = 'mutation observation acceptance ids:';
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

function observationAcceptanceCountsText(gate) {
  let text = 'mutation observation acceptance counts:';
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
  if (gate.intakePersistedResultCount !== undefined) {
    text += ` / intake persisted ${String(gate.intakePersistedResultCount)}`;
  }
  return text;
}

function observationAcceptancePolicyText(item) {
  let text = `observation acceptance policy ${item.key}: ${item.status || 'UNKNOWN'}`;
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

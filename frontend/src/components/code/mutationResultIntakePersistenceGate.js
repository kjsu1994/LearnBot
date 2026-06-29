const DISABLED_CONTROL_LABELS = [
  ['intakePersistenceEnabled', 'intake persistence'],
  ['acceptedObservationPersistenceEnabled', 'accepted observation persistence'],
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['postExecutionObservationEnabled', 'post-execution observation'],
  ['completedResultPersistenceEnabled', 'result persistence'],
  ['observationAcceptanceEnabled', 'acceptance'],
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
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['executionEnabled', 'execution'],
  ['writeHelperEnabled', 'write helper'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
  ['intakePersistenceEnabled', 'intake persistence'],
  ['acceptedObservationPersistenceEnabled', 'accepted observation persistence'],
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

export function buildMutationResultIntakePersistenceGateView(gate = null) {
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
    headerText: mutationResultIntakePersistenceHeaderText(gate),
    idsText: mutationResultIntakePersistenceIdsText(gate),
    countsText: mutationResultIntakePersistenceCountsText(gate),
    disabledText: `mutation result intake persistence disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(mutationResultIntakePersistencePolicyText),
    blockingText: blockingKeys.length ? `mutation result intake persistence blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function mutationResultIntakePersistenceHeaderText(gate) {
  let text = `mutation result intake persistence gate: ${gate.status || 'BLOCKED_INTAKE_PERSISTENCE_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.observationAcceptanceReady !== undefined) {
    text += ` / observation acceptance ready ${String(gate.observationAcceptanceReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.intakePersistencePolicy) {
    text += ` / policy ${gate.intakePersistencePolicy}`;
  }
  if (gate.sourceObservationAcceptanceGateStatus) {
    text += ` / acceptance status ${gate.sourceObservationAcceptanceGateStatus}`;
  }
  return text;
}

function mutationResultIntakePersistenceIdsText(gate) {
  let text = 'mutation result intake persistence ids:';
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

function mutationResultIntakePersistenceCountsText(gate) {
  let text = 'mutation result intake persistence counts:';
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

function disabledControlSuffix(gate) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => gate[key] === undefined ? null : ` ${label} ${String(gate[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationResultIntakePersistencePolicyText(policy) {
  let text = `result intake persistence policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
  if (policy.passed !== undefined) {
    text += ` / passed ${String(policy.passed)}`;
  }
  if (policy.blocking !== undefined) {
    text += ` / blocking ${String(policy.blocking)}`;
  }
  const suffix = POLICY_CONTROL_LABELS
    .map(([key, label]) => policy[key] === undefined ? null : ` / ${label} ${String(policy[key])}`)
    .filter(Boolean)
    .join('');
  if (policy.message) {
    return `${text}${suffix} / ${policy.message}`;
  }
  return text + suffix;
}

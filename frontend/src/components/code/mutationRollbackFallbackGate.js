const DISABLED_CONTROL_LABELS = [
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['rollbackFallbackInvocationEnabled', 'rollback invocation'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['intakePersistenceEnabled', 'intake persistence'],
  ['acceptedObservationPersistenceEnabled', 'accepted observation persistence'],
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
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationRollbackFallbackGateView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      countsText: '',
      sourceContextText: '',
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
    headerText: rollbackFallbackHeaderText(gate),
    idsText: rollbackFallbackIdsText(gate),
    countsText: rollbackFallbackCountsText(gate),
    sourceContextText: rollbackFallbackSourceContextText(gate),
    disabledText: `mutation rollback fallback disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(rollbackFallbackPolicyText),
    blockingText: blockingKeys.length ? `mutation rollback fallback blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function rollbackFallbackHeaderText(gate) {
  let text = `mutation rollback fallback gate: ${gate.status || 'BLOCKED_ROLLBACK_FALLBACK_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.intakePersistenceReady !== undefined) {
    text += ` / intake persistence ready ${String(gate.intakePersistenceReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.rollbackFallbackPolicy) {
    text += ` / policy ${gate.rollbackFallbackPolicy}`;
  }
  if (gate.sourceResultIntakePersistenceGateStatus) {
    text += ` / intake status ${gate.sourceResultIntakePersistenceGateStatus}`;
  }
  return text;
}

function rollbackFallbackIdsText(gate) {
  let text = 'mutation rollback fallback ids:';
  if (gate.sourceRequestId) {
    text += ` source ${gate.sourceRequestId}`;
  }
  if (gate.releaseAttemptId) {
    text += ` / release ${String(gate.releaseAttemptId).slice(0, 8)}`;
  }
  if (gate.sessionId) {
    text += ` / session ${gate.sessionId}`;
  }
  if (gate.userId) {
    text += ` / user ${gate.userId}`;
  }
  if (gate.agentId) {
    text += ` / agent ${gate.agentId}`;
  }
  if (gate.workspaceId) {
    text += ` / workspace ${gate.workspaceId}`;
  }
  return text;
}

function rollbackFallbackCountsText(gate) {
  let text = 'mutation rollback fallback counts:';
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

function rollbackFallbackSourceContextText(gate) {
  const parts = [
    ['publication gate', gate.sourceResultIntakePersistenceGatePublicationGateStatus],
    ['publication schema', gate.sourceResultIntakePersistenceGatePublicationGateSchema],
    ['publication session', gate.sourceResultIntakePersistenceGatePublicationGateSessionId],
    ['publication user', gate.sourceResultIntakePersistenceGatePublicationGateUserId],
    ['publication agent', gate.sourceResultIntakePersistenceGatePublicationGateAgentId],
    ['publication workspace', gate.sourceResultIntakePersistenceGatePublicationGateWorkspaceId],
    ['intake audit', gate.sourceResultIntakePersistenceGateAcceptedObservationAuditStatus],
    ['latest', gate.sourceResultIntakePersistenceGateLatestAcceptedObservationStatus],
    ['accepted', gate.sourceResultIntakePersistenceGateLatestAcceptedObservationAccepted],
    ['rejected', gate.sourceResultIntakePersistenceGateLatestAcceptedObservationRejected],
    ['terminal failure accepted', gate.sourceResultIntakePersistenceGateLatestAcceptedObservationTerminalFailureAccepted],
    ['tool', gate.sourceResultIntakePersistenceGateLatestAcceptedObservationToolName],
    ['verification', gate.sourceResultIntakePersistenceGateLatestAcceptedObservationVerificationStatus],
    ['summary observations', gate.sourceResultIntakePersistenceGateAcceptedObservationSummaryStatus],
    ['summary count', gate.sourceResultIntakePersistenceGateAcceptedObservationSummaryObservationCount],
    ['summary accepted', gate.sourceResultIntakePersistenceGateAcceptedObservationSummaryAcceptedCount],
    ['summary rejected', gate.sourceResultIntakePersistenceGateAcceptedObservationSummaryRejectedCount],
    ['summary missing result risk', gate.sourceResultIntakePersistenceGateAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['summary stale index risk', gate.sourceResultIntakePersistenceGateAcceptedObservationSummaryStaleIndexRiskVisible],
    ['rollback summary observations', gate.sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStatus],
    ['rollback summary count', gate.sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryObservationCount],
    ['rollback summary accepted', gate.sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryAcceptedCount],
    ['rollback summary rejected', gate.sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryRejectedCount],
    ['rollback summary missing result risk', gate.sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['rollback summary stale index risk', gate.sourceResultIntakePersistenceGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `mutation rollback fallback source context: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(gate) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => gate[key] === undefined ? null : ` ${label} ${String(gate[key])}`)
    .filter(Boolean)
    .join(' /');
}

function rollbackFallbackPolicyText(policy) {
  let text = `rollback fallback policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

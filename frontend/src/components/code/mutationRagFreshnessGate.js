import { buildAcceptedMutationObservationSummaryText } from './mutationObservationSummary.js';

const DISABLED_CONTROL_LABELS = [
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['ragFreshnessUpdateInvocationEnabled', 'freshness invocation'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['rollbackFallbackExecutionEnabled', 'rollback fallback'],
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
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationRagFreshnessGateView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      countsText: '',
      observationSummaryText: '',
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
    headerText: ragFreshnessHeaderText(gate),
    idsText: ragFreshnessIdsText(gate),
    countsText: ragFreshnessCountsText(gate),
    observationSummaryText: buildAcceptedMutationObservationSummaryText(gate, 'mutation RAG freshness accepted observations'),
    sourceContextText: ragFreshnessSourceContextText(gate),
    disabledText: `mutation RAG freshness disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(ragFreshnessPolicyText),
    blockingText: blockingKeys.length ? `mutation RAG freshness blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function ragFreshnessHeaderText(gate) {
  let text = `mutation RAG freshness gate: ${gate.status || 'BLOCKED_RAG_FRESHNESS_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.rollbackFallbackReady !== undefined) {
    text += ` / rollback fallback ready ${String(gate.rollbackFallbackReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.ragFreshnessPolicy) {
    text += ` / policy ${gate.ragFreshnessPolicy}`;
  }
  if (gate.sourceRollbackFallbackGateStatus) {
    text += ` / rollback fallback status ${gate.sourceRollbackFallbackGateStatus}`;
  }
  return text;
}

function ragFreshnessIdsText(gate) {
  let text = 'mutation RAG freshness ids:';
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

function ragFreshnessCountsText(gate) {
  let text = 'mutation RAG freshness counts:';
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

function ragFreshnessSourceContextText(gate) {
  const parts = [
    ['publication gate', gate.sourceRollbackFallbackGatePublicationGateStatus],
    ['publication schema', gate.sourceRollbackFallbackGatePublicationGateSchema],
    ['publication session', gate.sourceRollbackFallbackGatePublicationGateSessionId],
    ['publication user', gate.sourceRollbackFallbackGatePublicationGateUserId],
    ['publication agent', gate.sourceRollbackFallbackGatePublicationGateAgentId],
    ['publication workspace', gate.sourceRollbackFallbackGatePublicationGateWorkspaceId],
    ['rollback latest', gate.sourceRollbackFallbackGateLatestAcceptedObservationStatus],
    ['accepted', gate.sourceRollbackFallbackGateLatestAcceptedObservationAccepted],
    ['rejected', gate.sourceRollbackFallbackGateLatestAcceptedObservationRejected],
    ['terminal failure accepted', gate.sourceRollbackFallbackGateLatestAcceptedObservationTerminalFailureAccepted],
    ['tool', gate.sourceRollbackFallbackGateLatestAcceptedObservationToolName],
    ['verification', gate.sourceRollbackFallbackGateLatestAcceptedObservationVerificationStatus],
    ['missing result risk', gate.missingMutationResultRiskVisible],
    ['stale index risk', gate.staleIndexRiskVisible],
    ['summary observations', gate.sourceRollbackFallbackGateAcceptedObservationSummaryStatus],
    ['summary count', gate.sourceRollbackFallbackGateAcceptedObservationSummaryObservationCount],
    ['summary accepted', gate.sourceRollbackFallbackGateAcceptedObservationSummaryAcceptedCount],
    ['summary rejected', gate.sourceRollbackFallbackGateAcceptedObservationSummaryRejectedCount],
    ['summary missing result risk', gate.sourceRollbackFallbackGateAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['summary stale index risk', gate.sourceRollbackFallbackGateAcceptedObservationSummaryStaleIndexRiskVisible],
    ['rollback summary observations', gate.sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStatus],
    ['rollback summary count', gate.sourceRollbackFallbackGateRollbackAcceptedObservationSummaryObservationCount],
    ['rollback summary accepted', gate.sourceRollbackFallbackGateRollbackAcceptedObservationSummaryAcceptedCount],
    ['rollback summary rejected', gate.sourceRollbackFallbackGateRollbackAcceptedObservationSummaryRejectedCount],
    ['rollback summary missing result risk', gate.sourceRollbackFallbackGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible],
    ['rollback summary stale index risk', gate.sourceRollbackFallbackGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible],
  ]
    .map(([label, value]) => value === undefined ? null : `${label} ${String(value)}`)
    .filter(Boolean);
  return parts.length ? `mutation RAG freshness source context: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(gate) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => gate[key] === undefined ? null : ` ${label} ${String(gate[key])}`)
    .filter(Boolean)
    .join(' /');
}

function ragFreshnessPolicyText(policy) {
  let text = `RAG freshness policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

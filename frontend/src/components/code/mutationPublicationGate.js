const DISABLED_CONTROL_LABELS = [
  ['publicationEnabled', 'publication'],
  ['publicationInvocationEnabled', 'publication invocation'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalAnswerCompletionEnabled', 'final-answer completion'],
  ['finalAnswerDeliveryEnabled', 'final-answer delivery'],
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
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
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalAnswerCompletionEnabled', 'final-answer completion'],
  ['finalAnswerDeliveryEnabled', 'final-answer delivery'],
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationPublicationGateView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      countsText: '',
      disabledText: '',
      sourceContextText: '',
      policyLines: [],
      blockingText: '',
      message: '',
    };
  }

  const policyChecks = Array.isArray(gate.policyChecks) ? gate.policyChecks : [];
  const blockingKeys = Array.isArray(gate.blockingKeys) ? gate.blockingKeys : [];

  return {
    show: true,
    headerText: publicationHeaderText(gate),
    idsText: publicationIdsText(gate),
    countsText: publicationCountsText(gate),
    disabledText: `mutation publication disabled:${disabledControlSuffix(gate)}`,
    sourceContextText: publicationSourceContextText(gate),
    policyLines: policyChecks.map(publicationPolicyText),
    blockingText: blockingKeys.length ? `mutation publication blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function publicationHeaderText(gate) {
  let text = `mutation publication gate: ${gate.status || 'BLOCKED_PUBLICATION_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.resultAggregationReady !== undefined) {
    text += ` / result aggregation ready ${String(gate.resultAggregationReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.publicationPolicy) {
    text += ` / policy ${gate.publicationPolicy}`;
  }
  if (gate.sourceResultAggregationGateStatus) {
    text += ` / result aggregation status ${gate.sourceResultAggregationGateStatus}`;
  }
  if (gate.sourceResultAggregationGateSchema) {
    text += ` / ${gate.sourceResultAggregationGateSchema}`;
  }
  return text;
}

function publicationIdsText(gate) {
  let text = 'mutation publication ids:';
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

function publicationCountsText(gate) {
  let text = 'mutation publication counts:';
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

function publicationSourceContextText(gate) {
  if (!gate.sourceResultAggregationGatePublicationGateStatus
    && !gate.sourceResultAggregationGateAcceptedObservationSummaryStatus
    && !gate.sourceResultAggregationGateLatestAcceptedObservationStatus) {
    return '';
  }
  let text = 'mutation publication source context:';
  if (gate.sourceResultAggregationGatePublicationGateStatus) {
    text += ` publication gate ${gate.sourceResultAggregationGatePublicationGateStatus}`;
  }
  if (gate.sourceResultAggregationGatePublicationGateSchema) {
    text += ` / publication schema ${gate.sourceResultAggregationGatePublicationGateSchema}`;
  }
  if (gate.sourceResultAggregationGatePublicationGateSessionId) {
    text += ` / publication session ${gate.sourceResultAggregationGatePublicationGateSessionId}`;
  }
  if (gate.sourceResultAggregationGatePublicationGateUserId) {
    text += ` / publication user ${gate.sourceResultAggregationGatePublicationGateUserId}`;
  }
  if (gate.sourceResultAggregationGatePublicationGateAgentId) {
    text += ` / publication agent ${gate.sourceResultAggregationGatePublicationGateAgentId}`;
  }
  if (gate.sourceResultAggregationGatePublicationGateWorkspaceId) {
    text += ` / publication workspace ${gate.sourceResultAggregationGatePublicationGateWorkspaceId}`;
  }
  if (gate.sourceResultAggregationGateAcceptedObservationSummaryStatus) {
    text += `${gate.sourceResultAggregationGatePublicationGateStatus ? ' /' : ''} observations ${gate.sourceResultAggregationGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceResultAggregationGateAcceptedObservationCount !== undefined) {
    text += ` / count ${String(gate.sourceResultAggregationGateAcceptedObservationCount)}`;
  }
  if (gate.sourceResultAggregationGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceResultAggregationGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceResultAggregationGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceResultAggregationGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceResultAggregationGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceResultAggregationGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceResultAggregationGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceResultAggregationGateStaleIndexRiskVisible)}`;
  }
  if (gate.sourceResultAggregationGateLatestAcceptedObservationStatus) {
    text += ` / latest ${gate.sourceResultAggregationGateLatestAcceptedObservationStatus}`;
  }
  if (gate.sourceResultAggregationGateLatestAcceptedObservationToolName) {
    text += ` / tool ${gate.sourceResultAggregationGateLatestAcceptedObservationToolName}`;
  }
  if (gate.sourceResultAggregationGateLatestAcceptedObservationVerificationStatus) {
    text += ` / verification ${gate.sourceResultAggregationGateLatestAcceptedObservationVerificationStatus}`;
  }
  if (gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryStatus) {
    text += ` / rollback summary observations ${gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    text += ` / rollback summary count ${String(gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryObservationCount)}`;
  }
  if (gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    text += ` / rollback summary accepted ${String(gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryAcceptedCount)}`;
  }
  if (gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    text += ` / rollback summary rejected ${String(gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryRejectedCount)}`;
  }
  if (gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / rollback summary missing result risk ${String(gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    text += ` / rollback summary stale index risk ${String(gate.sourceResultAggregationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function disabledControlSuffix(gate) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => gate[key] === undefined ? null : ` ${label} ${String(gate[key])}`)
    .filter(Boolean)
    .join(' /');
}

function publicationPolicyText(policy) {
  let text = `publication policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

const DISABLED_CONTROL_LABELS = [
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['finalAnswerCompletionEnabled', 'completion'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['publicationEnabled', 'publication'],
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
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalAnswerCompletionEnabled', 'completion'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationFinalResponseHandoffGateView(gate = null) {
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
    headerText: finalResponseHandoffHeaderText(gate),
    idsText: finalResponseHandoffIdsText(gate),
    countsText: finalResponseHandoffCountsText(gate),
    sourceContextText: finalResponseHandoffSourceContextText(gate),
    disabledText: `mutation final-response handoff disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(finalResponseHandoffPolicyText),
    blockingText: blockingKeys.length ? `mutation final-response handoff blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function finalResponseHandoffSourceContextText(gate) {
  const hasSourceContext = gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryStatus
    || gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftStatus
    || gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationSummaryStatus
    || gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateStatus
    || gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateSchema
    || gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateSessionId
    || gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateUserId
    || gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateAgentId
    || gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateWorkspaceId;
  if (!hasSourceContext) {
    return '';
  }
  let text = 'mutation final-response handoff source context:';
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateStatus) {
    text += ` publication gate ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateSchema) {
    text += ` / publication schema ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateSchema}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateSessionId) {
    text += ` / publication session ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateSessionId}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateUserId) {
    text += ` / publication user ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateUserId}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateAgentId) {
    text += ` / publication agent ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateAgentId}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateWorkspaceId) {
    text += ` / publication workspace ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationGateWorkspaceId}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryStatus) {
    text += ` / publication boundary ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / publication prerequisites ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftStatus}`;
  }
  const draftSections = Array.isArray(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftSections)
    ? gate.sourceFinalAnswerUserVisibleCompletionGatePublicationBoundaryDraftSections
    : [];
  if (draftSections.length) {
    text += ` / sections ${draftSections.join(', ')}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationCount !== undefined) {
    text += ` / observed ${String(gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalAnswerUserVisibleCompletionGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalAnswerUserVisibleCompletionGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalAnswerUserVisibleCompletionGateStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationSummaryStatus) {
    text += ` / publication observations ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationCount !== undefined) {
    text += ` / publication count ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationAcceptedCount !== undefined) {
    text += ` / publication accepted ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationRejectedCount !== undefined) {
    text += ` / publication rejected ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication missing result risk ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationStaleIndexRiskVisible !== undefined) {
    text += ` / publication stale index risk ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationStatus) {
    text += ` / publication latest ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationToolName) {
    text += ` / publication tool ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationToolName}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationVerificationStatus) {
    text += ` / publication verification ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationLatestAcceptedObservationVerificationStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStatus) {
    text += ` / publication rollback summary observations ${gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    text += ` / publication rollback summary count ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    text += ` / publication rollback summary accepted ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    text += ` / publication rollback summary rejected ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication rollback summary missing result risk ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    text += ` / publication rollback summary stale index risk ${String(gate.sourceFinalAnswerUserVisibleCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function finalResponseHandoffHeaderText(gate) {
  let text = `mutation final-response handoff gate: ${gate.status || 'BLOCKED_FINAL_RESPONSE_HANDOFF_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.userVisibleCompletionReady !== undefined) {
    text += ` / user-visible completion ready ${String(gate.userVisibleCompletionReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.finalResponseHandoffPolicy) {
    text += ` / policy ${gate.finalResponseHandoffPolicy}`;
  }
  if (gate.deliveryHandoffEnabled !== undefined) {
    text += ` / delivery handoff ${String(gate.deliveryHandoffEnabled)}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateStatus) {
    text += ` / final-answer user-visible completion status ${gate.sourceFinalAnswerUserVisibleCompletionGateStatus}`;
  }
  if (gate.sourceFinalAnswerUserVisibleCompletionGateSchema) {
    text += ` / ${gate.sourceFinalAnswerUserVisibleCompletionGateSchema}`;
  }
  return text;
}

function finalResponseHandoffIdsText(gate) {
  let text = 'mutation final-response handoff ids:';
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

function finalResponseHandoffCountsText(gate) {
  let text = 'mutation final-response handoff counts:';
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

function finalResponseHandoffPolicyText(policy) {
  let text = `final-response handoff policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

const DISABLED_CONTROL_LABELS = [
  ['finalAnswerDeliveryEnabled', 'delivery'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
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
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
];

export function buildMutationFinalAnswerDeliveryGateView(gate = null) {
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
    headerText: finalAnswerDeliveryHeaderText(gate),
    idsText: finalAnswerDeliveryIdsText(gate),
    countsText: finalAnswerDeliveryCountsText(gate),
    sourceContextText: finalAnswerDeliverySourceContextText(gate),
    disabledText: `mutation final-answer delivery disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(finalAnswerDeliveryPolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer delivery blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function finalAnswerDeliverySourceContextText(gate) {
  const hasSourceContext = gate.sourceFinalResponseHandoffGatePublicationBoundaryStatus
    || gate.sourceFinalResponseHandoffGatePublicationBoundaryDraftStatus
    || gate.sourceFinalResponseHandoffGateAcceptedObservationSummaryStatus
    || gate.sourceFinalResponseHandoffGatePublicationGateStatus
    || gate.sourceFinalResponseHandoffGatePublicationGateSchema
    || gate.sourceFinalResponseHandoffGatePublicationGateSessionId
    || gate.sourceFinalResponseHandoffGatePublicationGateUserId
    || gate.sourceFinalResponseHandoffGatePublicationGateAgentId
    || gate.sourceFinalResponseHandoffGatePublicationGateWorkspaceId;
  if (!hasSourceContext) {
    return '';
  }
  let text = 'mutation final-answer delivery source context:';
  if (gate.sourceFinalResponseHandoffGatePublicationGateStatus) {
    text += ` publication gate ${gate.sourceFinalResponseHandoffGatePublicationGateStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationGateSchema) {
    text += ` / publication schema ${gate.sourceFinalResponseHandoffGatePublicationGateSchema}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationGateSessionId) {
    text += ` / publication session ${gate.sourceFinalResponseHandoffGatePublicationGateSessionId}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationGateUserId) {
    text += ` / publication user ${gate.sourceFinalResponseHandoffGatePublicationGateUserId}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationGateAgentId) {
    text += ` / publication agent ${gate.sourceFinalResponseHandoffGatePublicationGateAgentId}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationGateWorkspaceId) {
    text += ` / publication workspace ${gate.sourceFinalResponseHandoffGatePublicationGateWorkspaceId}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationBoundaryStatus) {
    text += ` / publication boundary ${gate.sourceFinalResponseHandoffGatePublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / publication prerequisites ${String(gate.sourceFinalResponseHandoffGatePublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalResponseHandoffGatePublicationBoundaryDraftStatus}`;
  }
  const draftSections = Array.isArray(gate.sourceFinalResponseHandoffGatePublicationBoundaryDraftSections)
    ? gate.sourceFinalResponseHandoffGatePublicationBoundaryDraftSections
    : [];
  if (draftSections.length) {
    text += ` / sections ${draftSections.join(', ')}`;
  }
  if (gate.sourceFinalResponseHandoffGateAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalResponseHandoffGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGateAcceptedObservationCount !== undefined) {
    text += ` / observed ${String(gate.sourceFinalResponseHandoffGateAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalResponseHandoffGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalResponseHandoffGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalResponseHandoffGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalResponseHandoffGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalResponseHandoffGateStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationSummaryStatus) {
    text += ` / publication observations ${gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationCount !== undefined) {
    text += ` / publication count ${String(gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationAcceptedCount !== undefined) {
    text += ` / publication accepted ${String(gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationRejectedCount !== undefined) {
    text += ` / publication rejected ${String(gate.sourceFinalResponseHandoffGatePublicationAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication missing result risk ${String(gate.sourceFinalResponseHandoffGatePublicationMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationStaleIndexRiskVisible !== undefined) {
    text += ` / publication stale index risk ${String(gate.sourceFinalResponseHandoffGatePublicationStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationStatus) {
    text += ` / publication latest ${gate.sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationToolName) {
    text += ` / publication tool ${gate.sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationToolName}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationVerificationStatus) {
    text += ` / publication verification ${gate.sourceFinalResponseHandoffGatePublicationLatestAcceptedObservationVerificationStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStatus) {
    text += ` / publication rollback summary observations ${gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    text += ` / publication rollback summary count ${String(gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryObservationCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    text += ` / publication rollback summary accepted ${String(gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryAcceptedCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    text += ` / publication rollback summary rejected ${String(gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryRejectedCount)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication rollback summary missing result risk ${String(gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    text += ` / publication rollback summary stale index risk ${String(gate.sourceFinalResponseHandoffGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function finalAnswerDeliveryHeaderText(gate) {
  let text = `mutation final-answer delivery gate: ${gate.status || 'BLOCKED_FINAL_ANSWER_DELIVERY_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.finalResponseHandoffReady !== undefined) {
    text += ` / final response handoff ready ${String(gate.finalResponseHandoffReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.finalAnswerDeliveryPolicy) {
    text += ` / policy ${gate.finalAnswerDeliveryPolicy}`;
  }
  if (gate.deliveryHandoffEnabled !== undefined) {
    text += ` / delivery handoff ${String(gate.deliveryHandoffEnabled)}`;
  }
  if (gate.sourceFinalResponseHandoffGateStatus) {
    text += ` / final-response handoff status ${gate.sourceFinalResponseHandoffGateStatus}`;
  }
  if (gate.sourceFinalResponseHandoffGateSchema) {
    text += ` / ${gate.sourceFinalResponseHandoffGateSchema}`;
  }
  return text;
}

function finalAnswerDeliveryIdsText(gate) {
  let text = 'mutation final-answer delivery ids:';
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

function finalAnswerDeliveryCountsText(gate) {
  let text = 'mutation final-answer delivery counts:';
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

function finalAnswerDeliveryPolicyText(policy) {
  let text = `final-answer delivery policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

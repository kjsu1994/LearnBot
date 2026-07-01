const DISABLED_CONTROL_LABELS = [
  ['deliveryReceiptEnabled', 'receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
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
  ['deliveryReceiptEnabled', 'receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
];

export function buildMutationFinalAnswerDeliveryReceiptGateView(gate = null) {
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
    headerText: deliveryReceiptHeaderText(gate),
    idsText: deliveryReceiptIdsText(gate),
    countsText: deliveryReceiptCountsText(gate),
    sourceContextText: deliveryReceiptSourceContextText(gate),
    disabledText: `mutation final-answer delivery receipt disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(deliveryReceiptPolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer delivery receipt blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function deliveryReceiptSourceContextText(gate) {
  const hasSourceContext = gate.sourceFinalAnswerDeliveryGatePublicationBoundaryStatus
    || gate.sourceFinalAnswerDeliveryGatePublicationBoundaryDraftStatus
    || gate.sourceFinalAnswerDeliveryGateAcceptedObservationSummaryStatus
    || gate.sourceFinalAnswerDeliveryGatePublicationGateStatus
    || gate.sourceFinalAnswerDeliveryGatePublicationGateSchema
    || gate.sourceFinalAnswerDeliveryGatePublicationGateSessionId
    || gate.sourceFinalAnswerDeliveryGatePublicationGateUserId
    || gate.sourceFinalAnswerDeliveryGatePublicationGateAgentId
    || gate.sourceFinalAnswerDeliveryGatePublicationGateWorkspaceId;
  if (!hasSourceContext) {
    return '';
  }
  let text = 'mutation final-answer delivery receipt source context:';
  if (gate.sourceFinalAnswerDeliveryGatePublicationGateStatus) {
    text += ` publication gate ${gate.sourceFinalAnswerDeliveryGatePublicationGateStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationGateSchema) {
    text += ` / publication schema ${gate.sourceFinalAnswerDeliveryGatePublicationGateSchema}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationGateSessionId) {
    text += ` / publication session ${gate.sourceFinalAnswerDeliveryGatePublicationGateSessionId}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationGateUserId) {
    text += ` / publication user ${gate.sourceFinalAnswerDeliveryGatePublicationGateUserId}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationGateAgentId) {
    text += ` / publication agent ${gate.sourceFinalAnswerDeliveryGatePublicationGateAgentId}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationGateWorkspaceId) {
    text += ` / publication workspace ${gate.sourceFinalAnswerDeliveryGatePublicationGateWorkspaceId}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationBoundaryStatus) {
    text += ` / publication boundary ${gate.sourceFinalAnswerDeliveryGatePublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / publication prerequisites ${String(gate.sourceFinalAnswerDeliveryGatePublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalAnswerDeliveryGatePublicationBoundaryDraftStatus}`;
  }
  const draftSections = Array.isArray(gate.sourceFinalAnswerDeliveryGatePublicationBoundaryDraftSections)
    ? gate.sourceFinalAnswerDeliveryGatePublicationBoundaryDraftSections
    : [];
  if (draftSections.length) {
    text += ` / sections ${draftSections.join(', ')}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalAnswerDeliveryGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateAcceptedObservationCount !== undefined) {
    text += ` / observed ${String(gate.sourceFinalAnswerDeliveryGateAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalAnswerDeliveryGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalAnswerDeliveryGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalAnswerDeliveryGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalAnswerDeliveryGateStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationSummaryStatus) {
    text += ` / publication observations ${gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationCount !== undefined) {
    text += ` / publication count ${String(gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationAcceptedCount !== undefined) {
    text += ` / publication accepted ${String(gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationRejectedCount !== undefined) {
    text += ` / publication rejected ${String(gate.sourceFinalAnswerDeliveryGatePublicationAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication missing result risk ${String(gate.sourceFinalAnswerDeliveryGatePublicationMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationStaleIndexRiskVisible !== undefined) {
    text += ` / publication stale index risk ${String(gate.sourceFinalAnswerDeliveryGatePublicationStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationStatus) {
    text += ` / publication latest ${gate.sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationToolName) {
    text += ` / publication tool ${gate.sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationToolName}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationVerificationStatus) {
    text += ` / publication verification ${gate.sourceFinalAnswerDeliveryGatePublicationLatestAcceptedObservationVerificationStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStatus) {
    text += ` / publication rollback summary observations ${gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    text += ` / publication rollback summary count ${String(gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryObservationCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    text += ` / publication rollback summary accepted ${String(gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    text += ` / publication rollback summary rejected ${String(gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication rollback summary missing result risk ${String(gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    text += ` / publication rollback summary stale index risk ${String(gate.sourceFinalAnswerDeliveryGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function deliveryReceiptHeaderText(gate) {
  let text = `mutation final-answer delivery receipt gate: ${gate.status || 'BLOCKED_FINAL_ANSWER_DELIVERY_RECEIPT_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.finalAnswerDeliveryReady !== undefined) {
    text += ` / final-answer delivery ready ${String(gate.finalAnswerDeliveryReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.deliveryReceiptPolicy) {
    text += ` / policy ${gate.deliveryReceiptPolicy}`;
  }
  if (gate.acknowledgementSavePolicy) {
    text += ` / acknowledgement ${gate.acknowledgementSavePolicy}`;
  }
  if (gate.acknowledgementSaveReady !== undefined) {
    text += ` / acknowledgement ready ${String(gate.acknowledgementSaveReady)}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateStatus) {
    text += ` / final-answer delivery status ${gate.sourceFinalAnswerDeliveryGateStatus}`;
  }
  if (gate.sourceFinalAnswerDeliveryGateSchema) {
    text += ` / ${gate.sourceFinalAnswerDeliveryGateSchema}`;
  }
  return text;
}

function deliveryReceiptIdsText(gate) {
  let text = 'mutation final-answer delivery receipt ids:';
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

function deliveryReceiptCountsText(gate) {
  let text = 'mutation final-answer delivery receipt counts:';
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

function deliveryReceiptPolicyText(policy) {
  let text = `final-answer delivery receipt policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

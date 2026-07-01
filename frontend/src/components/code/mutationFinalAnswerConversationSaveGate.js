const DISABLED_CONTROL_LABELS = [
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['conversationTurnSaveInvocationEnabled', 'conversation save invocation'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
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
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationFinalAnswerConversationSaveGateView(gate = null) {
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
    headerText: conversationSaveHeaderText(gate),
    idsText: conversationSaveIdsText(gate),
    countsText: conversationSaveCountsText(gate),
    sourceContextText: conversationSaveSourceContextText(gate),
    disabledText: `mutation final-answer conversation-save disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(conversationSavePolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer conversation-save blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function conversationSaveSourceContextText(gate) {
  const hasSourceContext = gate.sourceFinalAnswerPersistenceGatePublicationBoundaryStatus
    || gate.sourceFinalAnswerPersistenceGatePublicationBoundaryDraftStatus
    || gate.sourceFinalAnswerPersistenceGateAcceptedObservationSummaryStatus
    || gate.sourceFinalAnswerPersistenceGatePublicationGateStatus
    || gate.sourceFinalAnswerPersistenceGatePublicationGateSchema
    || gate.sourceFinalAnswerPersistenceGatePublicationGateSessionId
    || gate.sourceFinalAnswerPersistenceGatePublicationGateUserId
    || gate.sourceFinalAnswerPersistenceGatePublicationGateAgentId
    || gate.sourceFinalAnswerPersistenceGatePublicationGateWorkspaceId;
  if (!hasSourceContext) {
    return '';
  }
  let text = 'mutation final-answer conversation-save source context:';
  if (gate.sourceFinalAnswerPersistenceGatePublicationGateStatus) {
    text += ` publication gate ${gate.sourceFinalAnswerPersistenceGatePublicationGateStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationGateSchema) {
    text += ` / publication schema ${gate.sourceFinalAnswerPersistenceGatePublicationGateSchema}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationGateSessionId) {
    text += ` / publication session ${gate.sourceFinalAnswerPersistenceGatePublicationGateSessionId}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationGateUserId) {
    text += ` / publication user ${gate.sourceFinalAnswerPersistenceGatePublicationGateUserId}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationGateAgentId) {
    text += ` / publication agent ${gate.sourceFinalAnswerPersistenceGatePublicationGateAgentId}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationGateWorkspaceId) {
    text += ` / publication workspace ${gate.sourceFinalAnswerPersistenceGatePublicationGateWorkspaceId}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationBoundaryStatus) {
    text += ` / publication boundary ${gate.sourceFinalAnswerPersistenceGatePublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / publication prerequisites ${String(gate.sourceFinalAnswerPersistenceGatePublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalAnswerPersistenceGatePublicationBoundaryDraftStatus}`;
  }
  const draftSections = Array.isArray(gate.sourceFinalAnswerPersistenceGatePublicationBoundaryDraftSections)
    ? gate.sourceFinalAnswerPersistenceGatePublicationBoundaryDraftSections
    : [];
  if (draftSections.length) {
    text += ` / sections ${draftSections.join(', ')}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalAnswerPersistenceGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateAcceptedObservationCount !== undefined) {
    text += ` / observed ${String(gate.sourceFinalAnswerPersistenceGateAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalAnswerPersistenceGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalAnswerPersistenceGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalAnswerPersistenceGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalAnswerPersistenceGateStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationSummaryStatus) {
    text += ` / publication observations ${gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationCount !== undefined) {
    text += ` / publication count ${String(gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationAcceptedCount !== undefined) {
    text += ` / publication accepted ${String(gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationRejectedCount !== undefined) {
    text += ` / publication rejected ${String(gate.sourceFinalAnswerPersistenceGatePublicationAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication missing result risk ${String(gate.sourceFinalAnswerPersistenceGatePublicationMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationStaleIndexRiskVisible !== undefined) {
    text += ` / publication stale index risk ${String(gate.sourceFinalAnswerPersistenceGatePublicationStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationStatus) {
    text += ` / publication latest ${gate.sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationToolName) {
    text += ` / publication tool ${gate.sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationToolName}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationVerificationStatus) {
    text += ` / publication verification ${gate.sourceFinalAnswerPersistenceGatePublicationLatestAcceptedObservationVerificationStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStatus) {
    text += ` / publication rollback summary observations ${gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    text += ` / publication rollback summary count ${String(gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryObservationCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    text += ` / publication rollback summary accepted ${String(gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    text += ` / publication rollback summary rejected ${String(gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication rollback summary missing result risk ${String(gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    text += ` / publication rollback summary stale index risk ${String(gate.sourceFinalAnswerPersistenceGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function conversationSaveHeaderText(gate) {
  let text = `mutation final-answer conversation-save gate: ${gate.status || 'BLOCKED_FINAL_ANSWER_CONVERSATION_SAVE_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.finalAnswerPersistenceReady !== undefined) {
    text += ` / final answer persistence ready ${String(gate.finalAnswerPersistenceReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.finalAnswerConversationSavePolicy) {
    text += ` / policy ${gate.finalAnswerConversationSavePolicy}`;
  }
  if (gate.userVisibleCompletionEnabled !== undefined) {
    text += ` / user-visible completion ${String(gate.userVisibleCompletionEnabled)}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateStatus) {
    text += ` / final answer persistence status ${gate.sourceFinalAnswerPersistenceGateStatus}`;
  }
  if (gate.sourceFinalAnswerPersistenceGateSchema) {
    text += ` / ${gate.sourceFinalAnswerPersistenceGateSchema}`;
  }
  return text;
}

function conversationSaveIdsText(gate) {
  let text = 'mutation final-answer conversation-save ids:';
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

function conversationSaveCountsText(gate) {
  let text = 'mutation final-answer conversation-save counts:';
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

function conversationSavePolicyText(policy) {
  let text = `final-answer conversation-save policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

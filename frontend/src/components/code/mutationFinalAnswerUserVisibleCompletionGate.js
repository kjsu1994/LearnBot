const DISABLED_CONTROL_LABELS = [
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['conversationTurnSaveEnabled', 'conversation save'],
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
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationFinalAnswerUserVisibleCompletionGateView(gate = null) {
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
    headerText: userVisibleCompletionHeaderText(gate),
    idsText: userVisibleCompletionIdsText(gate),
    countsText: userVisibleCompletionCountsText(gate),
    sourceContextText: userVisibleCompletionSourceContextText(gate),
    disabledText: `mutation final-answer user-visible completion disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(userVisibleCompletionPolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer user-visible completion blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function userVisibleCompletionSourceContextText(gate) {
  const hasSourceContext = gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryStatus
    || gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftStatus
    || gate.sourceFinalAnswerConversationSaveGateAcceptedObservationSummaryStatus
    || gate.sourceFinalAnswerConversationSaveGatePublicationGateStatus
    || gate.sourceFinalAnswerConversationSaveGatePublicationGateSchema
    || gate.sourceFinalAnswerConversationSaveGatePublicationGateSessionId
    || gate.sourceFinalAnswerConversationSaveGatePublicationGateUserId
    || gate.sourceFinalAnswerConversationSaveGatePublicationGateAgentId
    || gate.sourceFinalAnswerConversationSaveGatePublicationGateWorkspaceId;
  if (!hasSourceContext) {
    return '';
  }
  let text = 'mutation final-answer user-visible completion source context:';
  if (gate.sourceFinalAnswerConversationSaveGatePublicationGateStatus) {
    text += ` publication gate ${gate.sourceFinalAnswerConversationSaveGatePublicationGateStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationGateSchema) {
    text += ` / publication schema ${gate.sourceFinalAnswerConversationSaveGatePublicationGateSchema}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationGateSessionId) {
    text += ` / publication session ${gate.sourceFinalAnswerConversationSaveGatePublicationGateSessionId}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationGateUserId) {
    text += ` / publication user ${gate.sourceFinalAnswerConversationSaveGatePublicationGateUserId}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationGateAgentId) {
    text += ` / publication agent ${gate.sourceFinalAnswerConversationSaveGatePublicationGateAgentId}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationGateWorkspaceId) {
    text += ` / publication workspace ${gate.sourceFinalAnswerConversationSaveGatePublicationGateWorkspaceId}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryStatus) {
    text += ` / publication boundary ${gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / publication prerequisites ${String(gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftStatus}`;
  }
  const draftSections = Array.isArray(gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftSections)
    ? gate.sourceFinalAnswerConversationSaveGatePublicationBoundaryDraftSections
    : [];
  if (draftSections.length) {
    text += ` / sections ${draftSections.join(', ')}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalAnswerConversationSaveGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateAcceptedObservationCount !== undefined) {
    text += ` / observed ${String(gate.sourceFinalAnswerConversationSaveGateAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalAnswerConversationSaveGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalAnswerConversationSaveGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalAnswerConversationSaveGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalAnswerConversationSaveGateStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationSummaryStatus) {
    text += ` / publication observations ${gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationCount !== undefined) {
    text += ` / publication count ${String(gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationAcceptedCount !== undefined) {
    text += ` / publication accepted ${String(gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationRejectedCount !== undefined) {
    text += ` / publication rejected ${String(gate.sourceFinalAnswerConversationSaveGatePublicationAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication missing result risk ${String(gate.sourceFinalAnswerConversationSaveGatePublicationMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationStaleIndexRiskVisible !== undefined) {
    text += ` / publication stale index risk ${String(gate.sourceFinalAnswerConversationSaveGatePublicationStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationStatus) {
    text += ` / publication latest ${gate.sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationToolName) {
    text += ` / publication tool ${gate.sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationToolName}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationVerificationStatus) {
    text += ` / publication verification ${gate.sourceFinalAnswerConversationSaveGatePublicationLatestAcceptedObservationVerificationStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStatus) {
    text += ` / publication rollback summary observations ${gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    text += ` / publication rollback summary count ${String(gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryObservationCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    text += ` / publication rollback summary accepted ${String(gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    text += ` / publication rollback summary rejected ${String(gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication rollback summary missing result risk ${String(gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    text += ` / publication rollback summary stale index risk ${String(gate.sourceFinalAnswerConversationSaveGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function userVisibleCompletionHeaderText(gate) {
  let text = `mutation final-answer user-visible completion gate: ${gate.status || 'BLOCKED_FINAL_ANSWER_USER_VISIBLE_COMPLETION_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.finalAnswerConversationSaveReady !== undefined) {
    text += ` / final answer conversation-save ready ${String(gate.finalAnswerConversationSaveReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.userVisibleCompletionPolicy) {
    text += ` / policy ${gate.userVisibleCompletionPolicy}`;
  }
  if (gate.finalResponseHandoffEnabled !== undefined) {
    text += ` / final response handoff ${String(gate.finalResponseHandoffEnabled)}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateStatus) {
    text += ` / final answer conversation-save status ${gate.sourceFinalAnswerConversationSaveGateStatus}`;
  }
  if (gate.sourceFinalAnswerConversationSaveGateSchema) {
    text += ` / ${gate.sourceFinalAnswerConversationSaveGateSchema}`;
  }
  return text;
}

function userVisibleCompletionIdsText(gate) {
  let text = 'mutation final-answer user-visible completion ids:';
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

function userVisibleCompletionCountsText(gate) {
  let text = 'mutation final-answer user-visible completion counts:';
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

function userVisibleCompletionPolicyText(policy) {
  let text = `final-answer user-visible completion policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

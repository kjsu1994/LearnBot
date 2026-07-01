const DISABLED_CONTROL_LABELS = [
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['finalAnswerPersistenceInvocationEnabled', 'persistence invocation'],
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['finalAnswerCompletionEnabled', 'completion'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
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

export function buildMutationFinalAnswerPersistenceGateView(gate = null) {
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
    headerText: finalAnswerPersistenceHeaderText(gate),
    idsText: finalAnswerPersistenceIdsText(gate),
    countsText: finalAnswerPersistenceCountsText(gate),
    sourceContextText: finalAnswerPersistenceSourceContextText(gate),
    disabledText: `mutation final-answer persistence disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(finalAnswerPersistencePolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer persistence blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function finalAnswerPersistenceSourceContextText(gate) {
  const hasSourceContext = gate.sourceFinalAnswerCompletionGatePublicationBoundaryStatus
    || gate.sourceFinalAnswerCompletionGatePublicationBoundaryDraftStatus
    || gate.sourceFinalAnswerCompletionGateAcceptedObservationSummaryStatus
    || gate.sourceFinalAnswerCompletionGatePublicationGateStatus
    || gate.sourceFinalAnswerCompletionGatePublicationGateSchema
    || gate.sourceFinalAnswerCompletionGatePublicationGateSessionId
    || gate.sourceFinalAnswerCompletionGatePublicationGateUserId
    || gate.sourceFinalAnswerCompletionGatePublicationGateAgentId
    || gate.sourceFinalAnswerCompletionGatePublicationGateWorkspaceId;
  if (!hasSourceContext) {
    return '';
  }
  let text = 'mutation final-answer persistence source context:';
  if (gate.sourceFinalAnswerCompletionGatePublicationGateStatus) {
    text += ` publication gate ${gate.sourceFinalAnswerCompletionGatePublicationGateStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationGateSchema) {
    text += ` / publication schema ${gate.sourceFinalAnswerCompletionGatePublicationGateSchema}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationGateSessionId) {
    text += ` / publication session ${gate.sourceFinalAnswerCompletionGatePublicationGateSessionId}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationGateUserId) {
    text += ` / publication user ${gate.sourceFinalAnswerCompletionGatePublicationGateUserId}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationGateAgentId) {
    text += ` / publication agent ${gate.sourceFinalAnswerCompletionGatePublicationGateAgentId}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationGateWorkspaceId) {
    text += ` / publication workspace ${gate.sourceFinalAnswerCompletionGatePublicationGateWorkspaceId}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationBoundaryStatus) {
    text += ` / publication boundary ${gate.sourceFinalAnswerCompletionGatePublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / publication prerequisites ${String(gate.sourceFinalAnswerCompletionGatePublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalAnswerCompletionGatePublicationBoundaryDraftStatus}`;
  }
  const draftSections = Array.isArray(gate.sourceFinalAnswerCompletionGatePublicationBoundaryDraftSections)
    ? gate.sourceFinalAnswerCompletionGatePublicationBoundaryDraftSections
    : [];
  if (draftSections.length) {
    text += ` / sections ${draftSections.join(', ')}`;
  }
  if (gate.sourceFinalAnswerCompletionGateAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalAnswerCompletionGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGateAcceptedObservationCount !== undefined) {
    text += ` / observed ${String(gate.sourceFinalAnswerCompletionGateAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalAnswerCompletionGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalAnswerCompletionGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalAnswerCompletionGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerCompletionGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalAnswerCompletionGateStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationSummaryStatus) {
    text += ` / publication observations ${gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationCount !== undefined) {
    text += ` / publication count ${String(gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationAcceptedCount !== undefined) {
    text += ` / publication accepted ${String(gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationRejectedCount !== undefined) {
    text += ` / publication rejected ${String(gate.sourceFinalAnswerCompletionGatePublicationAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication missing result risk ${String(gate.sourceFinalAnswerCompletionGatePublicationMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationStaleIndexRiskVisible !== undefined) {
    text += ` / publication stale index risk ${String(gate.sourceFinalAnswerCompletionGatePublicationStaleIndexRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationStatus) {
    text += ` / publication latest ${gate.sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationToolName) {
    text += ` / publication tool ${gate.sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationToolName}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationVerificationStatus) {
    text += ` / publication verification ${gate.sourceFinalAnswerCompletionGatePublicationLatestAcceptedObservationVerificationStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStatus) {
    text += ` / publication rollback summary observations ${gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    text += ` / publication rollback summary count ${String(gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryObservationCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    text += ` / publication rollback summary accepted ${String(gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    text += ` / publication rollback summary rejected ${String(gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / publication rollback summary missing result risk ${String(gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    text += ` / publication rollback summary stale index risk ${String(gate.sourceFinalAnswerCompletionGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function finalAnswerPersistenceHeaderText(gate) {
  let text = `mutation final-answer persistence gate: ${gate.status || 'BLOCKED_FINAL_ANSWER_PERSISTENCE_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.finalAnswerCompletionReady !== undefined) {
    text += ` / final answer completion ready ${String(gate.finalAnswerCompletionReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.finalAnswerPersistencePolicy) {
    text += ` / policy ${gate.finalAnswerPersistencePolicy}`;
  }
  if (gate.conversationTurnSaveEnabled !== undefined) {
    text += ` / conversation save ${String(gate.conversationTurnSaveEnabled)}`;
  }
  if (gate.sourceFinalAnswerCompletionGateStatus) {
    text += ` / final answer completion status ${gate.sourceFinalAnswerCompletionGateStatus}`;
  }
  if (gate.sourceFinalAnswerCompletionGateSchema) {
    text += ` / ${gate.sourceFinalAnswerCompletionGateSchema}`;
  }
  return text;
}

function finalAnswerPersistenceIdsText(gate) {
  let text = 'mutation final-answer persistence ids:';
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

function finalAnswerPersistenceCountsText(gate) {
  let text = 'mutation final-answer persistence counts:';
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

function finalAnswerPersistencePolicyText(policy) {
  let text = `final-answer persistence policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

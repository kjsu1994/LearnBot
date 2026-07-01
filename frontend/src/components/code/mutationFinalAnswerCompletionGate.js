const DISABLED_CONTROL_LABELS = [
  ['finalAnswerCompletionEnabled', 'completion'],
  ['finalAnswerCompletionInvocationEnabled', 'completion invocation'],
  ['finalAnswerDeliveryEnabled', 'delivery'],
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
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
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationFinalAnswerCompletionGateView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      countsText: '',
      generationContextText: '',
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
    headerText: finalAnswerCompletionHeaderText(gate),
    idsText: finalAnswerCompletionIdsText(gate),
    countsText: finalAnswerCompletionCountsText(gate),
    generationContextText: finalAnswerCompletionGenerationContextText(gate),
    sourceContextText: finalAnswerCompletionSourceContextText(gate),
    disabledText: `mutation final-answer completion disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(finalAnswerCompletionPolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer completion blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function finalAnswerCompletionGenerationContextText(gate) {
  const hasPublicationContext = gate.sourceFinalAnswerGenerationGatePublicationBoundaryStatus
    || gate.sourceFinalAnswerGenerationGatePublicationBoundaryDraftStatus
    || gate.sourceFinalAnswerGenerationGateAcceptedObservationSummaryStatus;
  if (!hasPublicationContext) {
    return '';
  }
  let text = 'mutation final-answer completion generation context:';
  if (gate.sourceFinalAnswerGenerationGatePublicationBoundaryStatus) {
    text += ` publication boundary ${gate.sourceFinalAnswerGenerationGatePublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / publication prerequisites ${String(gate.sourceFinalAnswerGenerationGatePublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalAnswerGenerationGatePublicationBoundaryDraftStatus}`;
  }
  const draftSections = Array.isArray(gate.sourceFinalAnswerGenerationGatePublicationBoundaryDraftSections)
    ? gate.sourceFinalAnswerGenerationGatePublicationBoundaryDraftSections
    : [];
  if (draftSections.length) {
    text += ` / sections ${draftSections.join(', ')}`;
  }
  if (gate.sourceFinalAnswerGenerationGateAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalAnswerGenerationGateAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerGenerationGateAcceptedObservationCount !== undefined) {
    text += ` / observed ${String(gate.sourceFinalAnswerGenerationGateAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerGenerationGateAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalAnswerGenerationGateAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerGenerationGateAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalAnswerGenerationGateAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerGenerationGateMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalAnswerGenerationGateMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerGenerationGateStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalAnswerGenerationGateStaleIndexRiskVisible)}`;
  }
  return text;
}

function finalAnswerCompletionSourceContextText(gate) {
  if (!gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationSummaryStatus
    && !gate.sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationStatus) {
    return '';
  }
  const parts = [];
  if (gate.sourceFinalAnswerGenerationGatePublicationGateStatus) {
    parts.push(`publication gate ${gate.sourceFinalAnswerGenerationGatePublicationGateStatus}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationGateSchema) {
    parts.push(`publication schema ${gate.sourceFinalAnswerGenerationGatePublicationGateSchema}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationGateSessionId) {
    parts.push(`publication session ${gate.sourceFinalAnswerGenerationGatePublicationGateSessionId}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationGateUserId) {
    parts.push(`publication user ${gate.sourceFinalAnswerGenerationGatePublicationGateUserId}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationGateAgentId) {
    parts.push(`publication agent ${gate.sourceFinalAnswerGenerationGatePublicationGateAgentId}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationGateWorkspaceId) {
    parts.push(`publication workspace ${gate.sourceFinalAnswerGenerationGatePublicationGateWorkspaceId}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationSummaryStatus) {
    parts.push(`publication observations ${gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationSummaryStatus}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationCount !== undefined) {
    parts.push(`count ${String(gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationCount)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationAcceptedCount !== undefined) {
    parts.push(`accepted ${String(gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationAcceptedCount)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationRejectedCount !== undefined) {
    parts.push(`rejected ${String(gate.sourceFinalAnswerGenerationGatePublicationAcceptedObservationRejectedCount)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationMissingMutationResultRiskVisible !== undefined) {
    parts.push(`missing result risk ${String(gate.sourceFinalAnswerGenerationGatePublicationMissingMutationResultRiskVisible)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationStaleIndexRiskVisible !== undefined) {
    parts.push(`stale index risk ${String(gate.sourceFinalAnswerGenerationGatePublicationStaleIndexRiskVisible)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationStatus) {
    parts.push(`latest ${gate.sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationStatus}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationToolName) {
    parts.push(`tool ${gate.sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationToolName}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationVerificationStatus) {
    parts.push(`verification ${gate.sourceFinalAnswerGenerationGatePublicationLatestAcceptedObservationVerificationStatus}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStatus) {
    parts.push(`rollback summary observations ${gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStatus}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    parts.push(`rollback summary count ${String(gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryObservationCount)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    parts.push(`rollback summary accepted ${String(gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryAcceptedCount)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    parts.push(`rollback summary rejected ${String(gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryRejectedCount)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    parts.push(`rollback summary missing result risk ${String(gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`);
  }
  if (gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    parts.push(`rollback summary stale index risk ${String(gate.sourceFinalAnswerGenerationGatePublicationRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`);
  }
  return parts.length ? `mutation final-answer completion source context: ${parts.join(' / ')}` : '';
}

function finalAnswerCompletionHeaderText(gate) {
  let text = `mutation final-answer completion gate: ${gate.status || 'BLOCKED_FINAL_ANSWER_COMPLETION_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.finalAnswerGenerationReady !== undefined) {
    text += ` / final answer generation ready ${String(gate.finalAnswerGenerationReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.finalAnswerCompletionPolicy) {
    text += ` / policy ${gate.finalAnswerCompletionPolicy}`;
  }
  if (gate.finalAnswerDeliveryEnabled !== undefined) {
    text += ` / delivery ${String(gate.finalAnswerDeliveryEnabled)}`;
  }
  if (gate.sourceFinalAnswerGenerationGateStatus) {
    text += ` / final answer generation status ${gate.sourceFinalAnswerGenerationGateStatus}`;
  }
  if (gate.sourceFinalAnswerGenerationGateSchema) {
    text += ` / ${gate.sourceFinalAnswerGenerationGateSchema}`;
  }
  return text;
}

function finalAnswerCompletionIdsText(gate) {
  let text = 'mutation final-answer completion ids:';
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

function finalAnswerCompletionCountsText(gate) {
  let text = 'mutation final-answer completion counts:';
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

function finalAnswerCompletionPolicyText(policy) {
  let text = `final-answer completion policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

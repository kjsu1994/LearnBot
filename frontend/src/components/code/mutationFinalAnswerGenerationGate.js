const DISABLED_CONTROL_LABELS = [
  ['finalAnswerGenerationEnabled', 'final answer'],
  ['finalAnswerGenerationInvocationEnabled', 'final answer invocation'],
  ['finalAnswerCompletionEnabled', 'final-answer completion'],
  ['finalAnswerDeliveryEnabled', 'final-answer delivery'],
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
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
  ['finalAnswerCompletionEnabled', 'final-answer completion'],
  ['finalAnswerDeliveryEnabled', 'final-answer delivery'],
  ['finalResponseHandoffEnabled', 'final-response handoff'],
  ['deliveryReceiptEnabled', 'delivery receipt'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
];

export function buildMutationFinalAnswerGenerationGateView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      countsText: '',
      disabledText: '',
      publicationContextText: '',
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
    headerText: finalAnswerGenerationHeaderText(gate),
    idsText: finalAnswerGenerationIdsText(gate),
    countsText: finalAnswerGenerationCountsText(gate),
    disabledText: `mutation final-answer generation disabled:${disabledControlSuffix(gate)}`,
    publicationContextText: finalAnswerGenerationPublicationContextText(gate),
    sourceContextText: finalAnswerGenerationSourceContextText(gate),
    policyLines: policyChecks.map(finalAnswerGenerationPolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer generation blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
}

function finalAnswerGenerationHeaderText(gate) {
  let text = `mutation final-answer generation gate: ${gate.status || 'BLOCKED_FINAL_ANSWER_GENERATION_DISABLED'}`;
  if (gate.schema) {
    text += ` / ${gate.schema}`;
  }
  if (gate.publicationReady !== undefined) {
    text += ` / publication ready ${String(gate.publicationReady)}`;
  }
  if (gate.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.prerequisitesPassed)}`;
  }
  if (gate.executionTarget) {
    text += ` / ${gate.executionTarget}`;
  }
  if (gate.finalAnswerGenerationPolicy) {
    text += ` / policy ${gate.finalAnswerGenerationPolicy}`;
  }
  if (gate.sourcePublicationGateStatus) {
    text += ` / publication status ${gate.sourcePublicationGateStatus}`;
  }
  if (gate.sourcePublicationGateSchema) {
    text += ` / ${gate.sourcePublicationGateSchema}`;
  }
  return text;
}

function finalAnswerGenerationIdsText(gate) {
  let text = 'mutation final-answer generation ids:';
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

function finalAnswerGenerationCountsText(gate) {
  let text = 'mutation final-answer generation counts:';
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

function finalAnswerGenerationPublicationContextText(gate) {
  if (!gate.sourceFinalAnswerPublicationBoundaryStatus && !gate.sourceFinalAnswerPublicationBoundaryDraftStatus) {
    return '';
  }
  let text = 'mutation final-answer generation publication context:';
  if (gate.sourceFinalAnswerPublicationBoundaryStatus) {
    text += ` boundary ${gate.sourceFinalAnswerPublicationBoundaryStatus}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryPrerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(gate.sourceFinalAnswerPublicationBoundaryPrerequisitesPassed)}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryDraftStatus) {
    text += ` / draft ${gate.sourceFinalAnswerPublicationBoundaryDraftStatus}`;
  }
  if (Array.isArray(gate.sourceFinalAnswerPublicationBoundaryDraftSections)) {
    text += ` / draft sections ${gate.sourceFinalAnswerPublicationBoundaryDraftSections.join(', ')}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationSummaryStatus) {
    text += ` / observations ${gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationSummaryStatus}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationCount !== undefined) {
    text += ` / count ${String(gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationCount)}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationAcceptedCount !== undefined) {
    text += ` / accepted ${String(gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationAcceptedCount)}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationRejectedCount !== undefined) {
    text += ` / rejected ${String(gate.sourceFinalAnswerPublicationBoundaryAcceptedObservationRejectedCount)}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryMissingMutationResultRiskVisible !== undefined) {
    text += ` / missing result risk ${String(gate.sourceFinalAnswerPublicationBoundaryMissingMutationResultRiskVisible)}`;
  }
  if (gate.sourceFinalAnswerPublicationBoundaryStaleIndexRiskVisible !== undefined) {
    text += ` / stale index risk ${String(gate.sourceFinalAnswerPublicationBoundaryStaleIndexRiskVisible)}`;
  }
  return text;
}

function finalAnswerGenerationSourceContextText(gate) {
  if (!gate.sourcePublicationGateStatus
    && !gate.sourcePublicationGateAcceptedObservationSummaryStatus
    && !gate.sourcePublicationGateLatestAcceptedObservationStatus) {
    return '';
  }
  const parts = [];
  if (gate.sourcePublicationGateStatus) {
    parts.push(`publication gate ${gate.sourcePublicationGateStatus}`);
  }
  if (gate.sourcePublicationGateSchema) {
    parts.push(`publication schema ${gate.sourcePublicationGateSchema}`);
  }
  if (gate.sourcePublicationGateSessionId) {
    parts.push(`publication session ${gate.sourcePublicationGateSessionId}`);
  }
  if (gate.sourcePublicationGateUserId) {
    parts.push(`publication user ${gate.sourcePublicationGateUserId}`);
  }
  if (gate.sourcePublicationGateAgentId) {
    parts.push(`publication agent ${gate.sourcePublicationGateAgentId}`);
  }
  if (gate.sourcePublicationGateWorkspaceId) {
    parts.push(`publication workspace ${gate.sourcePublicationGateWorkspaceId}`);
  }
  if (gate.sourcePublicationGateAcceptedObservationSummaryStatus) {
    parts.push(`publication observations ${gate.sourcePublicationGateAcceptedObservationSummaryStatus}`);
  }
  if (gate.sourcePublicationGateAcceptedObservationCount !== undefined) {
    parts.push(`count ${String(gate.sourcePublicationGateAcceptedObservationCount)}`);
  }
  if (gate.sourcePublicationGateAcceptedObservationAcceptedCount !== undefined) {
    parts.push(`accepted ${String(gate.sourcePublicationGateAcceptedObservationAcceptedCount)}`);
  }
  if (gate.sourcePublicationGateAcceptedObservationRejectedCount !== undefined) {
    parts.push(`rejected ${String(gate.sourcePublicationGateAcceptedObservationRejectedCount)}`);
  }
  if (gate.sourcePublicationGateMissingMutationResultRiskVisible !== undefined) {
    parts.push(`missing result risk ${String(gate.sourcePublicationGateMissingMutationResultRiskVisible)}`);
  }
  if (gate.sourcePublicationGateStaleIndexRiskVisible !== undefined) {
    parts.push(`stale index risk ${String(gate.sourcePublicationGateStaleIndexRiskVisible)}`);
  }
  if (gate.sourcePublicationGateLatestAcceptedObservationStatus) {
    parts.push(`latest ${gate.sourcePublicationGateLatestAcceptedObservationStatus}`);
  }
  if (gate.sourcePublicationGateLatestAcceptedObservationToolName) {
    parts.push(`tool ${gate.sourcePublicationGateLatestAcceptedObservationToolName}`);
  }
  if (gate.sourcePublicationGateLatestAcceptedObservationVerificationStatus) {
    parts.push(`verification ${gate.sourcePublicationGateLatestAcceptedObservationVerificationStatus}`);
  }
  if (gate.sourcePublicationGateRollbackAcceptedObservationSummaryStatus) {
    parts.push(`rollback summary observations ${gate.sourcePublicationGateRollbackAcceptedObservationSummaryStatus}`);
  }
  if (gate.sourcePublicationGateRollbackAcceptedObservationSummaryObservationCount !== undefined) {
    parts.push(`rollback summary count ${String(gate.sourcePublicationGateRollbackAcceptedObservationSummaryObservationCount)}`);
  }
  if (gate.sourcePublicationGateRollbackAcceptedObservationSummaryAcceptedCount !== undefined) {
    parts.push(`rollback summary accepted ${String(gate.sourcePublicationGateRollbackAcceptedObservationSummaryAcceptedCount)}`);
  }
  if (gate.sourcePublicationGateRollbackAcceptedObservationSummaryRejectedCount !== undefined) {
    parts.push(`rollback summary rejected ${String(gate.sourcePublicationGateRollbackAcceptedObservationSummaryRejectedCount)}`);
  }
  if (gate.sourcePublicationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible !== undefined) {
    parts.push(`rollback summary missing result risk ${String(gate.sourcePublicationGateRollbackAcceptedObservationSummaryMissingMutationResultRiskVisible)}`);
  }
  if (gate.sourcePublicationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible !== undefined) {
    parts.push(`rollback summary stale index risk ${String(gate.sourcePublicationGateRollbackAcceptedObservationSummaryStaleIndexRiskVisible)}`);
  }
  return parts.length ? `mutation final-answer generation source context: ${parts.join(' / ')}` : '';
}

function disabledControlSuffix(gate) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => gate[key] === undefined ? null : ` ${label} ${String(gate[key])}`)
    .filter(Boolean)
    .join(' /');
}

function finalAnswerGenerationPolicyText(policy) {
  let text = `final-answer generation policy ${policy.key}: ${policy.status || 'UNKNOWN'}`;
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

const DISABLED_CONTROL_LABELS = [
  ['finalResponseHandoffEnabled', 'final response handoff'],
  ['deliveryHandoffEnabled', 'delivery handoff'],
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
];

export function buildMutationFinalResponseHandoffGateView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      idsText: '',
      countsText: '',
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
    disabledText: `mutation final-response handoff disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(finalResponseHandoffPolicyText),
    blockingText: blockingKeys.length ? `mutation final-response handoff blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
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

const DISABLED_CONTROL_LABELS = [
  ['deliveryReceiptEnabled', 'receipt'],
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
    disabledText: `mutation final-answer delivery receipt disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(deliveryReceiptPolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer delivery receipt blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
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

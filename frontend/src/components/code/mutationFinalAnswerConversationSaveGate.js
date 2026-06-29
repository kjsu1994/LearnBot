const DISABLED_CONTROL_LABELS = [
  ['conversationTurnSaveEnabled', 'conversation save'],
  ['conversationTurnSaveInvocationEnabled', 'conversation save invocation'],
  ['userVisibleCompletionEnabled', 'user-visible completion'],
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
];

export function buildMutationFinalAnswerConversationSaveGateView(gate = null) {
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
    headerText: conversationSaveHeaderText(gate),
    idsText: conversationSaveIdsText(gate),
    countsText: conversationSaveCountsText(gate),
    disabledText: `mutation final-answer conversation-save disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(conversationSavePolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer conversation-save blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
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

const DISABLED_CONTROL_LABELS = [
  ['finalAnswerPersistenceEnabled', 'persistence'],
  ['finalAnswerPersistenceInvocationEnabled', 'persistence invocation'],
  ['conversationTurnSaveEnabled', 'conversation save'],
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
];

export function buildMutationFinalAnswerPersistenceGateView(gate = null) {
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
    headerText: finalAnswerPersistenceHeaderText(gate),
    idsText: finalAnswerPersistenceIdsText(gate),
    countsText: finalAnswerPersistenceCountsText(gate),
    disabledText: `mutation final-answer persistence disabled:${disabledControlSuffix(gate)}`,
    policyLines: policyChecks.map(finalAnswerPersistencePolicyText),
    blockingText: blockingKeys.length ? `mutation final-answer persistence blocking keys: ${blockingKeys.join(', ')}` : '',
    message: gate.message || '',
  };
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

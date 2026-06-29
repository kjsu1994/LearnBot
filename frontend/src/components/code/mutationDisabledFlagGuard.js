const forbiddenEnabledFlagKeys = new Set([
  'releaseGateEnabled',
  'requestCreationEnabled',
  'requestBlueprintEnabled',
  'dispatchDecisionEnabled',
  'dispatchPreflightEnabled',
  'pushEnabled',
  'claimEnabled',
  'claimGateEnabled',
  'claimNextInvocationEnabled',
  'claimable',
  'claimableAfterRelease',
  'claimableAfterEnqueue',
  'executionEnabled',
  'executionGateEnabled',
  'runningTransitionEnabled',
  'completedResultTransitionEnabled',
  'toolRunnerEnabled',
  'toolRunnerInvocationEnabled',
  'writeHelperEnabled',
  'writeHelperInvocationEnabled',
  'applyEnabled',
  'testEnabled',
  'rollbackRestoreEnabled',
  'ragFreshnessUpdateEnabled',
  'ragFreshnessUpdateInvocationEnabled',
  'mutationAllowed',
  'mutationResultAggregationEnabled',
  'resultAggregationInvocationEnabled',
  'publicationEnabled',
  'publicationInvocationEnabled',
  'postExecutionObservationEnabled',
  'completedResultPersistenceEnabled',
  'observationAcceptanceEnabled',
  'intakePersistenceEnabled',
  'acceptedObservationPersistenceEnabled',
  'rollbackFallbackExecutionEnabled',
  'rollbackFallbackInvocationEnabled',
  'resultIntakeEnabled',
  'finalAnswerGenerationEnabled',
  'finalAnswerGenerationInvocationEnabled',
  'finalAnswerCompletionEnabled',
  'finalAnswerPersistenceEnabled',
  'conversationTurnSaveEnabled',
  'userVisibleCompletionEnabled',
  'finalResponseHandoffEnabled',
  'deliveryHandoffEnabled',
  'finalAnswerDeliveryEnabled',
  'deliveryReceiptEnabled',
]);

const forbiddenControlKeyPrefixes = [
  'release',
  'request',
  'dispatch',
  'push',
  'claim',
  'execution',
  'running',
  'completedResult',
  'toolRunner',
  'writeHelper',
  'apply',
  'test',
  'rollback',
  'ragFreshness',
  'mutation',
  'result',
  'publication',
  'postExecution',
  'observation',
  'intake',
  'acceptedObservation',
  'finalAnswer',
  'conversationTurn',
  'userVisible',
  'finalResponse',
  'delivery',
];

const forbiddenControlKeySuffixes = [
  'Enabled',
  'Allowed',
  'InvocationEnabled',
  'TransitionEnabled',
  'PersistenceEnabled',
  'HandoffEnabled',
  'Claimable',
];

function isForbiddenEnabledFlagKey(key) {
  if (forbiddenEnabledFlagKeys.has(key)) {
    return true;
  }
  if (key.startsWith('claimableAfter')) {
    return true;
  }
  return forbiddenControlKeyPrefixes.some((prefix) => key.startsWith(prefix))
    && forbiddenControlKeySuffixes.some((suffix) => key.endsWith(suffix));
}

export function collectForbiddenTrueFlags(value, path = 'latestAttempt') {
  if (!value || typeof value !== 'object') {
    return [];
  }
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => collectForbiddenTrueFlags(item, `${path}[${index}]`));
  }
  return Object.entries(value).flatMap(([key, child]) => {
    const childPath = `${path}.${key}`;
    const violations = isForbiddenEnabledFlagKey(key) && child === true ? [childPath] : [];
    return violations.concat(collectForbiddenTrueFlags(child, childPath));
  });
}

export function assertNoForbiddenTrueFlags(value, path = 'latestAttempt') {
  const violations = collectForbiddenTrueFlags(value, path);
  if (violations.length) {
    throw new Error(`Forbidden enabled mutation flags: ${violations.join(', ')}`);
  }
  return true;
}

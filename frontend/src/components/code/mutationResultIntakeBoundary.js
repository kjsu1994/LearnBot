const DISABLED_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['writeHelperEnabled', 'write helper'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

const REQUIREMENT_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

export function buildMutationResultIntakeBoundaryView(boundary = null) {
  if (!boundary) {
    return {
      show: false,
      headerText: '',
      disabledText: '',
      requiredOutcomeText: '',
      acceptedStatusesText: '',
      requirementLines: [],
      blockingText: '',
      message: '',
    };
  }

  const requiredOutcomeKeys = Array.isArray(boundary.requiredOutcomeKeys) ? boundary.requiredOutcomeKeys : [];
  const acceptedTerminalStatuses = Array.isArray(boundary.acceptedTerminalStatuses)
    ? boundary.acceptedTerminalStatuses
    : [];
  const requirements = Array.isArray(boundary.requirements) ? boundary.requirements : [];
  const blockingKeys = Array.isArray(boundary.blockingKeys) ? boundary.blockingKeys : [];

  return {
    show: true,
    headerText: mutationResultIntakeHeaderText(boundary),
    disabledText: `mutation result intake disabled:${disabledControlSuffix(boundary)}`,
    requiredOutcomeText: requiredOutcomeKeys.length
      ? `mutation result required outcomes: ${requiredOutcomeKeys.join(', ')}`
      : '',
    acceptedStatusesText: acceptedTerminalStatuses.length
      ? `mutation result accepted terminal statuses: ${acceptedTerminalStatuses.join(', ')}`
      : '',
    requirementLines: requirements.map(mutationResultIntakeRequirementText),
    blockingText: blockingKeys.length ? `mutation result intake blocking keys: ${blockingKeys.join(', ')}` : '',
    message: boundary.message || '',
  };
}

function mutationResultIntakeHeaderText(boundary) {
  let text = `mutation result intake boundary: ${boundary.status || 'BLOCKED_INTAKE_DISABLED'}`;
  if (boundary.schema) {
    text += ` / ${boundary.schema}`;
  }
  if (boundary.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(boundary.prerequisitesPassed)}`;
  }
  if (boundary.executionTarget) {
    text += ` / ${boundary.executionTarget}`;
  }
  if (boundary.postMutationResultSchema) {
    text += ` / source ${boundary.postMutationResultSchema}`;
  }
  return text;
}

function disabledControlSuffix(boundary) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => boundary[key] === undefined ? null : ` ${label} ${String(boundary[key])}`)
    .filter(Boolean)
    .join(' /');
}

function mutationResultIntakeRequirementText(requirement) {
  let text = `${requirement.key}: ${requirement.status || 'UNKNOWN'}`;
  if (requirement.passed !== undefined) {
    text += ` / passed ${String(requirement.passed)}`;
  }
  if (requirement.blocking !== undefined) {
    text += ` / blocking ${String(requirement.blocking)}`;
  }
  const suffix = REQUIREMENT_CONTROL_LABELS
    .map(([key, label]) => requirement[key] === undefined ? null : ` / ${label} ${String(requirement[key])}`)
    .filter(Boolean)
    .join('');
  if (requirement.message) {
    return `${text}${suffix} / ${requirement.message}`;
  }
  return text + suffix;
}

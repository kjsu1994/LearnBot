const DISABLED_CONTROL_LABELS = [
  ['dispatchPreflightEnabled', 'dispatch preflight'],
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
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

export function buildMutationDispatchPreflightBoundaryView(boundary = null) {
  if (!boundary) {
    return {
      show: false,
      headerText: '',
      agentText: '',
      workspaceText: '',
      requiredCapabilitiesText: '',
      advertisedCapabilitiesText: '',
      capabilityLines: [],
      missingCapabilitiesText: '',
      disabledText: '',
      blockingText: '',
      message: '',
    };
  }

  const requiredCapabilities = Array.isArray(boundary.requiredCapabilities) ? boundary.requiredCapabilities : [];
  const advertisedCapabilities = Array.isArray(boundary.advertisedCapabilities) ? boundary.advertisedCapabilities : [];
  const capabilityChecks = Array.isArray(boundary.capabilityChecks) ? boundary.capabilityChecks : [];
  const missingCapabilities = Array.isArray(boundary.missingCapabilities) ? boundary.missingCapabilities : [];
  const blockingKeys = Array.isArray(boundary.blockingKeys) ? boundary.blockingKeys : [];

  return {
    show: true,
    headerText: mutationDispatchPreflightHeaderText(boundary),
    agentText: mutationDispatchPreflightAgentText(boundary),
    workspaceText: mutationDispatchPreflightWorkspaceText(boundary),
    requiredCapabilitiesText: requiredCapabilities.length
      ? `mutation dispatch required capabilities: ${requiredCapabilities.join(', ')}`
      : '',
    advertisedCapabilitiesText: advertisedCapabilities.length
      ? `mutation dispatch advertised capabilities: ${advertisedCapabilities.join(', ')}`
      : '',
    capabilityLines: capabilityChecks.map(mutationDispatchPreflightCapabilityText),
    missingCapabilitiesText: missingCapabilities.length
      ? `mutation dispatch missing capabilities: ${missingCapabilities.join(', ')}`
      : '',
    disabledText: `mutation dispatch preflight disabled:${disabledControlSuffix(boundary)}`,
    blockingText: blockingKeys.length ? `mutation dispatch preflight blocking keys: ${blockingKeys.join(', ')}` : '',
    message: boundary.message || '',
  };
}

function mutationDispatchPreflightHeaderText(boundary) {
  let text = `mutation dispatch preflight boundary: ${boundary.status || 'BLOCKED_PREFLIGHT_DISABLED'}`;
  if (boundary.schema) {
    text += ` / ${boundary.schema}`;
  }
  if (boundary.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(boundary.prerequisitesPassed)}`;
  }
  if (boundary.executionTarget) {
    text += ` / ${boundary.executionTarget}`;
  }
  if (boundary.dispatchEnvelopeStatus) {
    text += ` / envelope ${boundary.dispatchEnvelopeStatus}`;
  }
  if (boundary.dispatchEnvelopePrerequisitesPassed !== undefined) {
    text += ` / envelope prerequisites ${String(boundary.dispatchEnvelopePrerequisitesPassed)}`;
  }
  return text;
}

function mutationDispatchPreflightAgentText(boundary) {
  let text = 'mutation dispatch preflight agent:';
  if (boundary.connectionState) {
    text += ` ${boundary.connectionState}`;
  }
  if (boundary.agentConnected !== undefined) {
    text += ` / connected ${String(boundary.agentConnected)}`;
  }
  if (boundary.agentMatches !== undefined) {
    text += ` / matches ${String(boundary.agentMatches)}`;
  }
  if (boundary.requestedAgentId) {
    text += ` / requested ${boundary.requestedAgentId}`;
  }
  if (boundary.connectedAgentId) {
    text += ` / connected id ${boundary.connectedAgentId}`;
  }
  if (boundary.agentVersion) {
    text += ` / version ${boundary.agentVersion}`;
  }
  return text;
}

function mutationDispatchPreflightWorkspaceText(boundary) {
  let text = 'mutation dispatch preflight workspace:';
  if (boundary.workspaceId) {
    text += ` ${boundary.workspaceId}`;
  }
  if (boundary.approvedWorkspaceReady !== undefined) {
    text += ` / approved ready ${String(boundary.approvedWorkspaceReady)}`;
  }
  if (boundary.workspaceApproved !== undefined) {
    text += ` / approved ${String(boundary.workspaceApproved)}`;
  }
  if (boundary.workspaceName) {
    text += ` / ${boundary.workspaceName}`;
  }
  if (boundary.workspaceIdentityStatus) {
    text += ` / identity ${boundary.workspaceIdentityStatus}`;
  }
  if (boundary.workspaceIdentityVerified !== undefined) {
    text += ` / verified ${String(boundary.workspaceIdentityVerified)}`;
  }
  return text;
}

function mutationDispatchPreflightCapabilityText(item) {
  let text = `capability ${item.toolName}: ${item.available !== undefined ? `available ${String(item.available)}` : 'availability unknown'}`;
  if (item.passed !== undefined) {
    text += ` / passed ${String(item.passed)}`;
  }
  if (item.blocking !== undefined) {
    text += ` / blocking ${String(item.blocking)}`;
  }
  if (item.sideEffectful !== undefined) {
    text += ` / side-effect ${String(item.sideEffectful)}`;
  }
  if (item.requestCreationEnabled !== undefined) {
    text += ` / request creation ${String(item.requestCreationEnabled)}`;
  }
  if (item.pushEnabled !== undefined) {
    text += ` / push ${String(item.pushEnabled)}`;
  }
  if (item.claimable !== undefined) {
    text += ` / claimable ${String(item.claimable)}`;
  }
  if (item.mutationAllowed !== undefined) {
    text += ` / mutation ${String(item.mutationAllowed)}`;
  }
  return text;
}

function disabledControlSuffix(boundary) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => boundary[key] === undefined ? null : ` ${label} ${String(boundary[key])}`)
    .filter(Boolean)
    .join(' /');
}

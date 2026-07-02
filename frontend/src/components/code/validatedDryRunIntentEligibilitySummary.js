const DISABLED_LABELS = [
  ['requestCreationEnabled', 'request creation'],
  ['queueEnabled', 'queue'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['claimable', 'claimable'],
  ['dryRunOnly', 'dry-run only'],
  ['mutationAllowed', 'mutation'],
  ['approvalBypassAllowed', 'approval bypass'],
];

export function buildValidatedDryRunIntentEligibilityView(eligibility = null) {
  if (!eligibility) {
    return {
      show: false,
      headerText: '',
      identityText: '',
      stateText: '',
      disabledText: '',
      gateText: '',
      targetFilesText: '',
      blockingText: '',
      checkLines: [],
      message: '',
    };
  }
  const targetFiles = Array.isArray(eligibility.targetFiles) ? eligibility.targetFiles : [];
  const blockingKeys = Array.isArray(eligibility.blockingKeys) ? eligibility.blockingKeys : [];
  const checks = Array.isArray(eligibility.checks) ? eligibility.checks : [];
  const gate = eligibility.futureDryRunReleaseGate || {};
  const gateDisabled = disabledSuffix(gate).trimStart();
  return {
    show: true,
    headerText: `validated dry-run intent eligibility: ${eligibility.status || 'UNKNOWN'} / ${eligibility.schema || 'unknown schema'}`,
    identityText: [
      ['request', eligibility.requestId],
      ['session', eligibility.sessionId],
      ['agent', eligibility.agentId],
      ['workspace', eligibility.workspaceId],
      ['tool', eligibility.toolName],
      ['target', eligibility.executionTarget],
      ['approval', eligibility.approvalState],
      ['request status', eligibility.requestStatus],
    ].map(([label, value]) => value ? `${label} ${String(value)}` : null).filter(Boolean).join(' / '),
    stateText: [
      ['validated dry-run intent', eligibility.validatedDryRunIntent],
      ['persisted', eligibility.dryRunIntentPersisted],
      ['request persisted', eligibility.requestPersisted],
      ['prerequisites', eligibility.prerequisitesPassed],
    ].map(([label, value]) => value === undefined ? null : `${label} ${String(value)}`).filter(Boolean).join(' / '),
    disabledText: `validated dry-run controls disabled:${disabledSuffix(eligibility)}`,
    gateText: gate.schema
      ? `future dry-run release gate: ${gate.status || 'UNKNOWN'} / prerequisites ${String(gate.prerequisitesPassed)}${gateDisabled ? ` / ${gateDisabled}` : ''}`
      : '',
    targetFilesText: targetFiles.length ? `validated dry-run target files: ${targetFiles.join(', ')}` : '',
    blockingText: blockingKeys.length ? `validated dry-run blockers: ${blockingKeys.join(', ')}` : '',
    checkLines: checks.map((check) => {
      let text = `${check.key || 'unknown'} ${String(check.passed)}`;
      if (check.message) text += ` / ${check.message}`;
      return text;
    }),
    message: eligibility.message || gate.message || '',
  };
}

function disabledSuffix(source = {}) {
  return DISABLED_LABELS
    .filter(([key]) => source[key] !== undefined)
    .map(([key, label]) => ` ${label} ${String(source[key])}`)
    .join(' /');
}

const DISABLED_LABELS = [
  ['requestCreationEnabled', 'request creation'],
  ['requestPersisted', 'request persisted'],
  ['queueEnabled', 'queue'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['claimable', 'claimable'],
  ['dryRunOnly', 'dry-run only'],
  ['mutationAllowed', 'mutation'],
  ['approvalBypassAllowed', 'approval bypass'],
];

export function buildValidatedDryRunIntentTransitionPreviewView(preview = null) {
  if (!preview) {
    return {
      show: false,
      headerText: '',
      identityText: '',
      stateText: '',
      disabledText: '',
      gateText: '',
      eligibilityText: '',
      wouldBeText: '',
      wouldBeDisabledText: '',
      targetFilesText: '',
      blockingText: '',
      message: '',
    };
  }
  const gate = preview.transitionGate || {};
  const eligibility = preview.eligibility || {};
  const wouldBe = preview.wouldBeClaimableDryRunRequest || {};
  const targetFiles = Array.isArray(wouldBe?.input?.targetFiles)
    ? wouldBe.input.targetFiles
    : Array.isArray(preview.targetFiles)
      ? preview.targetFiles
      : [];
  const blockingKeys = Array.isArray(preview.blockingKeys) ? preview.blockingKeys : [];
  const gateDisabled = disabledSuffix(gate).trimStart();
  return {
    show: true,
    headerText: `validated dry-run transition preview: ${preview.status || 'UNKNOWN'} / ${preview.schema || 'unknown schema'}`,
    identityText: [
      ['source intent', preview.sourceRequestId || preview.requestId],
      ['session', preview.sessionId],
      ['agent', preview.agentId],
      ['workspace', preview.workspaceId],
    ].map(([label, value]) => value ? `${label} ${String(value)}` : null).filter(Boolean).join(' / '),
    stateText: [
      ['prerequisites', preview.prerequisitesPassed],
      ['dry-run only', preview.dryRunOnly],
      ['mutation allowed', preview.mutationAllowed],
      ['approval bypass', preview.approvalBypassAllowed],
    ].map(([label, value]) => value === undefined ? null : `${label} ${String(value)}`).filter(Boolean).join(' / '),
    disabledText: `validated dry-run transition controls disabled:${disabledSuffix(preview)}`,
    gateText: gate.schema
      ? `validated dry-run transition gate: ${gate.status || 'UNKNOWN'} / prerequisites ${String(gate.prerequisitesPassed)}${gateDisabled ? ` / ${gateDisabled}` : ''}`
      : '',
    eligibilityText: eligibility.schema
      ? `validated dry-run transition eligibility: ${eligibility.status || 'UNKNOWN'} / prerequisites ${String(eligibility.prerequisitesPassed)} / schema ${eligibility.schema}`
      : '',
    wouldBeText: wouldBe.schema
      ? `would-be claimable dry-run request: ${wouldBe.status || 'UNKNOWN'} / ${wouldBe.schema} / source intent ${wouldBe.sourceRequestId || 'unknown'} / tool ${wouldBe.toolName || wouldBe.input?.toolName || 'unknown'} / approval ${wouldBe.approvalState || 'unknown'} / target ${wouldBe.executionTarget || 'unknown'}`
      : '',
    wouldBeDisabledText: wouldBe.schema
      ? `would-be dry-run request controls disabled:${disabledSuffix(wouldBe)}`
      : '',
    targetFilesText: targetFiles.length ? `would-be dry-run target files: ${targetFiles.join(', ')}` : '',
    blockingText: blockingKeys.length ? `validated dry-run transition blockers: ${blockingKeys.join(', ')}` : '',
    message: preview.message || gate.message || '',
  };
}

function disabledSuffix(source = {}) {
  return DISABLED_LABELS
    .filter(([key]) => source[key] !== undefined)
    .map(([key, label]) => ` ${label} ${String(source[key])}`)
    .join(' /');
}

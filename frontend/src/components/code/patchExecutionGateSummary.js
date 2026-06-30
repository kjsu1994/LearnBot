export function buildPatchExecutionGateSummaryView(gate = null) {
  if (!gate) {
    return {
      show: false,
      headerText: '',
      message: '',
      controlText: '',
    };
  }

  return {
    show: true,
    headerText: `Internal patch execution gate: ${gate.status || 'UNKNOWN'}`,
    message: gate.message || '',
    controlText: patchExecutionGateControlText(gate),
  };
}

function patchExecutionGateControlText(gate) {
  let text = `claim enabled: ${String(gate.claimEnabled)}`;
  text += optionalBooleanText('write helper:', gate.writeHelperEnabled);
  text += optionalBooleanText('release gate:', gate.releaseGateEnabled);
  if (gate.sourceRequestRelationship) {
    text += ` / ${gate.sourceRequestRelationship}`;
  }
  return text;
}

function optionalBooleanText(label, value) {
  return value === undefined ? '' : ` / ${label} ${String(value)}`;
}

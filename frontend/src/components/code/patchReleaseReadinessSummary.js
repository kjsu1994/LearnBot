export function buildPatchReleaseReadinessSummaryView(readiness = null) {
  if (!readiness) {
    return {
      show: false,
      headerText: '',
      message: '',
      stateText: '',
      prerequisiteLines: [],
    };
  }

  const prerequisites = Array.isArray(readiness.prerequisites) ? readiness.prerequisites : [];

  return {
    show: true,
    headerText: `Pre-apply release checklist: ${readiness.status || 'UNKNOWN'}`,
    message: readiness.message || '',
    stateText: patchReleaseReadinessStateText(readiness),
    prerequisiteLines: prerequisites.map(patchReleasePrerequisiteText),
  };
}

function patchReleaseReadinessStateText(readiness) {
  let text = `preconditions passed: ${String(readiness.preconditionsPassed)}`;
  text += optionalBooleanText('release gate:', readiness.releaseGateEnabled);
  text += optionalBooleanText('mutation enabled:', readiness.mutationEnabled);
  return text;
}

function patchReleasePrerequisiteText(item) {
  return `${item.passed ? 'pass' : 'blocked'} / ${item.key}: ${item.message}`;
}

function optionalBooleanText(label, value) {
  return value === undefined ? '' : ` / ${label} ${String(value)}`;
}

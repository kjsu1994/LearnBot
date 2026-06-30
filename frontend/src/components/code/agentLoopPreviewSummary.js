export function buildAgentLoopPreviewSummaryView(preview = null) {
  if (!preview) {
    return null;
  }
  const steps = Array.isArray(preview.steps) ? preview.steps : [];
  const stopConditions = Array.isArray(preview.stopConditions) ? preview.stopConditions : [];
  const warnings = Array.isArray(preview.warnings) ? preview.warnings : [];
  return {
    headerText: `agent loop preview: ${preview.status || 'PREVIEW_ONLY'} / max steps ${preview.maxSteps ?? 'UNKNOWN'} / timeout ${preview.timeoutSeconds ?? 'UNKNOWN'}s`,
    stateText: `agent loop state: mutation ${String(preview.mutationEnabled)} / timeline persistence ${String(preview.timelinePersistenceEnabled)} / cancellation ${String(preview.cancellationEnabled)}`,
    stepLines: steps.map(agentLoopStepText),
    stopLines: stopConditions.map((condition) => `${condition.key || 'UNKNOWN'}: ${condition.message || ''}`.trim()),
    warnings,
  };
}

function agentLoopStepText(step = {}) {
  let text = `${step.index ?? '?'} ${step.phase || 'UNKNOWN'}: ${step.action || ''}`.trim();
  if (step.executionTarget) {
    text += ` / ${step.executionTarget}`;
  }
  if (step.toolName) {
    text += ` / ${step.toolName}`;
  }
  text += ` / approval ${String(step.requiresApproval)}`;
  text += ` / may mutate ${String(step.mayMutate)}`;
  text += ` / enabled ${String(step.enabled)}`;
  if (step.stopOnFailure) {
    text += ` / stop: ${step.stopOnFailure}`;
  }
  return text;
}

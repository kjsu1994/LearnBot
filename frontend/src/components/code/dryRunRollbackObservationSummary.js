export function buildDryRunRollbackObservationSummaryView(observation = null) {
  if (!observation) {
    return {
      show: false,
      text: '',
    };
  }

  let text = `rollback would restore: ${String(observation.wouldRestore)}`;
  if (observation.restored !== undefined) {
    text += ` / restored: ${String(observation.restored)}`;
  }
  if (observation.tool) {
    text += ` / ${observation.tool}`;
  }
  if (observation.restoreScope) {
    text += ` / ${observation.restoreScope}`;
  }

  return {
    show: true,
    text,
  };
}

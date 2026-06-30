const READINESS_CHECK_KEYS_RENDERED_BY_DEDICATED_SUMMARIES = new Set([
  'snapshotManifestPreview',
  'rollbackRestorePreconditions',
]);

export function buildReadinessChecksSummaryView(readiness = null) {
  const checkRows = (readiness?.checks || [])
    .filter((check) => !READINESS_CHECK_KEYS_RENDERED_BY_DEDICATED_SUMMARIES.has(check.key))
    .map(checkLine);

  return {
    show: checkRows.length > 0,
    checkRows,
  };
}

function checkLine(check) {
  return {
    key: check.key,
    passed: Boolean(check.passed),
    headerText: `${check.passed ? 'pass' : 'blocked'} 쨌 ${check.key}`,
    message: check.message || '',
  };
}

export function buildWorkspaceVerificationSummaryView(verification = null) {
  if (!verification) {
    return {
      show: false,
      headerText: '',
      blockingText: '',
      reason: '',
      sourceText: '',
    };
  }

  return {
    show: true,
    headerText: `Effective workspace verification: ${verification.status || 'UNVERIFIED'}`,
    blockingText: verification.blocking === undefined
      ? ''
      : `blocking release: ${String(verification.blocking)}`,
    reason: verification.reason || '',
    sourceText: verification.source ? `source: ${verification.source}` : '',
  };
}

export function buildDryRunResultSummaryView({
  result = null,
  expectedDryRunRefusal = false,
} = {}) {
  if (!result) {
    return {
      show: false,
      titleText: '',
      errorText: '',
      failureText: '',
      releaseEvidenceText: '',
      preflightText: '',
      mutationText: '',
      snapshotText: '',
    };
  }

  return {
    show: true,
    titleText: expectedDryRunRefusal ? 'Dry-run completed; mutation refused as expected' : `Dry-run status: ${result.status}`,
    errorText: result.error || '',
    failureText: result.failureCode ? `${expectedDryRunRefusal ? 'safety gate' : 'failure'}: ${result.failureCode}` : '',
    releaseEvidenceText: releaseEvidenceText(result.input),
    preflightText: result.output?.preflightPassed !== undefined ? `preflight passed: ${String(result.output.preflightPassed)}` : '',
    mutationText: result.output?.mutationApplied !== undefined ? `mutation applied: ${String(result.output.mutationApplied)}` : '',
    snapshotText: result.output?.snapshotCreated !== undefined ? `snapshot created: ${String(result.output.snapshotCreated)}` : '',
  };
}

function releaseEvidenceText(input = null) {
  if (!input?.releaseAttemptId) {
    return '';
  }
  let text = `linked release evidence: attempt ${String(input.releaseAttemptId).slice(0, 8)}`;
  if (input.freshObservationOnly) {
    text += ' / fresh observation only';
  }
  return text;
}

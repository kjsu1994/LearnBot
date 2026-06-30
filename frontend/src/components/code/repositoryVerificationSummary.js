export function buildRepositoryVerificationSummaryView(verification = null) {
  if (!verification) {
    return {
      show: false,
      headerText: '',
      message: '',
      linkageText: '',
      checkLines: [],
    };
  }

  const checks = Array.isArray(verification.checks) ? verification.checks : [];

  return {
    show: true,
    headerText: `Recorded repository verification: ${verification.status || 'UNVERIFIED'}`,
    message: verification.message || '',
    linkageText: observationLinkageText(verification),
    checkLines: checks
      .filter((check) => check.status !== 'SKIPPED')
      .map(repositoryVerificationCheckText),
  };
}

function repositoryVerificationCheckText(check) {
  let text = `${check.key}: ${check.status}`;
  text += check.expected ? ` / indexed ${String(check.expected).slice(0, 48)}` : ' / indexed unknown';
  text += check.actual ? ` / local ${String(check.actual).slice(0, 48)}` : ' / local unknown';
  return text;
}

function observationLinkageText(value) {
  const linkage = value?.observationLinkage;
  if (!linkage?.status) {
    return '';
  }
  const parts = [`observation linkage: ${linkage.status}`];
  if (linkage.releaseAttemptLinked !== undefined) {
    parts.push(`release-attempt linked: ${String(linkage.releaseAttemptLinked)}`);
  }
  if (linkage.sourceOnlyFallback !== undefined) {
    parts.push(`source-only fallback: ${String(linkage.sourceOnlyFallback)}`);
  }
  if (linkage.releaseAttemptId) {
    parts.push(`attempt ${String(linkage.releaseAttemptId).slice(0, 8)}`);
  }
  if (linkage.sourceRequestId) {
    parts.push(`source ${String(linkage.sourceRequestId).slice(0, 8)}`);
  }
  return parts.join(' / ');
}

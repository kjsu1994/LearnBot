export function buildReleaseAttemptModelSummaryView({
  preReleaseRevalidation = null,
  releaseAttemptModel = null,
} = {}) {
  return {
    showPreReleaseRevalidation: Boolean(preReleaseRevalidation),
    preReleaseRevalidationText: preReleaseRevalidation
      ? preReleaseRevalidationText(preReleaseRevalidation)
      : '',
    showReleaseAttemptModel: Boolean(releaseAttemptModel),
    releaseAttemptModelText: releaseAttemptModel
      ? releaseAttemptModelText(releaseAttemptModel)
      : '',
  };
}

function preReleaseRevalidationText(revalidation) {
  let text = `pre-release revalidation: ${revalidation.status || 'UNKNOWN'}`;
  text += optionalBooleanText('passed:', revalidation.passed);
  text += optionalBooleanText('fresh dry-run:', revalidation.requiresFreshDryRunAfterReleaseAttempt);
  text += optionalBooleanText('fresh repo check:', revalidation.requiresFreshRepositoryVerificationAfterReleaseAttempt);
  return text;
}

function releaseAttemptModelText(model) {
  let text = `release attempt model: ${model.status || 'UNKNOWN'}`;
  if (model.schema) {
    text += ` / ${model.schema}`;
  }
  if (model.staleWindowSeconds !== undefined) {
    text += ` / stale window ${model.staleWindowSeconds}s`;
  }
  if (model.requiredEvidence?.length) {
    text += ` / evidence ${model.requiredEvidence.length}`;
  }
  return text;
}

function optionalBooleanText(label, value) {
  return value === undefined ? '' : ` / ${label} ${String(value)}`;
}

export async function inspectValidatedDryRunIntentEligibility({
  request,
  run,
  setEligibility = () => {},
  requestId = null,
  eligibilityRoute = '',
}) {
  const safeRequestId = requestId || requestIdFromEligibilityRoute(eligibilityRoute);
  if (!safeRequestId) return null;
  return await run(`code-agent-validated-dry-run-intent-eligibility-${safeRequestId}`, async () => {
    const result = await request(`/api/code-agent/local-patch-request/dry-run-intent/${safeRequestId}/eligibility`);
    setEligibility(result);
    return result;
  });
}

export function requestIdFromEligibilityRoute(route = '') {
  const text = String(route || '').trim();
  const match = text.match(/\/api\/code-agent\/local-patch-request\/dry-run-intent\/([^/\s]+)\/eligibility\b/);
  return match ? decodeURIComponent(match[1]) : '';
}

import { requestIdFromEligibilityRoute } from './validatedDryRunIntentEligibilityClient.js';

export async function previewValidatedDryRunIntentTransition({
  request,
  run,
  setTransitionPreview = () => {},
  requestId = null,
  eligibilityRoute = '',
  transitionRoute = '',
}) {
  const safeRequestId = requestId
    || requestIdFromClaimableDryRunPreviewRoute(transitionRoute)
    || requestIdFromEligibilityRoute(eligibilityRoute);
  if (!safeRequestId) return null;
  return await run(`code-agent-validated-dry-run-intent-transition-${safeRequestId}`, async () => {
    const result = await request(`/api/code-agent/local-patch-request/dry-run-intent/${safeRequestId}/claimable-dry-run-preview`);
    setTransitionPreview(result);
    return result;
  });
}

export function requestIdFromClaimableDryRunPreviewRoute(route = '') {
  const text = String(route || '').trim();
  const match = text.match(/\/api\/code-agent\/local-patch-request\/dry-run-intent\/([^/\s]+)\/claimable-dry-run-preview\b/);
  return match ? decodeURIComponent(match[1]) : '';
}

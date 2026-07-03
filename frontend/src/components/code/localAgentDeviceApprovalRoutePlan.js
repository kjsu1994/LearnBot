function buildLocalAgentDeviceApprovalRoutePlan(routePath = '') {
  const routeMatched = routePath === '/settings/local-agent/device';
  return {
    schema: 'learnbot.web.local-agent.device-approval-route-plan.v1',
    status: routeMatched ? 'READY_FOR_BROWSER_APPROVAL' : 'NOT_SELECTED',
    routePath: '/settings/local-agent/device',
    routeMatched,
    browserApprovalEnabled: routeMatched,
    deviceCodeValidationEnabled: routeMatched,
    pendingSessionLookupEnabled: routeMatched,
    sessionClaimEnabled: routeMatched,
    accessTokenIssued: false,
    refreshTokenIssued: false,
    cookiePersistenceEnabled: false,
    localAgentTokenAccepted: false,
    tokenSecretPrinted: false,
    deviceCodeSecretPrinted: false,
    cliFollowUpCommands: [
      'learnbot login',
      'learnbot session status',
      'learnbot pair --workspace <path> --transport auto',
    ],
    serverEndpoints: [
      'POST /api/auth/cli-device-session/create',
      'POST /api/auth/cli-device-session/claim',
      'POST /api/auth/cli-device-session/claim-result',
    ],
    blockers: [],
    reason: 'This route approves a pending CLI device session for the current logged-in browser user. It never accepts Local Agent pairing credentials or prints token secrets.',
  };
}

function buildLocalAgentDeviceApprovalRouteSummary(plan) {
  if (!plan?.routeMatched) {
    return '';
  }
  return [
    `local agent device approval route: ${plan.status}`,
    plan.schema,
    `route ${plan.routePath}`,
    `browser approval ${plan.browserApprovalEnabled}`,
    `device-code validation ${plan.deviceCodeValidationEnabled}`,
    `session claim ${plan.sessionClaimEnabled}`,
    `access token ${plan.accessTokenIssued}`,
    `refresh token ${plan.refreshTokenIssued}`,
    `cookie persistence ${plan.cookiePersistenceEnabled}`,
    `local agent token ${plan.localAgentTokenAccepted}`,
    `token secret printed ${plan.tokenSecretPrinted}`,
    `next ${plan.cliFollowUpCommands.join(' / ')}`,
  ].join(' / ');
}

export {
  buildLocalAgentDeviceApprovalRoutePlan,
  buildLocalAgentDeviceApprovalRouteSummary,
};

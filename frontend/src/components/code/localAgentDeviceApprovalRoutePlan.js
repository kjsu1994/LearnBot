function buildLocalAgentDeviceApprovalRoutePlan(routePath = '') {
  const routeMatched = routePath === '/settings/local-agent/device';
  return {
    schema: 'learnbot.web.local-agent.device-approval-route-plan.v1',
    status: routeMatched ? 'DISABLED_PREVIEW' : 'NOT_SELECTED',
    routePath: '/settings/local-agent/device',
    routeMatched,
    browserApprovalEnabled: false,
    deviceCodeValidationEnabled: false,
    pendingSessionLookupEnabled: false,
    sessionClaimEnabled: false,
    accessTokenIssued: false,
    refreshTokenIssued: false,
    cookiePersistenceEnabled: false,
    localAgentTokenAccepted: false,
    tokenSecretPrinted: false,
    deviceCodeSecretPrinted: false,
    cliFollowUpCommands: [
      'learnbot session create-plan',
      'learnbot session claim-plan --device-code <device-code>',
      'learnbot session status',
    ],
    serverEndpoints: [
      'POST /api/auth/cli-device-session/create/plan',
      'POST /api/auth/cli-device-session/claim/plan',
    ],
    blockers: [
      'Browser approval is disabled until pending device sessions, user-code validation, and encrypted CLI session storage are implemented.',
    ],
    reason: 'This route is a disabled preview for the future browser approval screen. It does not approve devices, issue tokens, persist cookies, accept Local Agent credentials, or print secrets.',
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

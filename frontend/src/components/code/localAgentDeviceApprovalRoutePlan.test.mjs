import assert from 'node:assert/strict';
import {
  buildLocalAgentDeviceApprovalRoutePlan,
  buildLocalAgentDeviceApprovalRouteSummary,
} from './localAgentDeviceApprovalRoutePlan.js';

const inactive = buildLocalAgentDeviceApprovalRoutePlan('/code');
assert.equal(inactive.status, 'NOT_SELECTED');
assert.equal(inactive.routeMatched, false);
assert.equal(buildLocalAgentDeviceApprovalRouteSummary(inactive), '');

const plan = buildLocalAgentDeviceApprovalRoutePlan('/settings/local-agent/device');
const summary = buildLocalAgentDeviceApprovalRouteSummary(plan);

assert.equal(plan.schema, 'learnbot.web.local-agent.device-approval-route-plan.v1');
assert.equal(plan.status, 'DISABLED_PREVIEW');
assert.equal(plan.routeMatched, true);
assert.equal(plan.browserApprovalEnabled, false);
assert.equal(plan.deviceCodeValidationEnabled, false);
assert.equal(plan.pendingSessionLookupEnabled, false);
assert.equal(plan.sessionClaimEnabled, false);
assert.equal(plan.accessTokenIssued, false);
assert.equal(plan.refreshTokenIssued, false);
assert.equal(plan.cookiePersistenceEnabled, false);
assert.equal(plan.localAgentTokenAccepted, false);
assert.equal(plan.tokenSecretPrinted, false);
assert.equal(plan.deviceCodeSecretPrinted, false);
assert.ok(plan.cliFollowUpCommands.includes('learnbot session create-plan'));
assert.ok(plan.cliFollowUpCommands.includes('learnbot session claim-plan --device-code <device-code>'));
assert.ok(plan.serverEndpoints.includes('POST /api/auth/cli-device-session/create/plan'));
assert.ok(plan.serverEndpoints.includes('POST /api/auth/cli-device-session/claim/plan'));
assert.match(summary, /local agent device approval route: DISABLED_PREVIEW/);
assert.match(summary, /browser approval false/);
assert.match(summary, /session claim false/);
assert.match(summary, /token secret printed false/);
assert.doesNotMatch(JSON.stringify(plan), /secret-device-code|secret-token|local-agent-token/);

console.log('local-agent-device-approval-route-plan-ok');

import assert from 'node:assert/strict';
import { buildValidatedDryRunIntentEligibilityView } from './validatedDryRunIntentEligibilitySummary.js';

const view = buildValidatedDryRunIntentEligibilityView({
  schema: 'learnbot.server.validated-revised-patch-dry-run-eligibility.v1',
  status: 'READY_DRY_RUN_RELEASE_DISABLED',
  requestId: 'request-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  toolName: 'patch.apply',
  executionTarget: 'USER_LOCAL_AGENT',
  approvalState: 'REQUIRED',
  requestStatus: 'APPROVAL_REQUIRED',
  validatedDryRunIntent: true,
  dryRunIntentPersisted: true,
  requestPersisted: true,
  requestCreationEnabled: false,
  queueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  claimable: false,
  dryRunOnly: true,
  mutationAllowed: false,
  approvalBypassAllowed: false,
  prerequisitesPassed: true,
  blockingKeys: [],
  targetFiles: ['README.md'],
  checks: [
    { key: 'patchApplyTool', passed: true, message: 'Persisted intent must be a patch.apply Local Agent request.' },
    { key: 'mutationDisabled', passed: true, message: 'Mutation must remain disabled for this dry-run intent.' },
  ],
  futureDryRunReleaseGate: {
    schema: 'learnbot.server.validated-revised-patch-dry-run-release-gate.v1',
    status: 'READY_RELEASE_DISABLED',
    prerequisitesPassed: true,
    requestCreationEnabled: false,
    queueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    dryRunOnly: true,
    mutationAllowed: false,
    approvalBypassAllowed: false,
    message: 'Validated dry-run intent prerequisites are visible, but release to a claimable Local Agent dry-run remains disabled.',
  },
  message: 'This is a disabled eligibility read model only; it creates no request, pushes nothing, and makes no Local Agent work claimable.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'validated dry-run intent eligibility: READY_DRY_RUN_RELEASE_DISABLED / learnbot.server.validated-revised-patch-dry-run-eligibility.v1'
);
assert.equal(
  view.identityText,
  'request request-1 / session session-1 / agent agent-1 / workspace workspace-1 / tool patch.apply / target USER_LOCAL_AGENT / approval REQUIRED / request status APPROVAL_REQUIRED'
);
assert.equal(
  view.stateText,
  'validated dry-run intent true / persisted true / request persisted true / prerequisites true'
);
assert.equal(
  view.disabledText,
  'validated dry-run controls disabled: request creation false / queue false / push false / claim false / claimable false / dry-run only true / mutation false / approval bypass false'
);
assert.equal(
  view.gateText,
  'future dry-run release gate: READY_RELEASE_DISABLED / prerequisites true / request creation false / queue false / push false / claim false / claimable false / dry-run only true / mutation false / approval bypass false'
);
assert.equal(view.targetFilesText, 'validated dry-run target files: README.md');
assert.equal(view.blockingText, '');
assert.deepEqual(view.checkLines, [
  'patchApplyTool true / Persisted intent must be a patch.apply Local Agent request.',
  'mutationDisabled true / Mutation must remain disabled for this dry-run intent.',
]);
assert.match(view.message, /disabled eligibility read model/);
assert.equal(buildValidatedDryRunIntentEligibilityView(null).show, false);

console.log('validatedDryRunIntentEligibilitySummary view tests passed');

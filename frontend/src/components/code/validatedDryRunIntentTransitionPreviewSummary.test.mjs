import assert from 'node:assert/strict';
import { buildValidatedDryRunIntentTransitionPreviewView } from './validatedDryRunIntentTransitionPreviewSummary.js';

assert.equal(buildValidatedDryRunIntentTransitionPreviewView(null).show, false);

const view = buildValidatedDryRunIntentTransitionPreviewView({
  schema: 'learnbot.server.validated-revised-patch-dry-run-transition-preview.v1',
  status: 'READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED',
  sourceRequestId: 'dry-run-intent-1',
  sessionId: 'session-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  prerequisitesPassed: true,
  requestPersisted: false,
  requestCreationEnabled: false,
  queueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  claimable: false,
  dryRunOnly: true,
  mutationAllowed: false,
  approvalBypassAllowed: false,
  transitionGate: {
    schema: 'learnbot.server.validated-revised-patch-dry-run-transition-gate.v1',
    status: 'READY_TRANSITION_DISABLED',
    prerequisitesPassed: true,
    requestCreationEnabled: false,
    queueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    dryRunOnly: true,
    mutationAllowed: false,
    approvalBypassAllowed: false,
  },
  eligibility: {
    schema: 'learnbot.server.validated-revised-patch-dry-run-eligibility.v1',
    status: 'READY_DRY_RUN_RELEASE_DISABLED',
    prerequisitesPassed: true,
  },
  wouldBeClaimableDryRunRequest: {
    schema: 'learnbot.server.validated-revised-patch-claimable-dry-run-request-preview.v1',
    status: 'READY_REQUEST_PREVIEW_ONLY',
    sourceRequestId: 'dry-run-intent-1',
    toolName: 'patch.apply',
    approvalState: 'NOT_REQUIRED',
    executionTarget: 'USER_LOCAL_AGENT',
    requestPersisted: false,
    requestCreationEnabled: false,
    queueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    claimable: false,
    dryRunOnly: true,
    mutationAllowed: false,
    approvalBypassAllowed: false,
    input: {
      targetFiles: ['README.md'],
    },
  },
  message: 'Preview only.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'validated dry-run transition preview: READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED / learnbot.server.validated-revised-patch-dry-run-transition-preview.v1'
);
assert.match(view.identityText, /source intent dry-run-intent-1/);
assert.match(view.disabledText, /request creation false \/ request persisted false \/ queue false \/ push false \/ claim false \/ claimable false \/ dry-run only true \/ mutation false \/ approval bypass false/);
assert.match(view.gateText, /validated dry-run transition gate: READY_TRANSITION_DISABLED \/ prerequisites true/);
assert.match(view.eligibilityText, /validated dry-run transition eligibility: READY_DRY_RUN_RELEASE_DISABLED \/ prerequisites true/);
assert.match(view.wouldBeText, /would-be claimable dry-run request: READY_REQUEST_PREVIEW_ONLY/);
assert.match(view.wouldBeDisabledText, /would-be dry-run request controls disabled: request creation false \/ request persisted false \/ queue false \/ push false \/ claim false \/ claimable false \/ dry-run only true \/ mutation false \/ approval bypass false/);
assert.equal(view.targetFilesText, 'would-be dry-run target files: README.md');
assert.equal(view.message, 'Preview only.');

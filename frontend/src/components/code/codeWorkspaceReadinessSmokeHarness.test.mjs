import assert from 'node:assert/strict';
import { buildCodeWorkspaceReadinessSmokeProps } from './codeWorkspaceReadinessSmokeHarness.mjs';
import { assertNoForbiddenTrueFlags } from './mutationDisabledFlagGuard.js';

const latestAttempt = {
  mutationFinalAnswerDeliveryGate: {
    status: 'REFUSED_FINAL_ANSWER_DELIVERY_DISABLED',
    finalAnswerDeliveryEnabled: false,
    mutationAllowed: false,
  },
};

const props = buildCodeWorkspaceReadinessSmokeProps({
  requestId: 'request-1',
  latestAttempt,
});

assert.equal(props.selectedRepositoryId, 'repo-1');
assert.equal(props.codeAgentLocalPatchRequest.requestId, 'request-1');
assert.equal(props.codeAgentLocalPatchRequest.status, 'APPROVED_HELD');
assert.equal(props.codeAgentLocalPatchRequest.toolName, 'patch.apply');
assert.equal(props.codeAgentLocalPatchRequest.approvalState, 'APPROVED');
assert.equal(props.codeAgentLocalPatchReadiness.requestId, 'request-1');
assert.equal(props.codeAgentLocalPatchReadiness.readyToRelease, false);
assert.equal(props.codeAgentLocalPatchReadiness.patchExecutionGate.status, 'BLOCKED_RELEASE_DISABLED');
assert.equal(props.codeAgentLocalPatchReadiness.patchExecutionGate.claimEnabled, false);
assert.equal(props.codeAgentLocalPatchReadiness.patchExecutionGate.releaseGateEnabled, false);
assert.equal(props.codeAgentLocalPatchReadiness.releaseAttemptModel.status, 'READY_RELEASE_ATTEMPT_DISABLED');
assert.equal(props.codeAgentLocalPatchReadiness.releaseAttemptModel.latestAttempt, latestAttempt);
assert.equal(assertNoForbiddenTrueFlags(props, 'props'), true);

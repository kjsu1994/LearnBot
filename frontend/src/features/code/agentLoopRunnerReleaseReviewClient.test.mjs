import assert from 'node:assert/strict';
import { reviewAgentLoopRunnerReleaseGate } from './agentLoopRunnerReleaseReviewClient.js';

let stored = 'stale';
let requestCall = null;
const result = await reviewAgentLoopRunnerReleaseGate({
  request: async (path, options) => {
    requestCall = { path, options };
    return {
      runnerDecision: 'RELEASE_REVIEW_REFUSED_GATE_DISABLED',
      requestCreationEnabled: false,
      enqueueEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      mutationEnabled: false,
      boundary: {
        status: 'RELEASE_REFUSED_GATE_DISABLED',
        releaseGateEnabled: false,
        claimEnabled: false,
        claimable: false,
        mutationAllowed: false,
      },
    };
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-runner-release-review');
    return await task();
  },
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  setReleaseReviewResult: (value) => {
    stored = value;
  },
});

assert.deepEqual(requestCall, {
  path: '/api/code-agent/loop/runner/release-review',
  options: {
    method: 'POST',
    json: {
      repositoryId: 'repo-1',
      loopId: 'loop-1',
      agentId: 'agent-1',
      workspaceId: 'workspace-1',
    },
  },
});
assert.equal(result.runnerDecision, 'RELEASE_REVIEW_REFUSED_GATE_DISABLED');
assert.equal(result.claimEnabled, false);
assert.equal(result.mutationEnabled, false);
assert.equal(result.boundary.releaseGateEnabled, false);
assert.equal(stored, result);

stored = 'stale';
requestCall = null;
const missing = await reviewAgentLoopRunnerReleaseGate({
  request: async () => {
    throw new Error('request should not run');
  },
  run: async () => {
    throw new Error('run should not run');
  },
  repositoryId: '',
  loopId: 'loop-1',
  setReleaseReviewResult: (value) => {
    stored = value;
  },
});
assert.equal(missing, null);
assert.equal(stored, null);
assert.equal(requestCall, null);

stored = 'stale';
const failed = await reviewAgentLoopRunnerReleaseGate({
  request: async () => {
    throw new Error('request should not run when run returns false');
  },
  run: async () => false,
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  setReleaseReviewResult: (value) => {
    stored = value;
  },
});
assert.equal(failed, null);
assert.equal(stored, null);

console.log('agentLoopRunnerReleaseReviewClient tests passed');

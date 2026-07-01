import assert from 'node:assert/strict';
import { previewAgentLoopRunner } from './agentLoopRunnerPreviewClient.js';

const calls = [];
const previews = [];
const enqueueResults = ['stale'];
const response = {
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  handoffSummary: {
    status: 'READY_HANDOFF_CREATION_DISABLED',
    expectedRequestCount: 4,
    durableMutationExecutionRowCount: 0,
  },
};

const result = await previewAgentLoopRunner({
  request: async (path, options) => {
    calls.push({ path, options });
    return response;
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-runner-preview');
    return await task();
  },
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  setPreview: (value) => previews.push(value),
  setEnqueueResult: (value) => enqueueResults.push(value),
});

assert.equal(result, response);
assert.deepEqual(calls, [
  {
    path: '/api/code-agent/loop/runner/preview',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        loopId: 'loop-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
      },
    },
  },
]);
assert.equal(previews.at(-1), response);
assert.equal(enqueueResults.at(-1), null);
assert.equal(calls.some((call) => call.path.includes('enqueue-read-only')), false);
assert.equal(response.requestCreationEnabled, false);
assert.equal(response.enqueueEnabled, false);
assert.equal(response.pushEnabled, false);
assert.equal(response.claimEnabled, false);
assert.equal(response.mutationEnabled, false);

const missing = await previewAgentLoopRunner({
  request: async () => {
    throw new Error('should not call request');
  },
  run: async () => {
    throw new Error('should not run');
  },
  repositoryId: '',
  loopId: 'loop-1',
  setPreview: (value) => previews.push(value),
  setEnqueueResult: (value) => enqueueResults.push(value),
});
assert.equal(missing, null);
assert.equal(previews.at(-1), null);
assert.equal(enqueueResults.at(-1), null);

const failed = await previewAgentLoopRunner({
  request: async () => {
    throw new Error('backend unavailable');
  },
  run: async () => false,
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  setPreview: (value) => previews.push(value),
  setEnqueueResult: (value) => enqueueResults.push(value),
});
assert.equal(failed, null);
assert.equal(previews.at(-1), null);
assert.equal(enqueueResults.at(-1), null);

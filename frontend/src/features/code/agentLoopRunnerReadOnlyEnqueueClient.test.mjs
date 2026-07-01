import assert from 'node:assert/strict';
import { enqueueAgentLoopRunnerReadOnly } from './agentLoopRunnerReadOnlyEnqueueClient.js';

const calls = [];
const enqueueResults = [];
const response = {
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'NOT_ENQUEUED',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  queuedRequest: null,
  handoffSummary: {
    status: 'READY_HANDOFF_CREATION_DISABLED',
    expectedRequestCount: 4,
    durableMutationExecutionRowCount: 0,
  },
  preview: {
    runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
    handoffSummary: {
      status: 'READY_HANDOFF_CREATION_DISABLED',
    },
  },
};

const result = await enqueueAgentLoopRunnerReadOnly({
  request: async (path, options) => {
    calls.push({ path, options });
    return response;
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-runner-enqueue-read-only');
    return await task();
  },
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  setEnqueueResult: (value) => enqueueResults.push(value),
});

assert.equal(result, response);
assert.deepEqual(calls, [
  {
    path: '/api/code-agent/loop/runner/enqueue-read-only',
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
assert.equal(enqueueResults.at(-1), response);
assert.equal(response.runnerDecision, 'NOT_ENQUEUED');
assert.equal(response.queuedRequest, null);
assert.equal(response.requestCreationEnabled, false);
assert.equal(response.enqueueEnabled, false);
assert.equal(response.pushEnabled, false);
assert.equal(response.claimEnabled, false);
assert.equal(response.mutationEnabled, false);

const missing = await enqueueAgentLoopRunnerReadOnly({
  request: async () => {
    throw new Error('should not call request');
  },
  run: async () => {
    throw new Error('should not run');
  },
  repositoryId: '',
  loopId: 'loop-1',
  setEnqueueResult: (value) => enqueueResults.push(value),
});
assert.equal(missing, null);
assert.equal(enqueueResults.at(-1), null);

const failed = await enqueueAgentLoopRunnerReadOnly({
  request: async () => {
    throw new Error('backend unavailable');
  },
  run: async () => false,
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  setEnqueueResult: (value) => enqueueResults.push(value),
});
assert.equal(failed, null);
assert.equal(enqueueResults.at(-1), null);

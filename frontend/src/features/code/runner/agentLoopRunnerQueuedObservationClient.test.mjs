import assert from 'node:assert/strict';
import { refreshAgentLoopRunnerQueuedObservation } from './agentLoopRunnerQueuedObservationClient.js';

const calls = [];
const observations = [];
const response = {
  requestId: 'request-1',
  executionTarget: 'USER_LOCAL_AGENT',
  toolName: 'git.status',
  approvalState: 'NOT_REQUIRED',
  status: 'SUCCEEDED',
  input: {
    mutationAllowed: false,
    freshObservationOnly: true,
  },
  output: {
    repositoryVerification: {
      status: 'MATCH',
    },
  },
  responseWarnings: [],
};

const result = await refreshAgentLoopRunnerQueuedObservation({
  request: async (path) => {
    calls.push(path);
    return response;
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-runner-queued-observation-request-1');
    return await task();
  },
  requestId: 'request-1',
  setObservationResult: (value) => observations.push(value),
});

assert.equal(result, response);
assert.deepEqual(calls, ['/api/local-agents/tools/request-1']);
assert.equal(observations.at(-1), response);
assert.equal(response.status, 'SUCCEEDED');
assert.equal(response.toolName, 'git.status');
assert.equal(response.input.mutationAllowed, false);

const missing = await refreshAgentLoopRunnerQueuedObservation({
  request: async () => {
    throw new Error('should not call request');
  },
  run: async () => {
    throw new Error('should not run');
  },
  requestId: '',
  setObservationResult: (value) => observations.push(value),
});
assert.equal(missing, null);
assert.equal(observations.at(-1), null);

const failed = await refreshAgentLoopRunnerQueuedObservation({
  request: async () => {
    throw new Error('backend unavailable');
  },
  run: async () => false,
  requestId: 'request-2',
  setObservationResult: (value) => observations.push(value),
});
assert.equal(failed, null);
assert.equal(observations.at(-1), null);

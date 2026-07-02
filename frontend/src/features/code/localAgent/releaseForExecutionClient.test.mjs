import assert from 'node:assert/strict';
import { releaseLocalAgentPatchForExecution } from './releaseForExecutionClient.js';

let storedPatch = 'stale';
let storedInspection = 'stale-inspection';
let requestCall = null;
const result = await releaseLocalAgentPatchForExecution({
  request: async (path, options) => {
    requestCall = { path, options };
    return {
      requestId: 'request-1',
      status: 'APPROVED',
      input: {
        mutationAllowed: true,
        dryRunOnly: false,
        releaseAttemptId: 'attempt-1',
      },
    };
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-local-release-for-execution-request-1');
    return await task();
  },
  requestId: 'request-1',
  setPatchRequest: (value) => {
    storedPatch = value;
  },
  setInspection: (value) => {
    storedInspection = value;
  },
});

assert.deepEqual(requestCall, {
  path: '/api/local-agents/tools/request-1/release-for-execution',
  options: { method: 'POST' },
});
assert.equal(result.status, 'APPROVED');
assert.equal(result.input.mutationAllowed, true);
assert.equal(storedPatch, result);
assert.equal(storedInspection, null);

storedPatch = 'stale';
requestCall = null;
const missing = await releaseLocalAgentPatchForExecution({
  request: async () => {
    throw new Error('request should not run');
  },
  run: async () => {
    throw new Error('run should not run');
  },
  requestId: '',
  setPatchRequest: (value) => {
    storedPatch = value;
  },
});
assert.equal(missing, null);
assert.equal(storedPatch, 'stale');
assert.equal(requestCall, null);

console.log('releaseForExecutionClient tests passed');

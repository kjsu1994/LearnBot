import assert from 'node:assert/strict';
import { inspectApprovedExecutionFlow } from './approvedExecutionFlowInspectionClient.js';

const calls = [];
let stored = null;
const result = await inspectApprovedExecutionFlow({
  requestIds: ['patch-1', '', null, 'command-1'],
  setInspection: (value) => {
    stored = value;
  },
  run: async (key, task) => {
    calls.push({ type: 'run', key });
    return await task();
  },
  request: async (path, options) => {
    calls.push({ type: 'request', path, options });
    return {
      schema: 'learnbot.local-agent.approved-execution-flow-contract.v1',
      requestIds: options.json.requestIds,
      readModelOnly: true,
    };
  },
});

assert.deepEqual(result, {
  schema: 'learnbot.local-agent.approved-execution-flow-contract.v1',
  requestIds: ['patch-1', 'command-1'],
  readModelOnly: true,
});
assert.deepEqual(stored, result);
assert.deepEqual(calls, [
  {
    type: 'run',
    key: 'code-agent-approved-execution-flow-inspection-patch-1-command-1',
  },
  {
    type: 'request',
    path: '/api/local-agents/tools/approved-execution-flow/inspection',
    options: {
      method: 'POST',
      json: {
        requestIds: ['patch-1', 'command-1'],
      },
    },
  },
]);

let called = false;
const empty = await inspectApprovedExecutionFlow({
  requestIds: [],
  run: async () => {
    called = true;
  },
  request: async () => {
    called = true;
  },
});
assert.equal(empty, null);
assert.equal(called, false);

let releaseAttemptRequestCall = null;
const releaseAttemptResult = await inspectApprovedExecutionFlow({
  requestIds: ['ignored-patch-id'],
  releaseAttemptId: 'attempt-1',
  request: async (path, options) => {
    releaseAttemptRequestCall = { path, options };
    return {
      requestIdSource: 'durableCompletedRows',
      releaseAttemptId: options.json.releaseAttemptId,
      requestIds: ['patch-1', 'command-1', 'status-1', 'rollback-1'],
    };
  },
  run: async (key, task) => ({ key, value: await task() }),
  setInspection: (value) => {
    assert.equal(value.requestIdSource, 'durableCompletedRows');
  },
});

assert.equal(releaseAttemptResult.key, 'code-agent-approved-execution-flow-inspection-attempt-1');
assert.equal(releaseAttemptResult.value.releaseAttemptId, 'attempt-1');
assert.equal(releaseAttemptRequestCall.path, '/api/local-agents/tools/approved-execution-flow/inspection/by-release-attempt');
assert.deepEqual(releaseAttemptRequestCall.options, {
  method: 'POST',
  json: { releaseAttemptId: 'attempt-1' },
});

console.log('approvedExecutionFlowInspectionClient tests passed');

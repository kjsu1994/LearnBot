import assert from 'node:assert/strict';
import { enqueueAgentLoopRunnerSelectedReadOnly } from './agentLoopRunnerSelectedReadOnlyClient.js';

const calls = [];
const enqueueResults = [];
const response = {
  status: 'RECORDED',
  actionKey: 'QUEUE_READ_ONLY_OBSERVATION',
  runnerDecision: 'ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION',
  reason: 'Queued the model-selected read-only Local Agent git.status observation. Mutation remains disabled.',
  modelToolSelectionAttempted: true,
  modelToolSelectionAccepted: true,
  selectedByModel: true,
  requestCreationEnabled: true,
  enqueueEnabled: true,
  pushEnabled: true,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  selection: {
    runnerDecision: 'MODEL_SELECTED_READ_ONLY_OBSERVATION',
    candidate: {
      toolName: 'git.status',
      readOnly: true,
      sideEffectful: false,
      mutationAllowed: false,
      enqueueEnabled: true,
    },
  },
  queuedRequest: {
    requestId: 'request-1',
    request: {
      toolName: 'git.status',
      approvalState: 'NOT_REQUIRED',
      input: {
        mutationAllowed: false,
        freshObservationOnly: true,
      },
    },
  },
};

const result = await enqueueAgentLoopRunnerSelectedReadOnly({
  request: async (path, options) => {
    calls.push({ path, options });
    return response;
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-runner-enqueue-selected-read-only');
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
    path: '/api/code-agent/loop/runner/enqueue-selected-read-only',
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
assert.equal(response.runnerDecision, 'ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION');
assert.equal(response.modelToolSelectionAccepted, true);
assert.equal(response.selectedByModel, true);
assert.equal(response.queuedRequest.request.toolName, 'git.status');
assert.equal(response.queuedRequest.request.input.mutationAllowed, false);
assert.equal(response.requestCreationEnabled, true);
assert.equal(response.enqueueEnabled, true);
assert.equal(response.pushEnabled, true);
assert.equal(response.claimEnabled, false);
assert.equal(response.mutationEnabled, false);
assert.equal(response.finalResultEnabled, false);
assert.equal(response.publicationEnabled, false);
assert.equal(response.acknowledgementEnabled, false);

const missing = await enqueueAgentLoopRunnerSelectedReadOnly({
  request: async () => {
    throw new Error('should not call request');
  },
  run: async () => {
    throw new Error('should not run');
  },
  repositoryId: 'repo-1',
  loopId: '',
  setEnqueueResult: (value) => enqueueResults.push(value),
});
assert.equal(missing, null);
assert.equal(enqueueResults.at(-1), null);

const failed = await enqueueAgentLoopRunnerSelectedReadOnly({
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

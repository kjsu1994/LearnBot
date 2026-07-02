import assert from 'node:assert/strict';
import { continueAgentLoopRunnerAfterObservation } from './agentLoopRunnerObservationContinuationClient.js';

const calls = [];
const continuations = [];
const response = {
  requestId: 'request-1',
  status: 'SUCCEEDED',
  continuationDecision: 'NEXT_MODEL_TOOL_PREVIEW_READY',
  reason: 'The read-only Local Agent observation succeeded; the next model tool-selection preview is ready without enqueueing or mutation.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  mutationEnabled: false,
  iterationCount: 1,
  maxIterations: 6,
  remainingIterations: 5,
  iterationLimitReached: false,
  toolSelectionPreview: {
    selectionDecision: 'MODEL_SELECTED_READ_ONLY_CANDIDATE',
    candidate: {
      toolName: 'git.status',
      mutationAllowed: false,
    },
  },
};

const result = await continueAgentLoopRunnerAfterObservation({
  request: async (path, options) => {
    calls.push({ path, options });
    return response;
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-runner-observation-continuation-request-1');
    return await task();
  },
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  requestId: 'request-1',
  setContinuation: (value) => continuations.push(value),
});

assert.equal(result, response);
assert.deepEqual(calls, [
  {
    path: '/api/code-agent/loop/runner/continue-after-observation',
    options: {
      method: 'POST',
      json: {
        repositoryId: 'repo-1',
        loopId: 'loop-1',
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        requestId: 'request-1',
      },
    },
  },
]);
assert.equal(continuations.at(-1), response);
assert.equal(response.continuationDecision, 'NEXT_MODEL_TOOL_PREVIEW_READY');
assert.equal(response.requestCreationEnabled, false);
assert.equal(response.enqueueEnabled, false);
assert.equal(response.mutationEnabled, false);
assert.equal(response.iterationCount, 1);
assert.equal(response.maxIterations, 6);
assert.equal(response.remainingIterations, 5);
assert.equal(response.iterationLimitReached, false);

const missing = await continueAgentLoopRunnerAfterObservation({
  request: async () => {
    throw new Error('should not call request');
  },
  run: async () => {
    throw new Error('should not run');
  },
  repositoryId: 'repo-1',
  requestId: '',
  setContinuation: (value) => continuations.push(value),
});
assert.equal(missing, null);
assert.equal(continuations.at(-1), null);

const failed = await continueAgentLoopRunnerAfterObservation({
  request: async () => {
    throw new Error('backend unavailable');
  },
  run: async () => false,
  repositoryId: 'repo-1',
  requestId: 'request-2',
  setContinuation: (value) => continuations.push(value),
});
assert.equal(failed, null);
assert.equal(continuations.at(-1), null);

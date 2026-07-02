import assert from 'node:assert/strict';
import { previewAgentLoopRunnerToolSelection } from './agentLoopRunnerToolSelectionPreviewClient.js';

const calls = [];
const previews = [];
const response = {
  status: 'RECORDED',
  actionKey: 'QUEUE_READ_ONLY_OBSERVATION',
  selectionDecision: 'MODEL_SELECTED_READ_ONLY_CANDIDATE',
  reason: 'Model selected the read-only git.status candidate. Mutation remains disabled.',
  modelToolSelectionAttempted: true,
  modelToolSelectionAccepted: true,
  selectedByModel: true,
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  candidate: {
    toolName: 'git.status',
    approvalState: 'NOT_REQUIRED',
    readOnly: true,
    requiresApproval: false,
    mutationAllowed: false,
  },
  modelDecision: {
    toolName: 'git.status',
    readOnly: true,
    requiresApproval: false,
    mutationAllowed: false,
    reason: 'Check current workspace state.',
  },
};

const result = await previewAgentLoopRunnerToolSelection({
  request: async (path, options) => {
    calls.push({ path, options });
    return response;
  },
  run: async (label, task) => {
    assert.equal(label, 'code-agent-loop-runner-tool-selection-preview');
    return await task();
  },
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  setToolSelectionPreview: (value) => previews.push(value),
});

assert.equal(result, response);
assert.deepEqual(calls, [
  {
    path: '/api/code-agent/loop/runner/select-tool-preview',
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
assert.equal(response.selectionDecision, 'MODEL_SELECTED_READ_ONLY_CANDIDATE');
assert.equal(response.modelToolSelectionAccepted, true);
assert.equal(response.requestCreationEnabled, false);
assert.equal(response.enqueueEnabled, false);
assert.equal(response.pushEnabled, false);
assert.equal(response.claimEnabled, false);
assert.equal(response.mutationEnabled, false);

const missing = await previewAgentLoopRunnerToolSelection({
  request: async () => {
    throw new Error('should not call request');
  },
  run: async () => {
    throw new Error('should not run');
  },
  repositoryId: 'repo-1',
  loopId: '',
  setToolSelectionPreview: (value) => previews.push(value),
});
assert.equal(missing, null);
assert.equal(previews.at(-1), null);

const failed = await previewAgentLoopRunnerToolSelection({
  request: async () => {
    throw new Error('backend unavailable');
  },
  run: async () => false,
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  setToolSelectionPreview: (value) => previews.push(value),
});
assert.equal(failed, null);
assert.equal(previews.at(-1), null);

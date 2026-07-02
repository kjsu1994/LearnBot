import assert from 'node:assert/strict';
import { previewAgentLoopRunnerFinalResultPublication } from './agentLoopRunnerFinalResultPublicationPreviewClient.js';

const calls = [];
const response = {
  publicationDecision: 'READY_FINAL_RESULT_PUBLICATION_DISABLED',
  finalResultReady: true,
  publicationEnabled: false,
  acknowledgementSaveEnabled: false,
  mutationEnabled: false,
};

const result = await previewAgentLoopRunnerFinalResultPublication({
  request: async (url, options) => {
    calls.push({ url, options });
    return response;
  },
  run: async (_key, task) => task(),
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  setFinalResultPublicationPreview: (value) => calls.push({ state: value }),
});

assert.equal(result, response);
assert.equal(calls[0].url, '/api/code-agent/loop/runner/final-result-publication-preview');
assert.equal(calls[0].options.method, 'POST');
assert.deepEqual(calls[0].options.json, {
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
});
assert.equal(calls[1].state.publicationDecision, 'READY_FINAL_RESULT_PUBLICATION_DISABLED');
assert.equal(calls[1].state.publicationEnabled, false);
assert.equal(calls[1].state.acknowledgementSaveEnabled, false);
assert.equal(calls[1].state.mutationEnabled, false);

const missing = [];
const skipped = await previewAgentLoopRunnerFinalResultPublication({
  request: async () => {
    throw new Error('request should not run');
  },
  run: async (_key, task) => task(),
  repositoryId: '',
  loopId: 'loop-1',
  setFinalResultPublicationPreview: (value) => missing.push(value),
});

assert.equal(skipped, null);
assert.deepEqual(missing, [null]);

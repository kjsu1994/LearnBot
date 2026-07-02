import assert from 'node:assert/strict';
import { previewAgentLoopRunnerM8EntryReadiness } from './agentLoopRunnerM8EntryReadinessClient.js';

const calls = [];
const response = {
  m7ClosureDecision: 'M7_CLOSURE_READY',
  m8EntryDecision: 'M8_ENTRY_READY',
  m8EntryReady: true,
  m8WorkEnabled: false,
  publicationEnabled: false,
  acknowledgementSaveEnabled: false,
  mutationEnabled: false,
};

const result = await previewAgentLoopRunnerM8EntryReadiness({
  request: async (url, options) => {
    calls.push({ url, options });
    return response;
  },
  run: async (_key, task) => task(),
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
  setM8EntryReadiness: (value) => calls.push({ state: value }),
});

assert.equal(result, response);
assert.equal(calls[0].url, '/api/code-agent/loop/runner/m8-entry-readiness');
assert.deepEqual(calls[0].options.json, {
  repositoryId: 'repo-1',
  loopId: 'loop-1',
  agentId: 'agent-1',
  workspaceId: 'workspace-1',
});
assert.equal(calls[1].state.m8EntryDecision, 'M8_ENTRY_READY');
assert.equal(calls[1].state.m8WorkEnabled, false);
assert.equal(calls[1].state.publicationEnabled, false);
assert.equal(calls[1].state.acknowledgementSaveEnabled, false);
assert.equal(calls[1].state.mutationEnabled, false);

const missing = [];
const skipped = await previewAgentLoopRunnerM8EntryReadiness({
  request: async () => {
    throw new Error('request should not run');
  },
  run: async (_key, task) => task(),
  repositoryId: 'repo-1',
  loopId: '',
  setM8EntryReadiness: (value) => missing.push(value),
});

assert.equal(skipped, null);
assert.deepEqual(missing, [null]);

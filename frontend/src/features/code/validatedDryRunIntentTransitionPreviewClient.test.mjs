import assert from 'node:assert/strict';
import {
  previewValidatedDryRunIntentTransition,
  requestIdFromClaimableDryRunPreviewRoute,
} from './validatedDryRunIntentTransitionPreviewClient.js';

assert.equal(
  requestIdFromClaimableDryRunPreviewRoute('GET /api/code-agent/local-patch-request/dry-run-intent/request-1/claimable-dry-run-preview'),
  'request-1'
);
assert.equal(
  requestIdFromClaimableDryRunPreviewRoute('/api/code-agent/local-patch-request/dry-run-intent/request%202/claimable-dry-run-preview'),
  'request 2'
);
assert.equal(requestIdFromClaimableDryRunPreviewRoute('/api/code-agent/local-patch-request/dry-run-intent/request-1/eligibility'), '');

const calls = [];
const states = [];
const result = await previewValidatedDryRunIntentTransition({
  request: async (path) => {
    calls.push(path);
    return {
      schema: 'learnbot.server.validated-revised-patch-dry-run-transition-preview.v1',
      status: 'READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED',
    };
  },
  run: async (label, task) => {
    calls.push(label);
    return await task();
  },
  eligibilityRoute: 'GET /api/code-agent/local-patch-request/dry-run-intent/request-3/eligibility',
  setTransitionPreview: (value) => states.push(value),
});

assert.equal(result.status, 'READY_CLAIMABLE_DRY_RUN_TRANSITION_DISABLED');
assert.deepEqual(calls, [
  'code-agent-validated-dry-run-intent-transition-request-3',
  '/api/code-agent/local-patch-request/dry-run-intent/request-3/claimable-dry-run-preview',
]);
assert.equal(states.at(-1), result);

const missing = await previewValidatedDryRunIntentTransition({
  request: async () => {
    throw new Error('request should not be called');
  },
  run: async () => {
    throw new Error('run should not be called');
  },
});
assert.equal(missing, null);

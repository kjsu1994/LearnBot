import assert from 'node:assert/strict';
import {
  inspectValidatedDryRunIntentEligibility,
  requestIdFromEligibilityRoute,
} from './validatedDryRunIntentEligibilityClient.js';

assert.equal(
  requestIdFromEligibilityRoute('GET /api/code-agent/local-patch-request/dry-run-intent/request-1/eligibility'),
  'request-1'
);
assert.equal(requestIdFromEligibilityRoute('/api/code-agent/local-patch-request/dry-run-intent/request%202/eligibility'), 'request 2');
assert.equal(requestIdFromEligibilityRoute('/api/code-agent/local-patch-request/dry-run-intent/request-1/other'), '');

const calls = [];
let stored = null;
const result = await inspectValidatedDryRunIntentEligibility({
  eligibilityRoute: 'GET /api/code-agent/local-patch-request/dry-run-intent/request-1/eligibility',
  setEligibility: (value) => {
    stored = value;
  },
  run: async (key, task) => {
    calls.push({ type: 'run', key });
    return await task();
  },
  request: async (path) => {
    calls.push({ type: 'request', path });
    return {
      schema: 'learnbot.server.validated-revised-patch-dry-run-eligibility.v1',
      requestId: 'request-1',
      requestCreationEnabled: false,
      queueEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      mutationAllowed: false,
    };
  },
});

assert.deepEqual(result, stored);
assert.deepEqual(calls, [
  {
    type: 'run',
    key: 'code-agent-validated-dry-run-intent-eligibility-request-1',
  },
  {
    type: 'request',
    path: '/api/code-agent/local-patch-request/dry-run-intent/request-1/eligibility',
  },
]);

let called = false;
const empty = await inspectValidatedDryRunIntentEligibility({
  run: async () => {
    called = true;
  },
  request: async () => {
    called = true;
  },
});
assert.equal(empty, null);
assert.equal(called, false);

console.log('validatedDryRunIntentEligibilityClient tests passed');

import assert from 'node:assert/strict';
import {
  assertNoForbiddenTrueFlags,
  collectForbiddenTrueFlags,
} from './mutationDisabledFlagGuard.js';

assert.deepEqual(collectForbiddenTrueFlags(null), []);
assert.deepEqual(collectForbiddenTrueFlags({ mutationAllowed: false }), []);
assert.deepEqual(collectForbiddenTrueFlags({ nested: [{ claimable: false }] }), []);

assert.deepEqual(
  collectForbiddenTrueFlags({
    releaseGateEnabled: true,
    nested: {
      items: [
        { mutationAllowed: false },
        { applyEnabled: true },
      ],
    },
    harmlessEnabled: true,
  }),
  [
    'latestAttempt.releaseGateEnabled',
    'latestAttempt.nested.items[1].applyEnabled',
  ]
);

assert.deepEqual(
  collectForbiddenTrueFlags({ patchExecutionGate: { releaseGateEnabled: true } }, 'props'),
  ['props.patchExecutionGate.releaseGateEnabled']
);

assert.deepEqual(
  collectForbiddenTrueFlags({
    nested: {
      mutationTransportEnabled: true,
      finalAnswerReviewAllowed: true,
      deliveryAuditClaimable: true,
      claimableAfterDispatch: true,
      harmlessEnabled: true,
    },
  }),
  [
    'latestAttempt.nested.mutationTransportEnabled',
    'latestAttempt.nested.finalAnswerReviewAllowed',
    'latestAttempt.nested.deliveryAuditClaimable',
    'latestAttempt.nested.claimableAfterDispatch',
  ]
);

assert.equal(assertNoForbiddenTrueFlags({ mutationAllowed: false }), true);
assert.throws(
  () => assertNoForbiddenTrueFlags({ patchExecutionGate: { releaseGateEnabled: true } }, 'props'),
  /Forbidden enabled mutation flags: props\.patchExecutionGate\.releaseGateEnabled/
);

console.log('mutationDisabledFlagGuard tests passed');

import assert from 'node:assert/strict';
import { buildFreshObservationEnqueueBoundaryView } from './freshObservationEnqueueBoundary.js';

const boundary = {
  status: 'REFUSED_ENQUEUE_DISABLED',
  requestCreationEnabled: false,
  pushEnabled: false,
  enqueueEnabled: false,
  claimableAfterEnqueue: false,
  mutationAllowed: false,
  plannedRequests: [
    {
      key: 'freshRepositoryVerification',
      status: 'PLANNED_DISABLED',
      toolName: 'git.status',
      approvalState: 'NOT_REQUIRED',
      enqueueEnabled: false,
      claimableAfterEnqueue: false,
      releaseAttemptId: '12345678-1234-1234-1234-123456789abc',
    },
    {
      key: 'freshPatchDryRun',
      status: 'PLANNED_DISABLED',
      toolName: 'patch.apply',
      approvalState: 'APPROVED',
      enqueueEnabled: false,
      claimableAfterEnqueue: false,
      releaseAttemptId: '12345678-1234-1234-1234-123456789abc',
    },
  ],
  message: 'Fresh observation enqueue boundary is modeled for audit only.',
};

const view = buildFreshObservationEnqueueBoundaryView(boundary);

assert.equal(view.show, true);
assert.equal(
  view.boundaryText,
  'fresh observation enqueue boundary: REFUSED_ENQUEUE_DISABLED / request creation false / push false / enqueue false / claimable false / mutation false'
);
assert.deepEqual(view.plannedRequestLines, [
  'boundary planned freshRepositoryVerification: PLANNED_DISABLED / git.status / approval NOT_REQUIRED / enqueue false / claimable false / attempt 12345678',
  'boundary planned freshPatchDryRun: PLANNED_DISABLED / patch.apply / approval APPROVED / enqueue false / claimable false / attempt 12345678',
]);
assert.equal(view.message, boundary.message);

const fallbackView = buildFreshObservationEnqueueBoundaryView({
  plannedRequests: [
    {
      key: 'freshPatchDryRun',
    },
  ],
});

assert.equal(fallbackView.show, true);
assert.equal(fallbackView.boundaryText, 'fresh observation enqueue boundary: DISABLED');
assert.deepEqual(fallbackView.plannedRequestLines, [
  'boundary planned freshPatchDryRun: TEMPLATE_DISABLED',
]);

const hiddenView = buildFreshObservationEnqueueBoundaryView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.boundaryText, '');
assert.deepEqual(hiddenView.plannedRequestLines, []);

console.log('freshObservationEnqueueBoundary view tests passed');

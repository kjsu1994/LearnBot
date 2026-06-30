import assert from 'node:assert/strict';
import { buildFreshObservationRequestPlanView } from './freshObservationRequestPlan.js';

const releaseAttemptId = '12345678-1234-1234-1234-123456789abc';
const sourceRequestId = '87654321-4321-4321-4321-cba987654321';

const view = buildFreshObservationRequestPlanView([
  {
    key: 'freshRepositoryVerification',
    status: 'PLANNED_DISABLED',
    toolName: 'git.status',
    approvalState: 'NOT_REQUIRED',
    enqueueEnabled: false,
    claimableAfterEnqueue: false,
    mutationAllowed: false,
    releaseAttemptId,
    sourceRequestId,
  },
  {
    key: 'freshPatchDryRun',
    status: 'PLANNED_DISABLED',
    toolName: 'patch.apply',
    approvalState: 'APPROVED',
    enqueueEnabled: false,
    claimableAfterEnqueue: false,
    mutationAllowed: false,
    dryRunOnly: true,
    releaseAttemptId,
    sourceRequestId,
  },
]);

assert.equal(view.show, true);
assert.equal(view.headerText, 'fresh observation request plan: audit-only / no enqueue / no claim');
assert.deepEqual(view.requestLines, [
  'freshRepositoryVerification: PLANNED_DISABLED / git.status / approval NOT_REQUIRED / enqueue false / claimable false / mutation false / attempt 12345678 / source 87654321',
  'freshPatchDryRun: PLANNED_DISABLED / patch.apply / approval APPROVED / enqueue false / claimable false / mutation false / dry-run true / attempt 12345678 / source 87654321',
]);

const fallbackView = buildFreshObservationRequestPlanView([
  {
    key: 'freshPatchDryRun',
  },
]);

assert.equal(fallbackView.show, true);
assert.equal(fallbackView.headerText, 'fresh observation request plan: audit-only / no enqueue / no claim');
assert.deepEqual(fallbackView.requestLines, [
  'freshPatchDryRun: PLANNED_DISABLED',
]);

const hiddenView = buildFreshObservationRequestPlanView(null);
assert.equal(hiddenView.show, false);
assert.equal(hiddenView.headerText, '');
assert.deepEqual(hiddenView.requestLines, []);

console.log('freshObservationRequestPlan view tests passed');

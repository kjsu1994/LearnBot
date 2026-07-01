import assert from 'node:assert/strict';
import { buildApprovedExecutionFlowInspectionView } from './approvedExecutionFlowInspectionSummary.js';

const view = buildApprovedExecutionFlowInspectionView({
  schema: 'learnbot.local-agent.approved-execution-flow-contract.v1',
  repositoryBacked: true,
  readModelOnly: true,
  ordered: true,
  identityConsistent: true,
  releaseAttemptLinked: true,
  allTerminal: true,
  requestCreationEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  resultIntakeEnabled: false,
  acknowledgementSaveEnabled: false,
  mutationAllowedForFollowup: false,
  readyForServerOrchestration: false,
  requestIds: ['patch-1', 'command-1', 'status-1', 'rollback-1'],
  steps: [
    {
      index: 0,
      toolName: 'patch.apply',
      status: 'SUCCEEDED',
      verificationStatus: 'APPLIED',
      acceptanceStatus: 'ACCEPTED',
      accepted: true,
      requestId: 'patch-1',
    },
    {
      index: 1,
      toolName: 'command.runAllowed',
      status: 'SUCCEEDED',
      verificationStatus: 'PASSED',
      acceptanceStatus: 'ACCEPTED',
      accepted: true,
      requestId: 'command-1',
    },
  ],
  message: 'Read-only inspection only.',
});

assert.equal(view.show, true);
assert.equal(view.headerText, 'approved execution flow inspection: learnbot.local-agent.approved-execution-flow-contract.v1');
assert.equal(view.stateText, 'ordered true / identity true / release linked true / terminal true / repository backed true / read model true');
assert.equal(
  view.disabledText,
  'approved flow controls disabled: request creation false / push false / claim false / result intake false / acknowledgement save false / follow-up mutation false / server orchestration false'
);
assert.equal(view.requestText, 'approved flow request ids: patch-1, command-1, status-1, rollback-1');
assert.deepEqual(view.stepLines, [
  '0. patch.apply: SUCCEEDED / verification APPLIED / acceptance ACCEPTED / accepted true / request patch-1',
  '1. command.runAllowed: SUCCEEDED / verification PASSED / acceptance ACCEPTED / accepted true / request command-1',
]);
assert.equal(view.message, 'Read-only inspection only.');
assert.equal(buildApprovedExecutionFlowInspectionView(null).show, false);

console.log('approvedExecutionFlowInspectionSummary view tests passed');

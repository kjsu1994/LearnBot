import assert from 'node:assert/strict';
import { buildAgentLoopPreviewSummaryView } from './agentLoopPreviewSummary.js';

const preview = {
  status: 'PREVIEW_ONLY',
  maxSteps: 6,
  timeoutSeconds: 120,
  mutationEnabled: false,
  timelinePersistenceEnabled: true,
  cancellationEnabled: false,
  steps: [
    {
      index: 1,
      phase: 'PLAN',
      action: 'Retrieve code evidence.',
      executionTarget: 'SERVER_LOCAL',
      requiresApproval: false,
      mayMutate: false,
      enabled: true,
      stopOnFailure: 'Stop on weak evidence.',
    },
    {
      index: 3,
      phase: 'REQUEST_APPROVAL',
      action: 'Pause before patch.apply.',
      executionTarget: 'USER_LOCAL_AGENT',
      toolName: 'patch.apply',
      requiresApproval: true,
      mayMutate: false,
      enabled: true,
      stopOnFailure: 'Stop on approval denial.',
    },
  ],
  stopConditions: [
    { key: 'MAX_STEPS', message: 'Stop when bounded step count is reached.' },
    { key: 'MUTATION_DISABLED', message: 'Do not apply patches.' },
  ],
  warnings: ['Preview only.'],
};

const view = buildAgentLoopPreviewSummaryView(preview);

assert.equal(view.headerText, 'agent loop preview: PREVIEW_ONLY / max steps 6 / timeout 120s');
assert.equal(view.stateText, 'agent loop state: mutation false / timeline persistence true / cancellation false');
assert.deepEqual(view.stepLines, [
  '1 PLAN: Retrieve code evidence. / SERVER_LOCAL / approval false / may mutate false / enabled true / stop: Stop on weak evidence.',
  '3 REQUEST_APPROVAL: Pause before patch.apply. / USER_LOCAL_AGENT / patch.apply / approval true / may mutate false / enabled true / stop: Stop on approval denial.',
]);
assert.deepEqual(view.stopLines, [
  'MAX_STEPS: Stop when bounded step count is reached.',
  'MUTATION_DISABLED: Do not apply patches.',
]);
assert.deepEqual(view.warnings, ['Preview only.']);
assert.equal(buildAgentLoopPreviewSummaryView(null), null);

console.log('agentLoopPreviewSummary view tests passed');

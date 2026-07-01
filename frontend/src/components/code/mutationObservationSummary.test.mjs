import assert from 'node:assert/strict';
import { buildAcceptedMutationObservationSummaryText } from './mutationObservationSummary.js';

assert.equal(
  buildAcceptedMutationObservationSummaryText({
    schema: 'learnbot.local-agent.accepted-mutation-observation-summary.v1',
    status: 'OBSERVED',
    observationCount: 3,
    acceptedCount: 2,
    rejectedCount: 1,
    terminalFailureAcceptedCount: 1,
    toolObservationCounts: {
      'patch.apply': 1,
      'command.runAllowed': 1,
    },
    statusObservationCounts: {
      ACCEPTED: 2,
      REJECTED_DRY_RUN_ONLY: 1,
    },
  }),
  'accepted mutation observation summary: learnbot.local-agent.accepted-mutation-observation-summary.v1 / OBSERVED / observations 3 / accepted 2 / rejected 1 / terminal failures 1 / tool counts patch.apply=1, command.runAllowed=1 / status counts ACCEPTED=2, REJECTED_DRY_RUN_ONLY=1'
);

assert.equal(
  buildAcceptedMutationObservationSummaryText({
    acceptedMutationObservationSummarySchema: 'learnbot.local-agent.accepted-mutation-observation-summary.v1',
    acceptedMutationObservationSummaryStatus: 'EMPTY',
    acceptedMutationObservationCount: 0,
    acceptedMutationObservationAcceptedCount: 0,
    acceptedMutationObservationRejectedCount: 0,
    acceptedMutationObservationTerminalFailureAcceptedCount: 0,
    acceptedMutationObservationToolCounts: {},
    acceptedMutationObservationStatusCounts: {},
    missingMutationResultRiskVisible: true,
    staleIndexRiskVisible: false,
  }, 'final answer publication accepted observations'),
  'final answer publication accepted observations: learnbot.local-agent.accepted-mutation-observation-summary.v1 / EMPTY / observations 0 / accepted 0 / rejected 0 / terminal failures 0 / missing result risk true / stale index risk false'
);

assert.equal(buildAcceptedMutationObservationSummaryText(null), '');
assert.equal(buildAcceptedMutationObservationSummaryText({}), '');

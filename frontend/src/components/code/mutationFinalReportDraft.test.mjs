import assert from 'node:assert/strict';
import { buildMutationFinalReportDraftView } from './mutationFinalReportDraft.js';

const view = buildMutationFinalReportDraftView({
  schema: 'learnbot.local-agent.final-mutation-report-draft.v1',
  status: 'READY_DRAFT_DISABLED',
  prerequisitesPassed: true,
  executionTarget: 'USER_LOCAL_AGENT',
  aggregationPlanStatus: 'READY_AGGREGATION_DISABLED',
  finalMutationReportStatus: 'CONTRACT_DISABLED',
  acceptedMutationObservationSummarySchema: 'learnbot.local-agent.accepted-mutation-observation-summary.v1',
  acceptedMutationObservationSummaryStatus: 'OBSERVED',
  acceptedMutationObservationCount: 2,
  acceptedMutationObservationAcceptedCount: 2,
  acceptedMutationObservationRejectedCount: 0,
  acceptedMutationObservationTerminalFailureAcceptedCount: 0,
  acceptedMutationObservationToolCounts: {
    'patch.apply': 1,
  },
  acceptedMutationObservationStatusCounts: {
    ACCEPTED: 2,
  },
  missingMutationResultRiskVisible: false,
  staleIndexRiskVisible: true,
  releaseGateEnabled: false,
  mutationAllowed: false,
  mutationResultAggregationEnabled: false,
  finalMutationReportDraftEnabled: false,
  finalReportGenerationEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  sections: [
    {
      key: 'changedFiles',
      status: 'PENDING_RESULT_DISABLED',
      sourceOutcomeKey: 'patchApplyOutcome',
      aggregationStepStatus: 'PLANNED_DISABLED',
      aggregationSourceOutcomeKey: 'patchApplyOutcome',
      sourceOutcomeModeled: true,
      mutationAllowed: false,
      finalReportGenerationEnabled: false,
      publicationEnabled: false,
      finalAnswerGenerationEnabled: false,
      message: 'Do not claim changed files until Local Agent reports mutationApplied=true.',
    },
  ],
  blockingKeys: ['mutationResultAggregationEnabled', 'finalReportGenerationEnabled', 'publicationEnabled'],
  message: 'Future final mutation report draft is modeled from the aggregation plan, but aggregation remains disabled.',
});

assert.equal(view.show, true);
assert.equal(
  view.headerText,
  'final mutation report draft: READY_DRAFT_DISABLED / learnbot.local-agent.final-mutation-report-draft.v1 / prerequisites true / USER_LOCAL_AGENT / aggregation READY_AGGREGATION_DISABLED / report CONTRACT_DISABLED'
);
assert.equal(
  view.observationSummaryText,
  'final report draft accepted observations: learnbot.local-agent.accepted-mutation-observation-summary.v1 / OBSERVED / observations 2 / accepted 2 / rejected 0 / terminal failures 0 / tool counts patch.apply=1 / status counts ACCEPTED=2 / missing result risk false / stale index risk true'
);
assert.equal(
  view.disabledText,
  'final report draft disabled: release gate false / mutation false / result aggregation false / draft generation false / final report false / publication false / final answer false'
);
assert.deepEqual(view.sectionLines, [
  'final report draft section changedFiles: PENDING_RESULT_DISABLED / source patchApplyOutcome / aggregation PLANNED_DISABLED / aggregation source patchApplyOutcome / source modeled true / mutation false / final report false / publication false / final answer false / Do not claim changed files until Local Agent reports mutationApplied=true.',
]);
assert.equal(
  view.blockingText,
  'final report draft blocking keys: mutationResultAggregationEnabled, finalReportGenerationEnabled, publicationEnabled'
);
assert.match(view.message, /aggregation remains disabled/);

const hidden = buildMutationFinalReportDraftView(null);
assert.equal(hidden.show, false);
assert.deepEqual(hidden.sectionLines, []);

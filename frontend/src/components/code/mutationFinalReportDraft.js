import { buildAcceptedMutationObservationSummaryText } from './mutationObservationSummary.js';

const DISABLED_CONTROL_LABELS = [
  ['releaseGateEnabled', 'release gate'],
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['writeHelperEnabled', 'write helper'],
  ['claimable', 'claimable'],
  ['mutationAllowed', 'mutation'],
  ['applyEnabled', 'apply'],
  ['testEnabled', 'test'],
  ['rollbackRestoreEnabled', 'rollback restore'],
  ['ragFreshnessUpdateEnabled', 'rag freshness'],
  ['mutationResultAggregationEnabled', 'result aggregation'],
  ['acceptedObservationAggregationEnabled', 'accepted observation aggregation'],
  ['finalMutationReportDraftEnabled', 'draft generation'],
  ['finalReportGenerationEnabled', 'final report'],
  ['publicationEnabled', 'publication'],
  ['finalAnswerGenerationEnabled', 'final answer'],
];

export function buildMutationFinalReportDraftView(draft = null) {
  if (!draft) {
    return {
      show: false,
      headerText: '',
      observationSummaryText: '',
      disabledText: '',
      sectionLines: [],
      blockingText: '',
      message: '',
    };
  }

  const sections = Array.isArray(draft.sections) ? draft.sections : [];
  const blockingKeys = Array.isArray(draft.blockingKeys) ? draft.blockingKeys : [];
  return {
    show: true,
    headerText: headerText(draft),
    observationSummaryText: buildAcceptedMutationObservationSummaryText(draft, 'final report draft accepted observations'),
    disabledText: `final report draft disabled:${disabledControlSuffix(draft)}`,
    sectionLines: sections.map(sectionText),
    blockingText: blockingKeys.length ? `final report draft blocking keys: ${blockingKeys.join(', ')}` : '',
    message: draft.message || '',
  };
}

function headerText(draft) {
  let text = `final mutation report draft: ${draft.status || 'BLOCKED_DRAFT_DISABLED'}`;
  if (draft.schema) {
    text += ` / ${draft.schema}`;
  }
  if (draft.prerequisitesPassed !== undefined) {
    text += ` / prerequisites ${String(draft.prerequisitesPassed)}`;
  }
  if (draft.executionTarget) {
    text += ` / ${draft.executionTarget}`;
  }
  if (draft.aggregationPlanStatus) {
    text += ` / aggregation ${draft.aggregationPlanStatus}`;
  }
  if (draft.finalMutationReportStatus) {
    text += ` / report ${draft.finalMutationReportStatus}`;
  }
  return text;
}

function disabledControlSuffix(draft) {
  return DISABLED_CONTROL_LABELS
    .map(([key, label]) => draft[key] === undefined ? null : ` ${label} ${String(draft[key])}`)
    .filter(Boolean)
    .join(' /');
}

function sectionText(section) {
  let text = `final report draft section ${section.key}: ${section.status || 'PENDING_RESULT_DISABLED'}`;
  if (section.sourceOutcomeKey) {
    text += ` / source ${section.sourceOutcomeKey}`;
  }
  if (section.aggregationStepStatus) {
    text += ` / aggregation ${section.aggregationStepStatus}`;
  }
  if (section.aggregationSourceOutcomeKey) {
    text += ` / aggregation source ${section.aggregationSourceOutcomeKey}`;
  }
  if (section.sourceOutcomeModeled !== undefined) {
    text += ` / source modeled ${String(section.sourceOutcomeModeled)}`;
  }
  if (section.mutationAllowed !== undefined) {
    text += ` / mutation ${String(section.mutationAllowed)}`;
  }
  if (section.finalReportGenerationEnabled !== undefined) {
    text += ` / final report ${String(section.finalReportGenerationEnabled)}`;
  }
  if (section.publicationEnabled !== undefined) {
    text += ` / publication ${String(section.publicationEnabled)}`;
  }
  if (section.finalAnswerGenerationEnabled !== undefined) {
    text += ` / final answer ${String(section.finalAnswerGenerationEnabled)}`;
  }
  if (section.message) {
    text += ` / ${section.message}`;
  }
  return text;
}

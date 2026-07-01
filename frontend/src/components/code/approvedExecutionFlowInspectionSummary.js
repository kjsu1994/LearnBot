const DISABLED_LABELS = [
  ['requestCreationEnabled', 'request creation'],
  ['pushEnabled', 'push'],
  ['claimEnabled', 'claim'],
  ['resultIntakeEnabled', 'result intake'],
  ['acknowledgementSaveEnabled', 'acknowledgement save'],
  ['mutationAllowedForFollowup', 'follow-up mutation'],
  ['readyForServerOrchestration', 'server orchestration'],
];

export function buildApprovedExecutionFlowInspectionView(summary = null) {
  if (!summary) {
    return {
      show: false,
      headerText: '',
      stateText: '',
      disabledText: '',
      requestText: '',
      stepLines: [],
      message: '',
    };
  }
  const requestIds = Array.isArray(summary.requestIds) ? summary.requestIds : [];
  const steps = Array.isArray(summary.steps) ? summary.steps : [];
  return {
    show: true,
    headerText: `approved execution flow inspection: ${summary.schema || 'unknown schema'}`,
    stateText: [
      `ordered ${String(summary.ordered)}`,
      `identity ${String(summary.identityConsistent)}`,
      `release linked ${String(summary.releaseAttemptLinked)}`,
      `terminal ${String(summary.allTerminal)}`,
      `repository backed ${String(summary.repositoryBacked)}`,
      `read model ${String(summary.readModelOnly)}`,
    ].join(' / '),
    disabledText: `approved flow controls disabled:${disabledSuffix(summary)}`,
    requestText: requestIds.length ? `approved flow request ids: ${requestIds.join(', ')}` : '',
    stepLines: steps.map((step) => {
      let text = `${step.index}. ${step.toolName}: ${step.status || 'UNKNOWN'}`;
      if (step.verificationStatus) text += ` / verification ${step.verificationStatus}`;
      if (step.acceptanceStatus) text += ` / acceptance ${step.acceptanceStatus}`;
      if (step.accepted !== undefined) text += ` / accepted ${String(step.accepted)}`;
      if (step.requestId) text += ` / request ${step.requestId}`;
      return text;
    }),
    message: summary.message || '',
  };
}

function disabledSuffix(summary) {
  return DISABLED_LABELS
    .filter(([key]) => summary[key] !== undefined)
    .map(([key, label]) => ` ${label} ${String(summary[key])}`)
    .join(' /');
}

export function buildCodeChangeAssistantResult({ plan = null, patch = null } = {}) {
  const targetFiles = Array.isArray(plan?.targetFiles) ? plan.targetFiles : [];
  const evidence = Array.isArray(plan?.evidence) ? plan.evidence : [];
  const patchFiles = Array.isArray(patch?.files) ? patch.files : [];
  const byPath = new Map();

  targetFiles.forEach((file) => {
    if (!file?.path) return;
    byPath.set(file.path, {
      path: file.path,
      reason: file.reason || '',
      evidence: [],
      diff: '',
      status: 'CANDIDATE_ONLY',
      statusLabel: '후보만 확인됨',
      nextActionText: '근거와 수정 계획을 기준으로 직접 검토하세요.',
    });
  });

  patchFiles.forEach((file) => {
    if (!file?.path) return;
    const current = byPath.get(file.path) || {
      path: file.path,
      reason: '',
      evidence: [],
    };
    const diff = file.diff || '';
    byPath.set(file.path, {
      ...current,
      diff,
      status: diff ? 'DIFF_READY' : 'CANDIDATE_ONLY',
      statusLabel: diff ? '수정 예시 있음' : '후보만 확인됨',
      nextActionText: diff
        ? 'diff 초안을 복사해 직접 적용하거나 원문과 비교해 검토하세요.'
        : 'diff 초안이 생성되지 않아 근거와 수정 계획 확인이 필요합니다.',
    });
  });

  evidence.forEach((item) => {
    if (!item?.filePath) return;
    const current = byPath.get(item.filePath) || {
      path: item.filePath,
      reason: '',
      evidence: [],
      diff: '',
      status: 'CANDIDATE_ONLY',
      statusLabel: '후보만 확인됨',
      nextActionText: '근거로 검색된 파일입니다. 수정 대상인지 먼저 확인하세요.',
    };
    byPath.set(item.filePath, {
      ...current,
      evidence: [...(current.evidence || []), item],
    });
  });

  const cards = Array.from(byPath.values()).map((card) => {
    if (card.diff) return card;
    return {
      ...card,
      status: card.status || 'CANDIDATE_ONLY',
      statusLabel: card.statusLabel || '후보만 확인됨',
      nextActionText: card.nextActionText || '근거와 수정 계획을 기준으로 직접 검토하세요.',
    };
  });

  const counts = {
    diffReady: cards.filter((card) => card.status === 'DIFF_READY').length,
    candidatesOnly: cards.filter((card) => card.status === 'CANDIDATE_ONLY').length,
    needsMoreContext: plan?.needsMoreContext || (!cards.length && Boolean(plan)) ? 1 : 0,
  };

  let overallStatus = 'IDLE';
  if (patch?.valid === false) {
    overallStatus = 'FAILED';
  } else if (counts.diffReady > 0 && counts.candidatesOnly === 0 && counts.needsMoreContext === 0) {
    overallStatus = 'DIFF_READY';
  } else if (counts.diffReady > 0 || counts.candidatesOnly > 0) {
    overallStatus = 'PARTIAL';
  } else if (plan) {
    overallStatus = 'NEEDS_MORE_CONTEXT';
  }

  return {
    overallStatus,
    overallStatusLabel: overallStatusLabel(overallStatus),
    counts,
    cards,
    warnings: [
      ...(Array.isArray(plan?.warnings) ? plan.warnings : []),
      ...(Array.isArray(patch?.warnings) ? patch.warnings : []),
    ],
  };
}

function overallStatusLabel(status) {
  const labels = {
    IDLE: '대기 중',
    DIFF_READY: '수정 예시 생성됨',
    PARTIAL: '일부 수정 예시 생성됨',
    NEEDS_MORE_CONTEXT: '추가 정보 필요',
    FAILED: 'diff 검증 필요',
  };
  return labels[status] || status;
}

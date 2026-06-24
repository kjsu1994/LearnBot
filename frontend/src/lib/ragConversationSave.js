function buildSavedConversationPayload({
  spaceId,
  answerType,
  mode,
  conversationId,
  turns = [],
  fallbackAnswer = null,
  fallbackQuestion = '',
  repositoryId = null,
}) {
  const normalizedTurns = normalizeTurns(turns.length ? turns : [fallbackAnswer].filter(Boolean));
  const latestTurn = normalizedTurns.at(-1) || fallbackAnswer || {};
  const isConversation = normalizedTurns.length > 1;
  if (!isConversation) {
    return {
      spaceId,
      answerType,
      question: latestTurn.question || fallbackQuestion,
      mode: latestTurn.mode || mode,
      answer: latestTurn.answer || '',
      citations: latestTurn.citations || [],
      evidence: latestTurn.evidence || [],
      confidence: latestTurn.confidence,
      diagnostics: latestTurn.diagnostics || [],
      repositoryId,
    };
  }

  const firstQuestion = normalizedTurns[0]?.question || fallbackQuestion || '대화';
  const titlePrefix = answerType === 'CODE' ? '코드 대화' : '문서 대화';
  const answer = normalizedTurns.map((turn, index) => [
    `## ${index + 1}. 질문`,
    turn.question || '',
    '',
    '### 답변',
    turn.answer || '',
  ].join('\n')).join('\n\n---\n\n');

  const evidence = normalizedTurns.flatMap((turn, index) => (
    (turn.evidence || []).map((item) => ({
      ...item,
      turnIndex: index + 1,
      turnQuestion: turn.question || '',
    }))
  ));

  const snapshot = {
    kind: 'SAVED_CONVERSATION',
    version: 1,
    domain: answerType,
    conversationId: conversationId || latestTurn.conversationId || null,
    turnCount: normalizedTurns.length,
    turns: normalizedTurns.map((turn, index) => ({
      index: index + 1,
      id: turn.id || turn.turnId || null,
      question: turn.question || '',
      rewrittenQuestion: turn.rewrittenQuestion || '',
      mode: turn.mode || mode || '',
      answer: turn.answer || '',
      confidence: turn.confidence || '',
      diagnostics: Array.isArray(turn.diagnostics) ? turn.diagnostics : [],
      evidence: Array.isArray(turn.evidence) ? turn.evidence : [],
      citations: Array.isArray(turn.citations) ? turn.citations : [],
      createdAt: turn.createdAt || null,
      status: turn.status || 'completed',
    })),
  };

  return {
    spaceId,
    answerType,
    question: `${firstQuestion}${normalizedTurns.length > 1 ? ` 외 ${normalizedTurns.length - 1}개 질문` : ''}`,
    mode: latestTurn.mode || mode,
    answer,
    citations: normalizedTurns.flatMap((turn) => turn.citations || []),
    evidence,
    confidence: latestTurn.confidence,
    diagnostics: [snapshot],
    repositoryId,
    title: `${titlePrefix} · ${firstQuestion}`,
  };
}

function getSavedConversationSnapshot(savedAnswer) {
  const diagnostics = Array.isArray(savedAnswer?.diagnostics) ? savedAnswer.diagnostics : [];
  return diagnostics.find((item) => item?.kind === 'SAVED_CONVERSATION' && Array.isArray(item.turns)) || null;
}

function normalizeTurns(turns = []) {
  return turns
    .filter((turn) => turn && turn.answer && !turn.streaming && !turn.error && !turn.aborted)
    .map((turn) => ({
      ...turn,
      evidence: Array.isArray(turn.evidence) ? turn.evidence : [],
      citations: Array.isArray(turn.citations) ? turn.citations : [],
      diagnostics: Array.isArray(turn.diagnostics) ? turn.diagnostics : [],
    }));
}

export { buildSavedConversationPayload, getSavedConversationSnapshot };

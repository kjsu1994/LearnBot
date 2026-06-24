import { Bookmark, ChevronDown, ChevronUp, Loader2, Send, X } from 'lucide-react';
import { useRef, useState } from 'react';
import { getAnswerModeLabel, getCodeModeLabel } from '../../lib/formatters.js';
import { AnswerStatus } from './Common.jsx';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';

function RagChatPanel({
  turns = [],
  domain = 'DOCUMENT',
  question = '',
  setQuestion = () => {},
  onSubmit,
  onKeyDown,
  controls,
  guide,
  templates = [],
  placeholder = '',
  loading = false,
  disabled = false,
  submitLabel = 'Send',
  emptyTitle = '질문을 시작하세요',
  emptyDescription = '',
  evidenceRenderer,
  onSaveAnswer,
  onCancel,
  answerSavedId = '',
  saveLoading = false,
  streamAnchorRef,
  footer,
}) {
  const [detailsOpen, setDetailsOpen] = useState(false);
  const scrollRef = useRef(null);
  const latestTurn = turns.at(-1) || null;
  const latestStatus = latestTurn ? normalizeAnswerStatus(latestTurn) : '';
  const latestModeLabel = latestTurn ? answerModeLabel(domain, latestTurn.mode) : '';
  const latestAnswer = latestTurn?.answer || '';
  const latestEvidence = latestTurn?.evidence || [];
  const latestDiagnostics = latestTurn?.diagnostics || [];
  const hasDetails = Boolean(latestTurn) && (latestEvidence.length > 0 || latestDiagnostics.length > 0 || latestTurn.confidence);
  const canSave = Boolean(latestTurn) && latestAnswer && !['streaming', 'aborted', 'error'].includes(latestStatus) && !answerSavedId;
  const canCancel = latestStatus === 'streaming';
  const saveTitle = turns.length > 1 ? '대화 저장' : '답변 저장';

  function applyTemplate(template) {
    if (!template?.prompt || question.trim()) return;
    setQuestion(template.prompt);
  }

  function handleChatWheel(event) {
    const scrollEl = scrollRef.current;
    if (!scrollEl || event.defaultPrevented) return;
    if (event.target.closest('textarea, input, select, button, [role="button"], summary')) return;
    const nextTop = scrollEl.scrollTop + event.deltaY;
    const maxTop = scrollEl.scrollHeight - scrollEl.clientHeight;
    if (maxTop <= 0 && turns.length === 0) {
      event.preventDefault();
      window.scrollBy({ top: event.deltaY, behavior: 'auto' });
      return;
    }
    if (maxTop <= 0) return;
    const canScroll = (event.deltaY < 0 && scrollEl.scrollTop > 0) || (event.deltaY > 0 && scrollEl.scrollTop < maxTop);
    if (!canScroll) return;
    event.preventDefault();
    scrollEl.scrollTop = Math.max(0, Math.min(maxTop, nextTop));
  }

  return (
    <form className={turns.length === 0 ? 'panel ask-panel rag-command-panel rag-chat-panel rag-chat-panel-empty' : 'panel ask-panel rag-command-panel rag-chat-panel'} onSubmit={onSubmit} onWheel={handleChatWheel}>
      {(controls || guide || templates.length > 0 || footer) && (
        <div className="rag-chat-topbar">
          {latestTurn && (
            <div className="rag-chat-answer-summary">
              <span>{latestModeLabel ? `${latestModeLabel} 답변` : '답변'}</span>
              <strong className={`rag-answer-status rag-answer-status-${latestStatus}`}>{answerStatusLabel(latestStatus)}</strong>
              <div className="rag-chat-topbar-answer-actions">
                <button
                  className="icon-button answer-expand-button"
                  type="button"
                  title={answerSavedId ? '저장됨' : saveTitle}
                  disabled={!canSave || saveLoading}
                  onClick={onSaveAnswer}
                >
                  {saveLoading ? <Loader2 className="spin" size={15} /> : <Bookmark size={15} />}
                </button>
              </div>
            </div>
          )}
          {controls && <div className="rag-chat-controls">{controls}</div>}
          <div className="rag-chat-topbar-row">
            {templates.length > 0 && (
              <div className="rag-ai-template-row rag-chat-template-row" aria-label="질문 템플릿">
                {templates.map((template) => (
                  <button
                    key={template.label}
                    type="button"
                    title={question.trim() ? '입력 중인 질문이 있어 템플릿을 적용하지 않습니다.' : template.prompt}
                    onClick={() => applyTemplate(template)}
                  >
                    {template.icon && <span>{template.icon}</span>}
                    {template.label}
                  </button>
                ))}
              </div>
            )}
            {guide && <div className="rag-chat-topbar-actions">{guide}</div>}
          </div>
          {footer && <div className="rag-ai-composer-footer">{footer}</div>}
        </div>
      )}
      <div className="rag-chat-scroll" ref={scrollRef}>
        {turns.length === 0 ? (
          <div className="rag-chat-empty">
            <strong>{emptyTitle}</strong>
            {emptyDescription && <p>{emptyDescription}</p>}
          </div>
        ) : (
          <div className="rag-chat-turns">
            {turns.map((turn, index) => (
              <RagChatTurn
                key={turn.id || turn.turnId || turn.clientId || index}
                turn={turn}
                domain={domain}
                isLatest={index === turns.length - 1}
                evidenceRenderer={evidenceRenderer}
                streamAnchorRef={streamAnchorRef}
              />
            ))}
          </div>
        )}
      </div>

      <div className="rag-chat-composer">
        <div className="rag-chat-input-shell">
          <textarea
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={onKeyDown}
            placeholder={placeholder}
          />
          <div className="rag-chat-input-footer">
            <div className="rag-chat-input-tools" />
            <div className="rag-chat-submit-actions">
              {canCancel && (
                <button className="icon-button answer-expand-button stream-stop-button" type="button" title="답변 생성 중단" onClick={onCancel}>
                  <X size={15} />
                </button>
              )}
              <button className="rag-ai-send-button" disabled={disabled || loading} type="submit">
                {loading ? <Loader2 className="spin" size={16} /> : <Send size={16} />}
                {submitLabel}
              </button>
            </div>
          </div>
        </div>
        {hasDetails && (
          <div className="rag-chat-bottom-details">
            <button className="ghost-button compact-action rag-chat-evidence-toggle" type="button" onClick={() => setDetailsOpen((current) => !current)}>
              {detailsOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
              근거 {latestEvidence.length}개
            </button>
            {detailsOpen && (
              <div className="rag-answer-details">
                <AnswerStatus confidence={latestTurn.confidence} diagnostics={latestDiagnostics} />
                {evidenceRenderer?.(latestTurn)}
              </div>
            )}
          </div>
        )}
      </div>
    </form>
  );
}

function RagChatTurn({
  turn,
  isLatest,
  streamAnchorRef,
}) {
  const answer = turn.answer || '';
  const status = normalizeAnswerStatus(turn);

  return (
    <article className="rag-chat-turn">
      <div className="rag-message rag-message-user">
        <p>{turn.question}</p>
      </div>
      <div className={`rag-message rag-message-assistant rag-answer-${status}`}>
        {turn.rewrittenQuestion && turn.rewrittenQuestion !== turn.question && (
          <small className="answer-mode">이전 근거를 참고해 후속 질문으로 처리했습니다.</small>
        )}
        <div className="answer-body rag-chat-answer-body">
          <MarkdownAnswer text={answer || pendingStatusText(status)} streaming={status === 'streaming'} />
          {isLatest && <span className="stream-scroll-anchor" ref={streamAnchorRef} aria-hidden="true" />}
        </div>
      </div>
    </article>
  );
}

function normalizeAnswerStatus(turn = {}) {
  if (turn.status) return turn.status;
  if (turn.streaming) return 'streaming';
  if (turn.aborted) return 'aborted';
  if (turn.error) return 'error';
  return 'completed';
}

function answerModeLabel(domain, mode) {
  return domain === 'CODE' ? getCodeModeLabel(mode) : getAnswerModeLabel(mode);
}

function answerStatusLabel(status) {
  const labels = {
    streaming: '생성 중',
    completed: '완료',
    aborted: '중단됨',
    error: '오류',
    repaired: '보정됨',
    fallback: '폴백',
  };
  return labels[status] || '완료';
}

function pendingStatusText(status) {
  if (status === 'aborted') return '답변 생성이 중단되었습니다.';
  if (status === 'error') return '답변 생성 중 오류가 발생했습니다.';
  return '';
}

export { RagChatPanel };

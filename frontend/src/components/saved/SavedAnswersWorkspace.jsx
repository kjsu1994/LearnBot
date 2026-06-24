import { Bookmark, Code2, Database, Edit3, Loader2, Maximize2, MessageSquare, RefreshCw, Search, Trash2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { formatDate, getAnswerModeLabel, getCodeModeLabel } from '../../lib/formatters.js';
import { getSavedConversationSnapshot } from '../../lib/ragConversationSave.js';
import { AnswerModal } from '../common/AnswerModal.jsx';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';
import { Badge } from '../ui/badge.jsx';
import { DataTable } from '../ui/data-table.jsx';

function SavedAnswersWorkspace({
  savedAnswers = [],
  selectedSavedAnswer,
  savedAnswerQuery = '',
  setSavedAnswerQuery = () => {},
  savedAnswerType = '',
  setSavedAnswerType = () => {},
  ragConversations = [],
  selectedRagConversation,
  ragConversationType = '',
  setRagConversationType = () => {},
  refreshSavedAnswers = () => {},
  loadSavedAnswer = () => {},
  refreshRagConversations = () => {},
  loadRagConversation = () => {},
  deleteRagConversation = () => {},
  continueRagConversation = () => {},
  updateSavedAnswerTitle = () => {},
  deleteSavedAnswer = () => {},
  loading = () => false,
}) {
  const [activeTab, setActiveTab] = useState('answers');
  const [editingTitle, setEditingTitle] = useState('');

  useEffect(() => {
    setEditingTitle(selectedSavedAnswer?.title || '');
  }, [selectedSavedAnswer?.id, selectedSavedAnswer?.title]);

  function submitSearch(event) {
    event.preventDefault();
    refreshSavedAnswers();
  }

  function saveTitle(event) {
    event.preventDefault();
    if (!selectedSavedAnswer || !editingTitle.trim()) return;
    updateSavedAnswerTitle(selectedSavedAnswer.id, editingTitle.trim());
  }

  const savedColumns = useMemo(() => [
    {
      accessorKey: 'title',
      header: '제목',
      cell: ({ row }) => (
        <div className="saved-table-title">
          <strong>{row.original.title}</strong>
          <small>{row.original.question}</small>
        </div>
      ),
    },
    {
      accessorKey: 'answerType',
      header: '유형',
      cell: ({ row }) => <Badge variant="outline">{typeLabel(row.original.answerType)}</Badge>,
    },
    {
      accessorKey: 'createdAt',
      header: '저장일',
      cell: ({ row }) => <span className="text-muted-foreground">{formatDate(row.original.createdAt)}</span>,
    },
  ], []);

  const conversationColumns = useMemo(() => [
    {
      accessorKey: 'title',
      header: '대화',
      cell: ({ row }) => (
        <div className="saved-table-title">
          <strong>{row.original.title}</strong>
          <small>{conversationRetentionText(row.original)}</small>
        </div>
      ),
    },
    {
      accessorKey: 'domain',
      header: '유형',
      cell: ({ row }) => <Badge variant="outline">{typeLabel(row.original.domain)}</Badge>,
    },
    {
      accessorKey: 'updatedAt',
      header: '최근 사용',
      cell: ({ row }) => <span className="text-muted-foreground">{formatDate(row.original.updatedAt)}</span>,
    },
  ], []);

  return (
    <section className="workspace-grid saved-grid workspace-product saved-workspace-product">
      <div className="workspace-product-hero saved-product-hero">
        <div>
          <Badge variant="secondary">Knowledge Library</Badge>
          <h1>저장됨</h1>
          <p>저장한 답변과 최근 7일 대화형 RAG 히스토리를 다시 확인합니다.</p>
        </div>
        <div className="workspace-product-metrics" aria-label="저장됨 요약">
          <span><strong>{savedAnswers.length}</strong> saved</span>
          <span><strong>{ragConversations.length}</strong> chats</span>
          <span><strong>{activeTab === 'answers' ? 'ANSWERS' : 'CHATS'}</strong> tab</span>
        </div>
      </div>

      <div className="left-column">
        <section className="panel library-list-panel">
          <div className="mode-control three-tabs saved-library-tabs">
            <button className={activeTab === 'answers' ? 'mode-button active' : 'mode-button'} type="button" onClick={() => setActiveTab('answers')}>
              저장된 답변
            </button>
            <button className={activeTab === 'conversations' ? 'mode-button active' : 'mode-button'} type="button" onClick={() => setActiveTab('conversations')}>
              대화 목록
            </button>
          </div>

          {activeTab === 'answers' && (
            <>
              <div className="panel-title">
                <Bookmark size={18} />
                <div>
                  <h2>저장된 답변</h2>
                  <p>문서와 코드 RAG에서 저장한 답변을 다시 확인합니다.</p>
                </div>
              </div>
              <form className="stack" onSubmit={submitSearch}>
                <div className="form-grid two">
                  <select value={savedAnswerType} onChange={(event) => setSavedAnswerType(event.target.value)}>
                    <option value="">전체</option>
                    <option value="DOCUMENT">문서</option>
                    <option value="CODE">코드</option>
                  </select>
                  <button disabled={loading('saved-list')}>
                    {loading('saved-list') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
                    검색
                  </button>
                </div>
                <input value={savedAnswerQuery} onChange={(event) => setSavedAnswerQuery(event.target.value)} placeholder="저장된 답변 검색" />
              </form>
              <DataTable
                className="saved-answer-table"
                columns={savedColumns}
                data={savedAnswers}
                empty="저장된 답변이 없습니다."
                onRowClick={(item) => loadSavedAnswer(item.id)}
              />
            </>
          )}

          {activeTab === 'conversations' && (
            <>
              <div className="panel-title">
                <MessageSquare size={18} />
                <div>
                  <h2>대화 목록</h2>
                  <p>최초 생성일 기준 7일 동안 보관되는 문서/코드 대화입니다.</p>
                </div>
              </div>
              <div className="form-grid two">
                <select value={ragConversationType} onChange={(event) => setRagConversationType(event.target.value)}>
                  <option value="">전체</option>
                  <option value="DOCUMENT">문서 대화</option>
                  <option value="CODE">코드 대화</option>
                </select>
                <button type="button" disabled={loading('conversation-list')} onClick={refreshRagConversations}>
                  {loading('conversation-list') ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
                  새로고침
                </button>
              </div>
              <DataTable
                className="saved-answer-table"
                columns={conversationColumns}
                data={ragConversations}
                empty="최근 7일 대화가 없습니다."
                onRowClick={(item) => loadRagConversation(item.id)}
              />
            </>
          )}
        </section>
      </div>

      <div className="right-column">
        {activeTab === 'answers' && (
          <SavedAnswerDetail
            selectedSavedAnswer={selectedSavedAnswer}
            editingTitle={editingTitle}
            setEditingTitle={setEditingTitle}
            saveTitle={saveTitle}
            deleteSavedAnswer={deleteSavedAnswer}
            loading={loading}
          />
        )}

        {activeTab === 'conversations' && (
          <ConversationDetail
            selectedRagConversation={selectedRagConversation}
            deleteRagConversation={deleteRagConversation}
            continueRagConversation={continueRagConversation}
            loading={loading}
          />
        )}
      </div>
    </section>
  );
}

function SavedAnswerDetail({
  selectedSavedAnswer,
  editingTitle,
  setEditingTitle,
  saveTitle,
  deleteSavedAnswer,
  loading,
}) {
  const [answerModalOpen, setAnswerModalOpen] = useState(false);
  const conversationSnapshot = getSavedConversationSnapshot(selectedSavedAnswer);

  if (!selectedSavedAnswer) {
    return (
      <section className="panel muted-panel library-empty-panel">
        <div className="panel-title">
          <Bookmark size={18} />
          <div>
            <h2>답변을 선택하세요</h2>
            <p>저장된 답변을 선택하면 질문, 답변, 근거를 확인할 수 있습니다.</p>
          </div>
        </div>
      </section>
    );
  }

  if (conversationSnapshot) {
    return (
      <SavedConversationSnapshotDetail
        selectedSavedAnswer={selectedSavedAnswer}
        snapshot={conversationSnapshot}
        editingTitle={editingTitle}
        setEditingTitle={setEditingTitle}
        saveTitle={saveTitle}
        deleteSavedAnswer={deleteSavedAnswer}
        loading={loading}
      />
    );
  }

  return (
    <section className="panel saved-answer-detail library-detail-panel">
      <div className="panel-title">
        {selectedSavedAnswer.answerType === 'CODE' ? <Code2 size={18} /> : <Database size={18} />}
        <div>
          <h2>{selectedSavedAnswer.title}</h2>
          <p>{typeLabel(selectedSavedAnswer.answerType)} · {modeLabel(selectedSavedAnswer)} · {formatDate(selectedSavedAnswer.createdAt)}</p>
        </div>
      </div>
      <form className="inline-control" onSubmit={saveTitle}>
        <input value={editingTitle} onChange={(event) => setEditingTitle(event.target.value)} />
        <button className="ghost-button" disabled={!editingTitle.trim() || loading(`saved-title-${selectedSavedAnswer.id}`)}>
          {loading(`saved-title-${selectedSavedAnswer.id}`) ? <Loader2 className="spin" size={16} /> : <Edit3 size={16} />}
          이름 변경
        </button>
      </form>
      <div className="detail-box">
        <strong>질문</strong>
        <p>{selectedSavedAnswer.question}</p>
      </div>
      <div className="answer saved-answer-body">
        <div className="saved-answer-body-toolbar">
          <button className="icon-button answer-expand-button" type="button" title="크게 보기" onClick={() => setAnswerModalOpen(true)}>
            <Maximize2 size={15} />
          </button>
        </div>
        <MarkdownAnswer text={selectedSavedAnswer.answer} />
      </div>
      {answerModalOpen && (
        <AnswerModal
          title={selectedSavedAnswer.title || '저장된 답변'}
          subtitle={`${typeLabel(selectedSavedAnswer.answerType)} · ${modeLabel(selectedSavedAnswer)}`}
          answer={selectedSavedAnswer.answer}
          className="saved-answer-modal"
          bodyClassName="saved-answer-modal-body"
          onClose={() => setAnswerModalOpen(false)}
        />
      )}
      <SavedEvidence evidence={selectedSavedAnswer.evidence || []} />
      {visibleDiagnostics(selectedSavedAnswer.diagnostics).length > 0 && (
        <div className="detail-box">
          <strong>진단</strong>
          <ul className="saved-diagnostics">
            {visibleDiagnostics(selectedSavedAnswer.diagnostics).map((item, index) => <li key={`${index}-${item}`}>{String(item)}</li>)}
          </ul>
        </div>
      )}
      <div className="action-row">
        <button className="ghost-button" type="button" disabled={loading(`saved-delete-${selectedSavedAnswer.id}`)} onClick={() => deleteSavedAnswer(selectedSavedAnswer.id)}>
          {loading(`saved-delete-${selectedSavedAnswer.id}`) ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />}
          삭제
        </button>
      </div>
    </section>
  );
}

function SavedConversationSnapshotDetail({
  selectedSavedAnswer,
  snapshot,
  editingTitle,
  setEditingTitle,
  saveTitle,
  deleteSavedAnswer,
  loading,
}) {
  const [expandedTurn, setExpandedTurn] = useState(null);
  const turns = snapshot.turns || [];
  const expandedTurnTitle = expandedTurn
    ? `${selectedSavedAnswer.title || '저장된 대화'} · ${expandedTurn.index}`
    : '저장된 대화 답변';

  return (
    <section className="panel saved-answer-detail library-detail-panel conversation-history-panel">
      <div className="panel-title">
        {selectedSavedAnswer.answerType === 'CODE' ? <Code2 size={18} /> : <Database size={18} />}
        <div>
          <h2>{selectedSavedAnswer.title}</h2>
          <p>{typeLabel(selectedSavedAnswer.answerType)} · 저장된 대화 · {turns.length}턴 · {formatDate(selectedSavedAnswer.createdAt)}</p>
        </div>
      </div>
      <form className="inline-control" onSubmit={saveTitle}>
        <input value={editingTitle} onChange={(event) => setEditingTitle(event.target.value)} />
        <button className="ghost-button" disabled={!editingTitle.trim() || loading(`saved-title-${selectedSavedAnswer.id}`)}>
          {loading(`saved-title-${selectedSavedAnswer.id}`) ? <Loader2 className="spin" size={16} /> : <Edit3 size={16} />}
          이름 변경
        </button>
      </form>
      <div className="conversation-turn-list saved-conversation-snapshot-list">
        {turns.map((turn, index) => (
          <article className="conversation-turn-card" key={turn.id || index}>
            <div className="conversation-turn-head">
              <strong>{index === 0 ? '질문' : '추가 질문'} {index + 1}</strong>
              <small>{turn.createdAt ? formatDate(turn.createdAt) : modeLabel({ answerType: selectedSavedAnswer.answerType, mode: turn.mode })}</small>
            </div>
            <div className="conversation-message user-message">
              <strong>{index === 0 ? '질문' : '추가 질문'}</strong>
              <p>{turn.question}</p>
            </div>
            {turn.rewrittenQuestion && turn.rewrittenQuestion !== turn.question && (
              <details className="conversation-context-details">
                <summary>이전 대화 참고 정보</summary>
                <p>{turn.rewrittenQuestion}</p>
              </details>
            )}
            <div className="conversation-message assistant-message">
              <strong>답변</strong>
              <div className="saved-answer-body conversation-answer-body">
                <div className="saved-answer-body-toolbar">
                  <button
                    className="icon-button answer-expand-button"
                    type="button"
                    title="크게 보기"
                    onClick={() => setExpandedTurn(turn)}
                  >
                    <Maximize2 size={15} />
                  </button>
                </div>
                <MarkdownAnswer text={turn.answer} />
              </div>
            </div>
            <CollapsibleEvidence evidence={turn.evidence || turn.citations || []} />
          </article>
        ))}
      </div>
      {expandedTurn && (
        <AnswerModal
          title={expandedTurnTitle}
          subtitle={`${typeLabel(selectedSavedAnswer.answerType)} · ${modeLabel({ answerType: selectedSavedAnswer.answerType, mode: expandedTurn.mode })}`}
          answer={expandedTurn.answer}
          className="saved-answer-modal"
          bodyClassName="saved-answer-modal-body"
          onClose={() => setExpandedTurn(null)}
        />
      )}
      <div className="action-row">
        <button className="ghost-button" type="button" disabled={loading(`saved-delete-${selectedSavedAnswer.id}`)} onClick={() => deleteSavedAnswer(selectedSavedAnswer.id)}>
          {loading(`saved-delete-${selectedSavedAnswer.id}`) ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />}
          삭제
        </button>
      </div>
    </section>
  );
}

function ConversationDetail({ selectedRagConversation, deleteRagConversation = () => {}, continueRagConversation = () => {}, loading }) {
  const [expandedTurn, setExpandedTurn] = useState(null);

  if (!selectedRagConversation) {
    return (
      <section className="panel muted-panel library-empty-panel">
        <div className="panel-title">
          <MessageSquare size={18} />
          <div>
            <h2>대화를 선택하세요</h2>
            <p>대화 목록에서 항목을 선택하면 질문과 답변 히스토리를 확인할 수 있습니다.</p>
          </div>
        </div>
      </section>
    );
  }

  const conversation = selectedRagConversation.conversation;
  const turns = selectedRagConversation.turns || [];
  const expandedTurnTitle = expandedTurn
    ? `${conversation?.title || 'Saved conversation'} · ${expandedTurn.index + 1}`
    : 'Saved conversation answer';

  return (
    <section className="panel saved-answer-detail library-detail-panel conversation-history-panel">
      <div className="panel-title">
        {conversation?.domain === 'CODE' ? <Code2 size={18} /> : <Database size={18} />}
        <div>
          <h2>{conversation?.title || '대화'}</h2>
          <p>{typeLabel(conversation?.domain)} · {turns.length}턴 · {conversationRetentionText(conversation)}</p>
        </div>
      </div>

      {loading(`conversation-detail-${conversation?.id}`) && (
        <div className="code-modal-state">
          <Loader2 className="spin" size={20} />
          <strong>대화 히스토리를 불러오는 중입니다.</strong>
        </div>
      )}

      <div className="conversation-turn-list">
        {turns.map((turn, index) => (
          <article className="conversation-turn-card" key={turn.id || index}>
            <div className="conversation-turn-head">
              <strong>{index === 0 ? '질문' : '추가 질문'} {index + 1}</strong>
              <small>{formatDate(turn.createdAt)}</small>
            </div>
            <div className="conversation-message user-message">
              <strong>{index === 0 ? '질문' : '추가 질문'}</strong>
              <p>{turn.question}</p>
            </div>
            {turn.rewrittenQuestion && turn.rewrittenQuestion !== turn.question && (
              <details className="conversation-context-details">
                <summary>이전 대화 참고 정보</summary>
                <p>{turn.rewrittenQuestion}</p>
              </details>
            )}
            <div className="conversation-message assistant-message">
              <strong>답변</strong>
              <div className="saved-answer-body conversation-answer-body">
                <div className="saved-answer-body-toolbar">
                  <button
                    className="icon-button answer-expand-button"
                    type="button"
                    title="크게 보기"
                    onClick={() => setExpandedTurn({ ...turn, index })}
                  >
                    <Maximize2 size={15} />
                  </button>
                </div>
                <MarkdownAnswer text={turn.answer} />
              </div>
            </div>
            <CollapsibleEvidence evidence={turn.evidence || []} />
          </article>
        ))}
        {!turns.length && <p className="empty compact-empty">표시할 대화 히스토리가 없습니다.</p>}
      </div>
      {expandedTurn && (
        <AnswerModal
          title={expandedTurnTitle}
          subtitle={`${typeLabel(conversation?.domain)} · ${formatDate(expandedTurn.createdAt)}`}
          answer={expandedTurn.answer}
          className="saved-answer-modal"
          bodyClassName="saved-answer-modal-body"
          onClose={() => setExpandedTurn(null)}
        />
      )}
      <div className="action-row">
        <button className="ghost-button" type="button" onClick={() => continueRagConversation(conversation)}>
          <MessageSquare size={16} />
          이어서 질문하기
        </button>
        <button className="ghost-button" type="button" disabled={loading(`conversation-delete-${conversation?.id}`)} onClick={() => deleteRagConversation(conversation?.id)}>
          {loading(`conversation-delete-${conversation?.id}`) ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />}
          대화 삭제
        </button>
      </div>
    </section>
  );
}

function SavedEvidence({ evidence = [] }) {
  if (!Array.isArray(evidence) || !evidence.length) {
    return <p className="empty compact-empty">저장된 근거가 없습니다.</p>;
  }
  return <EvidenceList evidence={evidence} />;
}

function CollapsibleEvidence({ evidence = [] }) {
  const [expanded, setExpanded] = useState(false);
  if (!Array.isArray(evidence) || !evidence.length) {
    return <p className="empty compact-empty">근거 0개</p>;
  }
  return (
    <div className="conversation-evidence-collapsible">
      <button className="ghost-button compact-action" type="button" onClick={() => setExpanded((current) => !current)}>
        근거 {evidence.length}개
        <span>{expanded ? '접기' : '펼치기'}</span>
      </button>
      {expanded && <EvidenceList evidence={evidence} />}
    </div>
  );
}

function EvidenceList({ evidence = [] }) {
  return (
    <div className="evidence-section evidence-section-expanded">
      <div className="evidence-header">
        <strong>근거</strong>
        <small>{evidence.length}개</small>
      </div>
      <div className="evidence-list">
        {evidence.map((item, index) => (
          <article className="evidence-card" key={`${item.citationNumber || index}-${item.chunkId || item.filePath || item.title || index}`}>
            <strong>[{item.citationNumber || index + 1}] {item.title || item.filePath || 'Evidence'}</strong>
            <small>{item.sourceUri || item.repositoryName || item.chunkType || ''}</small>
            <p>{item.preview || item.content || ''}</p>
          </article>
        ))}
      </div>
    </div>
  );
}

function typeLabel(type) {
  if (type === 'CODE') return '코드';
  if (type === 'DOCUMENT') return '문서';
  return type || '-';
}

function visibleDiagnostics(diagnostics = []) {
  return (Array.isArray(diagnostics) ? diagnostics : []).filter((item) => item?.kind !== 'SAVED_CONVERSATION');
}

function modeLabel(answer) {
  if (answer.answerType === 'CODE') {
    return getCodeModeLabel(answer.mode);
  }
  return getAnswerModeLabel(answer.mode);
}

function conversationRetentionText(conversation = {}) {
  if (!conversation?.createdAt) return '7일 보관';
  const created = new Date(conversation.createdAt).getTime();
  const expires = created + 7 * 24 * 60 * 60 * 1000;
  const daysLeft = Math.max(0, Math.ceil((expires - Date.now()) / (24 * 60 * 60 * 1000)));
  return `최초 생성일 기준 ${daysLeft}일 남음`;
}

export { SavedAnswersWorkspace };

import { Bookmark, Code2, Database, Edit3, Loader2, Search, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { formatDate, getAnswerModeLabel, getCodeModeLabel } from '../../lib/formatters.js';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';

function SavedAnswersWorkspace({
  savedAnswers = [],
  selectedSavedAnswer,
  savedAnswerQuery = '',
  setSavedAnswerQuery = () => {},
  savedAnswerType = '',
  setSavedAnswerType = () => {},
  refreshSavedAnswers = () => {},
  loadSavedAnswer = () => {},
  updateSavedAnswerTitle = () => {},
  deleteSavedAnswer = () => {},
  loading = () => false,
}) {
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

  return (
    <section className="workspace-grid saved-grid">
      <div className="left-column">
        <section className="panel">
          <div className="panel-title">
            <Bookmark size={18} />
            <div>
              <h2>저장된 답변</h2>
              <p>개인별로 저장한 답변을 다시 확인합니다.</p>
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
          <div className="document-list scrollable-list saved-answer-list">
            {savedAnswers.map((item) => (
              <article className={selectedSavedAnswer?.id === item.id ? 'document-row selected saved-answer-row' : 'document-row saved-answer-row'} key={item.id} onClick={() => loadSavedAnswer(item.id)}>
                <div className="document-main">
                  <strong>{item.title}</strong>
                  <small>{item.question}</small>
                </div>
                <div className="document-meta">
                  <span className="status status-indexed">{item.answerType}</span>
                  <small>{formatDate(item.createdAt)}</small>
                </div>
              </article>
            ))}
            {!savedAnswers.length && <p className="empty">저장된 답변이 없습니다.</p>}
          </div>
        </section>
      </div>

      <div className="right-column">
        {!selectedSavedAnswer && (
          <section className="panel muted-panel">
            <div className="panel-title">
              <Bookmark size={18} />
              <div>
                <h2>답변을 선택하세요</h2>
                <p>문서와 코드 RAG에서 저장한 답변이 여기에 표시됩니다.</p>
              </div>
            </div>
          </section>
        )}

        {selectedSavedAnswer && (
          <section className="panel saved-answer-detail">
            <div className="panel-title">
              {selectedSavedAnswer.answerType === 'CODE' ? <Code2 size={18} /> : <Database size={18} />}
              <div>
                <h2>{selectedSavedAnswer.title}</h2>
                <p>{selectedSavedAnswer.answerType} · {modeLabel(selectedSavedAnswer)} · {formatDate(selectedSavedAnswer.createdAt)}</p>
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
              <MarkdownAnswer text={selectedSavedAnswer.answer} />
            </div>
            <SavedEvidence evidence={selectedSavedAnswer.evidence || []} />
            {Array.isArray(selectedSavedAnswer.diagnostics) && selectedSavedAnswer.diagnostics.length > 0 && (
              <div className="detail-box">
                <strong>Diagnostics</strong>
                <ul className="saved-diagnostics">
                  {selectedSavedAnswer.diagnostics.map((item, index) => <li key={`${index}-${item}`}>{String(item)}</li>)}
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
        )}
      </div>
    </section>
  );
}

function SavedEvidence({ evidence = [] }) {
  if (!Array.isArray(evidence) || !evidence.length) {
    return <p className="empty compact-empty">저장된 근거가 없습니다.</p>;
  }
  return (
    <div className="evidence-section evidence-section-expanded">
      <div className="evidence-header">
        <strong>저장된 근거</strong>
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

function modeLabel(answer) {
  if (answer.answerType === 'CODE') {
    return getCodeModeLabel(answer.mode);
  }
  return getAnswerModeLabel(answer.mode);
}

export { SavedAnswersWorkspace };

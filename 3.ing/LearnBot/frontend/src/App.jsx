import { useEffect, useMemo, useState } from 'react';
import {
  Bot,
  CheckCircle2,
  CircleHelp,
  Database,
  FileSpreadsheet,
  FileUp,
  Globe,
  Loader2,
  MessageSquare,
  RefreshCw,
  Search,
  Trash2,
  X,
} from 'lucide-react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const apiBase = import.meta.env.VITE_API_BASE_URL ?? '';

const sourceLabels = {
  FILE: '파일',
  WEB: '웹',
};

const statusLabels = {
  INDEXING: '색인 중',
  INDEXED: '색인 완료',
  PENDING: '대기 중',
  FAILED: '실패',
  PROCESSING: '처리 중',
};

function App() {
  const [documents, setDocuments] = useState([]);
  const [webUrl, setWebUrl] = useState('');
  const [file, setFile] = useState(null);
  const [query, setQuery] = useState('');
  const [question, setQuestion] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [answer, setAnswer] = useState(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [helpOpen, setHelpOpen] = useState(false);

  useEffect(() => {
    refreshDocuments();
  }, []);

  const indexedCount = useMemo(
    () => documents.filter((doc) => doc.sourceStatus === 'INDEXED').length,
    [documents],
  );

  const webCount = useMemo(
    () => documents.filter((doc) => doc.sourceType === 'WEB').length,
    [documents],
  );

  const fileCount = documents.length - webCount;
  const latestDocuments = documents.slice(0, 8);

  async function refreshDocuments() {
    const response = await fetch(`${apiBase}/api/documents`);
    if (response.ok) {
      setDocuments(await response.json());
    }
  }

  async function run(label, task) {
    setBusy(label);
    setError('');
    try {
      await task();
    } catch (err) {
      setError(err.message || '요청을 처리하지 못했습니다.');
    } finally {
      setBusy('');
    }
  }

  async function ingestWeb(event) {
    event.preventDefault();
    await run('web', async () => {
      const response = await fetch(`${apiBase}/api/sources/web`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: webUrl }),
      });
      await requireOk(response);
      setWebUrl('');
      await refreshDocuments();
    });
  }

  async function ingestFile(event) {
    event.preventDefault();
    if (!file) return;

    await run('file', async () => {
      const body = new FormData();
      body.append('file', file);
      const response = await fetch(`${apiBase}/api/sources/files`, { method: 'POST', body });
      await requireOk(response);
      setFile(null);
      event.currentTarget.reset();
      await refreshDocuments();
    });
  }

  async function search(event) {
    event.preventDefault();
    await run('search', async () => {
      const response = await fetch(`${apiBase}/api/search`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, limit: 8 }),
      });
      await requireOk(response);
      setSearchResults(await response.json());
    });
  }

  async function ask(event) {
    event.preventDefault();
    await run('ask', async () => {
      const response = await fetch(`${apiBase}/api/rag/ask`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
      });
      await requireOk(response);
      setAnswer(await response.json());
    });
  }

  async function deleteDocument(documentId, title) {
    if (!window.confirm(`'${title}' 문서를 삭제할까요?`)) {
      return;
    }
    await run(`delete-${documentId}`, async () => {
      const response = await fetch(`${apiBase}/api/documents/${documentId}`, { method: 'DELETE' });
      await requireOk(response);
      await refreshDocuments();
      setSearchResults((current) => current.filter((result) => result.documentId !== documentId));
      if (answer?.citations?.some((result) => result.documentId === documentId)) {
        setAnswer(null);
      }
    });
  }

  async function reindexDocument(documentId) {
    await run(`reindex-${documentId}`, async () => {
      const response = await fetch(`${apiBase}/api/documents/${documentId}/reindex`, { method: 'POST' });
      await requireOk(response);
      await refreshDocuments();
    });
  }

  const loading = (name) => busy === name;

  return (
    <main className="shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Bot size={22} />
          </div>
          <div>
            <span>LearnBot</span>
            <small>로컬 지식 작업공간</small>
          </div>
        </div>

        <div className="side-section">
          <span className="section-label">문서 상태</span>
          <div className="metric-grid">
            <div className="metric">
              <strong>{documents.length}</strong>
              <span>전체</span>
            </div>
            <div className="metric">
              <strong>{indexedCount}</strong>
              <span>색인 완료</span>
            </div>
          </div>
        </div>

        <div className="side-section">
          <span className="section-label">소스</span>
          <div className="source-stack">
            <div>
              <Globe size={15} />
              <span>웹</span>
              <strong>{webCount}</strong>
            </div>
            <div>
              <FileSpreadsheet size={15} />
              <span>파일</span>
              <strong>{fileCount}</strong>
            </div>
          </div>
        </div>

        <div className="model">
          <span className="section-label">모델</span>
          <div>
            <small>답변</small>
            <strong>gemma4:e2b</strong>
          </div>
          <div>
            <small>검색</small>
            <strong>bge-m3</strong>
          </div>
        </div>
      </aside>

      <section className="content">
        <header className="topbar">
          <div>
            <span className="eyebrow">Local RAG</span>
            <h1>지식 검색과 질문 답변</h1>
            <p>승인된 웹 문서와 CSV/Excel 파일을 기준으로 답변합니다.</p>
          </div>
          <button className="ghost-button" type="button" onClick={refreshDocuments}>
            <Database size={16} />
            새로고침
          </button>
        </header>

        {error && <div className="alert">{error}</div>}

        <section className="workspace-grid">
          <div className="left-column">
            <section className="panel">
              <div className="panel-title">
                <Database size={18} />
                <div>
                  <h2>소스 추가</h2>
                  <p>웹 URL 또는 표 파일을 색인합니다.</p>
                </div>
              </div>

              <form className="stack" onSubmit={ingestWeb}>
                <label htmlFor="web-url">웹 URL</label>
                <div className="inline-control">
                  <input
                    id="web-url"
                    value={webUrl}
                    onChange={(event) => setWebUrl(event.target.value)}
                    placeholder="https://example.com/docs"
                  />
                  <button disabled={!webUrl || loading('web')}>
                    {loading('web') ? <Loader2 className="spin" size={16} /> : <Globe size={16} />}
                    색인
                  </button>
                </div>
              </form>

              <form className="stack" onSubmit={ingestFile}>
                <label htmlFor="file-upload">CSV / Excel</label>
                <div className="file-row">
                  <label className="file-picker" htmlFor="file-upload">
                    <FileUp size={16} />
                    <span>{file ? file.name : '파일 선택'}</span>
                  </label>
                  <input
                    id="file-upload"
                    className="visually-hidden"
                    type="file"
                    accept=".csv,.xls,.xlsx"
                    onChange={(event) => setFile(event.target.files?.[0] ?? null)}
                  />
                  <button disabled={!file || loading('file')}>
                    {loading('file') ? <Loader2 className="spin" size={16} /> : <FileUp size={16} />}
                    업로드
                  </button>
                </div>
              </form>
            </section>

            <section className="panel documents-panel">
              <div className="panel-title">
                <Database size={18} />
                <div>
                  <h2>문서 목록</h2>
                  <p>{documents.length ? `최근 ${latestDocuments.length}개 문서` : '색인된 문서가 없습니다.'}</p>
                </div>
              </div>
              <div className="document-list">
                {latestDocuments.map((doc) => (
                  <article className="document-row" key={doc.id}>
                    <div className="document-main">
                      <strong>{doc.title}</strong>
                      <small>{doc.sourceUri || doc.contentType || '원본 정보 없음'}</small>
                    </div>
                    <div className="document-meta">
                      <StatusBadge status={doc.sourceStatus} />
                      <small>{getSourceLabel(doc.sourceType)} · {formatDate(doc.createdAt)}</small>
                    </div>
                    <div className="document-actions">
                      <button
                        className="icon-button"
                        type="button"
                        title="재색인"
                        disabled={loading(`reindex-${doc.id}`) || loading(`delete-${doc.id}`)}
                        onClick={() => reindexDocument(doc.id)}
                      >
                        {loading(`reindex-${doc.id}`) ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
                      </button>
                      <button
                        className="icon-button danger"
                        type="button"
                        title="삭제"
                        disabled={loading(`reindex-${doc.id}`) || loading(`delete-${doc.id}`)}
                        onClick={() => deleteDocument(doc.id, doc.title)}
                      >
                        {loading(`delete-${doc.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                      </button>
                    </div>
                  </article>
                ))}
                {documents.length === 0 && <p className="empty">웹 URL이나 파일을 추가하면 여기에 표시됩니다.</p>}
              </div>
            </section>
          </div>

          <div className="right-column">
            <form className="panel ask-panel" onSubmit={ask}>
              <div className="panel-title">
                <MessageSquare size={18} />
                <div>
                  <h2>질문하기</h2>
                  <p>색인된 문서를 근거로 답변을 생성합니다.</p>
                </div>
              </div>
              <textarea
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                placeholder="예: 업로드한 문서에서 재고가 없는 상품을 알려줘"
              />
              <div className="action-row">
                <button disabled={!question || loading('ask')}>
                  {loading('ask') ? <Loader2 className="spin" size={16} /> : <MessageSquare size={16} />}
                  답변 생성
                </button>
              </div>
              {answer && (
                <div className="answer">
                  <div className="answer-title">
                    <CheckCircle2 size={16} />
                    <strong>답변</strong>
                  </div>
                  <p>{answer.answer}</p>
                  <ResultList results={answer.citations} compact title="참고 문서" />
                </div>
              )}
            </form>

            <form className="panel search-panel" onSubmit={search}>
              <div className="panel-title">
                <Search size={18} />
                <div>
                  <h2>검색</h2>
                  <p>벡터 검색과 키워드 검색 결과를 함께 확인합니다.</p>
                </div>
              </div>
              <div className="inline-control">
                <input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="검색어를 입력하세요"
                />
                <button disabled={!query || loading('search')}>
                  {loading('search') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
                  검색
                </button>
              </div>
              <ResultList results={searchResults} title="검색 결과" />
            </form>
          </div>
        </section>
      </section>

      <button
        className="help-button"
        type="button"
        title="사용 방법"
        aria-label="사용 방법"
        onClick={() => setHelpOpen(true)}
      >
        <CircleHelp size={22} />
      </button>

      {helpOpen && (
        <div className="help-backdrop" role="presentation" onClick={() => setHelpOpen(false)}>
          <aside className="help-panel" role="dialog" aria-modal="true" aria-labelledby="help-title" onClick={(event) => event.stopPropagation()}>
            <div className="help-header">
              <div>
                <span className="eyebrow">Guide</span>
                <h2 id="help-title">사용 방법</h2>
              </div>
              <button className="icon-button" type="button" title="닫기" onClick={() => setHelpOpen(false)}>
                <X size={16} />
              </button>
            </div>

            <div className="help-content">
              <section>
                <h3>1. 문서 추가</h3>
                <p>웹 URL은 허용 도메인만 색인됩니다. CSV, XLS, XLSX 파일은 업로드하면 원본이 MinIO에 저장되고 내용이 색인됩니다.</p>
              </section>
              <section>
                <h3>2. 검색</h3>
                <p>키워드를 입력하면 색인된 문서 조각 중 관련도가 높은 결과를 확인할 수 있습니다.</p>
              </section>
              <section>
                <h3>3. 질문</h3>
                <p>질문을 입력하면 검색된 문서 내용을 근거로 답변합니다. LLM이 실패하면 관련 근거 문서를 반환합니다.</p>
              </section>
              <section>
                <h3>4. 관리</h3>
                <p>문서 목록의 회전 아이콘은 재색인, 휴지통 아이콘은 삭제입니다. 파일 재색인은 저장된 원본 파일을 다시 사용합니다.</p>
              </section>
            </div>
          </aside>
        </div>
      )}
    </main>
  );
}

function ResultList({ results, compact = false, title }) {
  if (!results?.length) return null;
  return (
    <div className={compact ? 'results compact' : 'results'}>
      {title && <h3>{title}</h3>}
      {results.map((result, index) => (
        <article className="result" key={`${result.chunkId}-${index}`}>
          <div className="result-heading">
            <strong>{index + 1}. {result.title}</strong>
            <span>{formatScore(result.score)}</span>
          </div>
          <small>{getSourceLabel(result.sourceType)} · chunk {result.chunkIndex + 1}</small>
          {!compact && <p>{result.content}</p>}
        </article>
      ))}
    </div>
  );
}

function StatusBadge({ status }) {
  return <span className={`status status-${String(status).toLowerCase()}`}>{statusLabels[status] ?? status}</span>;
}

function getSourceLabel(sourceType) {
  return sourceLabels[sourceType] ?? sourceType ?? '알 수 없음';
}

function formatScore(score) {
  const numericScore = Number(score);
  if (Number.isNaN(numericScore)) return '-';
  return numericScore.toFixed(3);
}

function formatDate(value) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

async function requireOk(response) {
  if (response.ok) return;
  const body = await response.json().catch(() => ({}));
  throw new Error(body.detail || `HTTP ${response.status}`);
}

createRoot(document.getElementById('root')).render(<App />);

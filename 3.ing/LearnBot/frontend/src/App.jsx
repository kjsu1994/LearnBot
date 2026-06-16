import { useEffect, useMemo, useState } from 'react';
import {
  Bot,
  CheckCircle2,
  CircleHelp,
  Code2,
  Database,
  FileCode2,
  FileSpreadsheet,
  FileUp,
  GitBranch,
  GitPullRequest,
  Globe,
  Info,
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
  RUNNING: '실행 중',
  CANCELLING: '취소 중',
  CANCELLED: '취소됨',
  SUCCEEDED: '완료',
};

const answerModes = [
  { value: 'qa', label: '질문 답변' },
  { value: 'summary', label: '요약' },
  { value: 'table', label: '표에서 찾기' },
  { value: 'quote', label: '원문 인용' },
];

const codeModes = [
  { value: 'locate', label: '기능 위치' },
  { value: 'method', label: '메서드 설명' },
  { value: 'flow', label: '호출 흐름' },
  { value: 'ui_event', label: 'UI 이벤트' },
  { value: 'impact', label: '영향 범위' },
];

function App() {
  const [activeView, setActiveView] = useState('code');
  const [documents, setDocuments] = useState([]);
  const [repositories, setRepositories] = useState([]);
  const [jobs, setJobs] = useState({});
  const [codeFiles, setCodeFiles] = useState([]);
  const [fileQuery, setFileQuery] = useState('');
  const [selectedCodeFile, setSelectedCodeFile] = useState(null);
  const [highlightRange, setHighlightRange] = useState(null);
  const [webUrl, setWebUrl] = useState('');
  const [file, setFile] = useState(null);
  const [query, setQuery] = useState('');
  const [question, setQuestion] = useState('');
  const [answerMode, setAnswerMode] = useState('qa');
  const [searchResults, setSearchResults] = useState([]);
  const [answer, setAnswer] = useState(null);
  const [selectedDocumentId, setSelectedDocumentId] = useState('');
  const [documentDetail, setDocumentDetail] = useState(null);
  const [repoForm, setRepoForm] = useState({
    gitUrl: '',
    name: '',
    branch: 'HEAD',
    authType: 'NONE',
    username: '',
    token: '',
    storeToken: false,
  });
  const [indexCredential, setIndexCredential] = useState({
    username: '',
    token: '',
    storeToken: true,
  });
  const [selectedRepositoryId, setSelectedRepositoryId] = useState('');
  const [codeQuestion, setCodeQuestion] = useState('');
  const [codeMode, setCodeMode] = useState('locate');
  const [codeAnswer, setCodeAnswer] = useState(null);
  const [referenceSymbol, setReferenceSymbol] = useState('');
  const [referenceResult, setReferenceResult] = useState(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [helpOpen, setHelpOpen] = useState(false);

  useEffect(() => {
    refreshDocuments();
    refreshRepositories();
  }, []);

  useEffect(() => {
    if (!selectedRepositoryId) {
      setCodeFiles([]);
      setSelectedCodeFile(null);
      return;
    }
    refreshJobs(selectedRepositoryId);
    refreshCodeFiles(selectedRepositoryId, fileQuery);
  }, [selectedRepositoryId]);

  useEffect(() => {
    const indexingRepos = repositories.filter((repo) => repo.status === 'INDEXING');
    if (!indexingRepos.length) return undefined;
    const timer = window.setInterval(() => {
      refreshRepositories();
      indexingRepos.forEach((repo) => refreshJobs(repo.id));
    }, 2500);
    return () => window.clearInterval(timer);
  }, [repositories]);

  useEffect(() => {
    const selected = repositories.find((repo) => repo.id === selectedRepositoryId);
    if (selected?.status === 'INDEXED') {
      refreshCodeFiles(selectedRepositoryId, fileQuery);
    }
  }, [repositories, selectedRepositoryId]);

  const indexedCount = useMemo(
    () => documents.filter((doc) => doc.sourceStatus === 'INDEXED').length,
    [documents],
  );

  const indexedRepoCount = useMemo(
    () => repositories.filter((repo) => repo.status === 'INDEXED').length,
    [repositories],
  );

  const codeChunkCount = repositories.reduce((sum, repo) => sum + Number(repo.activeChunkCount || 0), 0);
  const webCount = documents.filter((doc) => doc.sourceType === 'WEB').length;
  const fileCount = documents.length - webCount;
  const latestDocuments = documents.slice(0, 8);

  async function refreshDocuments() {
    const response = await fetch(`${apiBase}/api/documents`);
    if (response.ok) {
      setDocuments(await response.json());
    }
  }

  async function refreshRepositories() {
    const response = await fetch(`${apiBase}/api/code/repositories`);
    if (response.ok) {
      const data = await response.json();
      setRepositories(data);
      if (!selectedRepositoryId && data.length) {
        setSelectedRepositoryId(data[0].id);
      }
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
        body: JSON.stringify({ question, mode: answerMode }),
      });
      await requireOk(response);
      setAnswer(await response.json());
    });
  }

  async function registerRepository(event) {
    event.preventDefault();
    await run('repo-register', async () => {
      const response = await fetch(`${apiBase}/api/code/repositories`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          gitUrl: repoForm.gitUrl,
          name: repoForm.name,
          branch: repoForm.branch,
          authType: repoForm.authType,
          username: repoForm.username,
          token: repoForm.token,
          storeToken: repoForm.authType === 'TOKEN' && repoForm.storeToken,
        }),
      });
      await requireOk(response);
      const created = await response.json();
      setSelectedRepositoryId(created.id);
      setRepoForm((current) => ({ ...current, gitUrl: '', name: '' }));
      await refreshRepositories();
    });
  }

  async function indexRepository(repositoryId) {
    await run(`repo-index-${repositoryId}`, async () => {
      const targetRepository = repositories.find((repo) => repo.id === repositoryId);
      const tokenRequired = targetRepository?.authType === 'TOKEN' && !targetRepository?.credentialStored;
      if (tokenRequired && !indexCredential.token) {
        setSelectedRepositoryId(repositoryId);
        throw new Error('이 저장소는 토큰 인증이 필요합니다. 선택한 저장소의 재인덱싱 인증 정보를 입력하세요.');
      }
      const response = await fetch(`${apiBase}/api/code/repositories/${repositoryId}/index`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: indexCredential.username,
          token: targetRepository?.authType === 'TOKEN' ? indexCredential.token : '',
          storeToken: targetRepository?.authType === 'TOKEN' && indexCredential.storeToken,
        }),
      });
      await requireOk(response);
      if (indexCredential.token) {
        setIndexCredential((current) => ({ ...current, token: '' }));
      }
      await refreshRepositories();
      await refreshJobs(repositoryId);
    });
  }

  async function refreshJobs(repositoryId) {
    const response = await fetch(`${apiBase}/api/code/repositories/${repositoryId}/jobs`);
    if (response.ok) {
      const data = await response.json();
      setJobs((current) => ({ ...current, [repositoryId]: data }));
    }
  }

  async function refreshCodeFiles(repositoryId = selectedRepositoryId, queryText = fileQuery) {
    if (!repositoryId) return;
    const params = new URLSearchParams();
    if (queryText) params.set('query', queryText);
    params.set('limit', '80');
    const response = await fetch(`${apiBase}/api/code/repositories/${repositoryId}/files?${params.toString()}`);
    if (response.ok) {
      setCodeFiles(await response.json());
    }
  }

  async function searchCodeFiles(event) {
    event.preventDefault();
    await refreshCodeFiles(selectedRepositoryId, fileQuery);
  }

  async function cancelIndex(repositoryId, jobId) {
    await run(`repo-cancel-${jobId}`, async () => {
      const response = await fetch(`${apiBase}/api/code/repositories/${repositoryId}/jobs/${jobId}/cancel`, {
        method: 'POST',
      });
      await requireOk(response);
      await refreshRepositories();
      await refreshJobs(repositoryId);
    });
  }

  async function deleteRepository(repositoryId, name) {
    if (!window.confirm(`'${name}' 저장소와 인덱싱 데이터를 삭제할까요?`)) return;
    await run(`repo-delete-${repositoryId}`, async () => {
      const response = await fetch(`${apiBase}/api/code/repositories/${repositoryId}`, { method: 'DELETE' });
      await requireOk(response);
      setRepositories((current) => current.filter((repo) => repo.id !== repositoryId));
      setJobs((current) => {
        const next = { ...current };
        delete next[repositoryId];
        return next;
      });
      if (selectedRepositoryId === repositoryId) {
        setSelectedRepositoryId('');
        setCodeFiles([]);
        setSelectedCodeFile(null);
        setReferenceResult(null);
      }
      await refreshRepositories();
    });
  }

  async function clearFailedJobs(repositoryId) {
    await run(`repo-clear-jobs-${repositoryId}`, async () => {
      const response = await fetch(`${apiBase}/api/code/repositories/${repositoryId}/jobs`, { method: 'DELETE' });
      await requireOk(response);
      await refreshJobs(repositoryId);
    });
  }

  async function openCodeFile(repositoryId, fileId, range = null) {
    await run(`code-file-${fileId}`, async () => {
      const response = await fetch(`${apiBase}/api/code/repositories/${repositoryId}/files/${fileId}`);
      await requireOk(response);
      setSelectedCodeFile(await response.json());
      setHighlightRange(range);
    });
  }

  async function askCode(event) {
    event.preventDefault();
    await run('code-ask', async () => {
      const response = await fetch(`${apiBase}/api/code/ask`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          repositoryId: selectedRepositoryId || null,
          question: codeQuestion,
          mode: codeMode,
          limit: 10,
        }),
      });
      await requireOk(response);
      setCodeAnswer(await response.json());
    });
  }

  async function findReferences(event) {
    event.preventDefault();
    await run('code-references', async () => {
      const response = await fetch(`${apiBase}/api/code/references`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          repositoryId: selectedRepositoryId || null,
          symbol: referenceSymbol,
          limit: 24,
        }),
      });
      await requireOk(response);
      setReferenceResult(await response.json());
    });
  }

  async function deleteDocument(documentId, title) {
    if (!window.confirm(`'${title}' 문서를 삭제할까요?`)) return;
    await run(`delete-${documentId}`, async () => {
      const response = await fetch(`${apiBase}/api/documents/${documentId}`, { method: 'DELETE' });
      await requireOk(response);
      await refreshDocuments();
      if (selectedDocumentId === documentId) {
        setSelectedDocumentId('');
        setDocumentDetail(null);
      }
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
      const result = await response.json();
      await refreshDocuments();
      setSelectedDocumentId(result.documentId);
      await loadDocumentDetail(result.documentId);
    });
  }

  async function loadDocumentDetail(documentId) {
    setSelectedDocumentId(documentId);
    await run(`detail-${documentId}`, async () => {
      const response = await fetch(`${apiBase}/api/documents/${documentId}`);
      await requireOk(response);
      setDocumentDetail(await response.json());
    });
  }

  const loading = (name) => busy === name;
  const progressMessage = getProgressMessage(busy);

  return (
    <main className="shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Bot size={22} />
          </div>
          <div>
            <span>LearnBot</span>
            <small>로컬 코드/문서 RAG</small>
          </div>
        </div>

        <div className="side-section">
          <span className="section-label">인덱스 상태</span>
          <div className="metric-grid">
            <div className="metric">
              <strong>{indexedRepoCount}</strong>
              <span>코드 저장소</span>
            </div>
            <div className="metric">
              <strong>{indexedCount}</strong>
              <span>문서</span>
            </div>
          </div>
        </div>

        <div className="side-section">
          <span className="section-label">데이터</span>
          <div className="source-stack">
            <div>
              <FileCode2 size={15} />
              <span>코드 근거</span>
              <strong>{codeChunkCount}</strong>
            </div>
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
            <strong>gemma4:e2b-it-qat</strong>
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
            <span className="eyebrow">Private Assistant</span>
            <h1>코드와 문서를 근거로 답변합니다</h1>
            <p>사내 Git 저장소와 업무 문서를 로컬에서 색인하고, 근거가 있는 답변만 생성합니다.</p>
          </div>
          <div className="top-actions">
            <button className="ghost-button" type="button" onClick={refreshRepositories}>
              <GitPullRequest size={16} />
              저장소 새로고침
            </button>
            <button className="ghost-button" type="button" onClick={refreshDocuments}>
              <Database size={16} />
              문서 새로고침
            </button>
          </div>
        </header>

        <div className="view-tabs" aria-label="작업 영역">
          <button className={activeView === 'code' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => setActiveView('code')}>
            <Code2 size={16} />
            코드 RAG
          </button>
          <button className={activeView === 'docs' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => setActiveView('docs')}>
            <Database size={16} />
            문서 RAG
          </button>
        </div>

        {error && <div className="alert">{error}</div>}
        {progressMessage && <div className="progress-banner"><Loader2 className="spin" size={16} />{progressMessage}</div>}

        {activeView === 'code' ? (
          <CodeWorkspace
            repoForm={repoForm}
            setRepoForm={setRepoForm}
            indexCredential={indexCredential}
            setIndexCredential={setIndexCredential}
            repositories={repositories}
            selectedRepositoryId={selectedRepositoryId}
            setSelectedRepositoryId={setSelectedRepositoryId}
            jobs={jobs}
            codeFiles={codeFiles}
            fileQuery={fileQuery}
            setFileQuery={setFileQuery}
            selectedCodeFile={selectedCodeFile}
            highlightRange={highlightRange}
            codeQuestion={codeQuestion}
            setCodeQuestion={setCodeQuestion}
            codeMode={codeMode}
            setCodeMode={setCodeMode}
            codeAnswer={codeAnswer}
            referenceSymbol={referenceSymbol}
            setReferenceSymbol={setReferenceSymbol}
            referenceResult={referenceResult}
            registerRepository={registerRepository}
            indexRepository={indexRepository}
            cancelIndex={cancelIndex}
            deleteRepository={deleteRepository}
            clearFailedJobs={clearFailedJobs}
            refreshJobs={refreshJobs}
            refreshCodeFiles={refreshCodeFiles}
            searchCodeFiles={searchCodeFiles}
            openCodeFile={openCodeFile}
            askCode={askCode}
            findReferences={findReferences}
            loading={loading}
          />
        ) : (
          <DocumentWorkspace
            webUrl={webUrl}
            setWebUrl={setWebUrl}
            file={file}
            setFile={setFile}
            ingestWeb={ingestWeb}
            ingestFile={ingestFile}
            documents={documents}
            latestDocuments={latestDocuments}
            selectedDocumentId={selectedDocumentId}
            loadDocumentDetail={loadDocumentDetail}
            reindexDocument={reindexDocument}
            deleteDocument={deleteDocument}
            documentDetail={documentDetail}
            answerMode={answerMode}
            setAnswerMode={setAnswerMode}
            question={question}
            setQuestion={setQuestion}
            ask={ask}
            answer={answer}
            query={query}
            setQuery={setQuery}
            search={search}
            searchResults={searchResults}
            loading={loading}
          />
        )}
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

      {helpOpen && <HelpPanel onClose={() => setHelpOpen(false)} />}
    </main>
  );
}

function CodeWorkspace({
  repoForm,
  setRepoForm,
  indexCredential,
  setIndexCredential,
  repositories,
  selectedRepositoryId,
  setSelectedRepositoryId,
  jobs,
  codeFiles,
  fileQuery,
  setFileQuery,
  selectedCodeFile,
  highlightRange,
  codeQuestion,
  setCodeQuestion,
  codeMode,
  setCodeMode,
  codeAnswer,
  referenceSymbol,
  setReferenceSymbol,
  referenceResult,
  registerRepository,
  indexRepository,
  cancelIndex,
  deleteRepository,
  clearFailedJobs,
  refreshJobs,
  refreshCodeFiles,
  searchCodeFiles,
  openCodeFile,
  askCode,
  findReferences,
  loading,
}) {
  const selectedRepository = repositories.find((repo) => repo.id === selectedRepositoryId);

  return (
    <section className="workspace-grid code-grid">
      <div className="left-column">
        <section className="panel">
          <div className="panel-title">
            <GitBranch size={18} />
            <div>
              <h2>Git 저장소 등록</h2>
              <p>사내 Git, GitHub, GitLab, Enterprise HTTP/HTTPS 주소를 등록합니다.</p>
            </div>
          </div>

          <form className="stack" onSubmit={registerRepository}>
            <label htmlFor="git-url">Git URL</label>
            <input
              id="git-url"
              value={repoForm.gitUrl}
              onChange={(event) => setRepoForm((current) => ({ ...current, gitUrl: event.target.value }))}
              placeholder="http://192.168.1.146/Git/BRSE_Controller_WPF.git"
            />
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="repo-name">표시 이름</label>
                <input
                  id="repo-name"
                  value={repoForm.name}
                  onChange={(event) => setRepoForm((current) => ({ ...current, name: event.target.value }))}
                  placeholder="BRSE Controller"
                />
              </div>
              <div className="stack">
                <label htmlFor="repo-branch">Branch</label>
                <input
                  id="repo-branch"
                  value={repoForm.branch}
                  onChange={(event) => setRepoForm((current) => ({ ...current, branch: event.target.value }))}
                  placeholder="HEAD 또는 main"
                />
              </div>
            </div>
            <div className="mode-control auth-control" aria-label="Git 인증 방식">
              <button
                className={repoForm.authType === 'NONE' ? 'mode-button active' : 'mode-button'}
                type="button"
                onClick={() => setRepoForm((current) => ({ ...current, authType: 'NONE' }))}
              >
                인증 없음
              </button>
              <button
                className={repoForm.authType === 'TOKEN' ? 'mode-button active' : 'mode-button'}
                type="button"
                onClick={() => setRepoForm((current) => ({ ...current, authType: 'TOKEN' }))}
              >
                토큰
              </button>
            </div>
            {repoForm.authType === 'TOKEN' && (
              <>
                <div className="form-grid two">
                  <div className="stack">
                    <label htmlFor="git-username">Username</label>
                    <input
                      id="git-username"
                      value={repoForm.username}
                      onChange={(event) => setRepoForm((current) => ({ ...current, username: event.target.value }))}
                      placeholder="비워두면 oauth2"
                    />
                  </div>
                  <div className="stack">
                    <label htmlFor="git-token">Token</label>
                    <input
                      id="git-token"
                      type="password"
                      value={repoForm.token}
                      onChange={(event) => setRepoForm((current) => ({ ...current, token: event.target.value }))}
                      placeholder="저장하지 않으면 요청 때만 사용"
                    />
                  </div>
                </div>
                <label className="checkbox-row" htmlFor="store-token">
                  <input
                    id="store-token"
                    type="checkbox"
                    checked={repoForm.storeToken}
                    onChange={(event) => setRepoForm((current) => ({ ...current, storeToken: event.target.checked }))}
                  />
                  <span>토큰을 암호화해서 저장하고 다음 색인부터 재사용</span>
                </label>
              </>
            )}
            <div className="action-row">
              <button disabled={!repoForm.gitUrl || loading('repo-register')}>
                {loading('repo-register') ? <Loader2 className="spin" size={16} /> : <GitBranch size={16} />}
                저장소 등록
              </button>
            </div>
          </form>
        </section>

        <section className="panel documents-panel">
          <div className="panel-title">
            <FileCode2 size={18} />
            <div>
              <h2>저장소 목록</h2>
              <p>{repositories.length ? `${repositories.length}개 저장소` : '등록된 코드 저장소가 없습니다.'}</p>
            </div>
          </div>
          <div className="document-list">
            {repositories.map((repo) => {
              const latestJob = jobs[repo.id]?.[0];
              const runningJob = jobs[repo.id]?.find((job) => job.status === 'RUNNING' || job.status === 'CANCELLING');
              return (
                <article
                  className={repo.id === selectedRepositoryId ? 'document-row selected repo-row' : 'document-row repo-row'}
                  key={repo.id}
                  onClick={() => setSelectedRepositoryId(repo.id)}
                >
                  <div className="document-main">
                    <strong>{repo.name}</strong>
                    <small>{repo.gitUrl}</small>
                    {repo.credentialStored && <small className="success-note">암호화된 Git 토큰 저장됨</small>}
                  </div>
                  <div className="document-meta">
                    <StatusBadge status={repo.status} />
                    <small>{repo.branch} · {repo.activeFileCount} files · {repo.activeChunkCount} chunks</small>
                  </div>
                  <div className="document-actions">
                    <button
                      className="icon-button"
                      type="button"
                      title="작업 이력"
                      onClick={(event) => {
                        event.stopPropagation();
                        refreshJobs(repo.id);
                      }}
                    >
                      <Info size={15} />
                    </button>
                    <button
                      className="icon-button"
                      type="button"
                      title="실패/취소 이력 정리"
                      disabled={loading(`repo-clear-jobs-${repo.id}`)}
                      onClick={(event) => {
                        event.stopPropagation();
                        clearFailedJobs(repo.id);
                      }}
                    >
                      {loading(`repo-clear-jobs-${repo.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                    </button>
                    {runningJob ? (
                      <button
                        className="icon-button danger"
                        type="button"
                        title="색인 취소"
                        disabled={runningJob.status === 'CANCELLING' || loading(`repo-cancel-${runningJob.id}`)}
                        onClick={(event) => {
                          event.stopPropagation();
                          cancelIndex(repo.id, runningJob.id);
                        }}
                      >
                        {loading(`repo-cancel-${runningJob.id}`) ? <Loader2 className="spin" size={15} /> : <X size={15} />}
                      </button>
                    ) : (
                      <button
                        className="icon-button"
                        type="button"
                        title="인덱싱"
                        disabled={loading(`repo-index-${repo.id}`)}
                        onClick={(event) => {
                          event.stopPropagation();
                          indexRepository(repo.id);
                        }}
                      >
                        {loading(`repo-index-${repo.id}`) ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
                      </button>
                    )}
                    <button
                      className="icon-button danger"
                      type="button"
                      title="저장소 삭제"
                      disabled={!!runningJob || loading(`repo-delete-${repo.id}`)}
                      onClick={(event) => {
                        event.stopPropagation();
                        deleteRepository(repo.id, repo.name);
                      }}
                    >
                      {loading(`repo-delete-${repo.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                    </button>
                  </div>
                  {latestJob && (
                    <div className="job-strip">
                      <span>
                        {latestJob.status} · {latestJob.processedFiles}/{latestJob.totalFiles || '-'} files · {latestJob.totalChunks} chunks
                      </span>
                      <div className="progress-track" aria-label="인덱싱 진행률">
                        <span style={{ width: `${jobPercent(latestJob)}%` }} />
                      </div>
                    </div>
                  )}
                </article>
              );
            })}
            {repositories.length === 0 && <p className="empty">Git URL을 등록한 뒤 인덱싱 버튼을 누르세요.</p>}
          </div>
        </section>

        {selectedRepository?.authType === 'TOKEN' && (
          <section className="panel compact-auth-panel">
            <div className="panel-title">
              <GitPullRequest size={18} />
              <div>
                <h2>재인덱싱 인증</h2>
                <p>
                  {selectedRepository.credentialStored
                    ? '저장된 토큰을 사용합니다. 새 토큰을 입력하면 다음 인덱싱 때 갱신할 수 있습니다.'
                    : '이 저장소는 저장된 토큰이 없습니다. 인덱싱 전에 token을 입력하세요.'}
                </p>
              </div>
            </div>
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="index-username">Username</label>
                <input
                  id="index-username"
                  value={indexCredential.username}
                  onChange={(event) => setIndexCredential((current) => ({ ...current, username: event.target.value }))}
                  placeholder="비워두면 oauth2"
                />
              </div>
              <div className="stack">
                <label htmlFor="index-token">Token</label>
                <input
                  id="index-token"
                  type="password"
                  value={indexCredential.token}
                  onChange={(event) => setIndexCredential((current) => ({ ...current, token: event.target.value }))}
                  placeholder={selectedRepository.credentialStored ? '새 토큰으로 갱신할 때만 입력' : '인덱싱에 사용할 token'}
                />
              </div>
            </div>
            <label className="checkbox-row" htmlFor="index-store-token">
              <input
                id="index-store-token"
                type="checkbox"
                checked={indexCredential.storeToken}
                onChange={(event) => setIndexCredential((current) => ({ ...current, storeToken: event.target.checked }))}
              />
              <span>입력한 토큰을 암호화해서 저장</span>
            </label>
          </section>
        )}

        <section className="panel file-browser-panel">
          <div className="panel-title">
            <FileCode2 size={18} />
            <div>
              <h2>코드 파일</h2>
              <p>{selectedRepository ? `${codeFiles.length}개 파일 표시 중` : '저장소를 선택하면 파일 목록을 볼 수 있습니다.'}</p>
            </div>
          </div>
          <form className="inline-control" onSubmit={searchCodeFiles}>
            <input
              value={fileQuery}
              onChange={(event) => setFileQuery(event.target.value)}
              placeholder="MainWindow.xaml, Login, Controller..."
              disabled={!selectedRepositoryId}
            />
            <button disabled={!selectedRepositoryId}>
              <Search size={16} />
              찾기
            </button>
          </form>
          <div className="file-list">
            {codeFiles.slice(0, 80).map((fileItem) => (
              <button
                className="file-list-row"
                key={fileItem.id}
                type="button"
                onClick={() => openCodeFile(fileItem.repositoryId, fileItem.id)}
              >
                <span>{fileItem.filePath}</span>
                <small>{fileItem.language} · {fileItem.chunkCount} chunks</small>
              </button>
            ))}
            {selectedRepositoryId && !codeFiles.length && <p className="empty">색인된 파일이 없거나 검색 결과가 없습니다.</p>}
          </div>
        </section>
      </div>

      <div className="right-column">
        <form className="panel ask-panel" onSubmit={askCode}>
          <div className="panel-title">
            <MessageSquare size={18} />
            <div>
              <h2>코드에게 질문하기</h2>
              <p>파일, 클래스, 메서드, UI 이벤트 흐름을 근거 코드와 함께 답변합니다.</p>
            </div>
          </div>

          <div className="stack">
            <label htmlFor="repo-select">질문 대상</label>
            <select
              id="repo-select"
              value={selectedRepositoryId}
              onChange={(event) => setSelectedRepositoryId(event.target.value)}
            >
              <option value="">전체 저장소</option>
              {repositories.map((repo) => (
                <option key={repo.id} value={repo.id}>{repo.name}</option>
              ))}
            </select>
          </div>

          <div className="mode-control code-mode-control" aria-label="코드 질문 모드">
            {codeModes.map((mode) => (
              <button
                className={codeMode === mode.value ? 'mode-button active' : 'mode-button'}
                key={mode.value}
                type="button"
                onClick={() => setCodeMode(mode.value)}
              >
                {mode.label}
              </button>
            ))}
          </div>

          <textarea
            value={codeQuestion}
            onChange={(event) => setCodeQuestion(event.target.value)}
            placeholder="예: MainWindow.xaml의 버튼 클릭 이벤트는 어디서 처리돼?"
          />
          <div className="action-row">
            <button disabled={!codeQuestion || loading('code-ask')}>
              {loading('code-ask') ? <Loader2 className="spin" size={16} /> : <MessageSquare size={16} />}
              {loading('code-ask') ? '코드 검색 및 답변 생성 중' : '코드 질문'}
            </button>
          </div>

          {selectedRepository && (
            <div className="detail-box compact-box">
              <strong>{selectedRepository.name}</strong>
              <small>{selectedRepository.lastIndexedCommit ? `commit ${selectedRepository.lastIndexedCommit.slice(0, 12)}` : '아직 색인된 commit이 없습니다.'}</small>
            </div>
          )}

          {codeAnswer && (
            <div className="answer">
              <div className="answer-title">
                <CheckCircle2 size={16} />
                <strong>{getCodeModeLabel(codeAnswer.mode)} 답변</strong>
              </div>
              <p>{codeAnswer.answer}</p>
              <CodeEvidenceList evidence={codeAnswer.evidence} onOpenEvidence={openCodeFile} />
            </div>
          )}
        </form>

        <form className="panel reference-panel" onSubmit={findReferences}>
          <div className="panel-title">
            <Search size={18} />
            <div>
              <h2>심볼 참조 찾기</h2>
              <p>메서드, 클래스, 컨트롤 이름으로 정의와 사용 후보를 빠르게 좁힙니다.</p>
            </div>
          </div>
          <div className="inline-control">
            <input
              value={referenceSymbol}
              onChange={(event) => setReferenceSymbol(event.target.value)}
              placeholder="InitializeComponent, SaveData, MainWindow..."
            />
            <button disabled={!referenceSymbol || loading('code-references')}>
              {loading('code-references') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              찾기
            </button>
          </div>
          {referenceResult && (
            <CodeReferenceResults
              result={referenceResult}
              onOpenEvidence={openCodeFile}
            />
          )}
        </form>

        <CodeFileViewer detail={selectedCodeFile} highlightRange={highlightRange} loading={selectedCodeFile && loading(`code-file-${selectedCodeFile.id}`)} />
      </div>
    </section>
  );
}

function DocumentWorkspace(props) {
  return (
    <section className="workspace-grid">
      <div className="left-column">
        <section className="panel">
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>문서 소스 추가</h2>
              <p>허용된 웹 URL 또는 CSV/Excel 파일을 색인합니다.</p>
            </div>
          </div>

          <form className="stack" onSubmit={props.ingestWeb}>
            <label htmlFor="web-url">웹 URL</label>
            <div className="inline-control">
              <input
                id="web-url"
                value={props.webUrl}
                onChange={(event) => props.setWebUrl(event.target.value)}
                placeholder="https://example.com/docs"
              />
              <button disabled={!props.webUrl || props.loading('web')}>
                {props.loading('web') ? <Loader2 className="spin" size={16} /> : <Globe size={16} />}
                색인
              </button>
            </div>
          </form>

          <form className="stack" onSubmit={props.ingestFile}>
            <label htmlFor="file-upload">CSV / Excel</label>
            <div className="file-row">
              <label className="file-picker" htmlFor="file-upload">
                <FileUp size={16} />
                <span>{props.file ? props.file.name : '파일 선택'}</span>
              </label>
              <input
                id="file-upload"
                className="visually-hidden"
                type="file"
                accept=".csv,.xls,.xlsx"
                onChange={(event) => props.setFile(event.target.files?.[0] ?? null)}
              />
              <button disabled={!props.file || props.loading('file')}>
                {props.loading('file') ? <Loader2 className="spin" size={16} /> : <FileUp size={16} />}
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
              <p>{props.documents.length ? `최근 ${props.latestDocuments.length}개 문서` : '색인된 문서가 없습니다.'}</p>
            </div>
          </div>
          <div className="document-list">
            {props.latestDocuments.map((doc) => (
              <article
                className={doc.id === props.selectedDocumentId ? 'document-row selected' : 'document-row'}
                key={doc.id}
                onClick={() => props.loadDocumentDetail(doc.id)}
              >
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
                    disabled={props.loading(`reindex-${doc.id}`) || props.loading(`delete-${doc.id}`)}
                    onClick={(event) => {
                      event.stopPropagation();
                      props.reindexDocument(doc.id);
                    }}
                  >
                    {props.loading(`reindex-${doc.id}`) ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
                  </button>
                  <button
                    className="icon-button danger"
                    type="button"
                    title="삭제"
                    disabled={props.loading(`reindex-${doc.id}`) || props.loading(`delete-${doc.id}`)}
                    onClick={(event) => {
                      event.stopPropagation();
                      props.deleteDocument(doc.id, doc.title);
                    }}
                  >
                    {props.loading(`delete-${doc.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                  </button>
                </div>
              </article>
            ))}
            {props.documents.length === 0 && <p className="empty">웹 URL이나 파일을 추가하면 여기에 표시됩니다.</p>}
          </div>
        </section>

        <DocumentDetailPanel detail={props.documentDetail} loading={props.selectedDocumentId && props.loading(`detail-${props.selectedDocumentId}`)} />
      </div>

      <div className="right-column">
        <form className="panel ask-panel" onSubmit={props.ask}>
          <div className="panel-title">
            <MessageSquare size={18} />
            <div>
              <h2>문서 질문하기</h2>
              <p>색인된 문서를 근거로 답변을 생성합니다.</p>
            </div>
          </div>
          <div className="mode-control" aria-label="답변 모드">
            {answerModes.map((mode) => (
              <button
                className={props.answerMode === mode.value ? 'mode-button active' : 'mode-button'}
                key={mode.value}
                type="button"
                onClick={() => props.setAnswerMode(mode.value)}
              >
                {mode.label}
              </button>
            ))}
          </div>
          <textarea
            value={props.question}
            onChange={(event) => props.setQuestion(event.target.value)}
            placeholder="업로드한 문서에서 찾고 싶은 내용을 질문하세요."
          />
          <div className="action-row">
            <button disabled={!props.question || props.loading('ask')}>
              {props.loading('ask') ? <Loader2 className="spin" size={16} /> : <MessageSquare size={16} />}
              {props.loading('ask') ? '모델 응답 대기 중' : '답변 생성'}
            </button>
          </div>
          {props.answer && (
            <div className="answer">
              <div className="answer-title">
                <CheckCircle2 size={16} />
                <strong>답변</strong>
              </div>
              <small className="answer-mode">{getAnswerModeLabel(props.answer.mode)} 모드</small>
              <p>{props.answer.answer}</p>
              <EvidenceList evidence={props.answer.evidence} />
            </div>
          )}
        </form>

        <form className="panel search-panel" onSubmit={props.search}>
          <div className="panel-title">
            <Search size={18} />
            <div>
              <h2>문서 검색</h2>
              <p>벡터 검색과 키워드 검색 결과를 함께 확인합니다.</p>
            </div>
          </div>
          <div className="inline-control">
            <input
              value={props.query}
              onChange={(event) => props.setQuery(event.target.value)}
              placeholder="검색어를 입력하세요"
            />
            <button disabled={!props.query || props.loading('search')}>
              {props.loading('search') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              검색
            </button>
          </div>
          <ResultList results={props.searchResults} title="검색 결과" />
        </form>
      </div>
    </section>
  );
}

function DocumentDetailPanel({ detail, loading }) {
  if (loading) {
    return (
      <section className="panel detail-panel">
        <div className="panel-title">
          <Info size={18} />
          <div>
            <h2>문서 상세</h2>
            <p>문서 정보를 불러오는 중입니다.</p>
          </div>
        </div>
      </section>
    );
  }

  if (!detail) {
    return (
      <section className="panel detail-panel muted-panel">
        <div className="panel-title">
          <Info size={18} />
          <div>
            <h2>문서 상세</h2>
            <p>문서를 선택하면 색인 내용과 원본 저장 정보를 확인할 수 있습니다.</p>
          </div>
        </div>
      </section>
    );
  }

  const summary = detail.summary;
  return (
    <section className="panel detail-panel">
      <div className="panel-title">
        <Info size={18} />
        <div>
          <h2>문서 상세</h2>
          <p>{summary.title}</p>
        </div>
      </div>

      <dl className="detail-grid">
        <div>
          <dt>소스</dt>
          <dd>{getSourceLabel(summary.sourceType)}</dd>
        </div>
        <div>
          <dt>상태</dt>
          <dd><StatusBadge status={summary.sourceStatus} /></dd>
        </div>
        <div>
          <dt>청크</dt>
          <dd>{detail.chunkCount}개</dd>
        </div>
        <div>
          <dt>생성</dt>
          <dd>{formatDate(summary.createdAt)}</dd>
        </div>
      </dl>

      {detail.storedObject && (
        <div className="detail-box">
          <strong>MinIO 원본</strong>
          <span>{detail.storedObject.originalFilename}</span>
          <small>{detail.storedObject.bucket} · {detail.storedObject.contentType} · {formatBytes(detail.storedObject.sizeBytes)}</small>
        </div>
      )}

      {!!detail.crawlAudits?.length && (
        <div className="detail-box">
          <strong>최근 크롤링</strong>
          {detail.crawlAudits.slice(0, 3).map((audit) => (
            <small key={audit.id}>
              {audit.success ? '성공' : '실패'} · {audit.statusCode ?? '-'} · {audit.message || audit.url}
            </small>
          ))}
        </div>
      )}

      <div className="chunk-list">
        <h3>색인된 내용</h3>
        {detail.chunks.slice(0, 5).map((chunk) => (
          <article className="chunk-card" key={chunk.id}>
            <strong>chunk {chunk.chunkIndex + 1}</strong>
            <p>{chunk.content}</p>
          </article>
        ))}
      </div>
    </section>
  );
}

function EvidenceList({ evidence }) {
  if (!evidence?.length) return null;
  return (
    <div className="evidence-list">
      <h3>답변 근거</h3>
      {evidence.map((item) => (
        <article className="evidence-card" key={`${item.chunkId}-${item.citationNumber}`}>
          <div className="result-heading">
            <strong>[{item.citationNumber}] {item.title}</strong>
            <span>{formatScore(item.score)}</span>
          </div>
          <small>{getSourceLabel(item.sourceType)} · chunk {item.chunkIndex + 1}</small>
          <p>{item.preview}</p>
        </article>
      ))}
    </div>
  );
}

function CodeEvidenceList({ evidence, onOpenEvidence }) {
  if (!evidence?.length) return null;
  return (
    <div className="evidence-list">
      <h3>코드 근거</h3>
      {evidence.map((item) => (
        <article className="evidence-card code-evidence" key={`${item.chunkId}-${item.citationNumber}`}>
          <div className="result-heading">
            <strong>[{item.citationNumber}] {item.filePath}:{item.lineStart}-{item.lineEnd}</strong>
            <span>{formatScore(item.score)}</span>
          </div>
          <small>
            {item.chunkType}
            {item.className ? ` · ${item.className}` : ''}
            {item.methodName ? `.${item.methodName}` : ''}
            {item.controlName ? ` · ${item.controlName}` : ''}
          </small>
          <p>{item.preview}</p>
          <div className="action-row">
            <button
              className="ghost-button compact-action"
              type="button"
              onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}
            >
              <FileCode2 size={14} />
              파일 열기
            </button>
          </div>
        </article>
      ))}
    </div>
  );
}

function CodeReferenceResults({ result, onOpenEvidence }) {
  const renderItems = (items) => (
    <div className="evidence-list">
      {items.map((item) => (
        <article className="evidence-card code-evidence" key={item.chunkId}>
          <div className="result-heading">
            <strong>{item.filePath}:{item.lineStart}-{item.lineEnd}</strong>
            <span>{formatScore(item.score)}</span>
          </div>
          <small>
            {item.chunkType}
            {item.className ? ` · ${item.className}` : ''}
            {item.methodName ? `.${item.methodName}` : ''}
            {item.controlName ? ` · ${item.controlName}` : ''}
            {item.eventName ? ` · ${item.eventName}` : ''}
          </small>
          <p>{item.content}</p>
          <div className="action-row">
            <button
              className="ghost-button compact-action"
              type="button"
              onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}
            >
              <FileCode2 size={14} />
              파일 열기
            </button>
          </div>
        </article>
      ))}
    </div>
  );

  return (
    <div className="reference-results">
      <div className="reference-group">
        <h3>{result.symbol} 정의 후보</h3>
        {result.definitions?.length ? renderItems(result.definitions) : <p className="empty">정의 후보를 찾지 못했습니다.</p>}
      </div>
      <div className="reference-group">
        <h3>{result.symbol} 사용 후보</h3>
        {result.references?.length ? renderItems(result.references) : <p className="empty">사용 후보를 찾지 못했습니다.</p>}
      </div>
    </div>
  );
}

function CodeFileViewer({ detail, highlightRange }) {
  if (!detail) {
    return (
      <section className="panel detail-panel muted-panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>코드 파일 보기</h2>
            <p>파일 목록이나 답변 근거에서 파일을 열면 원본 라인을 확인할 수 있습니다.</p>
          </div>
        </div>
      </section>
    );
  }

  const lines = detail.content.split(/\r?\n/);
  return (
    <section className="panel code-viewer-panel">
      <div className="panel-title">
        <FileCode2 size={18} />
        <div>
          <h2>{detail.filePath}</h2>
          <p>{detail.repositoryName} · {detail.language} · {lines.length} lines</p>
        </div>
      </div>
      <div className="code-chunk-summary">
        {detail.chunks.slice(0, 8).map((chunk) => (
          <span key={chunk.id}>
            {chunk.chunkType} · {chunk.lineStart}-{chunk.lineEnd}
          </span>
        ))}
      </div>
      <pre className="code-viewer">
        {lines.map((line, index) => {
          const lineNumber = index + 1;
          const highlighted = highlightRange && lineNumber >= highlightRange.start && lineNumber <= highlightRange.end;
          return (
            <code className={highlighted ? 'highlighted-line' : ''} key={lineNumber}>
              <span>{String(lineNumber).padStart(4, ' ')}</span>
              {line || ' '}
            </code>
          );
        })}
      </pre>
    </section>
  );
}

function ResultList({ results, title }) {
  if (!results?.length) return null;
  return (
    <div className="results">
      {title && <h3>{title}</h3>}
      {results.map((result, index) => (
        <article className="result" key={`${result.chunkId}-${index}`}>
          <div className="result-heading">
            <strong>{index + 1}. {result.title}</strong>
            <span>{formatScore(result.score)}</span>
          </div>
          <small>{getSourceLabel(result.sourceType)} · chunk {result.chunkIndex + 1}</small>
          <p>{result.content}</p>
        </article>
      ))}
    </div>
  );
}

function HelpPanel({ onClose }) {
  return (
    <div className="help-backdrop" role="presentation" onClick={onClose}>
      <aside className="help-panel" role="dialog" aria-modal="true" aria-labelledby="help-title" onClick={(event) => event.stopPropagation()}>
        <div className="help-header">
          <div>
            <span className="eyebrow">Guide</span>
            <h2 id="help-title">사용 방법</h2>
          </div>
          <button className="icon-button" type="button" title="닫기" onClick={onClose}>
            <X size={16} />
          </button>
        </div>

        <div className="help-content">
          <section>
            <h3>1. 코드 저장소</h3>
            <p>Git URL을 등록하고 인덱싱 버튼을 누르면 소스 파일을 구조 단위로 색인합니다. 토큰 저장을 체크한 경우에만 암호화해서 재사용합니다.</p>
          </section>
          <section>
            <h3>2. 코드 질문</h3>
            <p>기능 위치, 메서드 설명, 호출 흐름, UI 이벤트, 영향 범위 중 하나를 선택하면 검색 의도가 좁아져 답변 품질이 좋아집니다.</p>
          </section>
          <section>
            <h3>3. 근거 확인</h3>
            <p>답변 아래에는 파일 경로와 라인 범위가 표시됩니다. 실제 유지보수 판단은 이 근거 코드를 먼저 확인하세요.</p>
          </section>
          <section>
            <h3>4. 문서 RAG</h3>
            <p>CSV, Excel 원본은 MinIO에 저장됩니다. 웹 문서는 허용 도메인과 robots.txt 정책을 통과한 경우에만 색인합니다.</p>
          </section>
        </div>
      </aside>
    </div>
  );
}

function getAnswerModeLabel(value) {
  return answerModes.find((mode) => mode.value === value)?.label ?? '질문 답변';
}

function getCodeModeLabel(value) {
  return codeModes.find((mode) => mode.value === value)?.label ?? '기능 위치';
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

function jobPercent(job) {
  const total = Number(job.totalFiles || 0);
  const processed = Number(job.processedFiles || 0);
  if (!total) {
    return job.status === 'SUCCEEDED' ? 100 : 4;
  }
  return Math.max(4, Math.min(100, Math.round((processed / total) * 100)));
}

function formatBytes(value) {
  const bytes = Number(value);
  if (Number.isNaN(bytes)) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
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

function getProgressMessage(busy) {
  if (!busy) return '';
  if (busy === 'repo-register') return 'Git 저장소를 등록하는 중입니다.';
  if (busy.startsWith('repo-index-')) return '백그라운드 인덱싱 작업을 시작하는 중입니다.';
  if (busy.startsWith('repo-cancel-')) return '인덱싱 취소를 요청하는 중입니다.';
  if (busy.startsWith('repo-clear-jobs-')) return '실패/취소 인덱싱 이력을 정리하는 중입니다.';
  if (busy.startsWith('repo-delete-')) return '저장소와 색인 데이터를 삭제하는 중입니다.';
  if (busy.startsWith('code-file-')) return '코드 파일을 불러오는 중입니다.';
  if (busy === 'code-ask') return '코드를 검색하고 로컬 모델 답변을 기다리는 중입니다.';
  if (busy === 'code-references') return '심볼 정의와 사용 후보를 찾는 중입니다.';
  if (busy === 'web') return '웹 페이지를 가져와 텍스트를 추출하고 색인하는 중입니다.';
  if (busy === 'file') return '파일을 업로드하고 원본 저장 및 색인을 진행하는 중입니다.';
  if (busy === 'search') return '색인된 문서에서 관련 내용을 검색하는 중입니다.';
  if (busy === 'ask') return '문서를 검색하고 로컬 모델 응답을 기다리는 중입니다.';
  if (busy.startsWith('reindex-')) return '원본 소스를 다시 읽고 문서를 재색인하는 중입니다.';
  if (busy.startsWith('delete-')) return '문서와 원본 저장 객체를 삭제하는 중입니다.';
  if (busy.startsWith('detail-')) return '문서 상세 정보를 불러오는 중입니다.';
  return '요청을 처리하는 중입니다.';
}

async function requireOk(response) {
  if (response.ok) return;
  const body = await response.json().catch(() => ({}));
  throw new Error(body.detail || `HTTP ${response.status}`);
}

createRoot(document.getElementById('root')).render(<App />);

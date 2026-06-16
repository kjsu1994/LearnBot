import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  Code2,
  Database,
  Eye,
  FileCode2,
  FileSpreadsheet,
  FileUp,
  GitBranch,
  GitPullRequest,
  Globe,
  Info,
  Loader2,
  LockKeyhole,
  LogOut,
  MessageSquare,
  RefreshCw,
  Search,
  ShieldCheck,
  Trash2,
  UserPlus,
  Users,
  X,
} from 'lucide-react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const apiBase = import.meta.env.VITE_API_BASE_URL ?? '';
const tokenKey = 'runbot.session.token';

const sourceLabels = {
  FILE: '파일',
  WEB: '웹',
};

const statusLabels = {
  INDEXING: '인덱싱 중',
  INDEXED: '인덱싱 완료',
  PENDING: '대기',
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
  { value: 'table', label: '표 추출' },
  { value: 'quote', label: '원문 인용' },
];

const codeModes = [
  { value: 'locate', label: '위치 찾기' },
  { value: 'method', label: '메서드 설명' },
  { value: 'flow', label: '호출 흐름' },
  { value: 'ui_event', label: 'UI 이벤트' },
  { value: 'impact', label: '영향 범위' },
];

async function fetchJson(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.token) {
    headers.set('Authorization', `Bearer ${options.token}`);
  }
  let body = options.body;
  if (Object.prototype.hasOwnProperty.call(options, 'json')) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.json);
  }

  const response = await fetch(`${apiBase}${path}`, {
    method: options.method || 'GET',
    headers,
    body,
  });
  if (!response.ok) {
    const message = await responseMessage(response);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function responseMessage(response) {
  const text = await response.text();
  if (!text) {
    return `요청 처리에 실패했습니다. (${response.status})`;
  }
  try {
    const data = JSON.parse(text);
    return data.message || data.error || text;
  } catch {
    return text;
  }
}

function App() {
  const [token, setToken] = useState(() => localStorage.getItem(tokenKey) || '');
  const [user, setUser] = useState(null);
  const [spaces, setSpaces] = useState([]);
  const [selectedSpaceId, setSelectedSpaceId] = useState('');
  const [bootstrapping, setBootstrapping] = useState(true);
  const [activeView, setActiveView] = useState('code');

  const [documents, setDocuments] = useState([]);
  const [repositories, setRepositories] = useState([]);
  const [jobs, setJobs] = useState({});
  const [jobFailures, setJobFailures] = useState({});
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
  const [codeSearchQuery, setCodeSearchQuery] = useState('');
  const [codeSearchResults, setCodeSearchResults] = useState([]);
  const [referenceSymbol, setReferenceSymbol] = useState('');
  const [referenceResult, setReferenceResult] = useState(null);

  const [adminUsers, setAdminUsers] = useState([]);
  const [auditLogs, setAuditLogs] = useState([]);
  const [inviteForm, setInviteForm] = useState({
    email: '',
    displayName: '',
    initialPassword: '',
    role: 'USER',
    spaceRole: 'MEMBER',
  });
  const [spaceForm, setSpaceForm] = useState({ name: '', description: '' });

  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');

  const activeSpaceId = selectedSpaceId || spaces[0]?.id || '';
  const selectedSpace = spaces.find((space) => space.id === activeSpaceId);
  const selectedRepository = repositories.find((repo) => repo.id === selectedRepositoryId);

  useEffect(() => {
    let mounted = true;
    async function loadSession() {
      if (!token) {
        setBootstrapping(false);
        return;
      }
      try {
        const data = await fetchJson('/api/auth/me', { token });
        if (mounted) applySession(data, token);
      } catch {
        if (mounted) clearSession();
      } finally {
        if (mounted) setBootstrapping(false);
      }
    }
    loadSession();
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    if (!user || !activeSpaceId) return;
    refreshDocuments();
    refreshRepositories();
  }, [user?.id, activeSpaceId]);

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
    if (activeView === 'admin' && user?.role === 'ADMIN') {
      refreshAdmin();
    }
  }, [activeView, user?.role]);

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

  function applySession(data, nextToken = token) {
    setToken(nextToken || data.token || '');
    setUser(data.user);
    setSpaces(data.spaces || []);
    setSelectedSpaceId((current) => {
      if (current && data.spaces?.some((space) => space.id === current)) return current;
      return data.spaces?.[0]?.id || '';
    });
  }

  function clearSession() {
    localStorage.removeItem(tokenKey);
    setToken('');
    setUser(null);
    setSpaces([]);
    setSelectedSpaceId('');
    setDocuments([]);
    setRepositories([]);
    setJobs({});
    setJobFailures({});
  }

  async function request(path, options = {}) {
    try {
      return await fetchJson(path, { ...options, token });
    } catch (err) {
      if (err.status === 401) clearSession();
      throw err;
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

  function spacePath(path) {
    if (!activeSpaceId) return path;
    const separator = path.includes('?') ? '&' : '?';
    return `${path}${separator}spaceId=${encodeURIComponent(activeSpaceId)}`;
  }

  async function login(credentials) {
    setError('');
    setBusy('login');
    try {
      const data = await fetchJson('/api/auth/login', {
        method: 'POST',
        json: credentials,
      });
      localStorage.setItem(tokenKey, data.token);
      applySession(data, data.token);
    } catch (err) {
      setError(err.message || '로그인에 실패했습니다.');
    } finally {
      setBusy('');
      setBootstrapping(false);
    }
  }

  async function logout() {
    await run('logout', async () => {
      try {
        await request('/api/auth/logout', { method: 'POST' });
      } finally {
        clearSession();
      }
    });
  }

  async function refreshSession() {
    const data = await request('/api/auth/me');
    applySession(data, token);
  }

  async function refreshDocuments() {
    const data = await request(spacePath('/api/documents'));
    setDocuments(data || []);
  }

  async function refreshRepositories() {
    const data = await request(spacePath('/api/code/repositories'));
    setRepositories(data || []);
    setSelectedRepositoryId((current) => {
      if (current && data?.some((repo) => repo.id === current)) return current;
      return data?.[0]?.id || '';
    });
  }

  async function ingestWeb(event) {
    event.preventDefault();
    await run('web', async () => {
      await request('/api/sources/web', {
        method: 'POST',
        json: { url: webUrl, spaceId: activeSpaceId },
      });
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
      body.append('spaceId', activeSpaceId);
      await request('/api/sources/files', { method: 'POST', body });
      setFile(null);
      event.currentTarget.reset();
      await refreshDocuments();
    });
  }

  async function search(event) {
    event.preventDefault();
    await run('search', async () => {
      const data = await request('/api/search', {
        method: 'POST',
        json: { query, limit: 8, spaceId: activeSpaceId },
      });
      setSearchResults(data || []);
    });
  }

  async function ask(event) {
    event.preventDefault();
    await run('ask', async () => {
      const data = await request('/api/rag/ask', {
        method: 'POST',
        json: { question, mode: answerMode, spaceId: activeSpaceId },
      });
      setAnswer(data);
    });
  }

  async function registerRepository(event) {
    event.preventDefault();
    await run('repo-register', async () => {
      const created = await request('/api/code/repositories', {
        method: 'POST',
        json: {
          ...repoForm,
          spaceId: activeSpaceId,
          storeToken: repoForm.authType === 'TOKEN' && repoForm.storeToken,
        },
      });
      setSelectedRepositoryId(created.id);
      setRepoForm((current) => ({ ...current, gitUrl: '', name: '', token: '' }));
      await refreshRepositories();
    });
  }

  async function indexRepository(repositoryId) {
    await run(`repo-index-${repositoryId}`, async () => {
      const targetRepository = repositories.find((repo) => repo.id === repositoryId);
      const tokenRequired = targetRepository?.authType === 'TOKEN' && !targetRepository?.credentialStored;
      if (tokenRequired && !indexCredential.token) {
        setSelectedRepositoryId(repositoryId);
        throw new Error('저장소 인덱싱에 사용할 Git 토큰을 입력하세요.');
      }
      await request(`/api/code/repositories/${repositoryId}/index`, {
        method: 'POST',
        json: {
          username: indexCredential.username,
          token: targetRepository?.authType === 'TOKEN' ? indexCredential.token : '',
          storeToken: targetRepository?.authType === 'TOKEN' && indexCredential.storeToken,
        },
      });
      if (indexCredential.token) {
        setIndexCredential((current) => ({ ...current, token: '' }));
      }
      await refreshRepositories();
      await refreshJobs(repositoryId);
    });
  }

  async function refreshJobs(repositoryId) {
    const data = await request(`/api/code/repositories/${repositoryId}/jobs`);
    setJobs((current) => ({ ...current, [repositoryId]: data || [] }));
  }

  async function loadJobFailures(repositoryId, jobId) {
    await run(`job-failures-${jobId}`, async () => {
      const data = await request(`/api/code/repositories/${repositoryId}/jobs/${jobId}/failures`);
      setJobFailures((current) => ({ ...current, [jobId]: data || [] }));
    });
  }

  async function refreshCodeFiles(repositoryId = selectedRepositoryId, queryText = fileQuery) {
    if (!repositoryId) return;
    const params = new URLSearchParams();
    if (queryText) params.set('query', queryText);
    params.set('limit', '80');
    const data = await request(`/api/code/repositories/${repositoryId}/files?${params.toString()}`);
    setCodeFiles(data || []);
  }

  async function searchCodeFiles(event) {
    event.preventDefault();
    await refreshCodeFiles(selectedRepositoryId, fileQuery);
  }

  async function cancelIndex(repositoryId, jobId) {
    await run(`repo-cancel-${jobId}`, async () => {
      await request(`/api/code/repositories/${repositoryId}/jobs/${jobId}/cancel`, { method: 'POST' });
      await refreshRepositories();
      await refreshJobs(repositoryId);
    });
  }

  async function deleteRepository(repositoryId, name) {
    if (!window.confirm(`'${name}' 저장소를 삭제할까요?`)) return;
    await run(`repo-delete-${repositoryId}`, async () => {
      await request(`/api/code/repositories/${repositoryId}`, { method: 'DELETE' });
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
      await request(`/api/code/repositories/${repositoryId}/jobs`, { method: 'DELETE' });
      await refreshJobs(repositoryId);
    });
  }

  async function openCodeFile(repositoryId, fileId, range = null) {
    await run(`code-file-${fileId}`, async () => {
      const data = await request(`/api/code/repositories/${repositoryId}/files/${fileId}`);
      setSelectedCodeFile(data);
      setHighlightRange(range);
    });
  }

  async function askCode(event) {
    event.preventDefault();
    await run('code-ask', async () => {
      const data = await request('/api/code/ask', {
        method: 'POST',
        json: {
          repositoryId: selectedRepositoryId || null,
          spaceId: activeSpaceId,
          question: codeQuestion,
          mode: codeMode,
          limit: 10,
        },
      });
      setCodeAnswer(data);
    });
  }

  async function searchCode(event) {
    event.preventDefault();
    await run('code-search', async () => {
      const data = await request('/api/code/search', {
        method: 'POST',
        json: {
          repositoryId: selectedRepositoryId || null,
          spaceId: activeSpaceId,
          query: codeSearchQuery,
          limit: 12,
        },
      });
      setCodeSearchResults(data || []);
    });
  }

  async function findReferences(event) {
    event.preventDefault();
    await run('code-references', async () => {
      const data = await request('/api/code/references', {
        method: 'POST',
        json: {
          repositoryId: selectedRepositoryId || null,
          spaceId: activeSpaceId,
          symbol: referenceSymbol,
          limit: 24,
        },
      });
      setReferenceResult(data);
    });
  }

  async function deleteDocument(documentId, title) {
    if (!window.confirm(`'${title}' 문서를 삭제할까요?`)) return;
    await run(`delete-${documentId}`, async () => {
      await request(`/api/documents/${documentId}`, { method: 'DELETE' });
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
      const result = await request(`/api/documents/${documentId}/reindex`, { method: 'POST' });
      await refreshDocuments();
      setSelectedDocumentId(result.documentId);
      await loadDocumentDetail(result.documentId);
    });
  }

  async function loadDocumentDetail(documentId) {
    setSelectedDocumentId(documentId);
    await run(`detail-${documentId}`, async () => {
      const data = await request(`/api/documents/${documentId}`);
      setDocumentDetail(data);
    });
  }

  async function refreshAdmin() {
    const [users, logs] = await Promise.all([
      request('/api/admin/users'),
      request('/api/admin/audit-logs?limit=80'),
    ]);
    setAdminUsers(users || []);
    setAuditLogs(logs || []);
  }

  async function createSpace(event) {
    event.preventDefault();
    await run('space-create', async () => {
      await request('/api/admin/spaces', { method: 'POST', json: spaceForm });
      setSpaceForm({ name: '', description: '' });
      await refreshSession();
      await refreshAdmin();
    });
  }

  async function inviteUser(event) {
    event.preventDefault();
    await run('user-invite', async () => {
      await request('/api/admin/users', {
        method: 'POST',
        json: {
          ...inviteForm,
          spaceId: activeSpaceId,
        },
      });
      setInviteForm({ email: '', displayName: '', initialPassword: '', role: 'USER', spaceRole: 'MEMBER' });
      await refreshAdmin();
    });
  }

  const loading = (name) => busy === name;
  const progressMessage = getProgressMessage(busy);

  if (bootstrapping) {
    return (
      <div className="boot-screen">
        <Loader2 className="spin" size={24} />
        <span>런봇 세션을 확인하는 중입니다.</span>
      </div>
    );
  }

  if (!user) {
    return <LoginScreen onLogin={login} busy={loading('login')} error={error} />;
  }

  return (
    <main className="shell">
      <Sidebar
        user={user}
        spaces={spaces}
        selectedSpaceId={activeSpaceId}
        setSelectedSpaceId={setSelectedSpaceId}
        indexedRepoCount={indexedRepoCount}
        indexedCount={indexedCount}
        codeChunkCount={codeChunkCount}
        webCount={webCount}
        fileCount={fileCount}
        onLogout={logout}
      />

      <section className="content">
        <header className="topbar">
          <div>
            <span className="eyebrow">Private RAG Workspace</span>
            <h1>런봇</h1>
            <p>
              {selectedSpace?.name || '공간'} 안에서 사내 위키, 문서, 코드 저장소를 근거 기반으로 검색하고 답변합니다.
            </p>
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

        <div className={user.role === 'ADMIN' ? 'view-tabs three-tabs' : 'view-tabs'} aria-label="작업 영역">
          <button className={activeView === 'code' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => setActiveView('code')}>
            <Code2 size={16} />
            코드 RAG
          </button>
          <button className={activeView === 'docs' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => setActiveView('docs')}>
            <Database size={16} />
            문서 RAG
          </button>
          {user.role === 'ADMIN' && (
            <button className={activeView === 'admin' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => setActiveView('admin')}>
              <ShieldCheck size={16} />
              관리자
            </button>
          )}
        </div>

        {error && <div className="alert">{error}</div>}
        {progressMessage && <div className="progress-banner"><Loader2 className="spin" size={16} />{progressMessage}</div>}

        {activeView === 'code' && (
          <CodeWorkspace
            repoForm={repoForm}
            setRepoForm={setRepoForm}
            indexCredential={indexCredential}
            setIndexCredential={setIndexCredential}
            repositories={repositories}
            selectedRepositoryId={selectedRepositoryId}
            setSelectedRepositoryId={setSelectedRepositoryId}
            selectedRepository={selectedRepository}
            jobs={jobs}
            jobFailures={jobFailures}
            loadJobFailures={loadJobFailures}
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
            codeSearchQuery={codeSearchQuery}
            setCodeSearchQuery={setCodeSearchQuery}
            codeSearchResults={codeSearchResults}
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
            searchCode={searchCode}
            findReferences={findReferences}
            loading={loading}
          />
        )}

        {activeView === 'docs' && (
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

        {activeView === 'admin' && user.role === 'ADMIN' && (
          <AdminWorkspace
            users={adminUsers}
            spaces={spaces}
            selectedSpaceId={activeSpaceId}
            auditLogs={auditLogs}
            inviteForm={inviteForm}
            setInviteForm={setInviteForm}
            spaceForm={spaceForm}
            setSpaceForm={setSpaceForm}
            createSpace={createSpace}
            inviteUser={inviteUser}
            refreshAdmin={refreshAdmin}
            loading={loading}
          />
        )}
      </section>
    </main>
  );
}

function LoginScreen({ onLogin, busy, error }) {
  const [email, setEmail] = useState('admin@learnbot.local');
  const [password, setPassword] = useState('learnbot1234');

  function submit(event) {
    event.preventDefault();
    onLogin({ email, password });
  }

  return (
    <main className="login-screen">
      <section className="login-panel panel">
        <div className="brand login-brand">
          <div className="brand-mark">
            <Bot size={22} />
          </div>
          <div>
            <span>런봇</span>
            <small>사내 지식 RAG</small>
          </div>
        </div>
        <div>
          <span className="eyebrow">Private Workspace</span>
          <h1>로그인</h1>
          <p className="login-copy">관리자가 초대한 계정으로 사내 위키, 코드, 문서 RAG 공간에 접속합니다.</p>
        </div>
        {error && <div className="alert">{error}</div>}
        <form className="stack" onSubmit={submit}>
          <label htmlFor="login-email">이메일</label>
          <input id="login-email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" />
          <label htmlFor="login-password">비밀번호</label>
          <input id="login-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
          <button disabled={!email || !password || busy}>
            {busy ? <Loader2 className="spin" size={16} /> : <LockKeyhole size={16} />}
            로그인
          </button>
        </form>
      </section>
    </main>
  );
}

function Sidebar({
  user,
  spaces,
  selectedSpaceId,
  setSelectedSpaceId,
  indexedRepoCount,
  indexedCount,
  codeChunkCount,
  webCount,
  fileCount,
  onLogout,
}) {
  return (
    <aside className="sidebar">
      <div className="brand">
        <div className="brand-mark">
          <Bot size={22} />
        </div>
        <div>
          <span>런봇</span>
          <small>사내 지식 RAG</small>
        </div>
      </div>

      <div className="side-section">
        <span className="section-label">공간</span>
        <select className="dark-select" value={selectedSpaceId} onChange={(event) => setSelectedSpaceId(event.target.value)}>
          {spaces.map((space) => (
            <option key={space.id} value={space.id}>{space.name}</option>
          ))}
        </select>
        <small className="sidebar-note">{user.displayName || user.email} · {user.role}</small>
      </div>

      <div className="side-section">
        <span className="section-label">인덱싱 상태</span>
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
        <span className="section-label">근거 데이터</span>
        <div className="source-stack">
          <div>
            <FileCode2 size={15} />
            <span>코드 청크</span>
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
        <span className="section-label">정책</span>
        <div>
          <small>답변</small>
          <strong>근거 필수</strong>
        </div>
        <div>
          <small>삭제</small>
          <strong>소프트 삭제</strong>
        </div>
      </div>

      <button className="ghost-button sidebar-logout" type="button" onClick={onLogout}>
        <LogOut size={16} />
        로그아웃
      </button>
    </aside>
  );
}

function CodeWorkspace(props) {
  const {
    repoForm,
    setRepoForm,
    indexCredential,
    setIndexCredential,
    repositories,
    selectedRepositoryId,
    setSelectedRepositoryId,
    selectedRepository,
    jobs,
    jobFailures,
    loadJobFailures,
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
    codeSearchQuery,
    setCodeSearchQuery,
    codeSearchResults,
    referenceSymbol,
    setReferenceSymbol,
    referenceResult,
    registerRepository,
    indexRepository,
    cancelIndex,
    deleteRepository,
    clearFailedJobs,
    refreshJobs,
    searchCodeFiles,
    openCodeFile,
    askCode,
    searchCode,
    findReferences,
    loading,
  } = props;

  return (
    <section className="workspace-grid code-grid">
      <div className="left-column">
        <section className="panel">
          <div className="panel-title">
            <GitBranch size={18} />
            <div>
              <h2>Git 저장소 등록</h2>
              <p>GitHub, GitLab, 사내 Git 서버의 HTTP/HTTPS/SSH 저장소를 코드 RAG 대상으로 등록합니다.</p>
            </div>
          </div>
          <form className="stack" onSubmit={registerRepository}>
            <label htmlFor="git-url">Git URL</label>
            <input id="git-url" value={repoForm.gitUrl} onChange={(event) => setRepoForm((current) => ({ ...current, gitUrl: event.target.value }))} placeholder="https://github.com/org/repo.git" />
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="repo-name">표시 이름</label>
                <input id="repo-name" value={repoForm.name} onChange={(event) => setRepoForm((current) => ({ ...current, name: event.target.value }))} placeholder="repo name" />
              </div>
              <div className="stack">
                <label htmlFor="repo-branch">Branch</label>
                <input id="repo-branch" value={repoForm.branch} onChange={(event) => setRepoForm((current) => ({ ...current, branch: event.target.value }))} placeholder="HEAD 또는 main" />
              </div>
            </div>
            <div className="mode-control auth-control" aria-label="Git 인증 방식">
              <button className={repoForm.authType === 'NONE' ? 'mode-button active' : 'mode-button'} type="button" onClick={() => setRepoForm((current) => ({ ...current, authType: 'NONE' }))}>인증 없음</button>
              <button className={repoForm.authType === 'TOKEN' ? 'mode-button active' : 'mode-button'} type="button" onClick={() => setRepoForm((current) => ({ ...current, authType: 'TOKEN' }))}>토큰</button>
            </div>
            {repoForm.authType === 'TOKEN' && (
              <>
                <div className="form-grid two">
                  <div className="stack">
                    <label htmlFor="git-username">Username</label>
                    <input id="git-username" value={repoForm.username} onChange={(event) => setRepoForm((current) => ({ ...current, username: event.target.value }))} placeholder="비워두면 oauth2" />
                  </div>
                  <div className="stack">
                    <label htmlFor="git-token">Token</label>
                    <input id="git-token" type="password" value={repoForm.token} onChange={(event) => setRepoForm((current) => ({ ...current, token: event.target.value }))} placeholder="개인 액세스 토큰" />
                  </div>
                </div>
                <label className="checkbox-row" htmlFor="store-token">
                  <input id="store-token" type="checkbox" checked={repoForm.storeToken} onChange={(event) => setRepoForm((current) => ({ ...current, storeToken: event.target.checked }))} />
                  <span>토큰을 암호화해 저장하고 다음 인덱싱부터 재사용</span>
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
              <p>{repositories.length ? `${repositories.length}개 저장소` : '등록된 저장소가 없습니다.'}</p>
            </div>
          </div>
          <div className="document-list">
            {repositories.map((repo) => {
              const latestJob = jobs[repo.id]?.[0];
              const runningJob = jobs[repo.id]?.find((job) => job.status === 'RUNNING' || job.status === 'CANCELLING');
              return (
                <article className={repo.id === selectedRepositoryId ? 'document-row selected repo-row' : 'document-row repo-row'} key={repo.id} onClick={() => setSelectedRepositoryId(repo.id)}>
                  <div className="document-main">
                    <strong>{repo.name}</strong>
                    <small>{repo.gitUrl}</small>
                    {repo.errorMessage && <small className="danger-note">{repo.errorMessage}</small>}
                    {repo.credentialStored && <small className="success-note">암호화된 Git 토큰 저장됨</small>}
                  </div>
                  <div className="document-meta">
                    <StatusBadge status={repo.status} />
                    <small>{repo.branch} · {repo.activeFileCount} files · {repo.activeChunkCount} chunks</small>
                  </div>
                  <div className="document-actions">
                    <IconButton title="작업 이력 새로고침" onClick={(event) => { event.stopPropagation(); refreshJobs(repo.id); }}>
                      <Info size={15} />
                    </IconButton>
                    <IconButton title="실패/취소 이력 정리" disabled={loading(`repo-clear-jobs-${repo.id}`)} onClick={(event) => { event.stopPropagation(); clearFailedJobs(repo.id); }}>
                      {loading(`repo-clear-jobs-${repo.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                    </IconButton>
                    {runningJob ? (
                      <IconButton danger title="인덱싱 취소" disabled={runningJob.status === 'CANCELLING' || loading(`repo-cancel-${runningJob.id}`)} onClick={(event) => { event.stopPropagation(); cancelIndex(repo.id, runningJob.id); }}>
                        {loading(`repo-cancel-${runningJob.id}`) ? <Loader2 className="spin" size={15} /> : <X size={15} />}
                      </IconButton>
                    ) : (
                      <IconButton title="인덱싱 시작" disabled={loading(`repo-index-${repo.id}`)} onClick={(event) => { event.stopPropagation(); indexRepository(repo.id); }}>
                        {loading(`repo-index-${repo.id}`) ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
                      </IconButton>
                    )}
                    <IconButton danger title="저장소 삭제" disabled={!!runningJob || loading(`repo-delete-${repo.id}`)} onClick={(event) => { event.stopPropagation(); deleteRepository(repo.id, repo.name); }}>
                      {loading(`repo-delete-${repo.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                    </IconButton>
                  </div>
                  {latestJob && (
                    <JobStrip
                      job={latestJob}
                      repoId={repo.id}
                      failures={jobFailures[latestJob.id]}
                      loadFailures={loadJobFailures}
                      loading={loading(`job-failures-${latestJob.id}`)}
                    />
                  )}
                </article>
              );
            })}
            {repositories.length === 0 && <p className="empty">Git URL을 등록한 뒤 인덱싱을 시작하세요.</p>}
          </div>
        </section>

        {selectedRepository?.authType === 'TOKEN' && (
          <section className="panel compact-auth-panel">
            <div className="panel-title">
              <GitPullRequest size={18} />
              <div>
                <h2>재인덱싱 인증</h2>
                <p>{selectedRepository.credentialStored ? '저장된 토큰을 사용할 수 있습니다. 새 토큰을 입력하면 갱신됩니다.' : '이 저장소는 토큰 인증이 필요합니다.'}</p>
              </div>
            </div>
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="index-username">Username</label>
                <input id="index-username" value={indexCredential.username} onChange={(event) => setIndexCredential((current) => ({ ...current, username: event.target.value }))} placeholder="비워두면 oauth2" />
              </div>
              <div className="stack">
                <label htmlFor="index-token">Token</label>
                <input id="index-token" type="password" value={indexCredential.token} onChange={(event) => setIndexCredential((current) => ({ ...current, token: event.target.value }))} placeholder={selectedRepository.credentialStored ? '새 토큰으로 갱신할 때만 입력' : '인덱싱에 사용할 token'} />
              </div>
            </div>
            <label className="checkbox-row" htmlFor="index-store-token">
              <input id="index-store-token" type="checkbox" checked={indexCredential.storeToken} onChange={(event) => setIndexCredential((current) => ({ ...current, storeToken: event.target.checked }))} />
              <span>입력한 토큰을 암호화해 저장</span>
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
            <input value={fileQuery} onChange={(event) => setFileQuery(event.target.value)} placeholder="MainWindow.xaml, Login, Controller..." disabled={!selectedRepositoryId} />
            <button disabled={!selectedRepositoryId}>
              <Search size={16} />
              찾기
            </button>
          </form>
          <div className="file-list">
            {codeFiles.map((fileItem) => (
              <button className="file-list-row" key={fileItem.id} type="button" onClick={() => openCodeFile(fileItem.repositoryId, fileItem.id)}>
                <span>{fileItem.filePath}</span>
                <small>{fileItem.language} · {fileItem.chunkCount} chunks</small>
              </button>
            ))}
            {selectedRepositoryId && !codeFiles.length && <p className="empty">인덱싱된 파일이 없거나 검색 결과가 없습니다.</p>}
          </div>
        </section>
      </div>

      <div className="right-column">
        <form className="panel ask-panel" onSubmit={askCode}>
          <div className="panel-title">
            <MessageSquare size={18} />
            <div>
              <h2>코드에게 질문하기</h2>
              <p>파일, 클래스, 메서드, UI 이벤트 흐름을 실제 코드 근거와 함께 답변합니다.</p>
            </div>
          </div>
          <RepositorySelect repositories={repositories} selectedRepositoryId={selectedRepositoryId} setSelectedRepositoryId={setSelectedRepositoryId} />
          <ModeControl modes={codeModes} value={codeMode} setValue={setCodeMode} className="code-mode-control" />
          <textarea value={codeQuestion} onChange={(event) => setCodeQuestion(event.target.value)} placeholder="예: 로그인 버튼 클릭 흐름은 어디서 처리돼?" />
          <div className="action-row">
            <button disabled={!codeQuestion || loading('code-ask')}>
              {loading('code-ask') ? <Loader2 className="spin" size={16} /> : <MessageSquare size={16} />}
              코드 질문
            </button>
          </div>
          {selectedRepository && (
            <div className="detail-box compact-box">
              <strong>{selectedRepository.name}</strong>
              <small>{selectedRepository.lastIndexedCommit ? `commit ${selectedRepository.lastIndexedCommit.slice(0, 12)}` : '아직 인덱싱된 commit이 없습니다.'}</small>
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

        <form className="panel search-panel" onSubmit={searchCode}>
          <div className="panel-title">
            <Search size={18} />
            <div>
              <h2>코드 검색</h2>
              <p>키워드, 심볼, 임베딩 검색을 합쳐 근거 후보를 빠르게 찾습니다.</p>
            </div>
          </div>
          <div className="inline-control">
            <input value={codeSearchQuery} onChange={(event) => setCodeSearchQuery(event.target.value)} placeholder="SearchService, error handling, AuthInterceptor..." />
            <button disabled={!codeSearchQuery || loading('code-search')}>
              {loading('code-search') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              검색
            </button>
          </div>
          <CodeSearchResults results={codeSearchResults} onOpenEvidence={openCodeFile} />
        </form>

        <form className="panel reference-panel" onSubmit={findReferences}>
          <div className="panel-title">
            <Search size={18} />
            <div>
              <h2>정의와 참조</h2>
              <p>메서드, 클래스, 컨트롤 이름으로 정의와 사용 위치를 확인합니다.</p>
            </div>
          </div>
          <div className="inline-control">
            <input value={referenceSymbol} onChange={(event) => setReferenceSymbol(event.target.value)} placeholder="InitializeComponent, SaveData, MainWindow..." />
            <button disabled={!referenceSymbol || loading('code-references')}>
              {loading('code-references') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              찾기
            </button>
          </div>
          {referenceResult && <CodeReferenceResults result={referenceResult} onOpenEvidence={openCodeFile} />}
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
              <p>허용된 웹 URL과 PDF, DOCX, Markdown, TXT, CSV, Excel 파일을 RAG 근거로 인덱싱합니다.</p>
            </div>
          </div>
          <form className="stack" onSubmit={props.ingestWeb}>
            <label htmlFor="web-url">웹 URL</label>
            <div className="inline-control">
              <input id="web-url" value={props.webUrl} onChange={(event) => props.setWebUrl(event.target.value)} placeholder="https://example.com/docs" />
              <button disabled={!props.webUrl || props.loading('web')}>
                {props.loading('web') ? <Loader2 className="spin" size={16} /> : <Globe size={16} />}
                인덱싱
              </button>
            </div>
          </form>
          <form className="stack" onSubmit={props.ingestFile}>
            <label htmlFor="file-upload">파일 업로드</label>
            <div className="file-row">
              <label className="file-picker" htmlFor="file-upload">
                <FileUp size={16} />
                <span>{props.file ? props.file.name : '파일 선택'}</span>
              </label>
              <input id="file-upload" className="visually-hidden" type="file" accept=".pdf,.docx,.md,.markdown,.txt,.csv,.xls,.xlsx" onChange={(event) => props.setFile(event.target.files?.[0] ?? null)} />
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
              <p>{props.documents.length ? `최근 ${props.latestDocuments.length}개 문서` : '인덱싱된 문서가 없습니다.'}</p>
            </div>
          </div>
          <div className="document-list">
            {props.latestDocuments.map((doc) => (
              <article className={doc.id === props.selectedDocumentId ? 'document-row selected' : 'document-row'} key={doc.id} onClick={() => props.loadDocumentDetail(doc.id)}>
                <div className="document-main">
                  <strong>{doc.title}</strong>
                  <small>{doc.sourceUri || doc.contentType || '원본 정보 없음'}</small>
                </div>
                <div className="document-meta">
                  <StatusBadge status={doc.sourceStatus} />
                  <small>{getSourceLabel(doc.sourceType)} · {formatDate(doc.createdAt)}</small>
                </div>
                <div className="document-actions">
                  <IconButton title="재색인" disabled={props.loading(`reindex-${doc.id}`) || props.loading(`delete-${doc.id}`)} onClick={(event) => { event.stopPropagation(); props.reindexDocument(doc.id); }}>
                    {props.loading(`reindex-${doc.id}`) ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
                  </IconButton>
                  <IconButton danger title="삭제" disabled={props.loading(`reindex-${doc.id}`) || props.loading(`delete-${doc.id}`)} onClick={(event) => { event.stopPropagation(); props.deleteDocument(doc.id, doc.title); }}>
                    {props.loading(`delete-${doc.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                  </IconButton>
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
              <h2>문서에게 질문하기</h2>
              <p>검색된 근거가 없으면 답변하지 않는 정책으로 RAG 신뢰성을 지킵니다.</p>
            </div>
          </div>
          <ModeControl modes={answerModes} value={props.answerMode} setValue={props.setAnswerMode} />
          <textarea value={props.question} onChange={(event) => props.setQuestion(event.target.value)} placeholder="업로드한 문서에서 확인할 내용을 질문하세요." />
          <div className="action-row">
            <button disabled={!props.question || props.loading('ask')}>
              {props.loading('ask') ? <Loader2 className="spin" size={16} /> : <MessageSquare size={16} />}
              답변 생성
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
            <input value={props.query} onChange={(event) => props.setQuery(event.target.value)} placeholder="검색어를 입력하세요." />
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

function AdminWorkspace({
  users,
  spaces,
  selectedSpaceId,
  auditLogs,
  inviteForm,
  setInviteForm,
  spaceForm,
  setSpaceForm,
  createSpace,
  inviteUser,
  refreshAdmin,
  loading,
}) {
  return (
    <section className="workspace-grid">
      <div className="left-column">
        <form className="panel" onSubmit={inviteUser}>
          <div className="panel-title">
            <UserPlus size={18} />
            <div>
              <h2>사용자 초대</h2>
              <p>현재 선택된 공간에 사용자를 추가합니다.</p>
            </div>
          </div>
          <div className="form-grid two">
            <div className="stack">
              <label htmlFor="invite-email">이메일</label>
              <input id="invite-email" value={inviteForm.email} onChange={(event) => setInviteForm((current) => ({ ...current, email: event.target.value }))} />
            </div>
            <div className="stack">
              <label htmlFor="invite-name">표시 이름</label>
              <input id="invite-name" value={inviteForm.displayName} onChange={(event) => setInviteForm((current) => ({ ...current, displayName: event.target.value }))} />
            </div>
          </div>
          <div className="form-grid two">
            <div className="stack">
              <label htmlFor="invite-password">초기 비밀번호</label>
              <input id="invite-password" type="password" value={inviteForm.initialPassword} onChange={(event) => setInviteForm((current) => ({ ...current, initialPassword: event.target.value }))} />
            </div>
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="invite-role">시스템 권한</label>
                <select id="invite-role" value={inviteForm.role} onChange={(event) => setInviteForm((current) => ({ ...current, role: event.target.value }))}>
                  <option value="USER">USER</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </div>
              <div className="stack">
                <label htmlFor="invite-space-role">공간 권한</label>
                <select id="invite-space-role" value={inviteForm.spaceRole} onChange={(event) => setInviteForm((current) => ({ ...current, spaceRole: event.target.value }))}>
                  <option value="MEMBER">MEMBER</option>
                  <option value="OWNER">OWNER</option>
                </select>
              </div>
            </div>
          </div>
          <div className="detail-box compact-box">
            <strong>{spaces.find((space) => space.id === selectedSpaceId)?.name || '선택된 공간'}</strong>
            <small>초대 사용자는 이 공간의 자료만 접근합니다.</small>
          </div>
          <div className="action-row">
            <button disabled={!inviteForm.email || !inviteForm.displayName || !inviteForm.initialPassword || loading('user-invite')}>
              {loading('user-invite') ? <Loader2 className="spin" size={16} /> : <UserPlus size={16} />}
              초대
            </button>
          </div>
        </form>

        <form className="panel" onSubmit={createSpace}>
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>공간 생성</h2>
              <p>팀, 제품, 보안 등 접근 범위별로 RAG 데이터를 분리합니다.</p>
            </div>
          </div>
          <div className="stack">
            <label htmlFor="space-name">공간 이름</label>
            <input id="space-name" value={spaceForm.name} onChange={(event) => setSpaceForm((current) => ({ ...current, name: event.target.value }))} />
          </div>
          <div className="stack">
            <label htmlFor="space-description">설명</label>
            <textarea id="space-description" value={spaceForm.description} onChange={(event) => setSpaceForm((current) => ({ ...current, description: event.target.value }))} />
          </div>
          <div className="action-row">
            <button disabled={!spaceForm.name || loading('space-create')}>
              {loading('space-create') ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              공간 생성
            </button>
          </div>
        </form>
      </div>

      <div className="right-column">
        <section className="panel">
          <div className="panel-title">
            <Users size={18} />
            <div>
              <h2>사용자</h2>
              <p>{users.length}개 계정</p>
            </div>
          </div>
          <div className="document-list">
            {users.map((item) => (
              <article className="document-row" key={item.id}>
                <div className="document-main">
                  <strong>{item.displayName}</strong>
                  <small>{item.email}</small>
                </div>
                <div className="document-meta">
                  <StatusBadge status={item.status} />
                  <small>{item.role}</small>
                </div>
              </article>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-title">
            <ShieldCheck size={18} />
            <div>
              <h2>감사 로그</h2>
              <p>핵심 작업 이력을 추적합니다.</p>
            </div>
          </div>
          <div className="top-actions">
            <button className="ghost-button compact-action" type="button" onClick={refreshAdmin}>
              <RefreshCw size={14} />
              새로고침
            </button>
          </div>
          <div className="results audit-list">
            {auditLogs.map((log) => (
              <article className="result" key={log.id}>
                <div className="result-heading">
                  <strong>{log.action}</strong>
                  <span>{formatDate(log.createdAt)}</span>
                </div>
                <small>{log.actorEmail || 'system'} · {log.spaceName || 'global'}</small>
                <p>{log.message}</p>
              </article>
            ))}
          </div>
        </section>
      </div>
    </section>
  );
}

function JobStrip({ job, repoId, failures, loadFailures, loading }) {
  const canShowFailures = job.failedFiles > 0 || job.status === 'FAILED' || job.errorMessage;
  return (
    <div className="job-strip">
      <span>
        {getStatusLabel(job.status)} · {job.processedFiles}/{job.totalFiles || '-'} files · {job.totalChunks} chunks
        {job.failedFiles > 0 ? ` · 실패 ${job.failedFiles}` : ''}
      </span>
      <div className="progress-track" aria-label="인덱싱 진행률">
        <span style={{ width: `${jobPercent(job)}%` }} />
      </div>
      {job.errorMessage && <div className="failure-line"><AlertTriangle size={14} />{job.errorMessage}</div>}
      {canShowFailures && (
        <button className="ghost-button compact-action" type="button" onClick={(event) => { event.stopPropagation(); loadFailures(repoId, job.id); }}>
          {loading ? <Loader2 className="spin" size={14} /> : <Eye size={14} />}
          실패 사유
        </button>
      )}
      {failures && <JobFailureList failures={failures} />}
    </div>
  );
}

function JobFailureList({ failures }) {
  if (!failures.length) {
    return <p className="empty compact-empty">기록된 파일별 실패 사유가 없습니다. 저장소 수준 오류 메시지를 확인하세요.</p>;
  }
  return (
    <div className="failure-list">
      {failures.map((failure) => (
        <div className="failure-item" key={failure.id}>
          <strong>{failure.filePath || 'repository'}</strong>
          <small>{failure.stage} · {formatDate(failure.createdAt)}</small>
          <span>{failure.message}</span>
        </div>
      ))}
    </div>
  );
}

function RepositorySelect({ repositories, selectedRepositoryId, setSelectedRepositoryId }) {
  return (
    <div className="stack">
      <label htmlFor="repo-select">질문 대상</label>
      <select id="repo-select" value={selectedRepositoryId} onChange={(event) => setSelectedRepositoryId(event.target.value)}>
        <option value="">전체 저장소</option>
        {repositories.map((repo) => (
          <option key={repo.id} value={repo.id}>{repo.name}</option>
        ))}
      </select>
    </div>
  );
}

function ModeControl({ modes, value, setValue, className = '' }) {
  return (
    <div className={`mode-control ${className}`} aria-label="답변 모드">
      {modes.map((mode) => (
        <button className={value === mode.value ? 'mode-button active' : 'mode-button'} key={mode.value} type="button" onClick={() => setValue(mode.value)}>
          {mode.label}
        </button>
      ))}
    </div>
  );
}

function IconButton({ children, title, disabled, onClick, danger = false }) {
  return (
    <button className={danger ? 'icon-button danger' : 'icon-button'} type="button" title={title} aria-label={title} disabled={disabled} onClick={onClick}>
      {children}
    </button>
  );
}

function StatusBadge({ status }) {
  const normalized = String(status || 'PENDING').toLowerCase();
  return <span className={`status status-${normalized}`}>{getStatusLabel(status)}</span>;
}

function DocumentDetailPanel({ detail, loading }) {
  if (loading) {
    return (
      <section className="panel detail-panel">
        <div className="panel-title">
          <Info size={18} />
          <div>
            <h2>문서 상세</h2>
            <p>불러오는 중입니다.</p>
          </div>
        </div>
      </section>
    );
  }
  if (!detail) {
    return (
      <section className="panel muted-panel">
        <div className="panel-title">
          <Info size={18} />
          <div>
            <h2>문서 상세</h2>
            <p>문서를 선택하면 청크와 원본 정보를 확인할 수 있습니다.</p>
          </div>
        </div>
      </section>
    );
  }
  return (
    <section className="panel detail-panel">
      <div className="panel-title">
        <Info size={18} />
        <div>
          <h2>{detail.summary.title}</h2>
          <p>{detail.summary.sourceUri}</p>
        </div>
      </div>
      <dl className="detail-grid">
        <div>
          <dt>유형</dt>
          <dd>{getSourceLabel(detail.summary.sourceType)}</dd>
        </div>
        <div>
          <dt>청크</dt>
          <dd>{detail.chunkCount}</dd>
        </div>
        <div>
          <dt>상태</dt>
          <dd>{getStatusLabel(detail.summary.sourceStatus)}</dd>
        </div>
        <div>
          <dt>업로드</dt>
          <dd>{detail.storedObject?.originalFilename || '-'}</dd>
        </div>
      </dl>
      <div className="chunk-list">
        {detail.chunks.slice(0, 6).map((chunk) => (
          <article className="chunk-card" key={chunk.id}>
            <strong>Chunk {chunk.chunkIndex}</strong>
            <p>{chunk.content}</p>
          </article>
        ))}
      </div>
    </section>
  );
}

function ResultList({ results, title }) {
  return (
    <div className="results">
      {results.map((result) => (
        <article className="result" key={result.chunkId}>
          <div className="result-heading">
            <strong>{result.title}</strong>
            <span>{Number(result.score || 0).toFixed(3)}</span>
          </div>
          <small>{result.sourceUri} · chunk {result.chunkIndex}</small>
          <p>{result.content}</p>
        </article>
      ))}
      {!results.length && <p className="empty">{title}가 없습니다.</p>}
    </div>
  );
}

function EvidenceList({ evidence = [] }) {
  if (!evidence.length) return <p className="empty compact-empty">표시할 근거가 없습니다.</p>;
  return (
    <div className="evidence-list">
      {evidence.map((item) => (
        <article className="evidence-card" key={`${item.citationNumber}-${item.chunkId}`}>
          <strong>[{item.citationNumber}] {item.title}</strong>
          <small>{item.sourceUri} · chunk {item.chunkIndex}</small>
          <p>{item.preview}</p>
        </article>
      ))}
    </div>
  );
}

function CodeEvidenceList({ evidence = [], onOpenEvidence }) {
  if (!evidence.length) return <p className="empty compact-empty">표시할 코드 근거가 없습니다.</p>;
  return (
    <div className="evidence-list">
      {evidence.map((item) => (
        <article className="evidence-card code-evidence" key={`${item.citationNumber}-${item.chunkId}`}>
          <div className="result-heading">
            <strong>[{item.citationNumber}] {item.filePath}</strong>
            <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}>
              <Eye size={14} />
              열기
            </button>
          </div>
          <small>{item.lineStart}-{item.lineEnd} · {item.chunkType}</small>
          <p>{item.preview}</p>
        </article>
      ))}
    </div>
  );
}

function CodeSearchResults({ results = [], onOpenEvidence }) {
  return (
    <div className="results compact">
      {results.map((item) => (
        <article className="result" key={item.chunkId}>
          <div className="result-heading">
            <strong>{item.filePath}</strong>
            <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}>
              <Eye size={14} />
              열기
            </button>
          </div>
          <small>{item.repositoryName} · {item.lineStart}-{item.lineEnd} · score {Number(item.score || 0).toFixed(3)}</small>
          <p>{item.content}</p>
        </article>
      ))}
      {!results.length && <p className="empty">코드 검색 결과가 없습니다.</p>}
    </div>
  );
}

function CodeReferenceResults({ result, onOpenEvidence }) {
  return (
    <div className="reference-results">
      <ReferenceGroup title="정의" items={result.definitions || []} onOpenEvidence={onOpenEvidence} />
      <ReferenceGroup title="참조" items={result.references || []} onOpenEvidence={onOpenEvidence} />
    </div>
  );
}

function ReferenceGroup({ title, items, onOpenEvidence }) {
  return (
    <div className="reference-group">
      <h3>{title}</h3>
      {items.map((item) => (
        <article className="evidence-card code-evidence" key={item.chunkId}>
          <div className="result-heading">
            <strong>{item.filePath}</strong>
            <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}>
              <Eye size={14} />
              열기
            </button>
          </div>
          <small>{item.lineStart}-{item.lineEnd} · {item.chunkType}</small>
          <p>{item.content}</p>
        </article>
      ))}
      {!items.length && <p className="empty compact-empty">결과 없음</p>}
    </div>
  );
}

function CodeFileViewer({ detail, highlightRange, loading }) {
  if (loading) {
    return (
      <section className="panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>코드 미리보기</h2>
            <p>파일을 불러오는 중입니다.</p>
          </div>
        </div>
      </section>
    );
  }
  if (!detail) {
    return (
      <section className="panel muted-panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>코드 미리보기</h2>
            <p>파일이나 근거를 선택하면 원문 코드를 확인할 수 있습니다.</p>
          </div>
        </div>
      </section>
    );
  }
  const lines = detail.content.split(/\r?\n/);
  return (
    <section className="panel">
      <div className="panel-title">
        <FileCode2 size={18} />
        <div>
          <h2>{detail.filePath}</h2>
          <p>{detail.language} · {detail.chunks?.length || 0} chunks</p>
        </div>
      </div>
      <pre className="code-viewer">
        <code>
          {lines.map((line, index) => {
            const lineNumber = index + 1;
            const highlighted = highlightRange && lineNumber >= highlightRange.start && lineNumber <= highlightRange.end;
            return (
              <div className={highlighted ? 'highlighted-line' : ''} key={lineNumber}>
                <span>{lineNumber}</span>{line || ' '}
              </div>
            );
          })}
        </code>
      </pre>
    </section>
  );
}

function getStatusLabel(status) {
  return statusLabels[status] || status || '대기';
}

function getSourceLabel(type) {
  return sourceLabels[type] || type || '문서';
}

function getAnswerModeLabel(mode) {
  return answerModes.find((item) => item.value === mode)?.label || '질문 답변';
}

function getCodeModeLabel(mode) {
  return codeModes.find((item) => item.value === mode)?.label || '위치 찾기';
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

function jobPercent(job) {
  if (!job?.totalFiles) return job?.status === 'SUCCEEDED' ? 100 : 8;
  return Math.max(5, Math.min(100, Math.round((job.processedFiles / job.totalFiles) * 100)));
}

function getProgressMessage(busy) {
  if (!busy) return '';
  if (busy === 'login') return '로그인 중입니다.';
  if (busy === 'web') return '웹 페이지를 추출하고 임베딩하는 중입니다.';
  if (busy === 'file') return '파일 텍스트를 추출하고 임베딩하는 중입니다.';
  if (busy === 'ask' || busy === 'code-ask') return '근거 검색 후 답변을 생성하는 중입니다.';
  if (busy === 'search' || busy === 'code-search') return '검색 결과를 가져오는 중입니다.';
  if (busy === 'repo-register') return '저장소를 등록하는 중입니다.';
  if (busy.startsWith('repo-index-')) return '저장소를 동기화하고 코드 청크를 인덱싱하는 중입니다.';
  if (busy.startsWith('repo-cancel-')) return '인덱싱 취소를 요청하는 중입니다.';
  if (busy.startsWith('repo-delete-')) return '저장소를 삭제하는 중입니다.';
  if (busy.startsWith('repo-clear-jobs-')) return '실패/취소 인덱싱 이력을 정리하는 중입니다.';
  if (busy.startsWith('job-failures-')) return '인덱싱 실패 사유를 불러오는 중입니다.';
  if (busy.startsWith('code-file-')) return '코드 파일을 불러오는 중입니다.';
  if (busy.startsWith('reindex-')) return '문서를 재색인하는 중입니다.';
  if (busy.startsWith('delete-')) return '문서를 삭제하는 중입니다.';
  if (busy === 'space-create') return '공간을 생성하는 중입니다.';
  if (busy === 'user-invite') return '사용자를 초대하는 중입니다.';
  return '요청을 처리하는 중입니다.';
}

createRoot(document.getElementById('root')).render(<App />);

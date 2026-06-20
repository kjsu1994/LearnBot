import { useEffect, useMemo, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { fetchBlob, fetchJson } from './lib/api.js';
import { clearStoredToken, readStoredToken, storeSessionToken } from './lib/session.js';
import { defaultSpaceId, routePaths } from './config/constants.js';
import { normalizeRoute, routeToView } from './lib/routing.js';
import { AdminWorkspace } from './components/admin/AdminWorkspace.jsx';
import { CodeWorkspace } from './components/code/CodeWorkspace.jsx';
import { DocumentWorkspace } from './components/documents/DocumentWorkspace.jsx';
import { HomePage, LoginScreen, WorkspaceShell } from './components/layout/Layout.jsx';
import { SavedAnswersWorkspace } from './components/saved/SavedAnswersWorkspace.jsx';
import { getProgressMessage } from './lib/formatters.js';

export default function App() {
  const [token, setToken] = useState(readStoredToken);
  const [user, setUser] = useState(null);
  const [spaces, setSpaces] = useState([]);
  const [selectedSpaceId, setSelectedSpaceId] = useState('');
  const [bootstrapping, setBootstrapping] = useState(true);
  const [routePath, setRoutePath] = useState(() => normalizeRoute(window.location.pathname));
  const [activeView, setActiveView] = useState(() => routeToView(normalizeRoute(window.location.pathname)));
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  const [documents, setDocuments] = useState([]);
  const [documentJobs, setDocumentJobs] = useState([]);
  const [repositories, setRepositories] = useState([]);
  const [jobs, setJobs] = useState({});
  const [jobFailures, setJobFailures] = useState({});
  const [jobDiagnostics, setJobDiagnostics] = useState({});
  const [codeFiles, setCodeFiles] = useState([]);
  const [fileQuery, setFileQuery] = useState('');
  const [selectedCodeFile, setSelectedCodeFile] = useState(null);
  const [highlightRange, setHighlightRange] = useState(null);
  const [codeModalOpen, setCodeModalOpen] = useState(false);

  const [webUrl, setWebUrl] = useState('');
  const [webRecursive, setWebRecursive] = useState(true);
  const [webMaxDepth, setWebMaxDepth] = useState(2);
  const [webMaxPages, setWebMaxPages] = useState(30);
  const [webCrawlScope, setWebCrawlScope] = useState('START_PATH');
  const [webRobotsFailurePolicy, setWebRobotsFailurePolicy] = useState('FAIL_CLOSED');
  const [webIncludeAttachments, setWebIncludeAttachments] = useState(false);
  const [webUseSitemap, setWebUseSitemap] = useState(false);
  const [webRenderMode, setWebRenderMode] = useState('STATIC');
  const [files, setFiles] = useState([]);
  const [fileBatchResult, setFileBatchResult] = useState(null);
  const [query, setQuery] = useState('');
  const [question, setQuestion] = useState('');
  const [answerMode, setAnswerMode] = useState('qa');
  const [documentSpeedProfile, setDocumentSpeedProfile] = useState('balanced');
  const [searchResults, setSearchResults] = useState([]);
  const [answer, setAnswer] = useState(null);
  const [answerSavedId, setAnswerSavedId] = useState('');
  const [selectedDocumentId, setSelectedDocumentId] = useState('');
  const [documentDetail, setDocumentDetail] = useState(null);
  const [documentPreviewOpen, setDocumentPreviewOpen] = useState(false);
  const [documentPreview, setDocumentPreview] = useState(null);
  const [documentPreviewBlobUrl, setDocumentPreviewBlobUrl] = useState('');
  const [documentPreviewLoading, setDocumentPreviewLoading] = useState(false);

  const [repoForm, setRepoForm] = useState({
    sourceMode: 'GIT',
    gitUrl: '',
    name: '',
    branch: 'HEAD',
    authType: 'NONE',
    username: '',
    token: '',
    storeToken: false,
  });
  const [zipForm, setZipForm] = useState({
    file: null,
    name: '',
  });
  const [zipReplaceFile, setZipReplaceFile] = useState(null);
  const [indexCredential, setIndexCredential] = useState({
    username: '',
    token: '',
    storeToken: true,
  });
  const [selectedRepositoryId, setSelectedRepositoryId] = useState('');
  const [codeQuestion, setCodeQuestion] = useState('');
  const [codeMode, setCodeMode] = useState('overview');
  const [codeAnswer, setCodeAnswer] = useState(null);
  const [codeAnswerSavedId, setCodeAnswerSavedId] = useState('');
  const [codeSearchQuery, setCodeSearchQuery] = useState('');
  const [codeSearchResults, setCodeSearchResults] = useState([]);
  const [referenceSymbol, setReferenceSymbol] = useState('');
  const [referenceResult, setReferenceResult] = useState(null);
  const [savedAnswers, setSavedAnswers] = useState([]);
  const [selectedSavedAnswer, setSelectedSavedAnswer] = useState(null);
  const [savedAnswerQuery, setSavedAnswerQuery] = useState('');
  const [savedAnswerType, setSavedAnswerType] = useState('');

  const [adminUsers, setAdminUsers] = useState([]);
  const [adminSettings, setAdminSettings] = useState({
    respectRobotsTxt: true,
    allowedDomains: [],
    ollamaBaseUrl: '',
    chatModel: '',
    primaryChatModel: '',
    auxiliaryChatModel: '',
    effectiveOllamaBaseUrl: '',
    effectiveChatModel: '',
    effectivePrimaryChatModel: '',
    effectiveAuxiliaryChatModel: '',
    llmUsingDefaults: true,
  });
  const [auditLogs, setAuditLogs] = useState([]);
  const [spaceTransferResult, setSpaceTransferResult] = useState(null);
  const [inviteForm, setInviteForm] = useState({
    loginId: '',
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
    refreshDocumentJobs();
    refreshRepositories();
  }, [user?.id, activeSpaceId]);

  useEffect(() => {
    if (!selectedRepositoryId) {
      setCodeFiles([]);
      setSelectedCodeFile(null);
      setHighlightRange(null);
      setCodeModalOpen(false);
      return;
    }
    setSelectedCodeFile(null);
    setHighlightRange(null);
    setCodeModalOpen(false);
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
    const runningJobs = documentJobs.filter((job) => job.status === 'RUNNING');
    if (!runningJobs.length) return undefined;
    const timer = window.setInterval(() => {
      refreshDocumentJobs();
      refreshDocuments();
    }, 2500);
    return () => window.clearInterval(timer);
  }, [documentJobs]);

  useEffect(() => {
    if (activeView === 'admin' && user?.role === 'ADMIN') {
      refreshAdmin();
    }
  }, [activeView, user?.role]);

  useEffect(() => {
    if (activeView === 'saved' && user && activeSpaceId) {
      refreshSavedAnswers();
    }
  }, [activeView, user?.id, activeSpaceId, savedAnswerType]);

  useEffect(() => () => {
    if (documentPreviewBlobUrl) {
      URL.revokeObjectURL(documentPreviewBlobUrl);
    }
  }, [documentPreviewBlobUrl]);

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

  useEffect(() => {
    function handleRouteChange() {
      const normalized = normalizeRoute(window.location.pathname);
      if (window.location.pathname !== normalized) {
        window.history.replaceState({}, '', normalized);
      }
      setRoutePath(normalized);
    }
    handleRouteChange();
    window.addEventListener('popstate', handleRouteChange);
    return () => window.removeEventListener('popstate', handleRouteChange);
  }, []);

  useEffect(() => {
    setActiveView(routeToView(routePath));
  }, [routePath]);

  useEffect(() => {
    if (user && routePath === routePaths.admin && user.role !== 'ADMIN') {
      navigateTo(routePaths.code);
    }
  }, [user?.role, routePath]);

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
    clearStoredToken();
    setToken('');
    setUser(null);
    setSpaces([]);
    setSelectedSpaceId('');
    setDocuments([]);
    setDocumentJobs([]);
    setRepositories([]);
    setJobs({});
    setJobFailures({});
    setCodeFiles([]);
    setSelectedCodeFile(null);
    setHighlightRange(null);
    setCodeModalOpen(false);
    setFiles([]);
    setFileBatchResult(null);
    setSavedAnswers([]);
    setSelectedSavedAnswer(null);
    setAnswerSavedId('');
    setCodeAnswerSavedId('');
    setDocumentPreviewOpen(false);
    setDocumentPreview(null);
    setDocumentPreviewBlobUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return '';
    });
  }

  function updateUploadFiles(nextFiles) {
    setFiles(nextFiles);
    setFileBatchResult(null);
  }

  async function request(path, options = {}) {
    try {
      return await fetchJson(path, { ...options, token });
    } catch (err) {
      if (err.status === 401) clearSession();
      throw err;
    }
  }

  async function requestBlob(path, options = {}) {
    try {
      return await fetchBlob(path, { ...options, token });
    } catch (err) {
      if (err.status === 401) clearSession();
      throw err;
    }
  }

  async function run(label, task) {
    setBusy(label);
    setError('');
    try {
      const result = await task();
      return result === undefined ? true : result;
    } catch (err) {
      setError(err.message || '요청을 처리하지 못했습니다.');
      return false;
    } finally {
      setBusy('');
    }
  }

  function spacePath(path) {
    if (!activeSpaceId) return path;
    const separator = path.includes('?') ? '&' : '?';
    return `${path}${separator}spaceId=${encodeURIComponent(activeSpaceId)}`;
  }

  function navigateTo(path) {
    const nextPath = normalizeRoute(path);
    if (window.location.pathname !== nextPath) {
      window.history.pushState({}, '', nextPath);
    }
    setRoutePath(nextPath);
  }

  async function login(credentials) {
    setError('');
    setBusy('login');
    try {
      const { rememberLogin, ...loginPayload } = credentials;
      const data = await fetchJson('/api/auth/login', {
        method: 'POST',
        json: loginPayload,
      });
      storeSessionToken(data.token, Boolean(rememberLogin));
      applySession(data, data.token);
      navigateTo(routePaths.home);
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
        navigateTo(routePaths.home);
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

  async function refreshDocumentJobs() {
    const data = await request(spacePath('/api/document-indexing/jobs'));
    setDocumentJobs(data || []);
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
        json: {
          url: webUrl.trim(),
          spaceId: activeSpaceId,
          recursive: webRecursive,
          maxDepth: Number(webMaxDepth),
          maxPages: Number(webMaxPages),
          crawlScope: webCrawlScope,
          robotsFailurePolicy: webRobotsFailurePolicy,
          includeAttachments: webIncludeAttachments,
          useSitemap: webUseSitemap,
          renderMode: webRenderMode,
        },
      });
      setWebUrl('');
      await Promise.all([refreshDocuments(), refreshDocumentJobs()]);
    });
  }

  async function ingestFile(event) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!files.length) return;
    await run('file', async () => {
      const body = new FormData();
      let firstDocumentId = null;
      setFileBatchResult(null);
      if (files.length === 1) {
        body.append('file', files[0]);
        body.append('spaceId', activeSpaceId);
        const response = await request('/api/sources/files', { method: 'POST', body });
        firstDocumentId = response?.documentId;
      } else {
        files.forEach((selectedFile) => body.append('files', selectedFile));
        body.append('spaceId', activeSpaceId);
        const result = await request('/api/sources/files/batch', { method: 'POST', body });
        setFileBatchResult(result);
        firstDocumentId = result?.items?.find((item) => item.success)?.response?.documentId || null;
      }
      setFiles([]);
      form.reset();
      await Promise.all([refreshDocuments(), refreshDocumentJobs()]);
      if (firstDocumentId) {
        await loadDocumentDetail(firstDocumentId);
      }
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
        json: { question, mode: answerMode, speedProfile: documentSpeedProfile, spaceId: activeSpaceId },
      });
      setAnswer(data);
      setAnswerSavedId('');
    });
  }

  async function saveAnswer() {
    if (!answer) return;
    await run('save-answer', async () => {
      const saved = await request('/api/saved-answers', {
        method: 'POST',
        json: {
          spaceId: activeSpaceId,
          answerType: 'DOCUMENT',
          question,
          mode: answer.mode,
          answer: answer.answer,
          citations: answer.citations || [],
          evidence: answer.evidence || [],
          confidence: answer.confidence,
          diagnostics: answer.diagnostics || [],
        },
      });
      setAnswerSavedId(saved.id);
      setSavedAnswers((current) => [savedSummary(saved), ...current.filter((item) => item.id !== saved.id)]);
      setSelectedSavedAnswer(saved);
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

  async function uploadZipRepository(event) {
    event.preventDefault();
    if (!zipForm.file) return;
    await run('repo-zip-upload', async () => {
      const body = new FormData();
      body.append('file', zipForm.file);
      body.append('spaceId', activeSpaceId);
      if (zipForm.name.trim()) body.append('name', zipForm.name.trim());
      const created = await request('/api/code/repositories/zip', { method: 'POST', body });
      setSelectedRepositoryId(created.id);
      setZipForm({ file: null, name: '' });
      event.currentTarget.reset();
      await refreshRepositories();
      await refreshJobs(created.id);
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

  async function replaceZipRepository(repositoryId, event) {
    event.preventDefault();
    if (!zipReplaceFile) return;
    await run(`repo-zip-replace-${repositoryId}`, async () => {
      const body = new FormData();
      body.append('file', zipReplaceFile);
      await request(`/api/code/repositories/${repositoryId}/zip`, { method: 'POST', body });
      setZipReplaceFile(null);
      event.currentTarget.reset();
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
        setHighlightRange(null);
        setCodeModalOpen(false);
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
    setSelectedCodeFile(null);
    setHighlightRange(range);
    setCodeModalOpen(true);
    const opened = await run(`code-file-${fileId}`, async () => {
      const data = await request(`/api/code/repositories/${repositoryId}/files/${fileId}`);
      setSelectedCodeFile(data);
    });
    if (!opened) setCodeModalOpen(false);
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
          limit: codeMode === 'overview' ? 16 : 10,
        },
      });
      setCodeAnswer(data);
      setCodeAnswerSavedId('');
    });
  }

  async function loadJobDiagnostics(repositoryId, jobId) {
    await run(`job-diagnostics-${jobId}`, async () => {
      const data = await request(`/api/code/repositories/${repositoryId}/jobs/${jobId}/diagnostics`);
      setJobDiagnostics((current) => ({ ...current, [jobId]: data || [] }));
    });
  }

  async function saveCodeAnswer() {
    if (!codeAnswer) return;
    await run('save-code-answer', async () => {
      const saved = await request('/api/saved-answers', {
        method: 'POST',
        json: {
          spaceId: activeSpaceId,
          answerType: 'CODE',
          question: codeQuestion,
          mode: codeAnswer.mode,
          answer: codeAnswer.answer,
          citations: [],
          evidence: codeAnswer.evidence || [],
          confidence: codeAnswer.confidence,
          diagnostics: codeAnswer.diagnostics || [],
          repositoryId: selectedRepositoryId || null,
        },
      });
      setCodeAnswerSavedId(saved.id);
      setSavedAnswers((current) => [savedSummary(saved), ...current.filter((item) => item.id !== saved.id)]);
      setSelectedSavedAnswer(saved);
    });
  }

  async function refreshSavedAnswers() {
    if (!activeSpaceId) return;
    const params = new URLSearchParams();
    params.set('spaceId', activeSpaceId);
    params.set('limit', '80');
    if (savedAnswerType) params.set('type', savedAnswerType);
    if (savedAnswerQuery.trim()) params.set('query', savedAnswerQuery.trim());
    await run('saved-list', async () => {
      const data = await request(`/api/saved-answers?${params.toString()}`);
      setSavedAnswers(data || []);
      setSelectedSavedAnswer((current) => {
        if (!current) return null;
        return data?.some((item) => item.id === current.id) ? current : null;
      });
    });
  }

  async function loadSavedAnswer(savedAnswerId) {
    await run(`saved-detail-${savedAnswerId}`, async () => {
      const data = await request(`/api/saved-answers/${savedAnswerId}`);
      setSelectedSavedAnswer(data);
    });
  }

  async function updateSavedAnswerTitle(savedAnswerId, title) {
    await run(`saved-title-${savedAnswerId}`, async () => {
      const data = await request(`/api/saved-answers/${savedAnswerId}`, {
        method: 'PATCH',
        json: { title },
      });
      setSelectedSavedAnswer(data);
      setSavedAnswers((current) => current.map((item) => (
        item.id === data.id ? savedSummary(data) : item
      )));
    });
  }

  async function deleteSavedAnswer(savedAnswerId) {
    if (!window.confirm('Delete this saved answer?')) return;
    await run(`saved-delete-${savedAnswerId}`, async () => {
      await request(`/api/saved-answers/${savedAnswerId}`, { method: 'DELETE' });
      setSavedAnswers((current) => current.filter((item) => item.id !== savedAnswerId));
      setSelectedSavedAnswer((current) => (current?.id === savedAnswerId ? null : current));
      setAnswerSavedId((current) => (current === savedAnswerId ? '' : current));
      setCodeAnswerSavedId((current) => (current === savedAnswerId ? '' : current));
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
    await run(`document-reindex-${documentId}`, async () => {
      await request(`/api/documents/${documentId}/reindex`, { method: 'POST' });
      await Promise.all([refreshDocuments(), refreshDocumentJobs()]);
    });
  }

  async function loadDocumentDetail(documentId) {
    setSelectedDocumentId(documentId);
    await run(`detail-${documentId}`, async () => {
      const data = await request(`/api/documents/${documentId}`);
      setDocumentDetail(data);
    });
  }

  async function openDocumentPreview(documentId) {
    setError('');
    setDocumentPreviewOpen(true);
    setDocumentPreview(null);
    setDocumentPreviewBlobUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return '';
    });
    setDocumentPreviewLoading(true);
    try {
      const preview = await request(`/api/documents/${documentId}/preview`);
      setDocumentPreview(preview);
      if (preview?.previewType === 'pdf' && preview.originalAvailable) {
        const blob = await requestBlob(`/api/documents/${documentId}/original`);
        const nextUrl = URL.createObjectURL(blob);
        setDocumentPreviewBlobUrl(nextUrl);
      } else if (preview?.previewType === 'presentation_pdf' && preview.renderedAvailable) {
        try {
          const blob = await requestBlob(`/api/documents/${documentId}/preview/rendered`);
          const nextUrl = URL.createObjectURL(blob);
          setDocumentPreviewBlobUrl(nextUrl);
        } catch (err) {
          setDocumentPreview({ ...preview, renderedAvailable: false, previewFallbackReason: 'PRESENTATION_RENDER_FETCH_FAILED' });
          setError(err.message || 'Presentation preview rendering failed. Showing extracted text instead.');
        }
      }
    } catch (err) {
      setDocumentPreview(null);
      setDocumentPreviewBlobUrl((current) => {
        if (current) URL.revokeObjectURL(current);
        return '';
      });
      setError(err.message || '문서 원문을 불러오지 못했습니다.');
    } finally {
      setDocumentPreviewLoading(false);
    }
  }

  function closeDocumentPreview() {
    setDocumentPreviewOpen(false);
    setDocumentPreview(null);
    setDocumentPreviewBlobUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return '';
    });
  }

  async function refreshAdmin() {
    const [users, logs, settings] = await Promise.all([
      request('/api/admin/users'),
      request('/api/admin/audit-logs?limit=50'),
      request('/api/admin/settings'),
    ]);
    setAdminUsers(users || []);
    setAuditLogs(logs || []);
    setAdminSettings(settings || {
      respectRobotsTxt: true,
      allowedDomains: [],
      ollamaBaseUrl: '',
      chatModel: '',
      primaryChatModel: '',
      auxiliaryChatModel: '',
      effectiveOllamaBaseUrl: '',
      effectiveChatModel: '',
      effectivePrimaryChatModel: '',
      effectiveAuxiliaryChatModel: '',
      llmUsingDefaults: true,
    });
  }

  async function updateAdminSettings(nextSettings) {
    return await run('admin-settings', async () => {
      const settings = await request('/api/admin/settings', {
        method: 'PATCH',
        json: nextSettings,
      });
      if (settings) {
        setAdminSettings(settings);
      }
    });
  }

  async function testAdminLlmSettings(settings) {
    return await run('admin-llm-test', async () => {
      return await request('/api/admin/settings/llm/test', {
        method: 'POST',
        json: settings,
      });
    });
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
      setInviteForm({ loginId: '', displayName: '', initialPassword: '', role: 'USER', spaceRole: 'MEMBER' });
      await refreshAdmin();
    });
  }

  async function deleteAdminUser(userId, displayName) {
    if (!window.confirm(`${displayName || '사용자'} 계정을 삭제할까요? 해당 사용자는 즉시 로그인할 수 없습니다.`)) return;
    await run(`user-delete-${userId}`, async () => {
      await request(`/api/admin/users/${userId}`, { method: 'DELETE' });
      await refreshAdmin();
    });
  }

  async function updateAdminUser(userId, values) {
    return await run(`user-update-${userId}`, async () => {
      await request(`/api/admin/users/${userId}`, {
        method: 'PATCH',
        json: values,
      });
      await refreshSession();
      await refreshAdmin();
    });
  }

  async function resetAdminUserPassword(userId, newPassword) {
    return await run(`user-password-${userId}`, async () => {
      await request(`/api/admin/users/${userId}/password`, {
        method: 'POST',
        json: { newPassword },
      });
      await refreshAdmin();
    });
  }

  async function saveAdminUserSpaceRoles(userId, operations = []) {
    return await run(`user-spaces-${userId}`, async () => {
      for (const operation of operations) {
        if (operation.role) {
          await request(`/api/admin/users/${userId}/spaces/${operation.spaceId}`, {
            method: 'PUT',
            json: { role: operation.role },
          });
        } else {
          await request(`/api/admin/users/${userId}/spaces/${operation.spaceId}`, { method: 'DELETE' });
        }
      }
      if (userId === user?.id) {
        await refreshSession();
      }
      await refreshAdmin();
    });
  }

  async function updateSpace(spaceId, values) {
    await run(`space-update-${spaceId}`, async () => {
      await request(`/api/admin/spaces/${spaceId}`, { method: 'PATCH', json: values });
      await refreshSession();
      await refreshAdmin();
    });
  }

  async function deleteSpace(spaceId, name) {
    if (!window.confirm(`${name || '공간'} 공간을 삭제할까요? 삭제된 공간의 문서와 코드 저장소는 더 이상 목록에 표시되지 않습니다.`)) return;
    await run(`space-delete-${spaceId}`, async () => {
      await request(`/api/admin/spaces/${spaceId}`, { method: 'DELETE' });
      await refreshSession();
      await refreshAdmin();
      setSelectedSpaceId((current) => (current === spaceId ? '' : current));
    });
  }

  async function exportSpaceArchive(spaceId) {
    return await run(`space-export-${spaceId}`, async () => {
      const result = await request(`/api/admin/spaces/${spaceId}/rag-export`, { method: 'POST' });
      setSpaceTransferResult({ type: 'export', spaceId, result });
      await refreshAdmin();
      return result;
    });
  }

  async function importSpaceArchive(spaceId, file) {
    return await run(`space-import-${spaceId}`, async () => {
      const body = new FormData();
      body.append('file', file);
      const result = await request(`/api/admin/spaces/${spaceId}/rag-import`, { method: 'POST', body });
      setSpaceTransferResult({ type: 'import', spaceId, result });
      await Promise.all([refreshDocuments(), refreshRepositories(), refreshAdmin()]);
      return result;
    });
  }

  async function downloadSpaceArchive(spaceId, fileName) {
    if (!fileName) return false;
    return await run(`space-download-${spaceId}`, async () => {
      const blob = await requestBlob(`/api/admin/spaces/${spaceId}/rag-export/files/${encodeURIComponent(fileName)}`);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    });
  }

  function savedSummary(saved) {
    return {
      id: saved.id,
      spaceId: saved.spaceId,
      answerType: saved.answerType,
      question: saved.question,
      mode: saved.mode,
      answerPreview: saved.answerPreview || saved.answer,
      confidence: saved.confidence,
      repositoryId: saved.repositoryId,
      title: saved.title,
      createdAt: saved.createdAt,
      updatedAt: saved.updatedAt,
    };
  }

  const loading = (name) => busy === name;
  const runningDocumentJobs = documentJobs.filter((job) => job.status === 'RUNNING');
  const documentProgressMessage = runningDocumentJobs.length
    ? `문서 인덱싱 ${runningDocumentJobs.length}건이 백그라운드에서 진행 중입니다.`
    : '';
  const progressMessage = getProgressMessage(busy) || documentProgressMessage;

  if (routePath === routePaths.home) {
    return <HomePage user={user} bootstrapping={bootstrapping} navigateTo={navigateTo} logout={logout} />;
  }

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
    <WorkspaceShell
      user={user}
      spaces={spaces}
      selectedSpace={selectedSpace}
      selectedSpaceId={activeSpaceId}
      setSelectedSpaceId={setSelectedSpaceId}
      sidebarCollapsed={sidebarCollapsed}
      setSidebarCollapsed={setSidebarCollapsed}
      indexedRepoCount={indexedRepoCount}
      indexedCount={indexedCount}
      codeChunkCount={codeChunkCount}
      webCount={webCount}
      fileCount={fileCount}
      activeView={activeView}
      navigateTo={navigateTo}
      refreshRepositories={refreshRepositories}
      refreshDocuments={refreshDocuments}
      logout={logout}
      error={error}
      progressMessage={progressMessage}
      selectedRepository={selectedRepository}
      selectedRepositoryId={selectedRepositoryId}
      codeFiles={codeFiles}
      fileQuery={fileQuery}
      setFileQuery={setFileQuery}
      searchCodeFiles={searchCodeFiles}
      openCodeFile={openCodeFile}
      documents={documents}
      selectedDocumentId={selectedDocumentId}
      loadDocumentDetail={loadDocumentDetail}
      openDocumentPreview={openDocumentPreview}
      deleteDocument={deleteDocument}
      loading={loading}
    >

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
            jobDiagnostics={jobDiagnostics}
            loadJobDiagnostics={loadJobDiagnostics}
            codeFiles={codeFiles}
            fileQuery={fileQuery}
            setFileQuery={setFileQuery}
            selectedCodeFile={selectedCodeFile}
            highlightRange={highlightRange}
            codeModalOpen={codeModalOpen}
            setCodeModalOpen={setCodeModalOpen}
            codeQuestion={codeQuestion}
            setCodeQuestion={setCodeQuestion}
            codeMode={codeMode}
            setCodeMode={setCodeMode}
            codeAnswer={codeAnswer}
            answerSavedId={codeAnswerSavedId}
            saveAnswer={saveCodeAnswer}
            codeSearchQuery={codeSearchQuery}
            setCodeSearchQuery={setCodeSearchQuery}
            codeSearchResults={codeSearchResults}
            referenceSymbol={referenceSymbol}
            setReferenceSymbol={setReferenceSymbol}
            referenceResult={referenceResult}
            registerRepository={registerRepository}
            uploadZipRepository={uploadZipRepository}
            zipForm={zipForm}
            setZipForm={setZipForm}
            zipReplaceFile={zipReplaceFile}
            setZipReplaceFile={setZipReplaceFile}
            replaceZipRepository={replaceZipRepository}
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
            codeFileLoading={busy.startsWith('code-file-')}
            showSourceManagement={false}
          />
        )}

        {activeView === 'docs' && (
          <DocumentWorkspace
            webUrl={webUrl}
            setWebUrl={setWebUrl}
            webRecursive={webRecursive}
            setWebRecursive={setWebRecursive}
            webMaxDepth={webMaxDepth}
            setWebMaxDepth={setWebMaxDepth}
            webMaxPages={webMaxPages}
            setWebMaxPages={setWebMaxPages}
            webCrawlScope={webCrawlScope}
            setWebCrawlScope={setWebCrawlScope}
            webRobotsFailurePolicy={webRobotsFailurePolicy}
            setWebRobotsFailurePolicy={setWebRobotsFailurePolicy}
            webIncludeAttachments={webIncludeAttachments}
            setWebIncludeAttachments={setWebIncludeAttachments}
            webUseSitemap={webUseSitemap}
            setWebUseSitemap={setWebUseSitemap}
            webRenderMode={webRenderMode}
            setWebRenderMode={setWebRenderMode}
            files={files}
            setFiles={updateUploadFiles}
            fileBatchResult={fileBatchResult}
            ingestWeb={ingestWeb}
            ingestFile={ingestFile}
            documents={documents}
            documentJobs={documentJobs}
            selectedDocumentId={selectedDocumentId}
            loadDocumentDetail={loadDocumentDetail}
            deleteDocument={deleteDocument}
            reindexDocument={reindexDocument}
            openDocumentPreview={openDocumentPreview}
            documentDetail={documentDetail}
            documentPreviewOpen={documentPreviewOpen}
            documentPreview={documentPreview}
            documentPreviewBlobUrl={documentPreviewBlobUrl}
            documentPreviewLoading={documentPreviewLoading}
            closeDocumentPreview={closeDocumentPreview}
            answerMode={answerMode}
            setAnswerMode={setAnswerMode}
            documentSpeedProfile={documentSpeedProfile}
            setDocumentSpeedProfile={setDocumentSpeedProfile}
            question={question}
            setQuestion={setQuestion}
            ask={ask}
            answer={answer}
            answerSavedId={answerSavedId}
            saveAnswer={saveAnswer}
            query={query}
            setQuery={setQuery}
            search={search}
            searchResults={searchResults}
            loading={loading}
            showSourceManagement={false}
          />
        )}

        {activeView === 'saved' && (
          <SavedAnswersWorkspace
            savedAnswers={savedAnswers}
            selectedSavedAnswer={selectedSavedAnswer}
            savedAnswerQuery={savedAnswerQuery}
            setSavedAnswerQuery={setSavedAnswerQuery}
            savedAnswerType={savedAnswerType}
            setSavedAnswerType={setSavedAnswerType}
            refreshSavedAnswers={refreshSavedAnswers}
            loadSavedAnswer={loadSavedAnswer}
            updateSavedAnswerTitle={updateSavedAnswerTitle}
            deleteSavedAnswer={deleteSavedAnswer}
            loading={loading}
          />
        )}

        {activeView === 'admin' && user.role === 'ADMIN' && (
          <AdminWorkspace
            currentUser={user}
            users={adminUsers}
            adminSettings={adminSettings}
            spaces={spaces}
            selectedSpaceId={activeSpaceId}
            auditLogs={auditLogs}
            inviteForm={inviteForm}
            setInviteForm={setInviteForm}
            spaceForm={spaceForm}
            setSpaceForm={setSpaceForm}
            createSpace={createSpace}
            inviteUser={inviteUser}
            deleteAdminUser={deleteAdminUser}
            updateAdminUser={updateAdminUser}
            resetAdminUserPassword={resetAdminUserPassword}
            saveAdminUserSpaceRoles={saveAdminUserSpaceRoles}
            updateSpace={updateSpace}
            deleteSpace={deleteSpace}
            exportSpaceArchive={exportSpaceArchive}
            importSpaceArchive={importSpaceArchive}
            downloadSpaceArchive={downloadSpaceArchive}
            spaceTransferResult={spaceTransferResult}
            updateAdminSettings={updateAdminSettings}
            testAdminLlmSettings={testAdminLlmSettings}
            refreshAdmin={refreshAdmin}
            loading={loading}
            codeSourceProps={{
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
              registerRepository,
              uploadZipRepository,
              zipForm,
              setZipForm,
              zipReplaceFile,
              setZipReplaceFile,
              replaceZipRepository,
              indexRepository,
              cancelIndex,
              deleteRepository,
              clearFailedJobs,
              refreshJobs,
            }}
            documentSourceProps={{
              webUrl,
              setWebUrl,
              webRecursive,
              setWebRecursive,
              webMaxDepth,
              setWebMaxDepth,
              webMaxPages,
              setWebMaxPages,
              webCrawlScope,
              setWebCrawlScope,
              webRobotsFailurePolicy,
              setWebRobotsFailurePolicy,
              webIncludeAttachments,
              setWebIncludeAttachments,
              webUseSitemap,
              setWebUseSitemap,
              webRenderMode,
              setWebRenderMode,
              files,
              setFiles: updateUploadFiles,
              fileBatchResult,
              ingestWeb,
              ingestFile,
              documents,
              documentJobs,
              selectedDocumentId,
              loadDocumentDetail,
              openDocumentPreview,
              reindexDocument,
              deleteDocument,
            }}
          />
        )}
    </WorkspaceShell>
  );
}

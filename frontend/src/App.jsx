import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
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
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import csharp from 'highlight.js/lib/languages/csharp';
import css from 'highlight.js/lib/languages/css';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import markdown from 'highlight.js/lib/languages/markdown';
import plaintext from 'highlight.js/lib/languages/plaintext';
import powershell from 'highlight.js/lib/languages/powershell';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';
import 'highlight.js/styles/github-dark.css';
import './styles.css';

hljs.registerLanguage('bash', bash);
hljs.registerLanguage('csharp', csharp);
hljs.registerLanguage('css', css);
hljs.registerLanguage('java', java);
hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('json', json);
hljs.registerLanguage('markdown', markdown);
hljs.registerLanguage('plaintext', plaintext);
hljs.registerLanguage('powershell', powershell);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('yaml', yaml);

const apiBase = import.meta.env.VITE_API_BASE_URL ?? '';
const tokenKey = 'runbot.session.token';
const defaultSpaceId = '00000000-0000-0000-0000-000000000001';

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
  ACTIVE: '활성',
  DELETED: '삭제됨',
};

const answerModes = [
  { value: 'qa', label: '질문 답변' },
  { value: 'summary', label: '요약' },
  { value: 'table', label: '표 추출' },
  { value: 'quote', label: '원문 인용' },
];

const codeModes = [
  { value: 'overview', label: '통합 질문' },
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

async function fetchBlob(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.token) {
    headers.set('Authorization', `Bearer ${options.token}`);
  }

  const response = await fetch(`${apiBase}${path}`, {
    method: options.method || 'GET',
    headers,
  });
  if (!response.ok) {
    const message = await responseMessage(response);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return response.blob();
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
  const [codeModalOpen, setCodeModalOpen] = useState(false);

  const [webUrl, setWebUrl] = useState('');
  const [webRecursive, setWebRecursive] = useState(true);
  const [webMaxDepth, setWebMaxDepth] = useState(2);
  const [webMaxPages, setWebMaxPages] = useState(30);
  const [files, setFiles] = useState([]);
  const [fileBatchResult, setFileBatchResult] = useState(null);
  const [query, setQuery] = useState('');
  const [question, setQuestion] = useState('');
  const [answerMode, setAnswerMode] = useState('qa');
  const [searchResults, setSearchResults] = useState([]);
  const [answer, setAnswer] = useState(null);
  const [selectedDocumentId, setSelectedDocumentId] = useState('');
  const [documentDetail, setDocumentDetail] = useState(null);
  const [documentPreviewOpen, setDocumentPreviewOpen] = useState(false);
  const [documentPreview, setDocumentPreview] = useState(null);
  const [documentPreviewBlobUrl, setDocumentPreviewBlobUrl] = useState('');
  const [documentPreviewLoading, setDocumentPreviewLoading] = useState(false);

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
  const [adminSettings, setAdminSettings] = useState({ respectRobotsTxt: true, allowedDomains: [] });
  const [auditLogs, setAuditLogs] = useState([]);
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
    if (activeView === 'admin' && user?.role === 'ADMIN') {
      refreshAdmin();
    }
  }, [activeView, user?.role]);

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
    setCodeFiles([]);
    setSelectedCodeFile(null);
    setHighlightRange(null);
    setCodeModalOpen(false);
    setFiles([]);
    setFileBatchResult(null);
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
      await task();
      return true;
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
        json: {
          url: webUrl.trim(),
          spaceId: activeSpaceId,
          recursive: webRecursive,
          maxDepth: Number(webMaxDepth),
          maxPages: Number(webMaxPages),
        },
      });
      setWebUrl('');
      await refreshDocuments();
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
      await refreshDocuments();
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
    setAdminSettings(settings || { respectRobotsTxt: true, allowedDomains: [] });
  }

  async function updateAdminSettings(nextSettings) {
    await run('admin-settings', async () => {
      const settings = await request('/api/admin/settings', {
        method: 'PATCH',
        json: nextSettings,
      });
      if (settings) {
        setAdminSettings(settings);
      }
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
            <button className="ghost-button compact-action top-logout" type="button" onClick={logout}>
              <LogOut size={14} />
              로그아웃
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

        <ScreenGuide activeView={activeView} />

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
            codeModalOpen={codeModalOpen}
            setCodeModalOpen={setCodeModalOpen}
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
            codeFileLoading={busy.startsWith('code-file-')}
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
            files={files}
            setFiles={updateUploadFiles}
            fileBatchResult={fileBatchResult}
            ingestWeb={ingestWeb}
            ingestFile={ingestFile}
            documents={documents}
            selectedDocumentId={selectedDocumentId}
            loadDocumentDetail={loadDocumentDetail}
            reindexDocument={reindexDocument}
            deleteDocument={deleteDocument}
            openDocumentPreview={openDocumentPreview}
            documentDetail={documentDetail}
            documentPreviewOpen={documentPreviewOpen}
            documentPreview={documentPreview}
            documentPreviewBlobUrl={documentPreviewBlobUrl}
            documentPreviewLoading={documentPreviewLoading}
            closeDocumentPreview={closeDocumentPreview}
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
            updateAdminSettings={updateAdminSettings}
            refreshAdmin={refreshAdmin}
            loading={loading}
          />
        )}
      </section>
    </main>
  );
}

function LoginScreen({ onLogin, busy, error }) {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');

  function submit(event) {
    event.preventDefault();
    onLogin({ loginId, password });
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
        <form className="stack" onSubmit={submit} autoComplete="off">
          <label htmlFor="login-id">ID</label>
          <input id="login-id" value={loginId} onChange={(event) => setLoginId(event.target.value)} autoComplete="off" spellCheck="false" />
          <label htmlFor="login-password">비밀번호</label>
          <input id="login-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" />
          <button disabled={!loginId || !password || busy}>
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
        <small className="sidebar-note">{user.displayName || user.loginId || user.email} · {user.role}</small>
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

    </aside>
  );
}

function ScreenGuide({ activeView }) {
  const guides = {
    code: {
      title: '코드 RAG 화면',
      description: 'Git 저장소를 등록하고 인덱싱한 뒤, 실제 파일 경로와 라인 범위를 근거로 코드 질문에 답합니다.',
      points: ['저장소 상태가 인덱싱 완료인지 확인', '실패 사유 버튼으로 모델, Git 인증, 파일별 실패 원인 확인', '코드 검색과 정의/참조로 답변 근거 검증'],
    },
    docs: {
      title: '문서 RAG 화면',
      description: 'PDF, DOCX, Markdown, TXT, CSV, Excel, 웹 문서를 업로드하거나 수집해 사내 위키형 근거로 사용합니다.',
      points: ['업로드 후 문서 상태가 인덱싱 완료인지 확인', '문서 상세에서 청크가 생성됐는지 확인', '근거가 없으면 답변하지 않는 정책으로 검증'],
    },
    admin: {
      title: '관리자 화면',
      description: '사내 다중 사용자 운영을 위해 계정, 공간, 감사 로그를 관리합니다.',
      points: ['공간별로 접근 가능한 자료 분리', '초대 사용자의 시스템/공간 권한 지정', '로그인, 인덱싱, 삭제 같은 핵심 작업 감사'],
    },
  };
  const guide = guides[activeView] || guides.code;
  return (
    <section className="screen-guide">
      <div>
        <span className="eyebrow">Screen Guide</span>
        <h2>{guide.title}</h2>
        <p>{guide.description}</p>
      </div>
      <ul>
        {guide.points.map((point) => (
          <li key={point}>{point}</li>
        ))}
      </ul>
    </section>
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
    codeModalOpen,
    setCodeModalOpen,
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
    codeFileLoading,
  } = props;
  const activeCodeModeGuide = getCodeModeGuide(codeMode);

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
          <div className="document-list scrollable-list">
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
          <div className="question-guide">
            <strong>{activeCodeModeGuide.title}</strong>
            <p>{activeCodeModeGuide.description}</p>
            <ul>
              {activeCodeModeGuide.tips.map((tip) => (
                <li key={tip}>{tip}</li>
              ))}
            </ul>
          </div>
          <textarea value={codeQuestion} onChange={(event) => setCodeQuestion(event.target.value)} placeholder={activeCodeModeGuide.placeholder} />
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
              {(codeAnswer.confidence || codeAnswer.diagnostics?.length > 0) && (
                <div className={`confidence-strip confidence-${confidenceClass(codeAnswer.confidence)}`}>
                  {codeAnswer.confidence && <strong>신뢰도 {codeAnswer.confidence}</strong>}
                  {codeAnswer.diagnostics?.length > 0 && (
                    <ul>
                      {codeAnswer.diagnostics.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
              <MarkdownAnswer text={codeAnswer.answer} />
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

        {codeModalOpen && (
          <CodeFileModal
            detail={selectedCodeFile}
            highlightRange={highlightRange}
            loading={codeFileLoading}
            onClose={() => setCodeModalOpen(false)}
          />
        )}
      </div>
    </section>
  );
}

function DocumentWorkspace(props) {
  const activeAnswerModeGuide = getAnswerModeGuide(props.answerMode);

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
              <input id="web-url" value={props.webUrl} onChange={(event) => props.setWebUrl(event.target.value)} placeholder="https://example.com/docs 또는 example.com/docs" />
              <button disabled={!props.webUrl || props.loading('web')}>
                {props.loading('web') ? <Loader2 className="spin" size={16} /> : <Globe size={16} />}
                인덱싱
              </button>
            </div>
            <label className="checkbox-row" htmlFor="web-recursive">
              <input id="web-recursive" type="checkbox" checked={props.webRecursive} onChange={(event) => props.setWebRecursive(event.target.checked)} />
              <span>시작 URL의 하위 경로를 재귀 수집</span>
            </label>
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="web-max-depth">깊이</label>
                <input
                  id="web-max-depth"
                  type="number"
                  min="0"
                  max="2"
                  value={props.webMaxDepth}
                  disabled={!props.webRecursive}
                  onChange={(event) => props.setWebMaxDepth(event.target.value)}
                />
              </div>
              <div className="stack">
                <label htmlFor="web-max-pages">최대 페이지</label>
                <input
                  id="web-max-pages"
                  type="number"
                  min="1"
                  max="30"
                  value={props.webMaxPages}
                  disabled={!props.webRecursive}
                  onChange={(event) => props.setWebMaxPages(event.target.value)}
                />
              </div>
            </div>
          </form>
          <form className="stack" onSubmit={props.ingestFile}>
            <label htmlFor="file-upload">파일 업로드</label>
            <div className="file-row">
              <label className="file-picker" htmlFor="file-upload">
                <FileUp size={16} />
                <span>{formatSelectedFiles(props.files)}</span>
              </label>
              <input id="file-upload" className="visually-hidden" type="file" accept=".pdf,.docx,.md,.markdown,.txt,.csv,.xls,.xlsx" multiple onChange={(event) => props.setFiles(Array.from(event.target.files || []))} />
              <button disabled={!props.files?.length || props.loading('file')}>
                {props.loading('file') ? <Loader2 className="spin" size={16} /> : <FileUp size={16} />}
                업로드
              </button>
            </div>
            {props.files?.length > 1 && (
              <div className="selected-file-list">
                {props.files.map((item) => <span key={`${item.name}-${item.size}`}>{item.name}</span>)}
              </div>
            )}
            {props.fileBatchResult && <FileBatchResult result={props.fileBatchResult} />}
          </form>
        </section>

        <section className="panel documents-panel">
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>문서 목록</h2>
              <p>{props.documents.length ? `${props.documents.length}개 문서` : '인덱싱된 문서가 없습니다.'}</p>
            </div>
          </div>
          <div className="document-list scrollable-list">
            {props.documents.map((doc) => (
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
                  <IconButton title="원문 보기" disabled={props.loading(`detail-${doc.id}`) || props.loading(`delete-${doc.id}`)} onClick={(event) => { event.stopPropagation(); props.openDocumentPreview(doc.id); }}>
                    <Eye size={15} />
                  </IconButton>
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
        {props.documentPreviewOpen && (
          <DocumentPreviewModal
            preview={props.documentPreview}
            blobUrl={props.documentPreviewBlobUrl}
            loading={props.documentPreviewLoading}
            onClose={props.closeDocumentPreview}
          />
        )}
      </div>

      <div className="right-column">
        <form className="panel ask-panel" onSubmit={props.ask}>
          <div className="panel-title">
            <MessageSquare size={18} />
            <div>
              <h2>문서에게 질문하기</h2>
              <p>현재 선택된 공간의 인덱싱 완료 문서 전체에서 근거를 찾아 답변합니다.</p>
            </div>
          </div>
          <ModeControl modes={answerModes} value={props.answerMode} setValue={props.setAnswerMode} />
          <div className="question-guide">
            <strong>{activeAnswerModeGuide.title}</strong>
            <p>{activeAnswerModeGuide.description}</p>
            <ul>
              {activeAnswerModeGuide.tips.map((tip) => (
                <li key={tip}>{tip}</li>
              ))}
            </ul>
          </div>
          <textarea value={props.question} onChange={(event) => props.setQuestion(event.target.value)} placeholder={activeAnswerModeGuide.placeholder} />
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
              {(props.answer.confidence || props.answer.diagnostics?.length > 0) && (
                <div className={`confidence-strip confidence-${confidenceClass(props.answer.confidence)}`}>
                  {props.answer.confidence && <strong>신뢰도 {props.answer.confidence}</strong>}
                  {props.answer.diagnostics?.length > 0 && (
                    <ul>
                      {props.answer.diagnostics.map((item) => (
                        <li key={item}>{item}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
              <MarkdownAnswer text={props.answer.answer} />
              <EvidenceList evidence={props.answer.evidence} />
            </div>
          )}
        </form>
      </div>
    </section>
  );
}

function AdminWorkspace({
  currentUser,
  users,
  adminSettings,
  spaces,
  selectedSpaceId,
  auditLogs,
  inviteForm,
  setInviteForm,
  spaceForm,
  setSpaceForm,
  createSpace,
  inviteUser,
  deleteAdminUser,
  updateAdminUser,
  resetAdminUserPassword,
  saveAdminUserSpaceRoles,
  updateSpace,
  deleteSpace,
  updateAdminSettings,
  refreshAdmin,
  loading,
}) {
  const [editingSpaceId, setEditingSpaceId] = useState('');
  const [spaceEditForm, setSpaceEditForm] = useState({ name: '', description: '' });
  const [allowedDomainText, setAllowedDomainText] = useState(() => (adminSettings?.allowedDomains || []).join('\n'));
  const [editingUser, setEditingUser] = useState(null);
  const [userEditForm, setUserEditForm] = useState({ loginId: '', displayName: '', role: 'USER' });
  const [passwordUser, setPasswordUser] = useState(null);
  const [passwordForm, setPasswordForm] = useState({ newPassword: '', confirmPassword: '' });
  const [permissionsUser, setPermissionsUser] = useState(null);
  const [permissionDraft, setPermissionDraft] = useState({});
  const [modalError, setModalError] = useState('');

  useEffect(() => {
    setAllowedDomainText((adminSettings?.allowedDomains || []).join('\n'));
  }, [adminSettings?.allowedDomains]);

  function beginEditSpace(space) {
    setEditingSpaceId(space.id);
    setSpaceEditForm({ name: space.name || '', description: space.description || '' });
  }

  function closeUserModals() {
    setEditingUser(null);
    setPasswordUser(null);
    setPermissionsUser(null);
    setModalError('');
    setUserEditForm({ loginId: '', displayName: '', role: 'USER' });
    setPasswordForm({ newPassword: '', confirmPassword: '' });
    setPermissionDraft({});
  }

  function beginEditUser(item) {
    setModalError('');
    setEditingUser(item);
    setUserEditForm({ loginId: item.loginId || item.email || '', displayName: item.displayName || '', role: item.role || 'USER' });
  }

  function beginPasswordReset(item) {
    setModalError('');
    setPasswordUser(item);
    setPasswordForm({ newPassword: '', confirmPassword: '' });
  }

  function beginPermissionEdit(item) {
    if (item.role === 'ADMIN') {
      return;
    }
    const draft = {};
    (item.spaces || []).forEach((space) => {
      draft[space.id] = space.role;
    });
    setModalError('');
    setPermissionsUser(item);
    setPermissionDraft(draft);
  }

  async function submitUserEdit(event) {
    event.preventDefault();
    if (!editingUser) return;
    const saved = await updateAdminUser(editingUser.id, userEditForm);
    if (saved) closeUserModals();
  }

  async function submitPasswordReset(event) {
    event.preventDefault();
    if (!passwordUser) return;
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      setModalError('비밀번호 확인이 일치하지 않습니다.');
      return;
    }
    const saved = await resetAdminUserPassword(passwordUser.id, passwordForm.newPassword);
    if (saved) closeUserModals();
  }

  async function submitPermissionEdit(event) {
    event.preventDefault();
    if (!permissionsUser) return;
    const currentRoles = new Map((permissionsUser.spaces || []).map((space) => [space.id, space.role]));
    const operations = spaces
      .map((space) => {
        const currentRole = currentRoles.get(space.id) || '';
        const nextRole = permissionDraft[space.id] || '';
        if (currentRole === nextRole) return null;
        return { spaceId: space.id, role: nextRole };
      })
      .filter(Boolean);
    if (!operations.length) {
      closeUserModals();
      return;
    }
    const saved = await saveAdminUserSpaceRoles(permissionsUser.id, operations);
    if (saved) closeUserModals();
  }

  async function submitSpaceEdit(event, spaceId) {
    event.preventDefault();
    await updateSpace(spaceId, spaceEditForm);
    setEditingSpaceId('');
    setSpaceEditForm({ name: '', description: '' });
  }

  async function submitAllowedDomains(event) {
    event.preventDefault();
    const allowedDomains = allowedDomainText
      .split(/[,\n]+/)
      .map((item) => item.trim())
      .filter(Boolean);
    await updateAdminSettings({
      respectRobotsTxt: adminSettings?.respectRobotsTxt ?? true,
      allowedDomains,
    });
  }

  return (
    <section className="workspace-grid">
      <div className="left-column">
        <section className="panel">
          <div className="panel-title">
            <ShieldCheck size={18} />
            <div>
              <h2>크롤링 정책</h2>
              <p>전체 사용자의 웹 인덱싱 정책을 관리합니다.</p>
            </div>
          </div>
          <label className="switch-row" htmlFor="respect-robots">
            <input
              id="respect-robots"
              type="checkbox"
              checked={adminSettings?.respectRobotsTxt ?? true}
              disabled={loading('admin-settings')}
              onChange={(event) => updateAdminSettings({ respectRobotsTxt: event.target.checked })}
            />
            <span className="switch-track" aria-hidden="true">
              <span />
            </span>
            <span>
              <strong>robots.txt 정책 준수</strong>
              <small>
                {(adminSettings?.respectRobotsTxt ?? true)
                  ? '켜짐 · robots.txt에서 차단한 URL은 인덱싱하지 않습니다.'
                  : '꺼짐 · robots.txt에서 차단한 URL도 인덱싱을 진행합니다.'}
              </small>
            </span>
          </label>
          <form className="stack admin-url-form" onSubmit={submitAllowedDomains}>
            <label htmlFor="allowed-domains">허용 URL / 도메인</label>
            <textarea
              id="allowed-domains"
              value={allowedDomainText}
              onChange={(event) => setAllowedDomainText(event.target.value)}
              placeholder={'example.com\nhttps://docs.example.com/guide\nintranet.local'}
              spellCheck="false"
            />
            <small className="field-help">
              한 줄에 하나씩 입력하거나 쉼표로 구분하세요. 전체 URL을 입력해도 호스트만 저장됩니다.
              예: `https://docs.example.com/guide` → `docs.example.com`.
              등록한 도메인과 그 하위 도메인만 웹 인덱싱할 수 있습니다.
            </small>
            <div className="action-row">
              <button disabled={!allowedDomainText.trim() || loading('admin-settings')}>
                {loading('admin-settings') ? <Loader2 className="spin" size={16} /> : <Globe size={16} />}
                허용 목록 저장
              </button>
            </div>
          </form>
        </section>

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
              <label htmlFor="invite-login-id">ID</label>
              <input id="invite-login-id" value={inviteForm.loginId} onChange={(event) => setInviteForm((current) => ({ ...current, loginId: event.target.value }))} autoComplete="off" spellCheck="false" />
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
            <button disabled={!inviteForm.loginId || !inviteForm.displayName || !inviteForm.initialPassword || loading('user-invite')}>
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
                  <small>{item.loginId || item.email}</small>
                  <small>{item.role === 'ADMIN' ? '전체 공간 접근' : `${item.spaces?.length || 0}개 공간 권한`}</small>
                </div>
                <div className="document-meta">
                  <StatusBadge status={item.status} />
                  <small>{item.role}</small>
                </div>
                <div className="document-actions">
                  <IconButton title="계정 편집" onClick={() => beginEditUser(item)}>
                    <Info size={15} />
                  </IconButton>
                  <IconButton
                    title={item.id === currentUser?.id ? '현재 로그인한 계정의 비밀번호는 여기서 재설정할 수 없습니다.' : '비밀번호 재설정'}
                    disabled={item.id === currentUser?.id || loading(`user-password-${item.id}`)}
                    onClick={() => beginPasswordReset(item)}
                  >
                    {loading(`user-password-${item.id}`) ? <Loader2 className="spin" size={15} /> : <LockKeyhole size={15} />}
                  </IconButton>
                  <IconButton
                    title={item.role === 'ADMIN' ? 'ADMIN 계정은 모든 공간에 접근합니다.' : '공간 권한 관리'}
                    disabled={item.role === 'ADMIN' || loading(`user-spaces-${item.id}`)}
                    onClick={() => beginPermissionEdit(item)}
                  >
                    {loading(`user-spaces-${item.id}`) ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}
                  </IconButton>
                  <IconButton
                    danger
                    title={item.id === currentUser?.id ? '현재 로그인한 계정은 삭제할 수 없습니다.' : '사용자 삭제'}
                    disabled={item.id === currentUser?.id || loading(`user-delete-${item.id}`)}
                    onClick={() => deleteAdminUser(item.id, item.displayName)}
                  >
                    {loading(`user-delete-${item.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                  </IconButton>
                </div>
              </article>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>공간 관리</h2>
              <p>{spaces.length}개 공간</p>
            </div>
          </div>
          <div className="document-list">
            {spaces.map((space) => (
              <article className="document-row space-admin-row" key={space.id}>
                {editingSpaceId === space.id ? (
                  <form className="space-edit-form" onSubmit={(event) => submitSpaceEdit(event, space.id)}>
                    <input
                      value={spaceEditForm.name}
                      onChange={(event) => setSpaceEditForm((current) => ({ ...current, name: event.target.value }))}
                      placeholder="공간 이름"
                    />
                    <textarea
                      value={spaceEditForm.description}
                      onChange={(event) => setSpaceEditForm((current) => ({ ...current, description: event.target.value }))}
                      placeholder="설명"
                    />
                    <div className="action-row">
                      <button disabled={!spaceEditForm.name || loading(`space-update-${space.id}`)}>
                        {loading(`space-update-${space.id}`) ? <Loader2 className="spin" size={15} /> : <CheckCircle2 size={15} />}
                        저장
                      </button>
                      <button className="ghost-button" type="button" onClick={() => setEditingSpaceId('')}>취소</button>
                    </div>
                  </form>
                ) : (
                  <>
                    <div className="document-main">
                      <strong>{space.name}</strong>
                      <small>{space.description || '설명 없음'}</small>
                    </div>
                    <div className="document-meta">
                      <small>{space.role} · {formatDate(space.createdAt)}</small>
                    </div>
                    <div className="document-actions">
                      <IconButton title="공간 이름/설명 편집" onClick={() => beginEditSpace(space)}>
                        <Info size={15} />
                      </IconButton>
                      <IconButton
                        danger
                        title={space.id === defaultSpaceId ? '기본 공간은 삭제할 수 없습니다.' : '공간 삭제'}
                        disabled={space.id === defaultSpaceId || loading(`space-delete-${space.id}`)}
                        onClick={() => deleteSpace(space.id, space.name)}
                      >
                        {loading(`space-delete-${space.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                      </IconButton>
                    </div>
                  </>
                )}
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
                <small>{log.actorLoginId || log.actorEmail || 'system'} · {log.spaceName || 'global'}</small>
                <p>{log.message}</p>
              </article>
            ))}
          </div>
        </section>
      </div>

      {editingUser && (
        <AdminUserModal title="계정 편집" subtitle={editingUser.loginId || editingUser.email} icon={<Info size={18} />} onClose={closeUserModals}>
          <form className="admin-modal-form" onSubmit={submitUserEdit}>
            <div className="stack">
              <label htmlFor="edit-user-login-id">ID</label>
              <input
                id="edit-user-login-id"
                value={userEditForm.loginId}
                disabled={editingUser.id === currentUser?.id}
                onChange={(event) => setUserEditForm((current) => ({ ...current, loginId: event.target.value }))}
                autoComplete="off"
                spellCheck="false"
              />
              {editingUser.id === currentUser?.id && <small className="field-help">현재 로그인한 관리자 계정의 ID는 이 화면에서 변경할 수 없습니다.</small>}
            </div>
            <div className="stack">
              <label htmlFor="edit-user-name">표시 이름</label>
              <input
                id="edit-user-name"
                value={userEditForm.displayName}
                onChange={(event) => setUserEditForm((current) => ({ ...current, displayName: event.target.value }))}
              />
            </div>
            <div className="stack">
              <label htmlFor="edit-user-role">시스템 권한</label>
              <select
                id="edit-user-role"
                value={userEditForm.role}
                disabled={editingUser.id === currentUser?.id}
                onChange={(event) => setUserEditForm((current) => ({ ...current, role: event.target.value }))}
              >
                <option value="USER">USER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
              {editingUser.id === currentUser?.id && <small className="field-help">현재 로그인한 관리자 계정의 시스템 권한은 이 화면에서 변경할 수 없습니다.</small>}
            </div>
            <div className="action-row">
              <button disabled={!userEditForm.loginId || !userEditForm.displayName || loading(`user-update-${editingUser.id}`)}>
                {loading(`user-update-${editingUser.id}`) ? <Loader2 className="spin" size={16} /> : <CheckCircle2 size={16} />}
                저장
              </button>
              <button className="ghost-button" type="button" onClick={closeUserModals}>취소</button>
            </div>
          </form>
        </AdminUserModal>
      )}

      {passwordUser && (
        <AdminUserModal title="비밀번호 재설정" subtitle={passwordUser.loginId || passwordUser.email} icon={<LockKeyhole size={18} />} onClose={closeUserModals}>
          <form className="admin-modal-form" onSubmit={submitPasswordReset}>
            {modalError && <div className="failure-line"><AlertTriangle size={14} />{modalError}</div>}
            <div className="stack">
              <label htmlFor="reset-password">새 임시 비밀번호</label>
              <input
                id="reset-password"
                type="password"
                value={passwordForm.newPassword}
                onChange={(event) => setPasswordForm((current) => ({ ...current, newPassword: event.target.value }))}
              />
            </div>
            <div className="stack">
              <label htmlFor="reset-password-confirm">새 임시 비밀번호 확인</label>
              <input
                id="reset-password-confirm"
                type="password"
                value={passwordForm.confirmPassword}
                onChange={(event) => setPasswordForm((current) => ({ ...current, confirmPassword: event.target.value }))}
              />
              <small className="field-help">저장하면 해당 사용자의 기존 로그인 세션이 만료됩니다.</small>
            </div>
            <div className="action-row">
              <button disabled={!passwordForm.newPassword || !passwordForm.confirmPassword || loading(`user-password-${passwordUser.id}`)}>
                {loading(`user-password-${passwordUser.id}`) ? <Loader2 className="spin" size={16} /> : <LockKeyhole size={16} />}
                재설정
              </button>
              <button className="ghost-button" type="button" onClick={closeUserModals}>취소</button>
            </div>
          </form>
        </AdminUserModal>
      )}

      {permissionsUser && (
        <AdminUserModal title="공간 권한 관리" subtitle={permissionsUser.loginId || permissionsUser.email} icon={<ShieldCheck size={18} />} onClose={closeUserModals}>
          <form className="admin-modal-form" onSubmit={submitPermissionEdit}>
            {permissionsUser.role === 'ADMIN' && (
              <div className="detail-box compact-box">
                <strong>시스템 관리자</strong>
                <small>ADMIN 계정은 모든 공간에 접근할 수 있습니다. 아래 멤버십은 공간별 표시 권한으로 관리됩니다.</small>
              </div>
            )}
            <div className="permission-grid">
              {spaces.map((space) => (
                <label className="permission-row" key={space.id}>
                  <span>
                    <strong>{space.name}</strong>
                    <small>{space.description || '설명 없음'}</small>
                  </span>
                  <select
                    value={permissionDraft[space.id] || ''}
                    onChange={(event) => setPermissionDraft((current) => ({ ...current, [space.id]: event.target.value }))}
                  >
                    <option value="">없음</option>
                    <option value="MEMBER">MEMBER</option>
                    <option value="OWNER">OWNER</option>
                  </select>
                </label>
              ))}
            </div>
            <div className="action-row">
              <button disabled={loading(`user-spaces-${permissionsUser.id}`)}>
                {loading(`user-spaces-${permissionsUser.id}`) ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
                저장
              </button>
              <button className="ghost-button" type="button" onClick={closeUserModals}>취소</button>
            </div>
          </form>
        </AdminUserModal>
      )}
    </section>
  );
}

function AdminUserModal({ title, subtitle, icon, children, onClose }) {
  return (
    <div className="code-modal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal document-preview-modal admin-user-modal" role="dialog" aria-modal="true" aria-labelledby="admin-user-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            {icon}
            <div>
              <h2 id="admin-user-modal-title">{title}</h2>
              <p>{subtitle}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="닫기" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>
        <div className="admin-user-modal-body">
          {children}
        </div>
      </section>
    </div>
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
      {jobChangeText(job) && <small className="job-change-line">{jobChangeText(job)}</small>}
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

function formatSelectedFiles(files = []) {
  if (!files.length) return '파일 선택';
  if (files.length === 1) return files[0].name;
  return `${files.length}개 파일 선택됨`;
}

function FileBatchResult({ result }) {
  if (!result) return null;
  return (
    <div className={result.failed ? 'batch-result batch-result-warning' : 'batch-result'}>
      <strong>{result.succeeded}/{result.total}개 파일 인덱싱 완료</strong>
      <div className="batch-result-list">
        {(result.items || []).map((item) => (
          <span className={item.success ? 'success-note' : 'danger-note'} key={item.filename}>
            {item.success ? '성공' : '실패'} · {item.filename}{item.errorMessage ? ` · ${item.errorMessage}` : ''}
          </span>
        ))}
      </div>
    </div>
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

function MarkdownAnswer({ text = '' }) {
  const blocks = parseMarkdownBlocks(text);
  return (
    <div className="markdown-answer">
      {blocks.map((block, index) => renderMarkdownBlock(block, index))}
    </div>
  );
}

function parseMarkdownBlocks(text = '') {
  const lines = String(text || '').replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  for (let index = 0; index < lines.length; index++) {
    const line = lines[index];
    if (!line.trim()) {
      continue;
    }
    if (line.trim().startsWith('```')) {
      const language = line.trim().slice(3).trim();
      const codeLines = [];
      index++;
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        codeLines.push(lines[index]);
        index++;
      }
      blocks.push({ type: 'code', language, text: codeLines.join('\n') });
      continue;
    }
    const heading = line.match(/^(#{1,4})\s+(.+)$/);
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, text: heading[2].trim() });
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items = [];
      while (index < lines.length && /^\s*[-*]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*[-*]\s+/, '').trim());
        index++;
      }
      index--;
      blocks.push({ type: 'ul', items });
      continue;
    }
    if (/^\s*\d+\.\s+/.test(line)) {
      const items = [];
      while (index < lines.length && /^\s*\d+\.\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*\d+\.\s+/, '').trim());
        index++;
      }
      index--;
      blocks.push({ type: 'ol', items });
      continue;
    }

    const paragraph = [line.trim()];
    while (index + 1 < lines.length
      && lines[index + 1].trim()
      && !lines[index + 1].trim().startsWith('```')
      && !/^(#{1,4})\s+/.test(lines[index + 1])
      && !/^\s*[-*]\s+/.test(lines[index + 1])
      && !/^\s*\d+\.\s+/.test(lines[index + 1])) {
      paragraph.push(lines[index + 1].trim());
      index++;
    }
    blocks.push({ type: 'p', text: paragraph.join(' ') });
  }
  return blocks.length ? blocks : [{ type: 'p', text: '' }];
}

function renderMarkdownBlock(block, index) {
  if (block.type === 'code') {
    return (
      <pre className="markdown-code" key={index}>
        {block.language && <span className="markdown-code-lang">{block.language}</span>}
        <code>{block.text}</code>
      </pre>
    );
  }
  if (block.type === 'heading') {
    const HeadingTag = block.level <= 2 ? 'h3' : 'h4';
    return <HeadingTag key={index}>{renderInlineMarkdown(block.text, `h-${index}`)}</HeadingTag>;
  }
  if (block.type === 'ul') {
    return (
      <ul key={index}>
        {block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInlineMarkdown(item, `u-${index}-${itemIndex}`)}</li>)}
      </ul>
    );
  }
  if (block.type === 'ol') {
    return (
      <ol key={index}>
        {block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInlineMarkdown(item, `o-${index}-${itemIndex}`)}</li>)}
      </ol>
    );
  }
  return <p key={index}>{renderInlineMarkdown(block.text, `p-${index}`)}</p>;
}

function renderInlineMarkdown(text = '', keyPrefix = 'md') {
  const parts = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*)/g;
  let lastIndex = 0;
  let match;
  let partIndex = 0;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(<Fragment key={`${keyPrefix}-t-${partIndex++}`}>{text.slice(lastIndex, match.index)}</Fragment>);
    }
    const token = match[0];
    if (token.startsWith('`')) {
      parts.push(<code key={`${keyPrefix}-c-${partIndex++}`}>{token.slice(1, -1)}</code>);
    } else {
      parts.push(<strong key={`${keyPrefix}-b-${partIndex++}`}>{token.slice(2, -2)}</strong>);
    }
    lastIndex = match.index + token.length;
  }
  if (lastIndex < text.length) {
    parts.push(<Fragment key={`${keyPrefix}-t-${partIndex++}`}>{text.slice(lastIndex)}</Fragment>);
  }
  return parts;
}

function EvidenceList({ evidence = [] }) {
  if (!evidence.length) return <p className="empty compact-empty">표시할 근거가 없습니다.</p>;
  return (
    <div className="evidence-section">
      <div className="evidence-header">
        <strong>근거 문서</strong>
        <small>{evidence.length}개</small>
      </div>
      <div className="evidence-list document-evidence-scroll" tabIndex={0}>
        {evidence.map((item) => (
          <article className="evidence-card" key={`${item.citationNumber}-${item.chunkId}`}>
            <strong>[{item.citationNumber}] {item.title}</strong>
            <small>{item.sourceUri} · chunk {item.chunkIndex}</small>
            <p>{item.preview}</p>
          </article>
        ))}
      </div>
    </div>
  );
}

function CodeEvidenceList({ evidence = [], onOpenEvidence }) {
  if (!evidence.length) return <p className="empty compact-empty">표시할 코드 근거가 없습니다.</p>;
  return (
    <div className="evidence-list">
      {evidence.map((item) => {
        const isCommitDiff = item.metadata?.kind === 'commit_diff';
        const canOpen = Boolean(item.repositoryId && item.fileId);
        const range = item.lineStart > 0
          ? { start: item.lineStart, end: item.lineEnd || item.lineStart }
          : null;
        const metaText = isCommitDiff
          ? `${item.metadata?.changeType || item.chunkType} · +${item.metadata?.insertions ?? 0}/-${item.metadata?.deletions ?? 0}`
          : `${item.lineStart}-${item.lineEnd} · ${item.chunkType}`;
        return (
          <article className="evidence-card code-evidence" key={`${item.citationNumber}-${item.chunkId || item.filePath || 'commit'}`}>
            <div className="result-heading">
              <strong>[{item.citationNumber}] {item.filePath}</strong>
              {canOpen && (
                <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, range)}>
                  <Eye size={14} />
                  열기
                </button>
              )}
            </div>
            <small>{metaText}</small>
            <p>{item.preview}</p>
          </article>
        );
      })}
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

function highlightLanguage(filePath, language) {
  const extension = String(filePath || '').toLowerCase().split('.').pop();
  const normalized = String(language || '').toLowerCase();
  const aliases = {
    cs: 'csharp',
    csharp: 'csharp',
    java: 'java',
    js: 'javascript',
    jsx: 'javascript',
    javascript: 'javascript',
    ts: 'typescript',
    tsx: 'typescript',
    typescript: 'typescript',
    xml: 'xml',
    xaml: 'xml',
    html: 'xml',
    css: 'css',
    sql: 'sql',
    json: 'json',
    yaml: 'yaml',
    yml: 'yaml',
    md: 'markdown',
    markdown: 'markdown',
    sh: 'bash',
    bash: 'bash',
    shell: 'bash',
    ps1: 'powershell',
    powershell: 'powershell',
  };
  return aliases[normalized] || aliases[extension] || 'plaintext';
}

function highlightedLineHtml(line, language) {
  const value = line || ' ';
  try {
    if (hljs.getLanguage(language)) {
      return hljs.highlight(value, { language, ignoreIllegals: true }).value;
    }
  } catch {
    // Fall back to escaped plain text below.
  }
  return escapeHtml(value);
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function DocumentPreviewModal({ preview, blobUrl, loading, onClose }) {
  const fileName = preview?.filename || preview?.title || 'document';
  const typeLabel = getPreviewTypeLabel(preview?.previewType);

  useEffect(() => {
    function handleKeyDown(event) {
      if (event.key === 'Escape') onClose?.();
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return (
    <div className="code-modal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal document-preview-modal" role="dialog" aria-modal="true" aria-labelledby="document-preview-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            <FileCode2 size={18} />
            <div>
              <h2 id="document-preview-title">{fileName}</h2>
              <p>{preview?.sourceUri || '문서 원문을 불러오는 중입니다.'}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="닫기" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>

        <div className="code-modal-tabs" aria-hidden="true">
          <span className="active-tab">{typeLabel}</span>
          {preview?.contentType && <span>{preview.contentType}</span>}
          {preview?.truncated && <span>preview truncated</span>}
        </div>

        <div className="code-modal-body document-preview-body">
          {loading && (
            <div className="code-modal-state">
              <Loader2 className="spin" size={22} />
              <strong>문서 원문을 불러오는 중입니다.</strong>
            </div>
          )}

          {!loading && !preview && (
            <div className="code-modal-state">
              <FileCode2 size={22} />
              <strong>표시할 문서 원문이 없습니다.</strong>
            </div>
          )}

          {!loading && preview && <DocumentPreviewContent preview={preview} blobUrl={blobUrl} />}
        </div>

        <footer className="code-modal-status">
          <span>{typeLabel}</span>
          {preview?.sizeBytes != null && <span>{formatFileSize(preview.sizeBytes)}</span>}
          {preview?.originalAvailable && <span>original stored</span>}
          {preview?.truncated && <span>일부만 표시</span>}
        </footer>
      </section>
    </div>
  );
}

function DocumentPreviewContent({ preview, blobUrl }) {
  if (preview.previewType === 'pdf') {
    if (!blobUrl) {
      return (
        <div className="code-modal-state">
          <Loader2 className="spin" size={22} />
          <strong>PDF 원본을 준비하는 중입니다.</strong>
        </div>
      );
    }
    return <iframe className="pdf-preview-frame" title={preview.title || 'PDF preview'} src={blobUrl} />;
  }

  if (preview.previewType === 'docx') {
    return (
      <div className="document-reader">
        {(preview.paragraphs || []).map((paragraph, index) => <p key={`p-${index}`}>{paragraph}</p>)}
        <PreviewTables tables={preview.tables || []} />
      </div>
    );
  }

  if (preview.previewType === 'excel') {
    return (
      <div className="document-table-workbook">
        {(preview.sheets || []).map((sheet, index) => (
          <section className="preview-sheet" key={`${sheet.name}-${index}`}>
            <h3>{sheet.name || `Sheet ${index + 1}`}</h3>
            <PreviewTable rows={sheet.rows || []} />
          </section>
        ))}
      </div>
    );
  }

  if (preview.previewType === 'csv') {
    return <PreviewTables tables={preview.tables || []} />;
  }

  if (preview.previewType === 'markdown') {
    return (
      <div className="document-reader markdown-preview">
        <MarkdownAnswer text={preview.text || ''} />
      </div>
    );
  }

  if (preview.previewType === 'web') {
    return <ReaderText text={preview.text} />;
  }

  return <pre className="document-text-viewer">{preview.text || ''}</pre>;
}

function ReaderText({ text = '' }) {
  const paragraphs = splitReaderParagraphs(text);
  if (!paragraphs.length) {
    return <div className="code-modal-state"><strong>표시할 본문이 없습니다.</strong></div>;
  }
  return (
    <div className="document-reader">
      {paragraphs.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
    </div>
  );
}

function PreviewTables({ tables = [] }) {
  if (!tables.length) {
    return <div className="code-modal-state"><strong>표시할 표가 없습니다.</strong></div>;
  }
  return (
    <div className="document-table-stack">
      {tables.map((table, index) => (
        <section className="preview-sheet" key={`${table.name}-${index}`}>
          {table.name && <h3>{table.name}</h3>}
          <PreviewTable rows={table.rows || []} />
        </section>
      ))}
    </div>
  );
}

function PreviewTable({ rows = [] }) {
  if (!rows.length) {
    return <p className="empty compact-empty">표 데이터가 없습니다.</p>;
  }
  return (
    <div className="preview-table-wrap">
      <table className="preview-table">
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {(row || []).map((cell, cellIndex) => (
                <td key={cellIndex}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function splitReaderParagraphs(text = '') {
  const normalized = String(text || '').replace(/\r\n/g, '\n').trim();
  if (!normalized) return [];
  const paragraphs = normalized.split(/\n{2,}/).map((item) => item.replace(/\s+/g, ' ').trim()).filter(Boolean);
  if (paragraphs.length > 1) return paragraphs;
  return normalized.split('\n').map((item) => item.trim()).filter(Boolean);
}

function getPreviewTypeLabel(type) {
  const labels = {
    pdf: 'PDF',
    docx: 'DOCX',
    excel: 'Excel',
    csv: 'CSV',
    markdown: 'Markdown',
    web: 'URL 본문',
    text: 'Text',
  };
  return labels[type] || 'Document';
}

function formatFileSize(value) {
  const bytes = Number(value || 0);
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function CodeFileModal({ detail, highlightRange, loading, onClose }) {
  const highlightedLineRef = useRef(null);
  const lines = detail?.content ? detail.content.split(/\r?\n/) : [];
  const fileName = detail?.filePath?.split(/[\\/]/).pop() || 'code';
  const language = detail?.language || 'code';
  const syntaxLanguage = highlightLanguage(detail?.filePath, language);
  const renderedLines = useMemo(
    () => lines.map((line) => highlightedLineHtml(line, syntaxLanguage)),
    [detail?.content, syntaxLanguage]
  );
  const chunkCount = detail?.chunks?.length || 0;

  useEffect(() => {
    function handleKeyDown(event) {
      if (event.key === 'Escape') onClose?.();
    }
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  useEffect(() => {
    if (loading || !highlightRange?.start || !highlightedLineRef.current) return undefined;
    const timer = window.setTimeout(() => {
      highlightedLineRef.current?.scrollIntoView({ block: 'center' });
    }, 80);
    return () => window.clearTimeout(timer);
  }, [detail?.id, highlightRange?.start, highlightRange?.end, loading]);

  return (
    <div className="code-modal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal" role="dialog" aria-modal="true" aria-labelledby="code-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            <FileCode2 size={18} />
            <div>
              <h2 id="code-modal-title">{fileName}</h2>
              <p>{detail?.filePath || '코드 파일을 불러오는 중입니다.'}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="닫기" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>

        <div className="code-modal-tabs" aria-hidden="true">
          <span className="active-tab">{fileName}</span>
          <span>{language}</span>
          {highlightRange && <span>lines {highlightRange.start}-{highlightRange.end}</span>}
        </div>

        <div className="code-modal-body">
          {loading && (
            <div className="code-modal-state">
              <Loader2 className="spin" size={22} />
              <strong>코드 파일을 불러오는 중입니다.</strong>
            </div>
          )}

          {!loading && !detail && (
            <div className="code-modal-state">
              <FileCode2 size={22} />
              <strong>표시할 코드가 없습니다.</strong>
            </div>
          )}

          {!loading && detail && (
            <pre className="ide-code-viewer">
              <code>
                {lines.map((line, index) => {
                  const lineNumber = index + 1;
                  const highlighted = highlightRange && lineNumber >= highlightRange.start && lineNumber <= highlightRange.end;
                  return (
                    <div
                      className={highlighted ? 'ide-code-line highlighted-line' : 'ide-code-line'}
                      key={lineNumber}
                      ref={lineNumber === highlightRange?.start ? highlightedLineRef : null}
                    >
                      <span className="ide-line-number">{lineNumber}</span>
                      <span className="ide-line-content" dangerouslySetInnerHTML={{ __html: renderedLines[index] || '&nbsp;' }} />
                    </div>
                  );
                })}
              </code>
            </pre>
          )}
        </div>

        <footer className="code-modal-status">
          <span>{language}</span>
          <span>{lines.length} lines</span>
          <span>{chunkCount} chunks</span>
        </footer>
      </section>
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

function getAnswerModeGuide(mode) {
  const guides = {
    qa: {
      title: '질문 답변 예시',
      description: '인덱싱된 문서 전체에서 관련 근거를 찾고, 질문에 맞는 자연어 답변을 받을 때 사용합니다.',
      placeholder: '예: 기간제 단시간 파견 근로자 차별예방을 위해 뭐가 개선되는거야?',
      tips: [
        '규정명, 파일명, 주제어처럼 알고 있는 단서를 같이 적으면 관련 문서를 더 잘 찾습니다.',
        '“왜?”, “어떻게?”, “무엇이 달라져?”처럼 설명형 질문에 적합합니다.',
        '답변 아래의 신뢰도, 진단, 근거 문서를 함께 확인하세요.',
      ],
    },
    summary: {
      title: '요약 질문 예시',
      description: '문서나 주제를 핵심 요점 중심으로 짧게 정리받을 때 사용합니다.',
      placeholder: '예: 기간제·단시간·파견 근로자 차별 예방 가이드라인을 핵심만 요약해줘.',
      tips: [
        '특정 문서를 요약하려면 문서명이나 파일명을 질문에 포함하세요.',
        '“핵심만”, “실무자가 볼 내용”, “결정해야 할 사항”처럼 요약 관점을 적으면 좋습니다.',
        '여러 문서가 있으면 현재 공간의 문서 전체에서 관련 근거를 찾아 요약합니다.',
      ],
    },
    table: {
      title: '표 추출 질문 예시',
      description: '표, 엑셀, 항목 목록, 비교 내용, 인원수나 건수 같은 집계 정보를 구조화할 때 사용합니다.',
      placeholder: '예: 육아기단축근로 대상자는 총 몇 명이고 부서별로 몇 명인지 표로 정리해줘.',
      tips: [
        '“총 몇 명”, “부서별”, “항목별”, “표로 정리”처럼 원하는 구조를 직접 적으세요.',
        '엑셀이나 CSV 집계 질문은 가능한 경우 서버가 전체 청크를 기준으로 계산합니다.',
        'PDF 표는 추출 품질에 따라 행과 열이 깨질 수 있으니 근거 내용을 같이 확인하세요.',
      ],
    },
    quote: {
      title: '원문 인용 질문 예시',
      description: '규정, 지침, 계약 문구처럼 실제 문서 표현을 짧게 확인하고 싶을 때 사용합니다.',
      placeholder: '예: 차별적 처우 금지와 관련된 원문 근거 문구를 인용해서 보여줘.',
      tips: [
        '법령, 규정, 가이드라인 문구를 확인할 때 가장 적합합니다.',
        '“그대로 인용”, “원문 문구”, “근거 조항”처럼 요청하면 인용 중심으로 답합니다.',
        '긴 문서 전체 복사보다 관련 짧은 발췌와 출처 확인에 맞춰져 있습니다.',
      ],
    },
  };
  return guides[mode] || guides.qa;
}

function getCodeModeLabel(mode) {
  if (mode === 'commit') return '커밋 분석';
  return codeModes.find((item) => item.value === mode)?.label || '위치 찾기';
}

function getCodeModeGuide(mode) {
  const guides = {
    overview: {
      title: '통합 질문 예시',
      description: '검색, 위치 찾기, 정의/참조, 주변 코드 근거를 함께 사용해 기능의 위치와 동작을 자연어로 설명받을 때 사용합니다.',
      placeholder: '예: 로그인 관련 파일이나 메서드가 어디 있고, 로그인 요청부터 세션 저장까지 어떻게 동작해?',
      tips: [
        '기능명, 화면명, 버튼명, 에러 문구처럼 사용자가 아는 단서를 자연어로 적어도 됩니다.',
        '어디 있는지와 어떻게 동작하는지를 한 번에 물어보면 관련 파일과 처리 흐름을 함께 정리합니다.',
        '답변의 신뢰도와 근거 부족 여부를 함께 확인하세요.',
      ],
    },
    locate: {
      title: '위치 찾기 질문 예시',
      description: '기능이나 화면이 어느 파일, 클래스, 메서드에 구현돼 있는지 찾을 때 사용합니다.',
      placeholder: '예: GitHub 저장소 인덱싱 실패 사유를 저장하는 로직은 어느 파일과 메서드에 있어?',
      tips: [
        '기능명, 화면명, 버튼명, 에러 메시지 중 아는 단어를 함께 적으세요.',
        '“어디에 있어?”, “어느 파일에서 처리해?”처럼 위치를 직접 물어보면 좋습니다.',
        '파일명을 일부 알고 있으면 같이 적으면 더 정확합니다.',
      ],
    },
    method: {
      title: '메서드 설명 질문 예시',
      description: '특정 메서드가 입력을 받아 어떤 검증, 저장, 호출, 반환을 하는지 설명받을 때 사용합니다.',
      placeholder: '예: CodeIndexingService.startIndex 메서드는 어떤 순서로 인덱싱 작업을 시작해?',
      tips: [
        '클래스명과 메서드명을 같이 적으세요.',
        '입력값, 예외, 부수효과, DB 업데이트 중 궁금한 관점을 덧붙이세요.',
        '정확한 메서드명을 모르면 관련 기능명과 “메서드 설명”이라고 적어도 됩니다.',
      ],
    },
    flow: {
      title: '호출 흐름 질문 예시',
      description: '컨트롤러에서 서비스, 저장소, 외부 API까지 이어지는 실행 순서를 보고 싶을 때 사용합니다.',
      placeholder: '예: 저장소 등록 버튼을 누른 뒤 Git clone, 청크 생성, 임베딩 저장까지 호출 흐름을 설명해줘.',
      tips: [
        '시작 이벤트와 끝 상태를 같이 적으세요.',
        '“A부터 B까지”처럼 범위를 지정하면 흐름이 덜 흩어집니다.',
        'API 경로를 알고 있으면 함께 적으세요.',
      ],
    },
    ui_event: {
      title: 'UI 이벤트 질문 예시',
      description: '화면의 버튼, 입력, 탭 변경이 어떤 핸들러와 API 호출로 이어지는지 추적할 때 사용합니다.',
      placeholder: '예: 코드 RAG 화면에서 실패 사유 버튼을 누르면 어떤 함수와 API가 호출돼?',
      tips: [
        '버튼명, 탭명, 화면명을 그대로 적으세요.',
        '프론트 이벤트와 백엔드 API 연결을 같이 물어보면 좋습니다.',
        'WPF/WinForms/XAML 코드도 컨트롤명이나 이벤트명을 같이 적으면 찾기 쉽습니다.',
      ],
    },
    impact: {
      title: '영향 범위 질문 예시',
      description: '설정, DTO, API, DB 컬럼 변경이 어느 코드와 화면에 영향을 주는지 분석할 때 사용합니다.',
      placeholder: '예: 임베딩 모델명을 바꾸면 영향을 받는 설정, DB 차원, 재인덱싱 코드는 어디야?',
      tips: [
        '바꾸려는 항목과 예상 변경 방향을 같이 적으세요.',
        '확정 근거와 추정 영역을 나눠달라고 요청하면 검토에 유리합니다.',
        '마이그레이션, API 계약, 프론트 요청 필드를 함께 확인시키면 좋습니다.',
      ],
    },
  };
  return guides[mode] || guides.locate;
}

function confidenceClass(value) {
  if (value === '높음') return 'high';
  if (value === '보통') return 'medium';
  return 'low';
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

function jobChangeText(job) {
  const parts = [
    ['추가', job?.addedFiles],
    ['수정', job?.modifiedFiles],
    ['유지', job?.unchangedFiles],
    ['삭제', job?.deletedFiles],
  ]
    .filter(([, value]) => Number(value || 0) > 0)
    .map(([label, value]) => `${label} ${value}`);
  return parts.length ? parts.join(' · ') : '';
}

function getProgressMessage(busy) {
  if (!busy) return '';
  if (busy === 'login') return '로그인 중입니다.';
  if (busy === 'web') return '웹 페이지를 수집하고 임베딩하는 중입니다.';
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
  if (busy.startsWith('user-delete-')) return '사용자 계정을 삭제하는 중입니다.';
  if (busy.startsWith('user-update-')) return '사용자 계정을 저장하는 중입니다.';
  if (busy.startsWith('user-password-')) return '사용자 비밀번호를 재설정하는 중입니다.';
  if (busy.startsWith('user-spaces-')) return '사용자 공간 권한을 저장하는 중입니다.';
  if (busy.startsWith('space-update-')) return '공간 정보를 저장하는 중입니다.';
  if (busy.startsWith('space-delete-')) return '공간을 삭제하는 중입니다.';
  if (busy === 'space-create') return '공간을 생성하는 중입니다.';
  if (busy === 'user-invite') return '사용자를 초대하는 중입니다.';
  return '요청을 처리하는 중입니다.';
}

createRoot(document.getElementById('root')).render(<App />);

import { useEffect, useRef, useState } from 'react';
import { buildSavedConversationPayload } from '../../lib/ragConversationSave.js';

export function useCodeRagController({
  activeSpaceId,
  request,
  streamRequest,
  run,
  savedSummary,
  setSavedAnswers,
  setSelectedSavedAnswer,
}) {
  const [repositories, setRepositories] = useState([]);
  const [jobs, setJobs] = useState({});
  const [jobFailures, setJobFailures] = useState({});
  const [jobDiagnostics, setJobDiagnostics] = useState({});
  const [codeFiles, setCodeFiles] = useState([]);
  const [fileQuery, setFileQuery] = useState('');
  const [selectedCodeFile, setSelectedCodeFile] = useState(null);
  const [highlightRange, setHighlightRange] = useState(null);
  const [codeModalOpen, setCodeModalOpen] = useState(false);
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
  const [zipForm, setZipForm] = useState({ file: null, name: '' });
  const [zipReplaceFile, setZipReplaceFile] = useState(null);
  const [indexCredential, setIndexCredential] = useState({ username: '', token: '', storeToken: true });
  const [selectedRepositoryId, setSelectedRepositoryId] = useState('');
  const [codeQuestion, setCodeQuestion] = useState('');
  const [codeMode, setCodeMode] = useState('overview');
  const [codeAnswer, setCodeAnswer] = useState(null);
  const [codeAnswerSavedId, setCodeAnswerSavedId] = useState('');
  const [codeConversations, setCodeConversations] = useState([]);
  const [codeConversationId, setCodeConversationId] = useState('');
  const [codeConversationTurns, setCodeConversationTurns] = useState([]);
  const [pendingCodeTurn, setPendingCodeTurn] = useState(null);
  const [codeSearchQuery, setCodeSearchQuery] = useState('');
  const [codeSearchResults, setCodeSearchResults] = useState([]);
  const [referenceSymbol, setReferenceSymbol] = useState('');
  const [referenceResult, setReferenceResult] = useState(null);
  const askAbortRef = useRef(null);

  const selectedRepository = repositories.find((repo) => repo.id === selectedRepositoryId);

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

  function resetState() {
    setRepositories([]);
    setJobs({});
    setJobFailures({});
    setCodeFiles([]);
    setSelectedCodeFile(null);
    setHighlightRange(null);
    setCodeModalOpen(false);
    setCodeAnswerSavedId('');
    setCodeConversations([]);
    setCodeConversationId('');
    setCodeConversationTurns([]);
    setPendingCodeTurn(null);
  }

  function spacePath(path) {
    if (!activeSpaceId) return path;
    const separator = path.includes('?') ? '&' : '?';
    return `${path}${separator}spaceId=${encodeURIComponent(activeSpaceId)}`;
  }

  async function refreshRepositories() {
    const data = await request(spacePath('/api/code/repositories'));
    setRepositories(data || []);
    setSelectedRepositoryId((current) => {
      if (current && data?.some((repo) => repo.id === current)) return current;
      return data?.[0]?.id || '';
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
        throw new Error('입력한 계정으로 저장소를 인덱싱하려면 Git 자격 증명을 입력하세요.');
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
    params.set('limit', '200');
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
    if (!window.confirm(`'${name}' 저장소를 삭제하시겠습니까?`)) return;
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
      const submittedQuestion = codeQuestion.trim();
      if (!submittedQuestion) return;
      const parentTurnId = codeConversationTurns.at(-1)?.id || null;
      const followup = Boolean(codeConversationId);
      const effectiveMode = followup ? '' : codeMode;
      const effectiveRepositoryId = followup
        ? codeAnswer?.repositoryId || selectedRepositoryId || null
        : selectedRepositoryId || null;
      const payload = {
        repositoryId: effectiveRepositoryId,
        spaceId: activeSpaceId,
        question: submittedQuestion,
        mode: effectiveMode,
        limit: followup ? null : codeMode === 'overview' ? 16 : 10,
        conversationId: codeConversationId || null,
        parentTurnId,
        conversational: true,
      };
      let data = null;
      let sawStream = false;
      let streamedText = '';
      const controller = new AbortController();
      askAbortRef.current = controller;
      const initialAnswer = {
        mode: effectiveMode || codeMode,
        question: submittedQuestion,
        answer: '',
        evidence: [],
        confidence: '',
        diagnostics: ['답변을 생성하는 중입니다.'],
        repositoryId: effectiveRepositoryId,
        streaming: true,
        status: 'streaming',
      };
      setCodeAnswer(initialAnswer);
      setPendingCodeTurn({
        ...initialAnswer,
        clientId: `pending-code-${Date.now()}`,
        parentTurnId,
      });
      setCodeQuestion('');
      try {
        await streamRequest('/api/code/ask/stream', {
          method: 'POST',
          json: payload,
          signal: controller.signal,
          onEvent: ({ event: eventName, data: eventData }) => {
            if (eventName === 'delta') {
              sawStream = true;
              const text = eventData?.text || '';
              streamedText += text;
              const update = (current) => ({ ...(current || {}), answer: `${current?.answer || ''}${text}`, repositoryId: effectiveRepositoryId, streaming: true, status: 'streaming' });
              setCodeAnswer(update);
              setPendingCodeTurn(update);
            } else if (eventName === 'evidence') {
              const update = (current) => ({ ...(current || {}), evidence: eventData?.evidence || [], repositoryId: effectiveRepositoryId });
              setCodeAnswer(update);
              setPendingCodeTurn(update);
            } else if (eventName === 'replace') {
              sawStream = true;
              if (streamedText) return;
              const replacement = eventData?.answer || '';
              streamedText = replacement;
              const update = (current) => ({ ...(current || {}), answer: replacement, repositoryId: effectiveRepositoryId, streaming: true, status: 'streaming' });
              setCodeAnswer(update);
              setPendingCodeTurn(update);
            } else if (eventName === 'done') {
              data = eventData;
            } else if (eventName === 'error') {
              const error = new Error(eventData?.message || '코드 RAG 스트리밍에 실패했습니다.');
              error.code = eventData?.code || '';
              throw error;
            }
          },
        });
        if (!data) {
          if (!sawStream) {
            data = await request('/api/code/ask', { method: 'POST', json: payload });
          } else {
            throw new Error('코드 RAG 스트림이 최종 응답 없이 종료되었습니다.');
          }
        }
      } catch (err) {
        if (err.name === 'AbortError') {
          const update = (current) => ({ ...(current || {}), streaming: false, aborted: true, status: 'aborted', diagnostics: ['사용자가 답변 생성을 중단했습니다.'] });
          setCodeAnswer(update);
          setPendingCodeTurn(update);
          return;
        }
        if (!sawStream && err.code !== 'STREAM_LIMIT_EXCEEDED') {
          data = await request('/api/code/ask', { method: 'POST', json: payload });
        } else {
          const update = (current) => ({
            ...(current || {}),
            streaming: false,
            error: true,
            status: 'error',
            diagnostics: [err.message || '코드 RAG 스트리밍에 실패했습니다.'],
          });
          setCodeAnswer(update);
          setPendingCodeTurn(update);
          return;
        }
      } finally {
        if (askAbortRef.current === controller) {
          askAbortRef.current = null;
        }
      }
      if (data && streamedText.trim()) {
        data = { ...data, answer: streamedText.trim() };
      }
      const completed = data ? { ...data, question: submittedQuestion, repositoryId: effectiveRepositoryId, status: answerLifecycleStatus(data, sawStream) } : data;
      setCodeAnswer(completed);
      setCodeAnswerSavedId('');
      if (data?.conversationId) {
        setCodeConversationId(data.conversationId);
        setCodeConversationTurns((current) => [
          ...current,
          {
            id: data.turnId,
            conversationId: data.conversationId,
            parentTurnId,
            question: submittedQuestion,
            rewrittenQuestion: data.rewrittenQuestion,
            mode: data.mode,
            answer: data.answer,
            confidence: data.confidence,
            evidence: data.evidence || [],
            diagnostics: data.diagnostics || [],
            repositoryId: effectiveRepositoryId,
            status: completed.status,
          },
        ]);
        setPendingCodeTurn(null);
        await refreshCodeConversations();
      } else {
        setPendingCodeTurn((current) => current ? { ...current, ...completed, streaming: false } : null);
      }
    });
  }

  function cancelCodeAsk() {
    askAbortRef.current?.abort();
  }

  async function refreshCodeConversations() {
    if (!activeSpaceId) return;
    const data = await request(`/api/rag/conversations?domain=CODE&spaceId=${encodeURIComponent(activeSpaceId)}`);
    setCodeConversations(data || []);
  }

  async function loadCodeConversation(conversationId) {
    if (!conversationId) return;
    await run(`code-conversation-${conversationId}`, async () => {
      const detail = await request(`/api/rag/conversations/${conversationId}`);
      const turns = detail?.turns || [];
      const repositoryId = detail?.conversation?.repositoryId || '';
      if (repositoryId) {
        setSelectedRepositoryId(repositoryId);
      }
      setCodeConversationId(conversationId);
      setCodeConversationTurns(turns.map((turn) => ({ ...turn, repositoryId, status: answerLifecycleStatus(turn, true) })));
      setPendingCodeTurn(null);
      const lastTurn = turns.at(-1);
      if (lastTurn) {
        setCodeAnswer({
          mode: lastTurn.mode,
          question: lastTurn.question || '',
          answer: lastTurn.answer,
          evidence: lastTurn.evidence || [],
          confidence: lastTurn.confidence,
          diagnostics: lastTurn.diagnostics || [],
          conversationId,
          turnId: lastTurn.id,
          rewrittenQuestion: lastTurn.rewrittenQuestion,
          repositoryId,
          status: answerLifecycleStatus(lastTurn, true),
        });
        setCodeAnswerSavedId('');
      }
    });
  }

  function startNewCodeConversation() {
    setCodeConversationId('');
    setCodeConversationTurns([]);
    setPendingCodeTurn(null);
    setCodeAnswer(null);
    setCodeAnswerSavedId('');
    setCodeQuestion('');
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
      const turnsForSave = codeConversationTurns.length
        ? codeConversationTurns
        : [codeAnswer];
      const saved = await request('/api/saved-answers', {
        method: 'POST',
        json: buildSavedConversationPayload({
          spaceId: activeSpaceId,
          answerType: 'CODE',
          mode: codeAnswer.mode,
          conversationId: codeConversationId || codeAnswer.conversationId || null,
          turns: turnsForSave,
          fallbackAnswer: codeAnswer,
          fallbackQuestion: codeAnswer.question || codeQuestion,
          repositoryId: codeAnswer.repositoryId || selectedRepositoryId || null,
        }),
      });
      setCodeAnswerSavedId(saved.id);
      setSavedAnswers((current) => [savedSummary(saved), ...current.filter((item) => item.id !== saved.id)]);
      setSelectedSavedAnswer(saved);
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
      const params = new URLSearchParams();
      if (selectedRepositoryId) params.set('repositoryId', selectedRepositoryId);
      if (activeSpaceId) params.set('spaceId', activeSpaceId);
      params.set('symbol', referenceSymbol);
      const data = await request(`/api/code/references?${params.toString()}`);
      setReferenceResult(data);
    });
  }

  return {
    repositories,
    jobs,
    jobFailures,
    jobDiagnostics,
    codeFiles,
    fileQuery,
    setFileQuery,
    selectedCodeFile,
    highlightRange,
    codeModalOpen,
    setCodeModalOpen,
    repoForm,
    setRepoForm,
    zipForm,
    setZipForm,
    zipReplaceFile,
    setZipReplaceFile,
    indexCredential,
    setIndexCredential,
    selectedRepositoryId,
    setSelectedRepositoryId,
    selectedRepository,
    codeQuestion,
    setCodeQuestion,
    codeMode,
    setCodeMode,
    codeAnswer,
    codeConversations,
    codeConversationId,
    codeConversationTurns,
    pendingCodeTurn,
    refreshCodeConversations,
    loadCodeConversation,
    startNewCodeConversation,
    codeAnswerSavedId,
    setCodeAnswerSavedId,
    codeSearchQuery,
    setCodeSearchQuery,
    codeSearchResults,
    referenceSymbol,
    setReferenceSymbol,
    referenceResult,
    resetState,
    refreshRepositories,
    refreshJobs,
    refreshCodeFiles,
    searchCodeFiles,
    registerRepository,
    uploadZipRepository,
    indexRepository,
    replaceZipRepository,
    loadJobFailures,
    cancelIndex,
    deleteRepository,
    clearFailedJobs,
    openCodeFile,
    askCode,
    cancelCodeAsk,
    loadJobDiagnostics,
    saveCodeAnswer,
    searchCode,
    findReferences,
  };
}

function answerLifecycleStatus(answer = {}, streamed = false) {
  if (answer?.status) return answer.status;
  if (answer?.streaming) return 'streaming';
  if (answer?.aborted) return 'aborted';
  if (answer?.error) return 'error';
  const diagnostics = (answer?.diagnostics || []).join(' ').toLowerCase();
  if (diagnostics.includes('fallback') || (!streamed && answer?.answer)) return 'fallback';
  if (diagnostics.includes('repair') || diagnostics.includes('repaired') || diagnostics.includes('보정')) return 'repaired';
  return 'completed';
}

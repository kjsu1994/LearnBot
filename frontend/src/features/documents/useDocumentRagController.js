import { useEffect, useRef, useState } from 'react';
import { buildSavedConversationPayload } from '../../lib/ragConversationSave.js';

export function useDocumentRagController({
  activeSpaceId,
  request,
  streamRequest,
  requestBlob,
  run,
  savedSummary,
  setError,
  setSavedAnswers,
  setSelectedSavedAnswer,
}) {
  const [documents, setDocuments] = useState([]);
  const [documentJobs, setDocumentJobs] = useState([]);
  const [documentJobDiagnostics, setDocumentJobDiagnostics] = useState({});
  const [webUrl, setWebUrl] = useState('');
  const [webRecursive, setWebRecursive] = useState(true);
  const [webMaxDepth, setWebMaxDepth] = useState(2);
  const [webMaxPages, setWebMaxPages] = useState(30);
  const [webCrawlScope, setWebCrawlScope] = useState('START_PATH');
  const [webRobotsFailurePolicy, setWebRobotsFailurePolicy] = useState('FAIL_CLOSED');
  const [webIncludeAttachments, setWebIncludeAttachments] = useState(false);
  const [webUseSitemap, setWebUseSitemap] = useState(false);
  const [webRenderMode, setWebRenderMode] = useState('PLAYWRIGHT_FALLBACK');
  const [files, setFiles] = useState([]);
  const [fileBatchResult, setFileBatchResult] = useState(null);
  const [query, setQuery] = useState('');
  const [question, setQuestion] = useState('');
  const [answerMode, setAnswerMode] = useState('qa');
  const [documentSpeedProfile, setDocumentSpeedProfile] = useState('balanced');
  const [searchResults, setSearchResults] = useState([]);
  const [answer, setAnswer] = useState(null);
  const [answerSavedId, setAnswerSavedId] = useState('');
  const [documentConversations, setDocumentConversations] = useState([]);
  const [documentConversationId, setDocumentConversationId] = useState('');
  const [documentConversationTurns, setDocumentConversationTurns] = useState([]);
  const [pendingDocumentTurn, setPendingDocumentTurn] = useState(null);
  const [selectedDocumentId, setSelectedDocumentId] = useState('');
  const [documentDetail, setDocumentDetail] = useState(null);
  const [documentPreviewOpen, setDocumentPreviewOpen] = useState(false);
  const [documentPreview, setDocumentPreview] = useState(null);
  const [documentPreviewBlobUrl, setDocumentPreviewBlobUrl] = useState('');
  const [documentPreviewLoading, setDocumentPreviewLoading] = useState(false);
  const askAbortRef = useRef(null);

  useEffect(() => {
    const runningJobs = documentJobs.filter((job) => job.status === 'RUNNING');
    if (!runningJobs.length) return undefined;
    const timer = window.setInterval(() => {
      refreshDocumentJobs();
      refreshDocuments();
    }, 2500);
    return () => window.clearInterval(timer);
  }, [documentJobs]);

  useEffect(() => () => {
    if (documentPreviewBlobUrl) {
      URL.revokeObjectURL(documentPreviewBlobUrl);
    }
  }, [documentPreviewBlobUrl]);

  function spacePath(path) {
    if (!activeSpaceId) return path;
    const separator = path.includes('?') ? '&' : '?';
    return `${path}${separator}spaceId=${encodeURIComponent(activeSpaceId)}`;
  }

  function updateUploadFiles(nextFiles) {
    setFiles(nextFiles);
    setFileBatchResult(null);
  }

  function resetState() {
    setDocuments([]);
    setDocumentJobs([]);
    setDocumentJobDiagnostics({});
    setFiles([]);
    setFileBatchResult(null);
    setAnswerSavedId('');
    setDocumentConversations([]);
    setDocumentConversationId('');
    setDocumentConversationTurns([]);
    setPendingDocumentTurn(null);
    setSelectedDocumentId('');
    setDocumentDetail(null);
    setDocumentPreviewOpen(false);
    setDocumentPreview(null);
    setDocumentPreviewBlobUrl((current) => {
      if (current) URL.revokeObjectURL(current);
      return '';
    });
  }

  async function refreshDocuments() {
    const data = await request(spacePath('/api/documents'));
    setDocuments(data || []);
  }

  async function refreshDocumentJobs() {
    const data = await request(spacePath('/api/document-indexing/jobs'));
    setDocumentJobs(data || []);
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
      const submittedQuestion = question.trim();
      if (!submittedQuestion) return;
      const parentTurnId = documentConversationTurns.at(-1)?.id || null;
      const payload = {
        question: submittedQuestion,
        mode: answerMode,
        speedProfile: documentSpeedProfile,
        spaceId: activeSpaceId,
        conversationId: documentConversationId || null,
        parentTurnId,
        conversational: true,
      };
      let data = null;
      let sawStream = false;
      const controller = new AbortController();
      askAbortRef.current = controller;
      const initialAnswer = {
        mode: answerMode,
        question: submittedQuestion,
        answer: '',
        citations: [],
        evidence: [],
        confidence: '',
        diagnostics: ['답변을 생성하는 중입니다.'],
        streaming: true,
        status: 'streaming',
      };
      setAnswer(initialAnswer);
      setPendingDocumentTurn({
        ...initialAnswer,
        clientId: `pending-document-${Date.now()}`,
        parentTurnId,
      });
      setQuestion('');
      try {
        await streamRequest('/api/rag/ask/stream', {
          method: 'POST',
          json: payload,
          signal: controller.signal,
          onEvent: ({ event: eventName, data: eventData }) => {
            if (eventName === 'delta') {
              sawStream = true;
              const text = eventData?.text || '';
              const update = (current) => ({ ...(current || {}), answer: `${current?.answer || ''}${text}`, streaming: true, status: 'streaming' });
              setAnswer(update);
              setPendingDocumentTurn(update);
            } else if (eventName === 'evidence') {
              const update = (current) => ({ ...(current || {}), citations: eventData?.citations || [], evidence: eventData?.evidence || [] });
              setAnswer(update);
              setPendingDocumentTurn(update);
            } else if (eventName === 'replace') {
              sawStream = true;
              const update = (current) => ({ ...(current || {}), answer: eventData?.answer || '', streaming: true, status: 'streaming' });
              setAnswer(update);
              setPendingDocumentTurn(update);
            } else if (eventName === 'done') {
              data = eventData;
            } else if (eventName === 'error') {
              const error = new Error(eventData?.message || '문서 RAG 스트리밍에 실패했습니다.');
              error.code = eventData?.code || '';
              throw error;
            }
          },
        });
        if (!data) {
          if (!sawStream) {
            data = await request('/api/rag/ask', { method: 'POST', json: payload });
          } else {
            throw new Error('문서 RAG 스트림이 최종 응답 없이 종료되었습니다.');
          }
        }
      } catch (err) {
        if (err.name === 'AbortError') {
          const update = (current) => ({ ...(current || {}), streaming: false, aborted: true, status: 'aborted', diagnostics: ['사용자가 답변 생성을 중단했습니다.'] });
          setAnswer(update);
          setPendingDocumentTurn(update);
          return;
        }
        if (!sawStream && err.code !== 'STREAM_LIMIT_EXCEEDED') {
          data = await request('/api/rag/ask', { method: 'POST', json: payload });
        } else {
          const update = (current) => ({
            ...(current || {}),
            streaming: false,
            error: true,
            status: 'error',
            diagnostics: [err.message || '문서 RAG 스트리밍에 실패했습니다.'],
          });
          setAnswer(update);
          setPendingDocumentTurn(update);
          return;
        }
      } finally {
        if (askAbortRef.current === controller) {
          askAbortRef.current = null;
        }
      }
      const completed = { ...data, question: submittedQuestion, status: answerLifecycleStatus(data, sawStream) };
      setAnswer(completed);
      setAnswerSavedId('');
      if (data?.conversationId) {
        setDocumentConversationId(data.conversationId);
        setDocumentConversationTurns((current) => [
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
            citations: data.citations || [],
            evidence: data.evidence || [],
            diagnostics: data.diagnostics || [],
            status: completed.status,
          },
        ]);
        setPendingDocumentTurn(null);
        await refreshDocumentConversations();
      } else {
        setPendingDocumentTurn((current) => current ? { ...current, ...completed, streaming: false } : null);
      }
    });
  }

  function cancelAsk() {
    askAbortRef.current?.abort();
  }

  async function refreshDocumentConversations() {
    if (!activeSpaceId) return;
    const data = await request(`/api/rag/conversations?domain=DOCUMENT&spaceId=${encodeURIComponent(activeSpaceId)}`);
    setDocumentConversations(data || []);
  }

  async function loadDocumentConversation(conversationId) {
    if (!conversationId) return;
    await run(`document-conversation-${conversationId}`, async () => {
      const detail = await request(`/api/rag/conversations/${conversationId}`);
      const turns = detail?.turns || [];
      setDocumentConversationId(conversationId);
      setDocumentConversationTurns(turns.map((turn) => ({ ...turn, status: answerLifecycleStatus(turn, true) })));
      setPendingDocumentTurn(null);
      const lastTurn = turns.at(-1);
      if (lastTurn) {
        setAnswer({
          mode: lastTurn.mode,
          question: lastTurn.question || '',
          answer: lastTurn.answer,
          citations: lastTurn.citations || [],
          evidence: lastTurn.evidence || [],
          confidence: lastTurn.confidence,
          diagnostics: lastTurn.diagnostics || [],
          conversationId,
          turnId: lastTurn.id,
          rewrittenQuestion: lastTurn.rewrittenQuestion,
          status: answerLifecycleStatus(lastTurn, true),
        });
        setAnswerSavedId('');
      }
    });
  }

  function startNewDocumentConversation() {
    setDocumentConversationId('');
    setDocumentConversationTurns([]);
    setPendingDocumentTurn(null);
    setAnswer(null);
    setAnswerSavedId('');
    setQuestion('');
  }

  async function saveAnswer() {
    if (!answer) return;
    await run('save-answer', async () => {
      const turnsForSave = documentConversationTurns.length
        ? documentConversationTurns
        : [answer];
      const saved = await request('/api/saved-answers', {
        method: 'POST',
        json: buildSavedConversationPayload({
          spaceId: activeSpaceId,
          answerType: 'DOCUMENT',
          mode: answer.mode,
          conversationId: documentConversationId || answer.conversationId || null,
          turns: turnsForSave,
          fallbackAnswer: answer,
          fallbackQuestion: answer.question || question,
        }),
      });
      setAnswerSavedId(saved.id);
      setSavedAnswers((current) => [savedSummary(saved), ...current.filter((item) => item.id !== saved.id)]);
      setSelectedSavedAnswer(saved);
    });
  }

  async function loadDocumentJobDiagnostics(jobId) {
    await run(`document-job-diagnostics-${jobId}`, async () => {
      const data = await request(`/api/document-indexing/jobs/${jobId}/diagnostics`);
      setDocumentJobDiagnostics((current) => ({ ...current, [jobId]: data || [] }));
    });
  }

  async function retryDocumentJobStage(jobId, stage) {
    const endpoint = stage === 'DOCUMENT_GRAPH_REBUILD' ? 'retry-graph' : 'retry-enrichment';
    await run(`document-job-retry-${stage}-${jobId}`, async () => {
      await request(`/api/document-indexing/jobs/${jobId}/${endpoint}`, { method: 'POST' });
      await Promise.all([refreshDocuments(), refreshDocumentJobs(), loadDocumentJobDiagnostics(jobId)]);
    });
  }

  async function deleteDocument(documentId, title) {
    if (!window.confirm(`'${title}' 문서를 삭제할까요? 삭제한 문서는 대화에서 복구할 수 없습니다.`)) return;
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
        setPendingDocumentTurn(null);
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
      const detail = await request(`/api/documents/${documentId}`);
      setDocumentDetail(detail);
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

  return {
    documents,
    documentJobs,
    documentJobDiagnostics,
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
    query,
    setQuery,
    question,
    setQuestion,
    answerMode,
    setAnswerMode,
    documentSpeedProfile,
    setDocumentSpeedProfile,
    searchResults,
    answer,
    documentConversations,
    documentConversationId,
    documentConversationTurns,
    pendingDocumentTurn,
    refreshDocumentConversations,
    loadDocumentConversation,
    startNewDocumentConversation,
    setAnswerSavedId,
    answerSavedId,
    selectedDocumentId,
    documentDetail,
    documentPreviewOpen,
    documentPreview,
    documentPreviewBlobUrl,
    documentPreviewLoading,
    resetState,
    refreshDocuments,
    refreshDocumentJobs,
    ingestWeb,
    ingestFile,
    search,
    ask,
    cancelAsk,
    saveAnswer,
    loadDocumentJobDiagnostics,
    retryDocumentJobStage,
    deleteDocument,
    reindexDocument,
    loadDocumentDetail,
    openDocumentPreview,
    closeDocumentPreview,
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

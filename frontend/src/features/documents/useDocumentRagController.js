import { useEffect, useState } from 'react';

export function useDocumentRagController({
  activeSpaceId,
  request,
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
  const [selectedDocumentId, setSelectedDocumentId] = useState('');
  const [documentDetail, setDocumentDetail] = useState(null);
  const [documentPreviewOpen, setDocumentPreviewOpen] = useState(false);
  const [documentPreview, setDocumentPreview] = useState(null);
  const [documentPreviewBlobUrl, setDocumentPreviewBlobUrl] = useState('');
  const [documentPreviewLoading, setDocumentPreviewLoading] = useState(false);

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
      const parentTurnId = documentConversationTurns.at(-1)?.id || null;
      const data = await request('/api/rag/ask', {
        method: 'POST',
        json: {
          question,
          mode: answerMode,
          speedProfile: documentSpeedProfile,
          spaceId: activeSpaceId,
          conversationId: documentConversationId || null,
          parentTurnId,
          conversational: true,
        },
      });
      setAnswer(data);
      setAnswerSavedId('');
      if (data?.conversationId) {
        setDocumentConversationId(data.conversationId);
        setDocumentConversationTurns((current) => [
          ...current,
          {
            id: data.turnId,
            conversationId: data.conversationId,
            parentTurnId,
            question,
            rewrittenQuestion: data.rewrittenQuestion,
            mode: data.mode,
            answer: data.answer,
            confidence: data.confidence,
            citations: data.citations || [],
            evidence: data.evidence || [],
            diagnostics: data.diagnostics || [],
          },
        ]);
        await refreshDocumentConversations();
      }
    });
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
      setDocumentConversationTurns(turns);
      const lastTurn = turns.at(-1);
      if (lastTurn) {
        setQuestion(lastTurn.question || '');
        setAnswer({
          mode: lastTurn.mode,
          answer: lastTurn.answer,
          citations: lastTurn.citations || [],
          evidence: lastTurn.evidence || [],
          confidence: lastTurn.confidence,
          diagnostics: lastTurn.diagnostics || [],
          conversationId,
          turnId: lastTurn.id,
          rewrittenQuestion: lastTurn.rewrittenQuestion,
        });
        setAnswerSavedId('');
      }
    });
  }

  function startNewDocumentConversation() {
    setDocumentConversationId('');
    setDocumentConversationTurns([]);
    setAnswer(null);
    setAnswerSavedId('');
    setQuestion('');
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
    if (!window.confirm(`'${title}' 문서를 삭제할까요? 삭제된 문서는 휴지통에서 복구할 수 있습니다.`)) return;
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

import { useEffect, useState } from 'react';
import { Bookmark, CheckCircle2, ChevronDown, ChevronUp, Database, Eye, FileCode2, FileUp, Globe, Info, Loader2, Maximize2, MessageSquare, RefreshCw, Search, Trash2, X } from 'lucide-react';
import { answerModes, documentSpeedProfiles, evidencePreviewLimit } from '../../config/constants.js';
import { formatDate, formatFileSize, formatSelectedFiles, getAnswerModeGuide, getAnswerModeLabel, getPreviewTypeLabel, getSourceLabel, getStatusLabel, splitReaderParagraphs, submitFormOnShortcut } from '../../lib/formatters.js';
import { AnswerStatus, IconButton, ModeControl, StatusBadge } from '../common/Common.jsx';
import { AnswerModal } from '../common/AnswerModal.jsx';
import { RagAskComposer } from '../common/RagAskComposer.jsx';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';
import { Badge } from '../ui/badge.jsx';
import { DataTable } from '../ui/data-table.jsx';

function DocumentWorkspace(props) {
  const activeAnswerModeGuide = getAnswerModeGuide(props.answerMode);
  const [answerModalOpen, setAnswerModalOpen] = useState(false);
  const showSourceManagement = props.showSourceManagement !== false;

  return (
    <section className="workspace-grid workspace-product document-workspace-product">
      <div className="workspace-product-hero document-product-hero">
        <div>
          <Badge variant="secondary">Document RAG</Badge>
          <h1>문서 어시스턴트</h1>
          <p>PDF, 웹, 오피스 문서에서 원문 근거를 찾고 답변 품질을 진단합니다.</p>
        </div>
        <div className="workspace-product-metrics" aria-label="문서 RAG 상태 요약">
          <span><strong>{props.documents?.length || 0}</strong> 소스</span>
          <span><strong>{props.searchResults?.length || 0}</strong> 검색 결과</span>
          <span><strong>{props.answer?.evidence?.length || 0}</strong> 근거</span>
        </div>
      </div>
      {showSourceManagement && (
      <div className="left-column">
        <DocumentSourcePanel {...props} />
        <DocumentDetailPanel detail={props.documentDetail} loading={props.selectedDocumentId && props.loading(`detail-${props.selectedDocumentId}`)} />
      </div>
      )}

      <div className={showSourceManagement ? 'right-column' : 'right-column full-column'}>
        <form className="panel ask-panel rag-command-panel" onSubmit={props.ask}>
          <RagAskComposer
            title="문서에게 질문하기"
            description="현재 선택된 공간의 문서 전체에서 원문 근거를 찾아 답변합니다."
            icon={<MessageSquare size={18} />}
            controls={(
              <>
                <ModeControl modes={answerModes} value={props.answerMode} setValue={props.setAnswerMode} />
                <ModeControl modes={documentSpeedProfiles} value={props.documentSpeedProfile} setValue={props.setDocumentSpeedProfile} />
              </>
            )}
            guide={(
              <ConversationInlineActions
                activeConversationId={props.documentConversationId || ''}
                turnCount={(props.documentConversationTurns || []).length}
                loading={props.loading}
                loadingKey="document-conversations"
                onRefresh={props.refreshDocumentConversations}
                onNew={props.startNewDocumentConversation}
              />
            )}
            value={props.question}
            setValue={props.setQuestion}
            onKeyDown={(event) => submitFormOnShortcut(event, Boolean(props.question.trim()) && !props.loading('ask'))}
            placeholder={activeAnswerModeGuide.placeholder}
            loading={props.loading('ask')}
            disabled={!props.question.trim()}
            submitLabel={props.documentConversationId ? '추가 질문' : '답변 생성'}
            templates={[
              { label: '요약', prompt: '이 문서들의 핵심 내용을 출처와 함께 요약해줘.' },
              { label: '절차', prompt: '관련 절차를 단계별로 정리하고 각 단계의 근거를 알려줘.' },
              { label: '위치', prompt: '질문과 관련된 값, 위치, 조건을 원문 근거와 함께 찾아줘.' },
              { label: '출처 중심', prompt: '답변보다 근거 문서와 인용 위치를 중심으로 정리해줘.' },
            ]}
          />
          {props.answer && (
            <div className="answer">
              <div className="answer-title">
                <div className="answer-title-main">
                  <CheckCircle2 size={16} />
                  <strong>답변</strong>
                </div>
                <div className="answer-actions">
                  <button className="icon-button answer-expand-button" type="button" title={props.answerSavedId ? '저장됨' : '답변 저장'} disabled={props.answerSavedId || props.loading('save-answer')} onClick={props.saveAnswer}>
                    {props.loading('save-answer') ? <Loader2 className="spin" size={15} /> : <Bookmark size={15} />}
                  </button>
                  <button className="icon-button answer-expand-button" type="button" title="크게 보기" onClick={() => setAnswerModalOpen(true)}>
                    <Maximize2 size={15} />
                  </button>
                </div>
              </div>
              <small className="answer-mode">{getAnswerModeLabel(props.answer.mode)} 紐⑤뱶</small>
              {props.answer.rewrittenQuestion && props.answer.rewrittenQuestion !== props.question && (
                <small className="answer-mode">이전 문서 근거를 참고해 후속 질문으로 처리했습니다.</small>
              )}
              <AnswerStatus confidence={props.answer.confidence} diagnostics={props.answer.diagnostics} />
              <div className="answer-body">
                <MarkdownAnswer text={props.answer.answer} />
              </div>
              <EvidenceList evidence={props.answer.evidence} onOpenEvidence={props.openDocumentPreview} />
            </div>
          )}
          {answerModalOpen && props.answer && (
            <AnswerModal
              title="답변"
              subtitle={`${getAnswerModeLabel(props.answer.mode)} 모드`}
              answer={props.answer.answer}
              onClose={() => setAnswerModalOpen(false)}
            />
          )}
        </form>
        <form className="panel search-panel rag-search-panel" onSubmit={props.search}>
          <div className="panel-title">
            <Search size={18} />
            <div>
              <h2>문서 검색</h2>
              <p>벡터 검색과 키워드 검색 결과를 함께 확인합니다.</p>
            </div>
          </div>
          <div className="inline-control">
            <input value={props.query} onChange={(event) => props.setQuery(event.target.value)} placeholder="검색어를 입력하세요" />
            <button disabled={!props.query || props.loading('search')}>
              {props.loading('search') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              검색
            </button>
          </div>
          <ResultList results={props.searchResults} title="검색 결과" />
        </form>
        {!showSourceManagement && (
          <DocumentDetailPanel detail={props.documentDetail} loading={props.selectedDocumentId && props.loading(`detail-${props.selectedDocumentId}`)} />
        )}
        {!showSourceManagement && props.documentPreviewOpen && (
          <DocumentPreviewModal
            preview={props.documentPreview}
            blobUrl={props.documentPreviewBlobUrl}
            loading={props.documentPreviewLoading}
            onClose={props.closeDocumentPreview}
          />
        )}
      </div>
    </section>
  );
}

function ConversationInlineActions({
  activeConversationId = '',
  turnCount = 0,
  loading = () => false,
  loadingKey = 'conversations',
  onRefresh = () => {},
  onNew = () => {},
}) {
  return (
    <div className="rag-conversation-inline-actions">
      <button className="ghost-button compact-action" type="button" onClick={onNew}>+ 새 대화</button>
      <button className="ghost-button compact-action" type="button" disabled={loading(loadingKey)} onClick={onRefresh}>
        {loading(loadingKey) ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
        새로고침
      </button>
      {activeConversationId && <span>현재 {turnCount}턴</span>}
    </div>
  );
}

function DocumentSourcePanel(props) {
  const {
    webUrl = '',
    setWebUrl = () => {},
    webRecursive = true,
    setWebRecursive = () => {},
    webMaxDepth = 2,
    setWebMaxDepth = () => {},
    webMaxPages = 30,
    setWebMaxPages = () => {},
    webCrawlScope = 'START_PATH',
    setWebCrawlScope = () => {},
    webRobotsFailurePolicy = 'FAIL_CLOSED',
    setWebRobotsFailurePolicy = () => {},
    webIncludeAttachments = false,
    setWebIncludeAttachments = () => {},
    webUseSitemap = false,
    setWebUseSitemap = () => {},
    webRenderMode = 'PLAYWRIGHT_FALLBACK',
    setWebRenderMode = () => {},
    files = [],
    setFiles = () => {},
    documents = [],
    documentJobs = [],
    documentJobDiagnostics = {},
    loadDocumentJobDiagnostics = () => {},
    retryDocumentJobStage = () => {},
    selectedDocumentId = '',
    loadDocumentDetail = () => {},
    openDocumentPreview = () => {},
    documentPreviewOpen = false,
    documentPreview = null,
    documentPreviewBlobUrl = '',
    documentPreviewLoading = false,
    closeDocumentPreview = () => {},
    reindexDocument = () => {},
    deleteDocument = () => {},
    fileBatchResult,
    ingestWeb = (event) => event.preventDefault(),
    ingestFile = (event) => event.preventDefault(),
    loading = () => false,
  } = props;
  return (
    <section className="panel">
      <div className="panel-title">
        <Database size={18} />
        <div>
          <h2>문서 소스 추가</h2>
          <p>웹 URL과 PDF, DOCX, PPTX, Markdown, TXT, CSV, Excel 파일을 RAG 근거로 인덱싱합니다.</p>
        </div>
      </div>
      <form className="stack" onSubmit={ingestWeb}>
        <label htmlFor="admin-web-url">웹 URL</label>
        <div className="inline-control">
          <input
            id="admin-web-url"
            value={webUrl}
            onChange={(event) => setWebUrl(event.target.value)}
            placeholder="https://example.com/docs 또는 example.com/docs"
          />
          <button disabled={!webUrl || loading('web')}>
            {loading('web') ? <Loader2 className="spin" size={16} /> : <Globe size={16} />}
            인덱싱
          </button>
        </div>
        <label className="checkbox-row" htmlFor="admin-web-recursive">
          <input
            id="admin-web-recursive"
            type="checkbox"
            checked={webRecursive}
            onChange={(event) => setWebRecursive(event.target.checked)}
          />
          <span>시작 URL의 하위 경로를 함께 수집</span>
        </label>
        <WebCrawlAdvancedOptions
          prefix="admin-web"
          recursive={webRecursive}
          allowIgnoreRobots
          crawlScope={webCrawlScope}
          setCrawlScope={setWebCrawlScope}
          robotsFailurePolicy={webRobotsFailurePolicy}
          setRobotsFailurePolicy={setWebRobotsFailurePolicy}
          includeAttachments={webIncludeAttachments}
          setIncludeAttachments={setWebIncludeAttachments}
          useSitemap={webUseSitemap}
          setUseSitemap={setWebUseSitemap}
          renderMode={webRenderMode}
          setRenderMode={setWebRenderMode}
        />
        <div className="form-grid two">
          <div className="stack">
            <label htmlFor="admin-web-max-depth">깊이</label>
            <input
              id="admin-web-max-depth"
              type="number"
              min="0"
              max="2"
              value={webMaxDepth}
              disabled={!webRecursive}
              onChange={(event) => setWebMaxDepth(event.target.value)}
            />
          </div>
          <div className="stack">
            <label htmlFor="admin-web-max-pages">최대 페이지</label>
            <input
              id="admin-web-max-pages"
              type="number"
              min="1"
              max="30"
              value={webMaxPages}
              disabled={!webRecursive}
              onChange={(event) => setWebMaxPages(event.target.value)}
            />
          </div>
        </div>
      </form>
      <form className="stack" onSubmit={ingestFile}>
        <label htmlFor="admin-file-upload">파일 업로드</label>
        <div className="file-row">
          <label className="file-picker" htmlFor="admin-file-upload">
            <FileUp size={16} />
            <span>{formatSelectedFiles(files)}</span>
          </label>
          <input
            id="admin-file-upload"
            className="visually-hidden"
            type="file"
            accept=".pdf,.docx,.ppt,.pptx,.md,.markdown,.txt,.csv,.xls,.xlsx"
            multiple
            onChange={(event) => setFiles(Array.from(event.target.files || []))}
          />
          <button disabled={!files?.length || loading('file')}>
            {loading('file') ? <Loader2 className="spin" size={16} /> : <FileUp size={16} />}
            업로드
          </button>
        </div>
        {files?.length > 1 && (
          <div className="selected-file-list">
            {files.map((item) => <span key={`${item.name}-${item.size}`}>{item.name}</span>)}
          </div>
        )}
        {fileBatchResult && <FileBatchResult result={fileBatchResult} />}
      </form>
      <DocumentSourceList
        documents={documents}
        jobs={documentJobs}
        diagnostics={documentJobDiagnostics}
        loadDiagnostics={loadDocumentJobDiagnostics}
        retryStage={retryDocumentJobStage}
        selectedDocumentId={selectedDocumentId}
        loadDocumentDetail={loadDocumentDetail}
        openDocumentPreview={openDocumentPreview}
        reindexDocument={reindexDocument}
        deleteDocument={deleteDocument}
        loading={loading}
      />
      {documentPreviewOpen && (
        <DocumentPreviewModal
          preview={documentPreview}
          blobUrl={documentPreviewBlobUrl}
          loading={documentPreviewLoading}
          onClose={closeDocumentPreview}
        />
      )}
    </section>
  );
}

function DocumentSourceList({
  documents = [],
  jobs = [],
  diagnostics = {},
  loadDiagnostics = () => {},
  retryStage = () => {},
  selectedDocumentId = '',
  loadDocumentDetail = () => {},
  openDocumentPreview = () => {},
  reindexDocument = () => {},
  deleteDocument = () => {},
  loading = () => false,
}) {
  const latestJobBySource = latestDocumentJobsBySource(jobs);
  return (
    <section className="panel documents-panel">
      <div className="panel-title">
        <FileCode2 size={18} />
        <div>
          <h2>{'\uBB38\uC11C \uC800\uC7A5\uC18C \uBAA9\uB85D'}</h2>
          <p>{documents.length ? `${documents.length}\uAC1C \uBB38\uC11C` : '\uB4F1\uB85D\uB41C \uBB38\uC11C\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.'}</p>
        </div>
      </div>
      <div className="document-list scrollable-list repo-list document-source-list">
        {documents.map((document) => {
          const latestJob = latestJobBySource.get(document.sourceId) || latestJobBySource.get(document.id);
          const runningJob = latestJob && (latestJob.status === 'RUNNING' || latestJob.status === 'CANCELLING') ? latestJob : null;
          return (
            <article
              className={document.id === selectedDocumentId ? 'document-row selected repo-row' : 'document-row repo-row'}
              key={document.id}
              onClick={() => loadDocumentDetail(document.id)}
            >
              <div className="document-main">
                <strong>{document.title || document.sourceUri || '\uBB38\uC11C'}</strong>
                <small>{document.sourceUri || document.contentType || '-'}</small>
              </div>
              <div className="document-meta">
                <StatusBadge status={document.sourceStatus} />
                <small>{getSourceLabel(document.sourceType)} {'\u00B7'} {formatDate(document.createdAt)}</small>
              </div>
              <div className="document-actions">
                <IconButton title={'\uBBF8\uB9AC\uBCF4\uAE30'} onClick={(event) => { event.stopPropagation(); openDocumentPreview(document.id); }}>
                  <Eye size={15} />
                </IconButton>
                <IconButton title={'\uC7AC\uC778\uB371\uC2F1'} disabled={!!runningJob || loading(`document-reindex-${document.id}`)} onClick={(event) => { event.stopPropagation(); reindexDocument(document.id); }}>
                  {loading(`document-reindex-${document.id}`) ? <Loader2 className="spin" size={15} /> : <RefreshCw size={15} />}
                </IconButton>
                <IconButton danger title={'\uBB38\uC11C \uC0AD\uC81C'} disabled={!!runningJob || loading(`delete-${document.id}`)} onClick={(event) => { event.stopPropagation(); deleteDocument(document.id, document.title || document.sourceUri); }}>
                  {loading(`delete-${document.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                </IconButton>
              </div>
              {latestJob && (
                <DocumentJobStrip
                  job={latestJob}
                  diagnostics={diagnostics[latestJob.id]}
                  loadDiagnostics={loadDiagnostics}
                  retryStage={retryStage}
                  diagnosticsLoading={loading(`document-job-diagnostics-${latestJob.id}`)}
                  retryLoading={(stage) => loading(`document-job-retry-${stage}-${latestJob.id}`)}
                />
              )}
            </article>
          );
        })}
        {documents.length === 0 && <p className="empty">{'\uC6F9 URL\uC744 \uB4F1\uB85D\uD558\uAC70\uB098 \uD30C\uC77C\uC744 \uC5C5\uB85C\uB4DC\uD574 \uC778\uB371\uC2F1\uC744 \uC2DC\uC791\uD558\uC138\uC694.'}</p>}
      </div>
    </section>
  );
}

function DocumentJobStrip({ job, diagnostics, loadDiagnostics, retryStage, diagnosticsLoading, retryLoading }) {
  return (
    <div className="job-strip">
      <span>{documentJobSummary(job)}</span>
      <EnrichmentStatusLine job={job} />
      <div className="progress-track" aria-label={'\uBB38\uC11C \uC778\uB371\uC2F1 \uC9C4\uD589\uB960'}>
        <span style={{ width: `${documentJobPercent(job)}%` }} />
      </div>
      {job.errorMessage && <div className="failure-line">{job.errorMessage}</div>}
      <button className="ghost-button compact-action" type="button" onClick={(event) => { event.stopPropagation(); loadDiagnostics(job.id); }}>
        {diagnosticsLoading ? <Loader2 className="spin" size={14} /> : <Info size={14} />}
        진단
      </button>
      {diagnostics && <DocumentDiagnosticList diagnostics={diagnostics} job={job} retryStage={retryStage} retryLoading={retryLoading} />}
    </div>
  );
}

function DocumentDiagnosticList({ diagnostics = [], job, retryStage, retryLoading }) {
  if (!diagnostics.length) {
    return <p className="empty compact-empty">기록된 문서 처리 진단이 없습니다.</p>;
  }
  return (
    <div className="failure-list">
      {diagnostics.map((diagnostic) => {
        const retryable = diagnostic.status === 'FAILED'
          && (diagnostic.stage === 'DOCUMENT_LLM_ENRICHMENT' || diagnostic.stage === 'DOCUMENT_GRAPH_REBUILD');
        return (
          <div className="failure-item" key={diagnostic.id}>
            <strong>{documentStageLabel(diagnostic.stage)} · {diagnostic.status}</strong>
            <small>{diagnostic.mode || diagnostic.analyzer} · {diagnostic.durationMillis}ms</small>
            <span>
              처리 {diagnostic.processedItems}/{diagnostic.attemptedItems}
              {diagnostic.nodeCount > 0 || diagnostic.edgeCount > 0 ? ` · 노드 ${diagnostic.nodeCount} · 관계 ${diagnostic.edgeCount}` : ''}
            </span>
            {diagnostic.message && <span>{diagnostic.message}</span>}
            {retryable && (
              <button className="ghost-button compact-action" type="button" onClick={(event) => { event.stopPropagation(); retryStage(job.id, diagnostic.stage); }}>
                {retryLoading?.(diagnostic.stage) ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
                이 단계 재시도
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

function documentStageLabel(stage) {
  const labels = {
    DOCUMENT_LLM_ENRICHMENT: 'LLM 보강',
    DOCUMENT_GRAPH_REBUILD: '문서 그래프 생성',
    DOCUMENT_CONTEXT_INLINE: '기본 문맥 생성',
  };
  return labels[stage] || stage;
}

function latestDocumentJobsBySource(jobs = []) {
  const sorted = [...jobs].sort((a, b) => new Date(b.createdAt || b.startedAt || 0) - new Date(a.createdAt || a.startedAt || 0));
  const latest = new Map();
  sorted.forEach((job) => {
    const key = job.sourceId;
    if (key && !latest.has(key)) {
      latest.set(key, job);
    }
  });
  return latest;
}

function documentJobSummary(job) {
  const status = getStatusLabel(job?.status);
  const processed = Number(job?.processedDocuments || 0);
  const total = Number(job?.totalDocuments || 0);
  const chunks = Number(job?.totalChunks || 0);
  const embedded = Number(job?.embeddedChunks || 0);
  const reused = Number(job?.reusedChunks || 0);
  const parts = [`${status} \u00B7 ${processed}/${total || '-'} files \u00B7 ${chunks} chunks`];
  if (embedded > 0) parts.push(`\uCD94\uAC00 ${embedded}`);
  if (reused > 0) parts.push(`\uC7AC\uC0AC\uC6A9 ${reused}`);
  return parts.join(' \u00B7 ');
}

function documentJobPercent(job) {
  if (job?.status === 'SUCCEEDED') return 100;
  const total = Number(job?.totalDocuments || 0);
  if (!total) return job?.status === 'RUNNING' ? 8 : 0;
  return Math.max(5, Math.min(100, Math.round((Number(job?.processedDocuments || 0) / total) * 100)));
}

function EnrichmentStatusLine({ job }) {
  const label = enrichmentStatusText(job?.enrichmentStatus);
  if (!label) return null;
  const message = job?.enrichmentMessage;
  return (
    <small className={`enrichment-line enrichment-${String(job.enrichmentStatus || '').toLowerCase()}`}>
      {label}{message ? ` · ${message}` : ''}
    </small>
  );
}

function enrichmentStatusText(status) {
  const labels = {
    PENDING: '보강 대기',
    RUNNING: '보강 중',
    RETRYING: '보강 재시도 예정',
    SUCCEEDED: '보강 완료',
    FAILED: '보강 실패',
    SKIPPED: '보강 생략',
    NOT_STARTED: '',
  };
  return labels[status] ?? status ?? '';
}

function WebIngestHelpModal({ onClose }) {
  return (
    <div className="code-modal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal document-preview-modal" role="dialog" aria-modal="true" aria-labelledby="web-ingest-help-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            <HelpCircle size={18} />
            <div>
              <h2 id="web-ingest-help-title">웹 문서 수집 옵션</h2>
              <p>수집 범위, 실패 처리, 렌더링 방식을 조정해 문서 인덱싱 품질과 속도를 제어합니다.</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="닫기" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>
        <div className="code-modal-body document-preview-body">
          <div className="document-reader">
            <h3>재귀 수집</h3>
            <p>끄면 입력한 URL 한 페이지만 수집하고, 켜면 하위 링크를 따라가며 관련 문서를 같은 소스로 인덱싱합니다.</p>
            <h3>수집 범위</h3>
            <p><strong>시작 경로 하위</strong>는 가장 안전한 기본값입니다. 같은 문서 경로 아래의 페이지만 따라갑니다.</p>
            <p><strong>같은 호스트 전체</strong>는 같은 도메인의 다른 경로까지 수집합니다.</p>
            <p><strong>허용 도메인 전체</strong>는 관리자가 허용한 도메인 사이에서 교차 링크를 따라갑니다.</p>
            <h3>robots.txt와 sitemap</h3>
            <p>robots.txt 조회가 실패하면 기본적으로 차단하고, sitemap은 발견 가능한 문서 URL을 보강하는 용도로 사용합니다.</p>
            <h3>첨부 파일과 렌더링</h3>
            <p>첨부 파일 옵션은 PDF, DOCX, PPTX, XLSX, CSV, TXT, Markdown 링크를 함께 인덱싱합니다. Playwright fallback은 정적 HTML 본문이 부족할 때 브라우저 렌더링으로 재시도합니다.</p>
          </div>
        </div>
      </section>
    </div>
  );
}
function WebCrawlAdvancedOptions({
  prefix,
  recursive,
  allowIgnoreRobots = false,
  crawlScope = 'START_PATH',
  setCrawlScope = () => {},
  robotsFailurePolicy = 'FAIL_CLOSED',
  setRobotsFailurePolicy = () => {},
  includeAttachments = false,
  setIncludeAttachments = () => {},
  useSitemap = false,
  setUseSitemap = () => {},
  renderMode = 'PLAYWRIGHT_FALLBACK',
  setRenderMode = () => {},
}) {
  useEffect(() => {
    if (!allowIgnoreRobots && robotsFailurePolicy === 'IGNORE') {
      setRobotsFailurePolicy('FAIL_CLOSED');
    }
  }, [allowIgnoreRobots, robotsFailurePolicy, setRobotsFailurePolicy]);

  const visibleRobotsFailurePolicy = allowIgnoreRobots || robotsFailurePolicy !== 'IGNORE'
    ? robotsFailurePolicy
    : 'FAIL_CLOSED';

  return (
    <div className="detail-box compact-box">
      <div className="form-grid two">
        <div className="stack">
          <label htmlFor={`${prefix}-crawl-scope`}>수집 범위</label>
          <select
            id={`${prefix}-crawl-scope`}
            value={crawlScope}
            disabled={!recursive}
            onChange={(event) => setCrawlScope(event.target.value)}
          >
            <option value="START_PATH">시작 경로 하위</option>
            <option value="SAME_HOST">같은 호스트 전체</option>
            <option value="SAME_SITE">같은 사이트</option>
            <option value="ALLOWLIST">허용 도메인 전체</option>
          </select>
        </div>
        <div className="stack">
          <label htmlFor={`${prefix}-robots-policy`}>robots.txt 조회 실패</label>
          <select
            id={`${prefix}-robots-policy`}
            value={visibleRobotsFailurePolicy}
            onChange={(event) => setRobotsFailurePolicy(event.target.value)}
          >
            <option value="FAIL_CLOSED">실패 시 차단</option>
            <option value="ALLOW_ON_ERROR">조회 실패만 허용</option>
            {allowIgnoreRobots && <option value="IGNORE">robots.txt 무시</option>}
          </select>
        </div>
      </div>
      <div className="form-grid two">
        <label className="checkbox-row" htmlFor={`${prefix}-sitemap`}>
          <input
            id={`${prefix}-sitemap`}
            type="checkbox"
            checked={useSitemap}
            disabled={!recursive}
            onChange={(event) => setUseSitemap(event.target.checked)}
          />
          <span>sitemap.xml 사용</span>
        </label>
        <label className="checkbox-row" htmlFor={`${prefix}-attachments`}>
          <input
            id={`${prefix}-attachments`}
            type="checkbox"
            checked={includeAttachments}
            disabled={!recursive}
            onChange={(event) => setIncludeAttachments(event.target.checked)}
          />
          <span>첨부 파일 수집</span>
        </label>
      </div>
      <div className="stack">
        <label htmlFor={`${prefix}-render-mode`}>렌더링 방식</label>
        <select
          id={`${prefix}-render-mode`}
          value={renderMode}
          onChange={(event) => setRenderMode(event.target.value)}
        >
          <option value="STATIC">정적 HTML</option>
          <option value="PLAYWRIGHT_FALLBACK">필요 시 Playwright fallback</option>
          <option value="PLAYWRIGHT_ALWAYS">Playwright 우선</option>
        </select>
      </div>
    </div>
  );
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
  const [auditFilter, setAuditFilter] = useState('issues');
  const [showAllAudits, setShowAllAudits] = useState(false);

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
    return null;
  }
  const audits = detail.crawlAudits || [];
  const auditSummary = summarizeCrawlAudits(audits);
  const filteredAudits = filterCrawlAudits(audits, auditFilter);
  const visibleAudits = showAllAudits ? filteredAudits : filteredAudits.slice(0, 8);

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
      {audits.length > 0 && (
        <div className="results audit-list">
          <div className="audit-summary-grid">
            <AuditSummaryItem label="Success" value={auditSummary.success} />
            <AuditSummaryItem label="Issues" value={auditSummary.issues} tone={auditSummary.issues ? 'warning' : ''} />
            <AuditSummaryItem label="Sitemap" value={auditSummary.sitemap} />
            <AuditSummaryItem label="Attachments" value={auditSummary.attachments} />
            <AuditSummaryItem label="Playwright" value={auditSummary.playwright} />
          </div>
          <div className="audit-filter-row">
            {[
              ['issues', '문제 우선'],
              ['all', '전체'],
              ['sitemap', 'Sitemap'],
              ['attachments', 'Attachments'],
              ['playwright', 'Playwright'],
            ].map(([value, label]) => (
              <button
                className={auditFilter === value ? 'ghost-button compact-action active' : 'ghost-button compact-action'}
                key={value}
                type="button"
                onClick={() => {
                  setAuditFilter(value);
                  setShowAllAudits(false);
                }}
              >
                {label}
              </button>
            ))}
          </div>
          {visibleAudits.map((audit) => (
            <article className={audit.success ? 'result audit-result' : 'result audit-result audit-result-warning'} key={audit.id}>
              <div className="result-heading">
                <strong>{crawlReasonLabel(audit.reasonCode || (audit.success ? 'FETCHED' : 'SKIPPED'))}</strong>
                <span>{audit.statusCode || '-'}</span>
              </div>
              <small title={audit.url}>
                {audit.host || '-'}{audit.depth != null ? ` · depth ${audit.depth}` : ''}{audit.referrerUrl ? ` · from ${audit.referrerUrl}` : ''}
              </small>
              <p>{audit.message || audit.url}</p>
              <small>{crawlAuditMetadataLine(audit)}</small>
            </article>
          ))}
          {filteredAudits.length > visibleAudits.length && (
            <button className="ghost-button compact-action" type="button" onClick={() => setShowAllAudits(true)}>
              audit 이벤트 {filteredAudits.length}개 모두 보기
            </button>
          )}
          {!filteredAudits.length && <p className="empty compact-empty">이 필터에 해당하는 crawl audit 이벤트가 없습니다.</p>}
        </div>
      )}
    </section>
  );
}

function AuditSummaryItem({ label, value, tone = '' }) {
  return (
    <div className={tone ? `audit-summary-item ${tone}` : 'audit-summary-item'}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function summarizeCrawlAudits(audits = []) {
  return audits.reduce((summary, audit) => {
    const reason = audit.reasonCode || '';
    summary.success += audit.success ? 1 : 0;
    summary.issues += audit.success ? 0 : 1;
    summary.sitemap += reason.startsWith('SITEMAP_') ? 1 : 0;
    summary.attachments += isAttachmentAudit(reason, audit) ? 1 : 0;
    summary.playwright += reason.includes('PLAYWRIGHT') || reason.includes('STATIC_LOW_CONTENT') ? 1 : 0;
    return summary;
  }, { success: 0, issues: 0, sitemap: 0, attachments: 0, playwright: 0 });
}

function filterCrawlAudits(audits = [], filter = 'issues') {
  if (filter === 'all') return audits;
  if (filter === 'sitemap') return audits.filter((audit) => (audit.reasonCode || '').startsWith('SITEMAP_'));
  if (filter === 'attachments') return audits.filter((audit) => isAttachmentAudit(audit.reasonCode || '', audit));
  if (filter === 'playwright') {
    return audits.filter((audit) => {
      const reason = audit.reasonCode || '';
      return reason.includes('PLAYWRIGHT') || reason.includes('STATIC_LOW_CONTENT');
    });
  }
  return audits.filter((audit) => !audit.success);
}

function isAttachmentAudit(reasonCode = '', audit = {}) {
  return reasonCode.includes('ATTACHMENT')
    || /\.(pdf|docx?|pptx?|xlsx?|csv|txt|md|markdown)(\?|#|$)/i.test(audit.url || '');
}

function crawlReasonLabel(reasonCode = '') {
  const labels = {
    ATTACHMENT_LIMIT_REACHED: 'Attachment limit reached',
    STATIC_LOW_CONTENT_PLAYWRIGHT_RETRY: 'Static page was thin, retried with Playwright',
    PLAYWRIGHT_RETRY_FAILED_STATIC_FALLBACK: 'Playwright retry failed, kept static fallback',
    SITEMAP_FETCH_FAILED: 'Sitemap fetch failed',
    SITEMAP_FETCHED: 'Sitemap fetched',
    SITEMAP_URL_DISCOVERED: 'Sitemap URLs discovered',
    ROBOTS_DISALLOWED: 'Blocked by robots.txt',
    ROBOTS_UNAVAILABLE: 'robots.txt unavailable',
    OUT_OF_SCOPE: 'Outside crawl scope',
    LOW_CONTENT: 'Low content page',
    LOW_TEXT_DENSITY: 'Low text density',
    NAVIGATION_ONLY_PAGE: 'Navigation-only page',
    DUPLICATE_CONTENT: 'Duplicate content',
    FETCH_ERROR: 'Fetch error',
    FETCHED: 'Fetched',
    SKIPPED: 'Skipped',
  };
  return labels[reasonCode] || reasonCode || 'Audit event';
}

function crawlAuditMetadataLine(audit = {}) {
  const metadata = audit.metadata || {};
  const parts = [];
  if (metadata.fileSizeBytes != null) parts.push(`size ${formatFileSize(Number(metadata.fileSizeBytes))}`);
  if (metadata.discoveredUrlCount != null) parts.push(`${metadata.discoveredUrlCount} URLs`);
  if (metadata.urlCount != null) parts.push(`${metadata.urlCount} URLs`);
  if (metadata.maxSitemapUrls != null) parts.push(`sitemap cap ${metadata.maxSitemapUrls}`);
  if (metadata.maxAttachmentsPerCrawl != null) parts.push(`attachment cap ${metadata.maxAttachmentsPerCrawl}`);
  if (metadata.contentLength != null) parts.push(`content ${metadata.contentLength}`);
  if (metadata.density != null) parts.push(`density ${Number(metadata.density).toFixed(2)}`);
  return parts.length ? parts.join(' · ') : audit.reasonCode || '';
}

function ResultList({ results, title }) {
  const columns = [
    {
      accessorKey: 'title',
      header: '문서',
      cell: ({ row }) => (
        <div className="document-table-title">
          <strong>{row.original.title}</strong>
          <small>{row.original.sourceUri}</small>
        </div>
      ),
    },
    {
      accessorKey: 'chunkIndex',
      header: '청크',
      cell: ({ row }) => <Badge variant="outline">chunk {row.original.chunkIndex}</Badge>,
    },
    {
      accessorKey: 'content',
      header: '미리보기',
      cell: ({ row }) => <span className="document-result-preview">{row.original.content}</span>,
    },
    {
      accessorKey: 'score',
      header: '점수',
      cell: ({ row }) => Number(row.original.score || 0).toFixed(3),
    },
  ];
  return (
    <DataTable
      className="document-search-table"
      columns={columns}
      data={results}
      empty={`${title}가 없습니다.`}
    />
  );
}

function EvidenceList({ evidence = [], onOpenEvidence }) {
  const [expanded, setExpanded] = useState(false);
  const evidenceKey = evidence.map((item) => item.chunkId || item.citationNumber).join('|');
  useEffect(() => {
    setExpanded(false);
  }, [evidenceKey]);
  if (!evidence.length) return <p className="empty compact-empty">표시할 근거가 없습니다.</p>;
  const groupedEvidence = groupDocumentEvidence(evidence);
  const visibleEvidence = expanded ? groupedEvidence : groupedEvidence.slice(0, evidencePreviewLimit);
  const hiddenCount = Math.max(groupedEvidence.length - visibleEvidence.length, 0);
  return (
    <div className={expanded ? 'evidence-section evidence-section-expanded' : 'evidence-section'}>
      <div className="evidence-header">
        <strong>근거 문서</strong>
        <small>{visibleEvidence.length}/{groupedEvidence.length}개 문서 표시</small>
      </div>
      <div className="evidence-list document-evidence-scroll" tabIndex={0}>
        {visibleEvidence.map((item) => (
          <article className="evidence-card document-evidence" key={item.evidenceKey}>
            <div className="result-heading">
              <strong title={item.title}>[{item.citationNumbers.join(', ')}] {item.title}</strong>
              {item.documentId && (
                <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.documentId)}>
                  <Eye size={14} />
                  열기
                </button>
              )}
            </div>
            <small title={item.sourceUri}>
              {item.sourceUri} · {item.matchedChunkCount > 1 ? `관련 청크 ${item.matchedChunkCount}개` : `chunk ${item.chunkIndex}`}
            </small>
            <p>{item.preview}</p>
          </article>
        ))}
      </div>
      {groupedEvidence.length > evidencePreviewLimit && (
        <button className="ghost-button compact-action evidence-toggle" type="button" onClick={() => setExpanded((current) => !current)}>
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          {expanded ? '상위 근거만 보기' : `전체 근거 문서 ${groupedEvidence.length}개 보기`}
          {!expanded && hiddenCount > 0 ? <span>+{hiddenCount}</span> : null}
        </button>
      )}
    </div>
  );
}

function groupDocumentEvidence(evidence) {
  const grouped = new Map();
  evidence.forEach((item, index) => {
    const key = item.documentId || item.sourceUri || item.title || item.chunkId || `evidence-${index}`;
    const current = grouped.get(key);
    if (!current) {
      grouped.set(key, {
        ...item,
        evidenceKey: String(key),
        citationNumbers: [item.citationNumber],
        matchedChunkCount: 1,
      });
      return;
    }
    current.matchedChunkCount += 1;
    if (!current.citationNumbers.includes(item.citationNumber)) {
      current.citationNumbers.push(item.citationNumber);
    }
    if (Number(item.score || 0) > Number(current.score || 0)) {
      current.preview = item.preview;
      current.chunkIndex = item.chunkIndex;
      current.score = item.score;
    }
  });
  return Array.from(grouped.values());
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
  if (preview.previewType === 'pdf' || preview.previewType === 'presentation_pdf') {
    if (!blobUrl) {
      if (preview.previewType === 'presentation_pdf' && ((preview.blocks || []).length || preview.text)) {
        return <PresentationReader blocks={preview.blocks || []} fallbackText={preview.text} />;
      }
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

  if (preview.previewType === 'pptx') {
    return <PresentationReader blocks={preview.blocks || []} fallbackText={preview.text} />;
  }

  if (preview.previewType === 'web') {
    if (preview.blocks?.length) {
      return <WebReader blocks={preview.blocks} fallbackText={preview.text} />;
    }
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

function PresentationReader({ blocks = [], fallbackText = '' }) {
  if (!blocks.length) {
    return <ReaderText text={fallbackText} />;
  }
  const slides = [];
  let current = null;
  for (const block of blocks) {
    if (block?.type === 'heading') {
      current = { title: block.text || `Slide ${slides.length + 1}`, paragraphs: [] };
      slides.push(current);
    } else if (block?.text) {
      if (!current) {
        current = { title: `Slide ${slides.length + 1}`, paragraphs: [] };
        slides.push(current);
      }
      current.paragraphs.push(block.text);
    }
  }
  return (
    <div className="presentation-reader">
      {slides.map((slide, index) => (
        <section className="presentation-slide" key={`${slide.title}-${index}`}>
          <h3>{slide.title}</h3>
          {slide.paragraphs.map((paragraph, paragraphIndex) => (
            <p key={paragraphIndex}>{paragraph}</p>
          ))}
        </section>
      ))}
    </div>
  );
}

function WebReader({ blocks = [], fallbackText = '' }) {
  if (!blocks.length) {
    return <ReaderText text={fallbackText} />;
  }
  return (
    <div className="document-reader web-reader">
      {blocks.map((block, index) => <WebReaderBlock block={block} key={index} />)}
    </div>
  );
}

function WebReaderBlock({ block }) {
  const type = block?.type || 'paragraph';
  if (type === 'heading') {
    const level = Math.max(1, Math.min(4, Number(block.level || 2)));
    const HeadingTag = `h${level}`;
    return <HeadingTag className="web-reader-heading">{block.text}</HeadingTag>;
  }
  if (type === 'list') {
    const items = block.items || [];
    if (!items.length) return null;
    return (
      <ul className="web-reader-list">
        {items.map((item, index) => <li key={index}>{item}</li>)}
      </ul>
    );
  }
  if (type === 'table') {
    return <PreviewTable rows={block.rows || []} />;
  }
  if (type === 'code') {
    return <pre className="web-reader-code"><code>{block.text}</code></pre>;
  }
  if (type === 'quote') {
    return <blockquote className="web-reader-quote">{block.text}</blockquote>;
  }
  if (type === 'image') {
    return (
      <div className="web-reader-asset">
        <span>{block.text || 'image'}</span>
        {block.href && <small>{block.href}</small>}
      </div>
    );
  }
  if (!block?.text) return null;
  return <p>{block.text}</p>;
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

export { DocumentSourcePanel, DocumentWorkspace };

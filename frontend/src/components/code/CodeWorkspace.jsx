import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, ChevronDown, ChevronUp, Eye, FileArchive, FileCode2, GitBranch, Info, Loader2, RefreshCw, Search, Trash2, X } from 'lucide-react';
import { codeModes, evidencePreviewLimit } from '../../config/constants.js';
import { formatDate, getCodeModeGuide, getCodeModeLabel, getStatusLabel, jobChangeText, jobPercent, submitFormOnShortcut } from '../../lib/formatters.js';
import { escapeHtml, highlightLanguage, highlightedLineHtml } from '../../lib/highlight.js';
import { useStreamingAutoScroll } from '../../lib/useStreamingAutoScroll.js';
import { IconButton, ModeControl, StatusBadge } from '../common/Common.jsx';
import { RagChatPanel } from '../common/RagChatPanel.jsx';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';
import { Badge } from '../ui/badge.jsx';
import { Button } from '../ui/button.jsx';
import { DataTable } from '../ui/data-table.jsx';

const SPACE_PLACEHOLDER = '\u00A0';

function sanitizeHighlightClassName(className) {
  return String(className || '')
    .split(/\s+/)
    .map((token) => token.trim())
    .filter((token) => token && /^(?:hljs|hljs-[\w-]+)$/.test(token))
    .join(' ');
}

function parseHighlightedLine(lineMarkup, fallback) {
  const rawFallback = fallback || SPACE_PLACEHOLDER;
  if (!lineMarkup || typeof lineMarkup !== 'string') {
    return rawFallback;
  }
  if (lineMarkup.indexOf('<') < 0) {
    return lineMarkup || rawFallback;
  }

  if (typeof DOMParser === 'undefined' || typeof window === 'undefined' || typeof document === 'undefined') {
    return lineMarkup || rawFallback;
  }

  try {
    const parser = new DOMParser();
    const parsed = parser.parseFromString(`<span>${lineMarkup}</span>`, 'text/html');
    const root = parsed.body?.firstElementChild;
    if (!root) return lineMarkup || rawFallback;

    function renderNodes(node) {
      if (!node || !node.childNodes?.length) {
        return null;
      }
      return Array.from(node.childNodes).map((child, index) => {
        if (child.nodeType === Node.TEXT_NODE) {
          return <span key={`text-${index}`}>{child.textContent || ''}</span>;
        }

        if (child.nodeType === Node.ELEMENT_NODE) {
          const tagName = String(child.tagName || '').toLowerCase();
          if (tagName === 'span') {
            const className = sanitizeHighlightClassName(child.getAttribute('class'));
            return (
              <span key={`span-${index}`} className={className || undefined}>
                {renderNodes(child)}
              </span>
            );
          }
          return (
            <span key={`text-${index}`} className="whitespace-pre-wrap">
              {child.textContent || ''}
            </span>
          );
        }

        return null;
      });
    }

    const rendered = renderNodes(root);
    if (!rendered || rendered.length === 0) {
      return rawFallback;
    }
    return rendered;
  } catch {
    return escapeHtml(lineMarkup) || rawFallback;
  }
}

function CodeWorkspace(props) {
  const {
    repositories = [],
    selectedRepositoryId = '',
    setSelectedRepositoryId = () => {},
    selectedRepository,
    selectedCodeFile,
    highlightRange,
    codeModalOpen,
    setCodeModalOpen = () => {},
    codeQuestion = '',
    setCodeQuestion = () => {},
    codeMode = 'overview',
    setCodeMode = () => {},
    codeAnswer,
    codeConversations = [],
    codeConversationId = '',
    codeConversationTurns = [],
    refreshCodeConversations = () => {},
    loadCodeConversation = () => {},
    startNewCodeConversation = () => {},
    codeSearchQuery = '',
    setCodeSearchQuery = () => {},
    codeSearchResults = [],
    referenceSymbol = '',
    setReferenceSymbol = () => {},
    referenceResult,
    openCodeFile = () => {},
    askCode = (event) => event.preventDefault(),
    searchCode = (event) => event.preventDefault(),
    findReferences = (event) => event.preventDefault(),
    loading = () => false,
    codeFileLoading = false,
    showSourceManagement = true,
  } = props;
  const activeCodeModeGuide = getCodeModeGuide(codeMode);
  const answerStreamAnchorRef = useRef(null);
  const chatTurns = props.pendingCodeTurn
    ? [...(codeConversationTurns || []), props.pendingCodeTurn]
    : (codeConversationTurns || []);
  const latestAnswer = props.pendingCodeTurn || codeAnswer;
  useStreamingAutoScroll(answerStreamAnchorRef, latestAnswer?.streaming, latestAnswer?.answer);

  return (
    <section className="workspace-grid code-grid workspace-product code-workspace-product">
      <div className="workspace-product-hero code-product-hero">
        <div>
          <Badge variant="secondary">Code RAG</Badge>
          <h1>코드 어시스턴트</h1>
          <p>저장소의 실제 파일, 라인, 참조 위치를 근거로 코드 질문에 답합니다.</p>
        </div>
        <div className="workspace-product-metrics" aria-label="코드 RAG 상태 요약">
          <span><strong>{repositories.length}</strong> repositories</span>
          <span><strong>{codeSearchResults.length}</strong> search hits</span>
          <span><strong>{referenceResult ? (referenceResult.definitions?.length || 0) + (referenceResult.references?.length || 0) : 0}</strong> references</span>
        </div>
      </div>
      {showSourceManagement && <CodeSourceManagementPanel {...props} />}

      <div className={showSourceManagement ? 'right-column' : 'right-column full-column'}>
        <RagChatPanel
          domain="CODE"
          turns={chatTurns}
          question={codeQuestion}
          setQuestion={setCodeQuestion}
          onSubmit={askCode}
          onKeyDown={(event) => submitFormOnShortcut(event, Boolean(codeQuestion.trim()) && !loading('code-ask'))}
          placeholder={activeCodeModeGuide.placeholder}
          loading={loading('code-ask')}
          disabled={!codeQuestion.trim()}
          submitLabel={codeConversationId ? '추가 질문' : '코드 질문'}
          emptyTitle="코드에게 질문하기"
          emptyDescription="저장소의 실제 파일, 라인, 참조 근거를 바탕으로 코드 질문에 답합니다."
          controls={(
            <>
              <RepositorySelect
                repositories={repositories}
                selectedRepository={selectedRepository}
                selectedRepositoryId={selectedRepositoryId}
                setSelectedRepositoryId={setSelectedRepositoryId}
              />
              <ModeControl modes={codeModes} value={codeMode} setValue={setCodeMode} className="code-mode-control" />
            </>
          )}
          guide={(
            <ConversationInlineActions
              activeConversationId={codeConversationId}
              turnCount={codeConversationTurns.length}
              loading={loading}
              loadingKey="code-conversations"
              onRefresh={refreshCodeConversations}
              onNew={startNewCodeConversation}
            />
          )}
          templates={[
            { label: '구조 요약', prompt: '선택한 저장소의 주요 구조와 진입점을 근거와 함께 요약해줘.' },
            { label: '오류 원인', prompt: '이 오류가 발생할 수 있는 코드 경로와 수정 후보를 근거와 함께 알려줘.' },
            { label: '참조 추적', prompt: '이 기능을 호출하는 위치와 영향 범위를 파일/라인 근거와 함께 추적해줘.' },
            { label: '변경 영향', prompt: '이 코드를 변경하면 영향을 받을 수 있는 모듈과 테스트 포인트를 알려줘.' },
          ]}
          evidenceRenderer={(turn) => <CodeEvidenceList evidence={turn.evidence} onOpenEvidence={openCodeFile} />}
          onSaveAnswer={props.saveAnswer}
          onCancel={props.cancelCodeAsk}
          answerSavedId={props.answerSavedId}
          saveLoading={loading('save-code-answer')}
          streamAnchorRef={answerStreamAnchorRef}
        />
        <form className="panel search-panel rag-search-panel" onSubmit={searchCode}>
          <div className="panel-title">
            <Search size={18} />
            <div>
              <h2>코드 검색</h2>
              <p>키워드 검색과 벡터 검색을 함께 사용해 코드 근거를 찾습니다.</p>
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

        <form className="panel reference-panel rag-reference-panel" onSubmit={findReferences}>
          <div className="panel-title">
            <Search size={18} />
            <div>
              <h2>정의와 참조</h2>
              <p>메서드, 클래스, 컴포넌트 이름으로 정의와 사용 위치를 확인합니다.</p>
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
function CodeSourceManagementPanel(props) {
  const {
    repoForm = { authType: 'NONE', gitUrl: '', name: '', branch: 'HEAD', username: '', token: '', storeToken: false },
    setRepoForm = () => {},
    zipForm = { file: null, name: '' },
    setZipForm = () => {},
    zipReplaceFile,
    setZipReplaceFile = () => {},
    indexCredential = { username: '', token: '', storeToken: true },
    setIndexCredential = () => {},
    repositories = [],
    selectedRepositoryId = '',
    setSelectedRepositoryId = () => {},
    selectedRepository,
    jobs = {},
    jobFailures = {},
    loadJobFailures = () => {},
    jobDiagnostics = {},
    loadJobDiagnostics = () => {},
    registerRepository = (event) => event.preventDefault(),
    uploadZipRepository = (event) => event.preventDefault(),
    replaceZipRepository = () => {},
    indexRepository = () => {},
    cancelIndex = () => {},
    deleteRepository = () => {},
    clearFailedJobs = () => {},
    refreshJobs = () => {},
    loading = () => false,
  } = props;

  return (
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
                  <input id="git-username" value={repoForm.username} onChange={(event) => setRepoForm((current) => ({ ...current, username: event.target.value }))} placeholder="비우면 oauth2" />
                </div>
                <div className="stack">
                  <label htmlFor="git-token">Token</label>
                  <input id="git-token" type="password" value={repoForm.token} onChange={(event) => setRepoForm((current) => ({ ...current, token: event.target.value }))} placeholder="개인 액세스 토큰" />
                </div>
              </div>
              <label className="checkbox-row" htmlFor="store-token">
                <input id="store-token" type="checkbox" checked={repoForm.storeToken} onChange={(event) => setRepoForm((current) => ({ ...current, storeToken: event.target.checked }))} />
                <span>토큰을 암호화해 저장하고 다음 인덱싱에 사용</span>
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
        <form className="stack" onSubmit={uploadZipRepository}>
          <div className="panel-title">
            <FileArchive size={18} />
            <div>
            <h2>ZIP 코드 업로드</h2>
              <p>압축 파일을 업로드하면 코드 RAG 저장소로 등록하고 바로 인덱싱합니다.</p>
            </div>
          </div>
          <label htmlFor="zip-file">ZIP 파일</label>
          <div className="file-row">
            <label className="file-picker" htmlFor="zip-file">
              <FileArchive size={16} />
              <span>{zipForm.file?.name || 'ZIP 파일 선택'}</span>
            </label>
            <input id="zip-file" className="visually-hidden" type="file" accept=".zip,application/zip,application/x-zip-compressed" onChange={(event) => setZipForm((current) => ({ ...current, file: event.target.files?.[0] || null }))} />
            <button disabled={!zipForm.file || loading('repo-zip-upload')}>
              {loading('repo-zip-upload') ? <Loader2 className="spin" size={16} /> : <FileArchive size={16} />}
              업로드
            </button>
          </div>
          <label htmlFor="zip-name">표시 이름</label>
          <input id="zip-name" value={zipForm.name} onChange={(event) => setZipForm((current) => ({ ...current, name: event.target.value }))} placeholder={zipForm.file?.name?.replace(/\.zip$/i, '') || 'code snapshot'} />
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
        <div className="document-list scrollable-list repo-list">
          {repositories.map((repo) => {
            const latestJob = jobs[repo.id]?.[0];
            const runningJob = jobs[repo.id]?.find((job) => job.status === 'RUNNING' || job.status === 'CANCELLING');
            return (
              <article className={repo.id === selectedRepositoryId ? 'document-row selected repo-row' : 'document-row repo-row'} key={repo.id} onClick={() => setSelectedRepositoryId(repo.id)}>
                <div className="document-main">
                  <strong>{repo.name}</strong>
                  <small>{repo.sourceType === 'ZIP' ? repo.sourceLabel : repo.gitUrl}</small>
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
                    diagnostics={jobDiagnostics[latestJob.id]}
                    loadDiagnostics={loadJobDiagnostics}
                    diagnosticsLoading={loading(`job-diagnostics-${latestJob.id}`)}
                  />
                )}
                {repo.id === selectedRepositoryId && repo.authType === 'TOKEN' && (
                  <RepoCredentialInlinePanel
                    repository={repo}
                    indexCredential={indexCredential}
                    setIndexCredential={setIndexCredential}
                  />
                )}
              </article>
            );
          })}
          {repositories.length === 0 && <p className="empty">Git URL을 등록하거나 ZIP 파일을 업로드해 인덱싱을 시작하세요.</p>}
        </div>
      </section>

      {selectedRepository?.sourceType === 'ZIP' && (
        <section className="panel compact-auth-panel">
          <form className="stack" onSubmit={(event) => replaceZipRepository(selectedRepository.id, event)}>
            <label htmlFor="replace-zip-file">새 ZIP 파일</label>
            <input id="replace-zip-file" type="file" accept=".zip,application/zip,application/x-zip-compressed" onChange={(event) => setZipReplaceFile(event.target.files?.[0] || null)} />
            <div className="action-row">
              <button disabled={!zipReplaceFile || loading(`repo-zip-replace-${selectedRepository.id}`)}>
                {loading(`repo-zip-replace-${selectedRepository.id}`) ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
                새 ZIP으로 재인덱싱
              </button>
            </div>
          </form>
        </section>
      )}

    </div>
  );
}

function RepoCredentialInlinePanel({ repository, indexCredential, setIndexCredential }) {
  return (
    <div className="detail-box compact-box" onClick={(event) => event.stopPropagation()}>
      <strong>인덱싱 인증</strong>
      <small>
        {repository.credentialStored
          ? '저장된 토큰을 사용합니다. 새 토큰으로 교체할 때만 입력하세요.'
          : '저장된 토큰이 없습니다. 비공개 저장소를 인덱싱하려면 토큰을 입력하세요.'}
      </small>
      <div className="form-grid two">
        <div className="stack">
          <label htmlFor={`index-username-${repository.id}`}>Username</label>
          <input
            id={`index-username-${repository.id}`}
            value={indexCredential.username}
            onChange={(event) => setIndexCredential((current) => ({ ...current, username: event.target.value }))}
            placeholder="비우면 oauth2"
          />
        </div>
        <div className="stack">
          <label htmlFor={`index-token-${repository.id}`}>Token</label>
          <input
            id={`index-token-${repository.id}`}
            type="password"
            value={indexCredential.token}
            onChange={(event) => setIndexCredential((current) => ({ ...current, token: event.target.value }))}
            placeholder={repository.credentialStored ? '새 토큰으로 갱신할 때만 입력' : '인덱싱에 사용할 token'}
          />
        </div>
      </div>
      <label className="checkbox-row" htmlFor={`index-store-token-${repository.id}`}>
        <input
          id={`index-store-token-${repository.id}`}
          type="checkbox"
          checked={indexCredential.storeToken}
          onChange={(event) => setIndexCredential((current) => ({ ...current, storeToken: event.target.checked }))}
        />
        <span>입력한 토큰을 암호화해 저장</span>
      </label>
    </div>
  );
}

function JobStrip({ job, repoId, failures, loadFailures, loading, diagnostics, loadDiagnostics, diagnosticsLoading }) {
  const canShowFailures = job.failedFiles > 0 || job.status === 'FAILED' || job.errorMessage;
  return (
    <div className="job-strip">
      <span>
        {getStatusLabel(job.status)} {'·'} {job.processedFiles}/{job.totalFiles || '-'} files {'·'} {job.totalChunks} chunks
        {job.failedFiles > 0 ? ` · ${'실패'} ${job.failedFiles}` : ''}
      </span>
      {jobChangeText(job) && <small className="job-change-line">{jobChangeText(job)}</small>}
      <EnrichmentStatusLine job={job} />
      <div className="progress-track" aria-label={'인덱싱 진행률'}>
        <span style={{ width: `${jobPercent(job)}%` }} />
      </div>
      {job.errorMessage && <div className="failure-line"><AlertTriangle size={14} />{job.errorMessage}</div>}
      {canShowFailures && (
        <button className="ghost-button compact-action" type="button" onClick={(event) => { event.stopPropagation(); loadFailures(repoId, job.id); }}>
          {loading ? <Loader2 className="spin" size={14} /> : <Eye size={14} />}
          {'실패 사유'}
        </button>
      )}
      <button className="ghost-button compact-action" type="button" onClick={(event) => { event.stopPropagation(); loadDiagnostics(repoId, job.id); }}>
        {diagnosticsLoading ? <Loader2 className="spin" size={14} /> : <Info size={14} />}
        분석 진단
      </button>
      {failures && <JobFailureList failures={failures} />}
      {diagnostics && <JobDiagnosticList diagnostics={diagnostics} />}
    </div>
  );
}

function JobDiagnosticList({ diagnostics }) {
  if (!diagnostics.length) {
    return <p className="empty compact-empty">기록된 분석 진단이 없습니다.</p>;
  }
  return (
    <div className="failure-list">
      {diagnostics.map((diagnostic) => (
        <div className="failure-item" key={diagnostic.id}>
          <strong>{diagnostic.stage} · {diagnostic.status}</strong>
          <small>{diagnostic.mode || diagnostic.analyzer} · {diagnostic.durationMillis}ms</small>
          <span>
            files {diagnostic.analyzedFiles}/{diagnostic.attemptedFiles} · relations {diagnostic.resolvedRelations}
            {diagnostic.unresolvedRelations > 0 ? ` · unresolved ${diagnostic.unresolvedRelations}` : ''}
          </span>
          {diagnostic.metadata?.failedProjects > 0 && (
            <span>C# project parse failures: {diagnostic.metadata.failedProjects}</span>
          )}
          {diagnostic.metadata?.fallbackFiles > 0 && (
            <span>C# files outside safe project inputs: {diagnostic.metadata.fallbackFiles}</span>
          )}
          {diagnostic.message && <span>{diagnostic.message}</span>}
        </div>
      ))}
    </div>
  );
}

function JobFailureList({ failures }) {
  if (!failures.length) {
    return <p className="empty compact-empty">{'기록된 파일별 실패 사유가 없습니다. 저장소 수준 오류 메시지를 확인하세요.'}</p>;
  }
  return (
    <div className="failure-list">
      {failures.map((failure) => (
        <div className="failure-item" key={failure.id}>
          <strong>{failure.filePath || 'repository'}</strong>
          <small>{failure.stage} {'·'} {formatDate(failure.createdAt)}</small>
          <span>{failure.message}</span>
        </div>
      ))}
    </div>
  );
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
    PENDING: '대기',
    RUNNING: '실행 중',
    RETRYING: '재시도 예정',
    SUCCEEDED: '완료',
    FAILED: '실패',
    SKIPPED: '건너뜀',
    NOT_STARTED: '',
  };
  return labels[status] ?? status ?? '';
}

function RepositorySelect({ repositories, selectedRepository, selectedRepositoryId, setSelectedRepositoryId }) {
  const repositoryMeta = selectedRepository
    ? `${selectedRepository.name}${selectedRepository.lastIndexedCommit ? ` · commit ${selectedRepository.lastIndexedCommit.slice(0, 12)}` : ''}`
    : '전체 저장소';
  return (
    <div className="stack">
      <label className="rag-repo-label-row" htmlFor="repo-select">
        <span>질문 대상</span>
        <small className="rag-repo-inline-meta">{repositoryMeta}</small>
      </label>
      <select id="repo-select" value={selectedRepositoryId} onChange={(event) => setSelectedRepositoryId(event.target.value)}>
        <option value="">전체 저장소</option>
        {repositories.map((repo) => (
          <option key={repo.id} value={repo.id}>{repo.name}</option>
        ))}
      </select>
    </div>
  );
}

function CodeEvidenceList({ evidence = [], onOpenEvidence }) {
  const [expanded, setExpanded] = useState(false);
  const evidenceKey = evidence.map((item) => item.chunkId || item.filePath || item.citationNumber).join('|');
  useEffect(() => {
    setExpanded(false);
  }, [evidenceKey]);
  if (!evidence.length) return <p className="empty compact-empty">{'표시할 코드 근거가 없습니다.'}</p>;
  const groupedEvidence = groupCodeEvidence(evidence);
  const visibleEvidence = expanded ? groupedEvidence : groupedEvidence.slice(0, evidencePreviewLimit);
  const hiddenCount = Math.max(groupedEvidence.length - visibleEvidence.length, 0);
  return (
    <div className={expanded ? 'evidence-section evidence-section-expanded' : 'evidence-section'}>
      <div className="evidence-header">
        <strong>{'코드 근거'}</strong>
        <small>{visibleEvidence.length}/{groupedEvidence.length}{'개 파일 표시'}</small>
      </div>
      <div className="evidence-list">
        {visibleEvidence.map((group) => {
          const item = group.primary;
          const canOpen = Boolean(item.repositoryId && item.fileId);
          const primaryRange = codeEvidenceRange(item);
          const groupRanges = codeEvidenceRanges(group.items);
          const openRanges = groupRanges.length ? groupRanges : primaryRange;
          const metaText = group.items.length > 1
            ? `${group.items.length}개 근거 · ${group.locationSummary}`
            : codeEvidenceMetaText(item);
          return (
            <article className="evidence-card code-evidence" key={group.evidenceKey}>
              <div className="result-heading">
                <strong title={item.filePath}>[{group.citationNumbers.join(', ')}] {item.filePath}</strong>
                {canOpen && (
                  <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, openRanges)}>
                    <Eye size={14} />
                    {'열기'}
                  </button>
                )}
              </div>
              <small>{metaText}</small>
              <p>{item.preview}</p>
              {group.items.length > 1 && (
                <div className="code-evidence-locations">
                  {group.items.map((part) => {
                    const partRange = codeEvidenceRange(part);
                    return (
                      <button
                        className="ghost-button compact-action"
                        disabled={!part.repositoryId || !part.fileId}
                        key={`${part.citationNumber}-${part.chunkId || part.lineStart || part.metadata?.changeType || 'part'}`}
                        type="button"
                        onClick={() => onOpenEvidence?.(part.repositoryId, part.fileId, partRange)}
                      >
                        [{part.citationNumber}] {codeEvidenceMetaText(part)}
                      </button>
                    );
                  })}
                </div>
              )}
            </article>
          );
        })}
      </div>
      {groupedEvidence.length > evidencePreviewLimit && (
        <button className="ghost-button compact-action evidence-toggle" type="button" onClick={() => setExpanded((current) => !current)}>
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          {expanded ? '핵심 근거만 보기' : `전체 근거 파일 ${groupedEvidence.length}개 보기`}
          {!expanded && hiddenCount > 0 ? <span>+{hiddenCount}</span> : null}
        </button>
      )}
    </div>
  );
}

function groupCodeEvidence(evidence = []) {
  const grouped = new Map();
  evidence.forEach((item, index) => {
    const key = codeEvidenceGroupKey(item, index);
    const current = grouped.get(key);
    if (!current) {
      grouped.set(key, {
        evidenceKey: key,
        primary: item,
        items: [item],
        citationNumbers: [item.citationNumber],
        locationSummary: codeEvidenceLocationSummary([item]),
      });
      return;
    }
    current.items.push(item);
    if (!current.citationNumbers.includes(item.citationNumber)) {
      current.citationNumbers.push(item.citationNumber);
    }
    if (Number(item.score || 0) > Number(current.primary.score || 0)) {
      current.primary = item;
    }
    current.locationSummary = codeEvidenceLocationSummary(current.items);
  });
  return Array.from(grouped.values());
}

function codeEvidenceGroupKey(item = {}, index = 0) {
  if (item.repositoryId && item.fileId) return `${item.repositoryId}:${item.fileId}`;
  if (item.repositoryName || item.filePath) return `${item.repositoryName || ''}:${item.filePath || ''}`;
  return item.chunkId || `code-evidence-${index}`;
}

function codeEvidenceRange(item = {}) {
  return item.lineStart > 0
    ? { start: item.lineStart, end: item.lineEnd || item.lineStart }
    : null;
}

function codeEvidenceRanges(items = []) {
  const seen = new Set();
  return items
    .map(codeEvidenceRange)
    .filter(Boolean)
    .filter((range) => {
      const key = `${range.start}-${range.end}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((left, right) => left.start - right.start || left.end - right.end);
}

function codeEvidenceMetaText(item = {}) {
  const isCommitDiff = item.metadata?.kind === 'commit_diff';
  if (isCommitDiff) {
    return `${item.metadata?.changeType || item.chunkType} · +${item.metadata?.insertions ?? 0}/-${item.metadata?.deletions ?? 0}`;
  }
  const location = item.lineStart > 0 ? `${item.lineStart}-${item.lineEnd || item.lineStart}` : 'lines -';
  return `${location} · ${item.chunkType || 'code'}`;
}

function codeEvidenceLocationSummary(items = []) {
  const values = items
    .map((item) => item.lineStart > 0 ? `${item.lineStart}-${item.lineEnd || item.lineStart}` : item.metadata?.changeType || item.chunkType)
    .filter(Boolean);
  return values.slice(0, 4).join(', ') + (values.length > 4 ? ` +${values.length - 4}` : '');
}

function CodeSearchResults({ results = [], onOpenEvidence }) {
  const columns = [
    {
      accessorKey: 'filePath',
      header: '파일',
      cell: ({ row }) => (
        <div className="code-table-file">
          <strong>{row.original.filePath}</strong>
          <small>{row.original.repositoryName}</small>
        </div>
      ),
    },
    {
      accessorKey: 'lineStart',
      header: '라인',
      cell: ({ row }) => <Badge variant="outline">{row.original.lineStart}-{row.original.lineEnd}</Badge>,
    },
    {
      accessorKey: 'score',
      header: '점수',
      cell: ({ row }) => Number(row.original.score || 0).toFixed(3),
    },
    {
      id: 'actions',
      header: '',
      cell: ({ row }) => (
        <Button
          size="sm"
          variant="outline"
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            onOpenEvidence?.(row.original.repositoryId, row.original.fileId, { start: row.original.lineStart, end: row.original.lineEnd });
          }}
        >
          <Eye size={14} />
          {'열기'}
        </Button>
      ),
    },
  ];
  return (
    <DataTable
      className="code-search-table"
      columns={columns}
      data={results}
      empty={'코드 검색 결과가 없습니다.'}
      onRowClick={(item) => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}
    />
  );
}

function CodeReferenceResults({ result, onOpenEvidence }) {
  return (
    <div className="reference-results">
      <ReferenceGroup title={'정의'} items={result.definitions || []} onOpenEvidence={onOpenEvidence} />
      <ReferenceGroup title={'참조'} items={result.references || []} onOpenEvidence={onOpenEvidence} />
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
              {'열기'}
            </button>
          </div>
          <small>{item.lineStart}-{item.lineEnd} {'·'} {item.chunkType}</small>
          <p>{item.content}</p>
        </article>
      ))}
      {!items.length && <p className="empty compact-empty">{'결과 없음'}</p>}
    </div>
  );
}

function CodeFileModal({ detail, highlightRange, loading, onClose }) {
  const highlightedLineRef = useRef(null);
  const lines = detail?.content ? detail.content.split(/\r?\n/) : [];
  const highlightRanges = normalizeHighlightRanges(highlightRange);
  const firstHighlightRange = highlightRanges[0] || null;
  const fileName = detail?.filePath?.split(/[\\/]/).pop() || 'code';
  const language = detail?.language || 'code';
  const syntaxLanguage = highlightLanguage(detail?.filePath, language);
  const renderedLines = useMemo(
    () => lines.map((line) => parseHighlightedLine(highlightedLineHtml(line, syntaxLanguage), line)),
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
    if (loading || !firstHighlightRange?.start || !highlightedLineRef.current) return undefined;
    const timer = window.setTimeout(() => {
      highlightedLineRef.current?.scrollIntoView({ block: 'center' });
    }, 80);
    return () => window.clearTimeout(timer);
  }, [detail?.id, firstHighlightRange?.start, firstHighlightRange?.end, loading]);

  const modal = (
    <div className="code-modal-backdrop source-modal-portal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal" role="dialog" aria-modal="true" aria-labelledby="code-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            <FileCode2 size={18} />
            <div>
              <h2 id="code-modal-title">{fileName}</h2>
              <p>{detail?.filePath || '코드 파일을 불러오는 중입니다.'}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title={'닫기'} onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>

        <div className="code-modal-tabs" aria-hidden="true">
          <span className="active-tab">{fileName}</span>
          <span>{language}</span>
          {highlightRanges.length > 0 && <span>{highlightRangeLabel(highlightRanges)}</span>}
        </div>

        <div className="code-modal-body">
          {loading && (
            <div className="code-modal-state">
              <Loader2 className="spin" size={22} />
              <strong>{'코드 파일을 불러오는 중입니다.'}</strong>
            </div>
          )}

          {!loading && !detail && (
            <div className="code-modal-state">
              <FileCode2 size={22} />
              <strong>{'표시할 코드가 없습니다.'}</strong>
            </div>
          )}

          {!loading && detail && (
            <pre className="ide-code-viewer">
              <code>
                {lines.map((line, index) => {
                  const lineNumber = index + 1;
                  const highlighted = isLineHighlighted(lineNumber, highlightRanges);
                  return (
                    <div
                      className={highlighted ? 'ide-code-line highlighted-line' : 'ide-code-line'}
                      key={lineNumber}
                      ref={lineNumber === firstHighlightRange?.start ? highlightedLineRef : null}
                    >
                      <span className="ide-line-number">{lineNumber}</span>
                      <span className="ide-line-content">{renderedLines[index] || SPACE_PLACEHOLDER}</span>
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

  if (typeof document === 'undefined') return modal;
  return createPortal(modal, document.body);
}

function CodeFileViewer({ detail, highlightRange, loading }) {
  if (loading) {
    return (
      <section className="panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>{'코드 미리보기'}</h2>
            <p>{'파일을 불러오는 중입니다.'}</p>
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
            <h2>{'코드 미리보기'}</h2>
            <p>{'파일이나 근거를 선택하면 원문 코드를 확인할 수 있습니다.'}</p>
          </div>
        </div>
      </section>
    );
  }
  const lines = detail.content.split(/\r?\n/);
  const highlightRanges = normalizeHighlightRanges(highlightRange);
  return (
    <section className="panel">
      <div className="panel-title">
        <FileCode2 size={18} />
        <div>
          <h2>{detail.filePath}</h2>
          <p>{detail.language} {'·'} {detail.chunks?.length || 0} chunks</p>
        </div>
      </div>
      <pre className="code-viewer">
        <code>
          {lines.map((line, index) => {
            const lineNumber = index + 1;
            const highlighted = isLineHighlighted(lineNumber, highlightRanges);
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

function normalizeHighlightRanges(highlightRange) {
  const ranges = Array.isArray(highlightRange) ? highlightRange : highlightRange ? [highlightRange] : [];
  return ranges
    .map((range) => ({
      start: Number(range?.start || 0),
      end: Number(range?.end || range?.start || 0),
    }))
    .filter((range) => range.start > 0 && range.end >= range.start)
    .sort((left, right) => left.start - right.start || left.end - right.end);
}

function isLineHighlighted(lineNumber, ranges = []) {
  return ranges.some((range) => lineNumber >= range.start && lineNumber <= range.end);
}

function highlightRangeLabel(ranges = []) {
  if (!ranges.length) return '';
  if (ranges.length === 1) return `lines ${ranges[0].start}-${ranges[0].end}`;
  return `${ranges.length} ranges · ${ranges.slice(0, 3).map((range) => `${range.start}-${range.end}`).join(', ')}${ranges.length > 3 ? ` +${ranges.length - 3}` : ''}`;
}

export { CodeSourceManagementPanel, CodeWorkspace };





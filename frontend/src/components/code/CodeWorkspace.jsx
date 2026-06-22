import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Bookmark, CheckCircle2, ChevronDown, ChevronUp, Eye, FileArchive, FileCode2, GitBranch, Info, Loader2, Maximize2, MessageSquare, RefreshCw, Search, Trash2, X } from 'lucide-react';
import { codeModes, evidencePreviewLimit } from '../../config/constants.js';
import { formatDate, getCodeModeGuide, getCodeModeLabel, getStatusLabel, jobChangeText, jobPercent, submitFormOnShortcut } from '../../lib/formatters.js';
import { escapeHtml, highlightLanguage, highlightedLineHtml } from '../../lib/highlight.js';
import { AnswerStatus, IconButton, ModeControl, StatusBadge } from '../common/Common.jsx';
import { AnswerModal } from '../common/AnswerModal.jsx';
import { RagAskComposer } from '../common/RagAskComposer.jsx';
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
  const [answerModalOpen, setAnswerModalOpen] = useState(false);

  return (
    <section className="workspace-grid code-grid workspace-product code-workspace-product">
      <div className="workspace-product-hero code-product-hero">
        <div>
          <Badge variant="secondary">Code RAG</Badge>
          <h1>코드 어시스턴트</h1>
          <p>저장소의 실제 파일, 심볼, 참조 위치를 근거로 코드 질문에 답합니다.</p>
        </div>
        <div className="workspace-product-metrics" aria-label="코드 RAG 상태 요약">
          <span><strong>{repositories.length}</strong> repositories</span>
          <span><strong>{codeSearchResults.length}</strong> search hits</span>
          <span><strong>{referenceResult ? (referenceResult.definitions?.length || 0) + (referenceResult.references?.length || 0) : 0}</strong> references</span>
        </div>
      </div>
      {showSourceManagement && <CodeSourceManagementPanel {...props} />}

      <div className={showSourceManagement ? 'right-column' : 'right-column full-column'}>
        <form className="panel ask-panel rag-command-panel" onSubmit={askCode}>
          <RagAskComposer
            title="코드에게 질문하기"
            description="파일, 클래스, 메서드, UI 이벤트 흐름을 실제 코드 근거와 함께 분석합니다."
            icon={<MessageSquare size={18} />}
            controls={(
              <>
                <RepositorySelect repositories={repositories} selectedRepositoryId={selectedRepositoryId} setSelectedRepositoryId={setSelectedRepositoryId} />
                <ModeControl modes={codeModes} value={codeMode} setValue={setCodeMode} className="code-mode-control" />
              </>
            )}
            guide={null}
            value={codeQuestion}
            setValue={setCodeQuestion}
            onKeyDown={(event) => submitFormOnShortcut(event, Boolean(codeQuestion.trim()) && !loading('code-ask'))}
            placeholder={activeCodeModeGuide.placeholder}
            loading={loading('code-ask')}
            disabled={!codeQuestion.trim()}
            submitLabel="코드 질문"
            templates={[
              { label: '구조 요약', prompt: '선택한 저장소의 주요 구조와 진입점을 근거와 함께 요약해줘.' },
              { label: '오류 원인', prompt: '이 오류가 발생할 수 있는 코드 경로와 수정 후보를 근거와 함께 알려줘.' },
              { label: '참조 추적', prompt: '이 기능이 호출되는 위치와 영향 범위를 파일/라인 근거와 함께 추적해줘.' },
              { label: '변경 영향', prompt: '이 코드를 변경하면 영향을 받을 수 있는 모듈과 테스트 포인트를 알려줘.' },
            ]}
            footer={selectedRepository && (
              <div className="detail-box compact-box">
                <strong>{selectedRepository.name}</strong>
                <small>{selectedRepository.lastIndexedCommit ? `commit ${selectedRepository.lastIndexedCommit.slice(0, 12)}` : '아직 인덱싱된 commit이 없습니다.'}</small>
              </div>
            )}
          />
          <div className="panel-title">
            <MessageSquare size={18} />
            <div>
              <h2>코드에게 질문하기</h2>
              <p>파일, 클래스, 메서드, UI 이벤트 이름을 실제 코드 근거와 함께 분석합니다.</p>
            </div>
          </div>
          <RepositorySelect repositories={repositories} selectedRepositoryId={selectedRepositoryId} setSelectedRepositoryId={setSelectedRepositoryId} />
          <ModeControl modes={codeModes} value={codeMode} setValue={setCodeMode} className="code-mode-control" />
          <div className="code-question-toolbar">
            <button className="code-ask-button" disabled={!codeQuestion || loading('code-ask')}>
              {loading('code-ask') ? <Loader2 className="spin" size={15} /> : <MessageSquare size={15} />}
              코드 질문
            </button>
          </div>
          <textarea
            value={codeQuestion}
            onChange={(event) => setCodeQuestion(event.target.value)}
            onKeyDown={(event) => submitFormOnShortcut(event, Boolean(codeQuestion.trim()) && !loading('code-ask'))}
            placeholder={activeCodeModeGuide.placeholder}
          />
          {selectedRepository && (
            <div className="detail-box compact-box">
              <strong>{selectedRepository.name}</strong>
              <small>{selectedRepository.lastIndexedCommit ? `commit ${selectedRepository.lastIndexedCommit.slice(0, 12)}` : '아직 인덱싱된 commit이 없습니다.'}</small>
            </div>
          )}
          {codeAnswer && (
            <div className="answer code-answer">
              <div className="answer-title">
                <div className="answer-title-main">
                  <CheckCircle2 size={16} />
                  <strong>{getCodeModeLabel(codeAnswer.mode)} 답변</strong>
                </div>
                <div className="answer-actions">
                  <button className="icon-button answer-expand-button" type="button" title={props.answerSavedId ? '저장됨' : '답변 저장'} disabled={props.answerSavedId || loading('save-code-answer')} onClick={props.saveAnswer}>
                    {loading('save-code-answer') ? <Loader2 className="spin" size={15} /> : <Bookmark size={15} />}
                  </button>
                  <button className="icon-button answer-expand-button" type="button" title="전체 화면으로 보기" onClick={() => setAnswerModalOpen(true)}>
                    <Maximize2 size={15} />
                  </button>
                </div>
              </div>
              <AnswerStatus confidence={codeAnswer.confidence} diagnostics={codeAnswer.diagnostics} />
              <div className="answer-body">
                <MarkdownAnswer text={codeAnswer.answer} />
              </div>
              <CodeEvidenceList evidence={codeAnswer.evidence} onOpenEvidence={openCodeFile} />
            </div>
          )}
          {answerModalOpen && codeAnswer && (
            <AnswerModal
              title={`${getCodeModeLabel(codeAnswer.mode)} 답변`}
              subtitle={selectedRepository?.name || '코드 답변'}
              answer={codeAnswer.answer}
              className="code-answer-modal"
              bodyClassName="code-answer-modal-body"
              onClose={() => setAnswerModalOpen(false)}
            />
          )}
        </form>

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
                <span>토큰을 암호화해 저장하고 다음 인덱싱에 재사용</span>
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
              <h2>ZIP 코드 스냅샷 업로드</h2>
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
            <label htmlFor="replace-zip-file">새 ZIP 스냅샷</label>
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
        {getStatusLabel(job.status)} {'\u00B7'} {job.processedFiles}/{job.totalFiles || '-'} files {'\u00B7'} {job.totalChunks} chunks
        {job.failedFiles > 0 ? ` \u00B7 ${'\uC2E4\uD328'} ${job.failedFiles}` : ''}
      </span>
      {jobChangeText(job) && <small className="job-change-line">{jobChangeText(job)}</small>}
      <EnrichmentStatusLine job={job} />
      <div className="progress-track" aria-label={'\uC778\uB371\uC2F1 \uC9C4\uD589\uB960'}>
        <span style={{ width: `${jobPercent(job)}%` }} />
      </div>
      {job.errorMessage && <div className="failure-line"><AlertTriangle size={14} />{job.errorMessage}</div>}
      {canShowFailures && (
        <button className="ghost-button compact-action" type="button" onClick={(event) => { event.stopPropagation(); loadFailures(repoId, job.id); }}>
          {loading ? <Loader2 className="spin" size={14} /> : <Eye size={14} />}
          {'\uC2E4\uD328 \uC0AC\uC720'}
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
            파일 {diagnostic.analyzedFiles}/{diagnostic.attemptedFiles} · 관계 {diagnostic.resolvedRelations}개
            {diagnostic.unresolvedRelations > 0 ? ` · 미해결 ${diagnostic.unresolvedRelations}개` : ''}
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
    return <p className="empty compact-empty">{'\uAE30\uB85D\uB41C \uD30C\uC77C\uBCC4 \uC2E4\uD328 \uC0AC\uC720\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4. \uC800\uC7A5\uC18C \uC218\uC900 \uC624\uB958 \uBA54\uC2DC\uC9C0\uB97C \uD655\uC778\uD558\uC138\uC694.'}</p>;
  }
  return (
    <div className="failure-list">
      {failures.map((failure) => (
        <div className="failure-item" key={failure.id}>
          <strong>{failure.filePath || 'repository'}</strong>
          <small>{failure.stage} {'\u00B7'} {formatDate(failure.createdAt)}</small>
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
    PENDING: '품질 보강 대기',
    RUNNING: '품질 보강 중',
    RETRYING: '품질 보강 재시도 예정',
    SUCCEEDED: '품질 보강 완료',
    FAILED: '품질 보강 실패',
    SKIPPED: '품질 보강 생략',
    NOT_STARTED: '',
  };
  return labels[status] ?? status ?? '';
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

function CodeEvidenceList({ evidence = [], onOpenEvidence }) {
  const [expanded, setExpanded] = useState(false);
  const evidenceKey = evidence.map((item) => item.chunkId || item.filePath || item.citationNumber).join('|');
  useEffect(() => {
    setExpanded(false);
  }, [evidenceKey]);
  if (!evidence.length) return <p className="empty compact-empty">{'\uD45C\uC2DC\uD560 \uCF54\uB4DC \uADFC\uAC70\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.'}</p>;
  const visibleEvidence = expanded ? evidence : evidence.slice(0, evidencePreviewLimit);
  const hiddenCount = Math.max(evidence.length - visibleEvidence.length, 0);
  return (
    <div className={expanded ? 'evidence-section evidence-section-expanded' : 'evidence-section'}>
      <div className="evidence-header">
        <strong>{'\uCF54\uB4DC \uADFC\uAC70'}</strong>
        <small>{visibleEvidence.length}/{evidence.length}{'\uAC1C \uD45C\uC2DC'}</small>
      </div>
      <div className="evidence-list">
        {visibleEvidence.map((item) => {
          const isCommitDiff = item.metadata?.kind === 'commit_diff';
          const canOpen = Boolean(item.repositoryId && item.fileId);
          const range = item.lineStart > 0
            ? { start: item.lineStart, end: item.lineEnd || item.lineStart }
            : null;
          const metaText = isCommitDiff
            ? `${item.metadata?.changeType || item.chunkType} \u00B7 +${item.metadata?.insertions ?? 0}/-${item.metadata?.deletions ?? 0}`
            : `${item.lineStart}-${item.lineEnd} \u00B7 ${item.chunkType}`;
          return (
            <article className="evidence-card code-evidence" key={`${item.citationNumber}-${item.chunkId || item.filePath || 'commit'}`}>
              <div className="result-heading">
                <strong title={item.filePath}>[{item.citationNumber}] {item.filePath}</strong>
                {canOpen && (
                  <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, range)}>
                    <Eye size={14} />
                    {'\uC5F4\uAE30'}
                  </button>
                )}
              </div>
              <small>{metaText}</small>
              <p>{item.preview}</p>
            </article>
          );
        })}
      </div>
      {evidence.length > evidencePreviewLimit && (
        <button className="ghost-button compact-action evidence-toggle" type="button" onClick={() => setExpanded((current) => !current)}>
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          {expanded ? '\uD575\uC2EC \uADFC\uAC70\uB9CC \uBCF4\uAE30' : `\uC804\uCCB4 \uADFC\uAC70 ${evidence.length}\uAC1C \uBCF4\uAE30`}
          {!expanded && hiddenCount > 0 ? <span>+{hiddenCount}</span> : null}
        </button>
      )}
    </div>
  );
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
          {'\uC5F4\uAE30'}
        </Button>
      ),
    },
  ];
  return (
    <DataTable
      className="code-search-table"
      columns={columns}
      data={results}
      empty={'\uCF54\uB4DC \uAC80\uC0C9 \uACB0\uACFC\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.'}
      onRowClick={(item) => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}
    />
  );
}

function CodeReferenceResults({ result, onOpenEvidence }) {
  return (
    <div className="reference-results">
      <ReferenceGroup title={'\uC815\uC758'} items={result.definitions || []} onOpenEvidence={onOpenEvidence} />
      <ReferenceGroup title={'\uCC38\uC870'} items={result.references || []} onOpenEvidence={onOpenEvidence} />
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
              {'\uC5F4\uAE30'}
            </button>
          </div>
          <small>{item.lineStart}-{item.lineEnd} {'\u00B7'} {item.chunkType}</small>
          <p>{item.content}</p>
        </article>
      ))}
      {!items.length && <p className="empty compact-empty">{'\uACB0\uACFC \uC5C6\uC74C'}</p>}
    </div>
  );
}

function CodeFileModal({ detail, highlightRange, loading, onClose }) {
  const highlightedLineRef = useRef(null);
  const lines = detail?.content ? detail.content.split(/\r?\n/) : [];
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
              <p>{detail?.filePath || '\uCF54\uB4DC \uD30C\uC77C\uC744 \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4.'}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title={'\uB2EB\uAE30'} onClick={() => onClose?.()}>
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
              <strong>{'\uCF54\uB4DC \uD30C\uC77C\uC744 \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4.'}</strong>
            </div>
          )}

          {!loading && !detail && (
            <div className="code-modal-state">
              <FileCode2 size={22} />
              <strong>{'\uD45C\uC2DC\uD560 \uCF54\uB4DC\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.'}</strong>
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
}

function CodeFileViewer({ detail, highlightRange, loading }) {
  if (loading) {
    return (
      <section className="panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>{'\uCF54\uB4DC \uBBF8\uB9AC\uBCF4\uAE30'}</h2>
            <p>{'\uD30C\uC77C\uC744 \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4.'}</p>
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
            <h2>{'\uCF54\uB4DC \uBBF8\uB9AC\uBCF4\uAE30'}</h2>
            <p>{'\uD30C\uC77C\uC774\uB098 \uADFC\uAC70\uB97C \uC120\uD0DD\uD558\uBA74 \uC6D0\uBB38 \uCF54\uB4DC\uB97C \uD655\uC778\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.'}</p>
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
          <p>{detail.language} {'\u00B7'} {detail.chunks?.length || 0} chunks</p>
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

export { CodeSourceManagementPanel, CodeWorkspace };

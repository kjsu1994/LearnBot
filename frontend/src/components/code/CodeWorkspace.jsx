import { useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, CheckCircle2, ChevronDown, ChevronUp, Eye, FileCode2, GitBranch, GitPullRequest, Info, Loader2, MessageSquare, RefreshCw, Search, Trash2, X } from 'lucide-react';
import { codeModes, evidencePreviewLimit } from '../../config/constants.js';
import { formatDate, getCodeModeGuide, getCodeModeLabel, getStatusLabel, jobChangeText, jobPercent, submitFormOnShortcut } from '../../lib/formatters.js';
import { highlightLanguage, highlightedLineHtml } from '../../lib/highlight.js';
import { AnswerStatus, IconButton, ModeControl, StatusBadge } from '../common/Common.jsx';
import { QuestionGuide } from '../layout/Layout.jsx';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';

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
          <QuestionGuide guide={activeCodeModeGuide} />
          <textarea
            value={codeQuestion}
            onChange={(event) => setCodeQuestion(event.target.value)}
            onKeyDown={(event) => submitFormOnShortcut(event, Boolean(codeQuestion.trim()) && !loading('code-ask'))}
            placeholder={activeCodeModeGuide.placeholder}
          />
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
              <AnswerStatus confidence={codeAnswer.confidence} diagnostics={codeAnswer.diagnostics} />
              <div className="answer-body">
                <MarkdownAnswer text={codeAnswer.answer} />
              </div>
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

function CodeEvidenceList({ evidence = [], onOpenEvidence }) {
  const [expanded, setExpanded] = useState(false);
  const evidenceKey = evidence.map((item) => item.chunkId || item.filePath || item.citationNumber).join('|');
  useEffect(() => {
    setExpanded(false);
  }, [evidenceKey]);
  if (!evidence.length) return <p className="empty compact-empty">표시할 코드 근거가 없습니다.</p>;
  const visibleEvidence = expanded ? evidence : evidence.slice(0, evidencePreviewLimit);
  const hiddenCount = Math.max(evidence.length - visibleEvidence.length, 0);
  return (
    <div className={expanded ? 'evidence-section evidence-section-expanded' : 'evidence-section'}>
      <div className="evidence-header">
        <strong>코드 근거</strong>
        <small>{visibleEvidence.length}/{evidence.length}개 표시</small>
      </div>
      <div className="evidence-list">
        {visibleEvidence.map((item) => {
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
                <strong title={item.filePath}>[{item.citationNumber}] {item.filePath}</strong>
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
      {evidence.length > evidencePreviewLimit && (
        <button className="ghost-button compact-action evidence-toggle" type="button" onClick={() => setExpanded((current) => !current)}>
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          {expanded ? '핵심 근거만 보기' : `전체 근거 ${evidence.length}개 보기`}
          {!expanded && hiddenCount > 0 ? <span>+{hiddenCount}</span> : null}
        </button>
      )}
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

export { CodeWorkspace };

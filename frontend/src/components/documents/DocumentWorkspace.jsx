import { useEffect, useState } from 'react';
import { Bookmark, CheckCircle2, ChevronDown, ChevronUp, Database, Eye, FileCode2, FileUp, Globe, HelpCircle, Info, Loader2, Maximize2, MessageSquare, Search, X } from 'lucide-react';
import { answerModes, evidencePreviewLimit } from '../../config/constants.js';
import { formatDate, formatFileSize, formatSelectedFiles, getAnswerModeGuide, getAnswerModeLabel, getPreviewTypeLabel, getSourceLabel, getStatusLabel, splitReaderParagraphs, submitFormOnShortcut } from '../../lib/formatters.js';
import { AnswerStatus, IconButton, ModeControl, StatusBadge } from '../common/Common.jsx';
import { AnswerModal } from '../common/AnswerModal.jsx';
import { QuestionGuide } from '../layout/Layout.jsx';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';

function DocumentWorkspace(props) {
  const activeAnswerModeGuide = getAnswerModeGuide(props.answerMode);
  const [answerModalOpen, setAnswerModalOpen] = useState(false);
  const [webIngestHelpOpen, setWebIngestHelpOpen] = useState(false);
  const showSourceManagement = props.showSourceManagement !== false;

  return (
    <section className="workspace-grid">
      {showSourceManagement && (
      <div className="left-column">
        <section className="panel">
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>문서 소스 추가</h2>
              <p>허용된 웹 URL과 PDF, DOCX, PPTX, Markdown, TXT, CSV, Excel 파일을 RAG 근거로 인덱싱합니다.</p>
            </div>
            <button className="icon-button" type="button" title="웹 수집 옵션 도움말" onClick={() => setWebIngestHelpOpen(true)}>
              <HelpCircle size={16} />
            </button>
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
            <WebCrawlAdvancedOptions
              prefix="web"
              recursive={props.webRecursive}
              allowIgnoreRobots={false}
              crawlScope={props.webCrawlScope}
              setCrawlScope={props.setWebCrawlScope}
              robotsFailurePolicy={props.webRobotsFailurePolicy}
              setRobotsFailurePolicy={props.setWebRobotsFailurePolicy}
              includeAttachments={props.webIncludeAttachments}
              setIncludeAttachments={props.setWebIncludeAttachments}
              useSitemap={props.webUseSitemap}
              setUseSitemap={props.setWebUseSitemap}
              renderMode={props.webRenderMode}
              setRenderMode={props.setWebRenderMode}
            />
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
              <input id="file-upload" className="visually-hidden" type="file" accept=".pdf,.docx,.pptx,.md,.markdown,.txt,.csv,.xls,.xlsx" multiple onChange={(event) => props.setFiles(Array.from(event.target.files || []))} />
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

        <DocumentDetailPanel detail={props.documentDetail} loading={props.selectedDocumentId && props.loading(`detail-${props.selectedDocumentId}`)} />
        {props.documentPreviewOpen && (
          <DocumentPreviewModal
            preview={props.documentPreview}
            blobUrl={props.documentPreviewBlobUrl}
            loading={props.documentPreviewLoading}
            onClose={props.closeDocumentPreview}
          />
        )}
        {webIngestHelpOpen && <WebIngestHelpModal onClose={() => setWebIngestHelpOpen(false)} />}
      </div>
      )}

      <div className={showSourceManagement ? 'right-column' : 'right-column full-column'}>
        <form className="panel ask-panel" onSubmit={props.ask}>
          <div className="panel-title">
            <MessageSquare size={18} />
            <div>
              <h2>문서에게 질문하기</h2>
              <p>현재 선택된 공간의 인덱싱 완료 문서 전체에서 근거를 찾아 답변합니다.</p>
            </div>
          </div>
          <ModeControl modes={answerModes} value={props.answerMode} setValue={props.setAnswerMode} />
          <QuestionGuide guide={activeAnswerModeGuide} />
          <textarea
            value={props.question}
            onChange={(event) => props.setQuestion(event.target.value)}
            onKeyDown={(event) => submitFormOnShortcut(event, Boolean(props.question.trim()) && !props.loading('ask'))}
            placeholder={activeAnswerModeGuide.placeholder}
          />
          <div className="action-row">
            <button disabled={!props.question || props.loading('ask')}>
              {props.loading('ask') ? <Loader2 className="spin" size={16} /> : <MessageSquare size={16} />}
              답변 생성
            </button>
          </div>
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
                  <button className="icon-button answer-expand-button" type="button" title="큰창으로 보기" onClick={() => setAnswerModalOpen(true)}>
                    <Maximize2 size={15} />
                  </button>
                </div>
              </div>
              <small className="answer-mode">{getAnswerModeLabel(props.answer.mode)} 모드</small>
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
    webRenderMode = 'STATIC',
    setWebRenderMode = () => {},
    files = [],
    setFiles = () => {},
    fileBatchResult,
    ingestWeb = (event) => event.preventDefault(),
    ingestFile = (event) => event.preventDefault(),
    loading = () => false,
  } = props;
  const [webIngestHelpOpen, setWebIngestHelpOpen] = useState(false);

  return (
    <section className="panel">
      <div className="panel-title">
        <Database size={18} />
        <div>
          <h2>문서 소스 추가</h2>
          <p>허용된 웹 URL과 PDF, DOCX, PPTX, Markdown, TXT, CSV, Excel 파일을 RAG 근거로 인덱싱합니다.</p>
        </div>
        <button className="icon-button" type="button" title="웹 수집 옵션 도움말" onClick={() => setWebIngestHelpOpen(true)}>
          <HelpCircle size={16} />
        </button>
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
          <span>시작 URL의 하위 경로를 재귀 수집</span>
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
            accept=".pdf,.docx,.pptx,.md,.markdown,.txt,.csv,.xls,.xlsx"
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
      {webIngestHelpOpen && <WebIngestHelpModal onClose={() => setWebIngestHelpOpen(false)} />}
    </section>
  );
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
              <p>수집 범위와 실패 처리 방식에 따라 인덱싱되는 문서 수와 품질이 달라집니다.</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="닫기" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>
        <div className="code-modal-body document-preview-body">
          <div className="document-reader">
            <h3>재귀 수집</h3>
            <p>꺼져 있으면 입력한 URL 한 페이지만 수집합니다. 켜면 페이지 안의 링크를 따라가며 여러 문서를 같은 소스로 묶어 인덱싱합니다. 문서 사이트, 도움말 센터, 정책 페이지처럼 관련 문서가 여러 페이지로 나뉜 경우 켜는 것이 좋습니다.</p>

            <h3>수집 범위</h3>
            <p><strong>시작 경로 하위</strong>는 가장 안전한 기본값입니다. 예를 들어 /docs에서 시작하면 /docs/install은 수집하지만 /blog는 제외합니다.</p>
            <p><strong>같은 호스트 전체</strong>는 같은 도메인의 다른 경로까지 수집합니다. 문서가 /guide, /reference처럼 여러 경로에 나뉘어 있을 때 유용하지만 불필요한 페이지도 늘 수 있습니다.</p>
            <p><strong>같은 사이트</strong>는 하위 도메인까지 넓게 봅니다. docs.example.com과 help.example.com을 함께 수집해야 할 때 사용합니다.</p>
            <p><strong>허용 도메인 전체</strong>는 관리자 설정의 허용 도메인 안에서 교차 링크를 따라갑니다. 가장 넓은 범위라 운영자가 신뢰하는 도메인만 허용 목록에 넣었을 때 사용하세요.</p>

            <h3>robots.txt 조회 실패</h3>
            <p>관리자 설정의 robots.txt 정책 준수가 켜져 있을 때 적용됩니다. <strong>실패 시 차단</strong>은 가장 보수적인 기본값입니다. robots.txt를 읽지 못하면 수집하지 않습니다.</p>
            <p><strong>조회 실패만 허용</strong>은 robots.txt 서버가 일시적으로 실패하거나 느린 경우 문서 수집을 계속합니다. 단, robots.txt가 정상적으로 읽히고 명시적으로 차단한 URL은 계속 수집하지 않습니다.</p>
            <p><strong>robots.txt 무시</strong>는 관리자 소스 등록 화면에서만 선택할 수 있습니다. 해당 수집 요청에서 robots 검사를 건너뛰므로 내부 문서나 명확히 허가된 사이트에서만 사용하는 것이 안전합니다.</p>

            <h3>sitemap.xml 활용</h3>
            <p>사이트가 sitemap.xml을 제공하면 링크를 클릭해서 찾기 어려운 문서도 seed로 추가합니다. 문서 사이트의 누락을 줄이는 데 효과적입니다. sitemap을 못 읽어도 전체 작업은 실패하지 않고 일반 링크 수집으로 폴백합니다.</p>

            <h3>첨부파일 수집</h3>
            <p>웹 페이지에 연결된 PDF, DOCX, PPTX, XLSX, CSV, TXT, Markdown 파일을 함께 인덱싱합니다. 기본값은 꺼짐입니다. 켜면 답변 근거가 풍부해지지만 수집 시간이 길어지고 저장 용량이 늘 수 있습니다. 파일 크기와 형식 제한은 서버 설정을 따릅니다.</p>

            <h3>렌더링 방식</h3>
            <p><strong>정적 HTML</strong>은 빠르고 안정적인 기본값입니다. 서버가 내려준 HTML만 파싱합니다.</p>
            <p><strong>필요 시 Playwright 폴백</strong>은 정적 HTML에 본문이 거의 없거나 SPA 페이지로 보일 때 브라우저 렌더링을 시도하는 모드입니다.</p>
            <p><strong>Playwright 우선</strong>은 처음부터 브라우저 렌더링을 우선합니다. JavaScript로 본문을 만드는 사이트에 유리하지만 느리고 자원 사용량이 큽니다. 현재 런타임에 Playwright가 준비되지 않은 경우 정적 HTML 수집으로 안전하게 폴백하고 audit에 기록됩니다.</p>

            <h3>추천 시작값</h3>
            <p>처음에는 재귀 수집을 켜고, 수집 범위는 <strong>시작 경로 하위</strong>, robots.txt 조회 실패는 <strong>실패 시 차단</strong>, 렌더링 방식은 <strong>정적 HTML</strong>로 시작하세요. 결과가 부족하면 sitemap.xml 활용을 켜고, 문서가 여러 경로에 흩어져 있으면 수집 범위를 같은 호스트 전체로 넓히는 순서가 안전합니다.</p>
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
  renderMode = 'STATIC',
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
          <span>sitemap.xml 활용</span>
        </label>
        <label className="checkbox-row" htmlFor={`${prefix}-attachments`}>
          <input
            id={`${prefix}-attachments`}
            type="checkbox"
            checked={includeAttachments}
            disabled={!recursive}
            onChange={(event) => setIncludeAttachments(event.target.checked)}
          />
          <span>첨부파일 수집</span>
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
          <option value="PLAYWRIGHT_FALLBACK">필요 시 Playwright 폴백</option>
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
      {detail.crawlAudits?.length > 0 && (
        <div className="results audit-list">
          {detail.crawlAudits.slice(0, 8).map((audit) => (
            <article className="result" key={audit.id}>
              <div className="result-heading">
                <strong>{audit.reasonCode || (audit.success ? 'FETCHED' : 'SKIPPED')}</strong>
                <span>{audit.statusCode || '-'}</span>
              </div>
              <small title={audit.url}>
                {audit.host || '-'}{audit.depth != null ? ` · depth ${audit.depth}` : ''}{audit.referrerUrl ? ` · from ${audit.referrerUrl}` : ''}
              </small>
              <p>{audit.message || audit.url}</p>
            </article>
          ))}
        </div>
      )}
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
          {expanded ? '핵심 근거만 보기' : `전체 근거 문서 ${groupedEvidence.length}개 보기`}
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

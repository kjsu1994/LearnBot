import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, Info, Loader2, Search, Eye, Trash2 } from 'lucide-react';
import { IconBook, IconCode, IconDatabase, IconFileText, IconLock, IconLogout, IconRefresh, IconSearch, IconShieldCheck, IconSparkles } from '@tabler/icons-react';
import { routePaths } from '../../config/constants.js';
import { formatBrandText, formatDate, getSourceLabel } from '../../lib/formatters.js';
import { AnimatedContent, AnimatedPage, AnimatedSection, IconButton, StatusBadge } from '../common/Common.jsx';
import { ShaderBackground } from '../effects/ShaderBackground.jsx';
import { Badge } from '../ui/badge.jsx';
import { Button } from '../ui/button.jsx';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card.jsx';
import { MetricBarChart } from '../ui/metric-chart.jsx';

function HomePage({ user, bootstrapping, navigateTo, logout }) {
  const shellRef = useRef(null);
  const visualRef = useRef(null);
  const proofRef = useRef(null);
  const supportedSources = ['PDF', 'DOCX', 'PPTX', 'Markdown', 'Excel', 'CSV', 'Web crawl', 'Git repository', 'Saved answers'];
  const flowSteps = [
    { label: '수집', detail: '문서와 코드를 안전하게 가져옵니다.' },
    { label: '인덱싱', detail: '구조, 메타데이터, 근거 청크를 정리합니다.' },
    { label: '검색', detail: '질문 의도에 맞는 원문 근거를 찾습니다.' },
    { label: '답변', detail: '출처와 함께 검증 가능한 답변을 제공합니다.' },
  ];
  const featureCards = [
    {
      path: routePaths.code,
      icon: <IconCode size={22} />,
      title: 'Code RAG',
      eyebrow: 'CODE',
      description: '저장소를 인덱싱하고 최신 커밋, 호출 흐름, UI 이벤트를 코드 근거와 함께 확인합니다.',
      metric: 'Git / Commit / Trace',
    },
    {
      path: routePaths.docs,
      icon: <IconFileText size={22} />,
      title: 'Document RAG',
      eyebrow: 'DOCS',
      description: 'PDF, 엑셀, 웹 문서를 사내 지식으로 축적하고 원문 근거 기반 답변을 생성합니다.',
      metric: 'PDF / Excel / Web',
    },
    {
      path: routePaths.admin,
      icon: <IconShieldCheck size={22} />,
      title: 'Admin Console',
      eyebrow: 'ADMIN',
      description: '사용자, 공간, 크롤링 정책, 모델 설정, RAG 데이터를 한 곳에서 관리합니다.',
      metric: 'Users / Spaces / Audit',
    },
  ];
  const bentoCards = [
    {
      title: '근거 중심 답변',
      copy: '답변마다 문서, 파일, 청크, 라인 정보를 연결해 검토 시간을 줄입니다.',
      icon: <IconSearch size={22} />,
    },
    {
      title: '사내 지식 통합',
      copy: '문서 RAG와 코드 RAG를 한 워크스페이스에서 운영합니다.',
      icon: <IconDatabase size={22} />,
    },
    {
      title: '관리자 운영성',
      copy: '인덱싱 상태, 진단, 감사 로그, 사용자 권한을 운영 화면에서 확인합니다.',
      icon: <IconShieldCheck size={22} />,
    },
    {
      title: '재사용 가능한 답변',
      copy: '좋은 답변은 저장하고 팀의 표준 지식으로 다시 활용합니다.',
      icon: <IconBook size={22} />,
    },
  ];

  useEffect(() => {
    if (!shellRef.current) return undefined;
    let active = true;
    let context;
    async function setupParallax() {
      const [{ gsap }, { ScrollTrigger }] = await Promise.all([
        import('gsap'),
        import('gsap/ScrollTrigger'),
      ]);
      if (!active || !shellRef.current) return;
      gsap.registerPlugin(ScrollTrigger);
      context = gsap.context(() => {
        if (visualRef.current) {
          gsap.to(visualRef.current, {
            y: -48,
            ease: 'none',
            scrollTrigger: {
              trigger: shellRef.current,
              start: 'top top',
              end: 'bottom top',
              scrub: true,
            },
          });
        }
        if (proofRef.current) {
          gsap.fromTo(
            proofRef.current,
            { opacity: 0.84, y: 22 },
            {
              opacity: 1,
              y: 0,
              ease: 'none',
              scrollTrigger: {
                trigger: proofRef.current,
                start: 'top 82%',
                end: 'top 50%',
                scrub: true,
              },
            },
          );
        }
      }, shellRef);
    }
    setupParallax();
    return () => {
      active = false;
      context?.revert();
    };
  }, []);

  return (
    <AnimatedPage ref={shellRef} className="home-shell commercial-shell landing-shell min-h-screen bg-slate-950 text-slate-50">
      <ShaderBackground className="landing-shader-canvas" />
      <header className="home-nav">
        <button className="home-brand" type="button" onClick={() => navigateTo(routePaths.home)}>
          <span className="home-brand-mark overflow-hidden bg-white">
            <img src="/LearnBot_Mark.png" alt="" />
          </span>
          <span>
            <strong>LearnBot</strong>
            <small>Private Knowledge RAG</small>
          </span>
        </button>
        <nav aria-label="LearnBot 주요 영역">
          <button type="button" onClick={() => navigateTo(routePaths.code)}>코드</button>
          <button type="button" onClick={() => navigateTo(routePaths.docs)}>문서</button>
          <button type="button" onClick={() => navigateTo(routePaths.admin)}>관리자</button>
        </nav>
        <div className="home-nav-actions">
          {bootstrapping && <Loader2 className="spin" size={16} />}
          {user ? (
            <Button variant="outline" type="button" onClick={logout}>
              <IconLogout size={15} />
              로그아웃
            </Button>
          ) : (
            <Button variant="outline" type="button" onClick={() => navigateTo(routePaths.code)}>
              <IconLock size={15} />
              로그인
            </Button>
          )}
        </div>
      </header>

      <AnimatedSection className="home-hero launch-hero" delay={0.04}>
        <div className="home-hero-copy">
          <Badge className="w-fit border-blue-400/30 bg-blue-400/10 text-blue-100" variant="outline">
            <IconSparkles size={14} />
            Commercial-grade private RAG
          </Badge>
          <h1>사내 지식 운영을 위한<br />프라이빗 AI 워크스페이스</h1>
          <p>
            코드, 문서, 저장 답변, 관리자 운영을 하나의 제품 경험으로 연결하고
            모든 답변을 원문 근거 중심으로 검증합니다.
          </p>
          <div className="home-hero-actions">
            <Button type="button" onClick={() => navigateTo(routePaths.docs)}>
              <IconFileText size={17} />
              문서 RAG 시작
            </Button>
            <Button variant="outline" type="button" onClick={() => navigateTo(routePaths.code)}>
              <IconCode size={17} />
              코드 분석 열기
            </Button>
          </div>
          <div className="landing-trust-row">
            <span>Private workspace</span>
            <span>Source-grounded answers</span>
            <span>Admin diagnostics</span>
          </div>
        </div>
        <Card className="launch-hero-panel landing-product-mockup" ref={visualRef}>
          <CardHeader>
            <div className="launch-logo-lockup">
              <img src="/LearnBot_Wordmark.png" alt="LearnBot" />
              <div>
                <CardTitle>LearnBot Console</CardTitle>
                <CardDescription>Knowledge operations overview</CardDescription>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            <div className="mockup-browser-bar">
              <span />
              <span />
              <span />
              <strong>learnbot.local/rag</strong>
            </div>
            <div className="mockup-query">
              <IconSparkles size={18} />
              <span>“이 장애 코드의 원인과 조치 절차를 근거와 함께 알려줘”</span>
            </div>
            <MetricBarChart
              data={[
                { name: 'Code', value: 38 },
                { name: 'Docs', value: 64 },
                { name: 'Saved', value: 22 },
              ]}
            />
            <div className="launch-proof-grid">
              <span><IconCode size={15} /> Code RAG</span>
              <span><IconFileText size={15} /> Document RAG</span>
              <span><IconShieldCheck size={15} /> Admin Guardrails</span>
            </div>
            <div className="mockup-answer">
              <strong>근거 기반 답변</strong>
              <p>요약 답변과 함께 문서명, 섹션, 원문 청크, 코드 파일 라인을 함께 제공합니다.</p>
            </div>
          </CardContent>
        </Card>
      </AnimatedSection>

      <AnimatedSection className="home-marquee landing-marquee" aria-label="지원 데이터 소스" delay={0.08}>
        <div className="landing-marquee-track">
          {[...supportedSources, ...supportedSources].map((source, index) => (
            <span key={`${source}-${index}`}>{source}</span>
          ))}
        </div>
      </AnimatedSection>

      <AnimatedSection className="home-feature-grid" aria-label="LearnBot 기능 진입" delay={0.12}>
        {featureCards.map((card) => (
          <button className="home-feature-card" type="button" key={card.path} onClick={() => navigateTo(card.path)}>
            <span className="home-feature-eyebrow">{card.eyebrow}</span>
            <span className="home-feature-icon">{card.icon}</span>
            <strong>{card.title}</strong>
            <p>{card.description}</p>
            <small>{card.metric}</small>
          </button>
        ))}
      </AnimatedSection>

      <AnimatedSection className="landing-flow-section" delay={0.14}>
        <div className="landing-section-copy">
          <span className="home-kicker">RAG OPERATING FLOW</span>
          <h2>수집부터 답변까지 한 화면에서 추적합니다</h2>
          <p>Magic UI의 beam형 흐름을 LearnBot에 맞게 단순화해, 사용자가 서비스 구조를 바로 이해할 수 있도록 구성합니다.</p>
        </div>
        <div className="landing-flow-grid">
          {flowSteps.map((step, index) => (
            <div className="landing-flow-card" key={step.label}>
              <span>{String(index + 1).padStart(2, '0')}</span>
              <strong>{step.label}</strong>
              <p>{step.detail}</p>
            </div>
          ))}
        </div>
      </AnimatedSection>

      <AnimatedSection className="landing-bento-section" delay={0.15}>
        <div className="landing-section-copy">
          <span className="home-kicker">PRODUCT CAPABILITIES</span>
          <h2>검색 품질, 운영성, 보안을 함께 설계한 RAG 제품</h2>
        </div>
        <div className="landing-bento-grid">
          {bentoCards.map((card, index) => (
            <div className={index === 0 ? 'landing-bento-card landing-bento-card-large' : 'landing-bento-card'} key={card.title}>
              <span className="landing-bento-icon">{card.icon}</span>
              <strong>{card.title}</strong>
              <p>{card.copy}</p>
            </div>
          ))}
        </div>
      </AnimatedSection>

      <AnimatedSection ref={proofRef} className="home-proof" delay={0.16}>
        <div>
          <span className="home-kicker">OPERATING MODEL</span>
          <h2>로컬 환경 중심의 안전한 지식 운영</h2>
        </div>
        <div className="home-proof-list">
          <span>공간별 데이터 분리</span>
          <span>근거 기반 답변</span>
          <span>권한과 감사 로그</span>
          <span>Export / Import 지원</span>
        </div>
      </AnimatedSection>

      <AnimatedSection className="landing-cta-section" delay={0.18}>
        <div>
          <span className="home-kicker">READY TO OPERATE</span>
          <h2>지금 사내 문서와 코드를 LearnBot 지식으로 전환하세요</h2>
          <p>문서 저장소, 코드 저장소, 관리자 진단까지 같은 제품 흐름 안에서 운영할 수 있습니다.</p>
        </div>
        <div className="landing-cta-actions">
          <Button type="button" onClick={() => navigateTo(routePaths.docs)}>
            <IconFileText size={17} />
            문서 소스 등록
          </Button>
          <Button variant="outline" type="button" onClick={() => navigateTo(routePaths.code)}>
            <IconCode size={17} />
            코드 저장소 등록
          </Button>
        </div>
      </AnimatedSection>
    </AnimatedPage>
  );
}

function LoginScreen({ onLogin, busy, error }) {
  const [loginId, setLoginId] = useState('');
  const [password, setPassword] = useState('');
  const [rememberLogin, setRememberLogin] = useState(false);

  function submit(event) {
    event.preventDefault();
    onLogin({ loginId, password, rememberLogin });
  }

  return (
    <AnimatedPage className="login-screen commercial-shell bg-slate-950">
      <AnimatedSection className="login-panel panel commercial-login-card">
        <div className="brand login-brand">
          <div className="brand-mark overflow-hidden bg-white">
            <img src="/LearnBot_Mark.png" alt="" />
          </div>
          <div>
            <span>LearnBot</span>
            <small>사내 지식 RAG</small>
          </div>
        </div>
        <div>
          <Badge className="mb-3 w-fit" variant="secondary">Private Workspace</Badge>
          <h1>로그인</h1>
          <p className="login-copy">관리자가 초대한 계정으로 사내 위키, 코드, 문서 RAG 공간에 접속합니다.</p>
        </div>
        {error && <div className="alert">{error}</div>}
        <form className="stack" onSubmit={submit} autoComplete="off">
          <label htmlFor="login-id">ID</label>
          <input id="login-id" value={loginId} onChange={(event) => setLoginId(event.target.value)} autoComplete="off" spellCheck="false" />
          <label htmlFor="login-password">비밀번호</label>
          <input id="login-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" />
          <label className="checkbox-row login-remember" htmlFor="login-remember">
            <input id="login-remember" type="checkbox" checked={rememberLogin} onChange={(event) => setRememberLogin(event.target.checked)} />
            자동 로그인
          </label>
          <Button disabled={!loginId || !password || busy}>
            {busy ? <Loader2 className="spin" size={16} /> : <IconLock size={16} />}
            로그인
          </Button>
        </form>
      </AnimatedSection>
    </AnimatedPage>
  );
}
function WorkspaceShell({
  user,
  spaces,
  selectedSpace,
  selectedSpaceId,
  setSelectedSpaceId,
  sidebarCollapsed,
  setSidebarCollapsed,
  indexedRepoCount,
  indexedCount,
  codeChunkCount,
  webCount,
  fileCount,
  activeView,
  navigateTo,
  refreshRepositories,
  refreshDocuments,
  logout,
  error,
  progressMessage,
  children,
  selectedRepository,
  selectedRepositoryId,
  codeFiles,
  fileQuery,
  setFileQuery,
  searchCodeFiles,
  openCodeFile,
  documents,
  selectedDocumentId,
  loadDocumentDetail,
  openDocumentPreview,
  deleteDocument,
  loading,
}) {
  return (
    <AnimatedPage className={sidebarCollapsed ? 'shell shell-sidebar-collapsed commercial-shell workspace-shell-v3' : 'shell commercial-shell workspace-shell-v3'}>
      <Sidebar
        user={user}
        spaces={spaces}
        selectedSpaceId={selectedSpaceId}
        setSelectedSpaceId={setSelectedSpaceId}
        collapsed={sidebarCollapsed}
        setCollapsed={setSidebarCollapsed}
        indexedRepoCount={indexedRepoCount}
        indexedCount={indexedCount}
        codeChunkCount={codeChunkCount}
        webCount={webCount}
        fileCount={fileCount}
        navigateTo={navigateTo}
        activeView={activeView}
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
      />

      <section className="content">
        <header className="topbar workspace-topbar-v3">
          <div>
            <Badge className="mb-2 w-fit" variant="outline">Private RAG Workspace</Badge>
            <h1>런봇</h1>
            <p>
              {selectedSpace?.name || '공간'} 안에서 사내 위키, 문서, 코드 저장소를 근거 기반으로 검색하고 답변합니다.
            </p>
          </div>
          <div className="top-actions">
            <Button variant="outline" type="button" onClick={refreshRepositories}>
              <IconRefresh size={16} />
              저장소 새로고침
            </Button>
            <Button variant="outline" type="button" onClick={refreshDocuments}>
              <IconDatabase size={16} />
              문서 새로고침
            </Button>
            <Button className="top-logout" variant="ghost" size="sm" type="button" onClick={logout}>
              <IconLogout size={14} />
              로그아웃
            </Button>
          </div>
        </header>

        <div className={user.role === 'ADMIN' ? 'view-tabs workspace-tabs-admin' : 'view-tabs workspace-tabs-user'} aria-label="작업 영역">
          <button className={activeView === 'code' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => navigateTo(routePaths.code)}>
            <IconCode size={16} />
            코드
          </button>
          <button className={activeView === 'docs' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => navigateTo(routePaths.docs)}>
            <IconFileText size={16} />
            문서
          </button>
          <button className={activeView === 'saved' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => navigateTo(routePaths.saved)}>
            <IconBook size={16} />
            저장됨
          </button>
          {user.role === 'ADMIN' && (
            <button className={activeView === 'admin' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => navigateTo(routePaths.admin)}>
              <IconShieldCheck size={16} />
              관리자
            </button>
          )}
        </div>

        <ScreenGuide activeView={activeView} />

        {error && <div className="alert">{error}</div>}
        {progressMessage && <div className="progress-banner"><Loader2 className="spin" size={16} />{progressMessage}</div>}

        <AnimatedContent className="workspace-motion-body" delay={0.08}>
          {children}
        </AnimatedContent>
      </section>
    </AnimatedPage>
  );
}

function Sidebar({
  user,
  spaces,
  selectedSpaceId,
  setSelectedSpaceId,
  collapsed,
  setCollapsed,
  indexedRepoCount,
  indexedCount,
  codeChunkCount,
  webCount,
  fileCount,
  activeView,
  selectedRepository,
  selectedRepositoryId,
  codeFiles,
  fileQuery,
  setFileQuery,
  searchCodeFiles,
  openCodeFile,
  navigateTo,
  documents,
  selectedDocumentId,
  loadDocumentDetail,
  openDocumentPreview,
  deleteDocument,
  loading,
}) {
  const userLabel = formatBrandText(user.displayName || user.loginId || user.email);
  return (
    <aside className={collapsed ? 'sidebar collapsed' : 'sidebar'}>
      <div className="brand">
        <button className="brand-home-button" type="button" title="메인 대시보드로 이동" onClick={() => navigateTo(routePaths.home)}>
          <span className="brand-mark brand-home-mark bg-white">
            <img src="/LearnBot_Mark.png" alt="" />
          </span>
          <span className="brand-copy">
            <span>LearnBot</span>
            <small>사내 지식 RAG</small>
          </span>
        </button>
        <button className="icon-button sidebar-toggle" type="button" title={collapsed ? '사이드바 펼치기' : '사이드바 접기'} onClick={() => setCollapsed((current) => !current)}>
          {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        </button>
      </div>

      <div className="side-section">
        <span className="section-label">공간</span>
        <select className="dark-select" value={selectedSpaceId} onChange={(event) => setSelectedSpaceId(event.target.value)}>
          {spaces.map((space) => (
            <option key={space.id} value={space.id}>{space.name}</option>
          ))}
        </select>
        <small className="sidebar-note">{userLabel} · {user.role}</small>
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
            <IconCode size={15} />
            <span>코드 청크</span>
            <strong>{codeChunkCount}</strong>
          </div>
          <div>
            <IconSearch size={15} />
            <span>웹</span>
            <strong>{webCount}</strong>
          </div>
          <div>
            <IconFileText size={15} />
            <span>파일</span>
            <strong>{fileCount}</strong>
          </div>
        </div>
      </div>
      {activeView === 'code' && !collapsed && (
          <div className="side-section sidebar-code-files">
            <span className="section-label">코드 파일</span>

            <small className="sidebar-note">
              {selectedRepository
                  ? `${codeFiles.length}개 파일 표시 중`
                  : '저장소를 선택하세요'}
            </small>

            <form className="sidebar-file-search" onSubmit={searchCodeFiles}>
              <input
                  value={fileQuery}
                  onChange={(event) => setFileQuery(event.target.value)}
                  placeholder="파일 검색..."
                  disabled={!selectedRepositoryId}
              />
              <button disabled={!selectedRepositoryId}>
                <Search size={14} />
              </button>
            </form>

            <div className="sidebar-file-list">
              {codeFiles.map((fileItem) => (
                  <button
                      className="sidebar-file-row"
                      key={fileItem.id}
                      type="button"
                      title={fileItem.filePath}
                      onClick={() => openCodeFile(fileItem.repositoryId, fileItem.id)}
                  >
                    <span>{fileItem.filePath.split(/[\\/]/).pop()}</span>
                    <small>{fileItem.language} · {fileItem.chunkCount} chunks</small>
                  </button>
              ))}

              {selectedRepositoryId && !codeFiles.length && (
                  <p className="empty sidebar-empty">파일이 없습니다.</p>
              )}
            </div>
          </div>
      )}
      {activeView === 'docs' && !collapsed && (
          <div className="side-section sidebar-documents">
            <span className="section-label">문서 목록</span>

            <small className="sidebar-note">
              {documents.length ? `${documents.length}개 문서` : '인덱싱된 문서가 없습니다.'}
            </small>

            <div className="sidebar-document-list">
              {documents.map((doc) => (
                  <article
                      className={doc.id === selectedDocumentId ? 'sidebar-document-row selected' : 'sidebar-document-row'}
                      key={doc.id}
                      onClick={() => loadDocumentDetail(doc.id)}
                  >
                    <div className="sidebar-document-main">
                      <strong>{doc.title}</strong>
                      <small>{doc.sourceUri || doc.contentType || '원본 정보 없음'}</small>
                    </div>

                    <div className="sidebar-document-meta">
                      <StatusBadge status={doc.sourceStatus} />
                      <small>{getSourceLabel(doc.sourceType)} · {formatDate(doc.createdAt)}</small>
                    </div>

                    <div className="sidebar-document-actions">
                      <IconButton
                          title="원문 보기"
                          disabled={loading(`detail-${doc.id}`) || loading(`delete-${doc.id}`)}
                          onClick={(event) => {
                            event.stopPropagation();
                            openDocumentPreview(doc.id);
                          }}
                      >
                        <Eye size={14} />
                      </IconButton>


                      <IconButton
                          danger
                          title="삭제"
                          disabled={loading(`delete-${doc.id}`)}
                          onClick={(event) => {
                            event.stopPropagation();
                            deleteDocument(doc.id, doc.title);
                          }}
                      >
                        {loading(`delete-${doc.id}`)
                            ? <Loader2 className="spin" size={14} />
                            : <Trash2 size={14} />}
                      </IconButton>
                    </div>
                  </article>
              ))}

              {documents.length === 0 && (
                  <p className="empty sidebar-empty">웹 URL이나 파일을 추가하면 여기에 표시됩니다.</p>
              )}
            </div>
          </div>
      )}
    </aside>
  );
}

function ScreenGuide({ activeView }) {
  const [open, setOpen] = useState(false);
  const guides = {
    code: {
      title: '코드 RAG 화면',
      description: 'Git 저장소를 등록하고 인덱싱한 뒤, 실제 파일 경로와 라인 범위를 근거로 코드 질문에 답합니다.',
      points: ['저장소 상태가 인덱싱 완료인지 확인', '실패 사유 버튼으로 모델, Git 인증, 파일별 실패 원인 확인', '코드 검색과 정의/참조로 답변 근거 검증'],
    },
    docs: {
      title: '문서 RAG 화면',
      description: 'PDF, DOCX, PPTX, Markdown, TXT, CSV, Excel, 웹 문서를 업로드하거나 수집해 사내 위키형 근거로 사용합니다.',
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
    <section className={open ? 'screen-guide' : 'screen-guide screen-guide-compact'}>
      <div className="screen-guide-main">
        <span className="eyebrow">Screen Guide</span>
        <h2>{guide.title}</h2>
        {open && <p>{guide.description}</p>}
      </div>
      {open && (
        <ul>
          {guide.points.map((point) => (
            <li key={point}>{point}</li>
          ))}
        </ul>
      )}
      <button className="ghost-button compact-action guide-toggle-button" type="button" onClick={() => setOpen((current) => !current)}>
        {open ? <ChevronUp size={14} /> : <Info size={14} />}
        {open ? '도움말 접기' : '도움말'}
      </button>
    </section>
  );
}

function QuestionGuide({ guide }) {
  const [open, setOpen] = useState(false);
  return (
    <div className={open ? 'question-guide question-guide-open' : 'question-guide'}>
      <button className="guide-toggle" type="button" onClick={() => setOpen((current) => !current)}>
        <Info size={14} />
        <span>{guide.title}</span>
        {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
      </button>
      {open && (
        <div className="question-guide-body">
          <p>{guide.description}</p>
          <ul>
            {guide.tips.map((tip) => (
              <li key={tip}>{tip}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

export { HomePage, LoginScreen, WorkspaceShell, Sidebar, ScreenGuide, QuestionGuide };

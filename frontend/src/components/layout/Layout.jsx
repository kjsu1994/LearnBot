import { useEffect, useRef, useState } from 'react';
import { Bot, ChevronDown, ChevronLeft, ChevronRight, ChevronUp, Code2, Database, FileCode2, FileSpreadsheet, GitPullRequest, Globe, Info, Loader2, LockKeyhole, LogOut, ShieldCheck, Search } from 'lucide-react';
import { routePaths } from '../../config/constants.js';
import { formatBrandText } from '../../lib/formatters.js';
import { AnimatedContent, AnimatedPage, AnimatedSection } from '../common/Common.jsx';

function HomePage({ user, bootstrapping, navigateTo, logout }) {
  const shellRef = useRef(null);
  const visualRef = useRef(null);
  const proofRef = useRef(null);
  const featureCards = [
    {
      path: routePaths.code,
      icon: <Code2 size={22} />,
      title: 'Code RAG',
      eyebrow: 'CODE',
      description: '저장소를 인덱싱하고 최신 커밋, 호출 흐름, UI 이벤트를 코드 근거와 함께 확인합니다.',
      metric: 'Git · Commit · Trace',
    },
    {
      path: routePaths.docs,
      icon: <Database size={22} />,
      title: 'Document RAG',
      eyebrow: 'DOCS',
      description: 'PDF, 엑셀, 웹 문서를 사내 지식으로 축적하고 원문 근거 기반 답변을 생성합니다.',
      metric: 'PDF · Excel · Web',
    },
    {
      path: routePaths.admin,
      icon: <ShieldCheck size={22} />,
      title: 'Admin Console',
      eyebrow: 'ADMIN',
      description: '사용자, 공간, 크롤링 정책, 모델 설정, RAG 데이터 이관을 한 곳에서 관리합니다.',
      metric: 'Users · Spaces · Audit',
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
    <AnimatedPage ref={shellRef} className="home-shell">
      <header className="home-nav">
        <button className="home-brand" type="button" onClick={() => navigateTo(routePaths.home)}>
          <span className="home-brand-mark"><Bot size={20} /></span>
          <span>
            <strong>런봇</strong>
            <small>Private Knowledge RAG</small>
          </span>
        </button>
        <nav aria-label="런봇 주요 영역">
          <button type="button" onClick={() => navigateTo(routePaths.code)}>코드</button>
          <button type="button" onClick={() => navigateTo(routePaths.docs)}>문서</button>
          <button type="button" onClick={() => navigateTo(routePaths.admin)}>관리자</button>
        </nav>
        <div className="home-nav-actions">
          {bootstrapping && <Loader2 className="spin" size={16} />}
          {user ? (
            <button className="ghost-button" type="button" onClick={logout}>
              <LogOut size={15} />
              로그아웃
            </button>
          ) : (
            <button className="ghost-button" type="button" onClick={() => navigateTo(routePaths.code)}>
              <LockKeyhole size={15} />
              로그인
            </button>
          )}
        </div>
      </header>

      <AnimatedSection className="home-hero" delay={0.04}>
        <div className="home-hero-copy">
          <span className="home-kicker">CREATE KNOWLEDGE SYSTEM</span>
          <h1>사내 <br />워크스페이스</h1>
          <p>
            코드, 문서, 관리자 운영을 분리된 경로로 정리하고,
            팀별 공간에서 검색 가능한 RAG 지식 기반을 운영합니다.
          </p>
          <div className="home-hero-actions">
            <button type="button" onClick={() => navigateTo(routePaths.code)}>
              <Code2 size={17} />
              코드 RAG 시작
            </button>
            <button className="ghost-button" type="button" onClick={() => navigateTo(routePaths.docs)}>
              <Database size={17} />
              문서 지식 보기
            </button>
          </div>
        </div>
        <div className="home-visual" aria-hidden="true" ref={visualRef}>
          <div className="home-orbit home-orbit-one" />
          <div className="home-orbit home-orbit-two" />
          <div className="home-visual-core">
            <Bot size={44} />
            <span>RUNBOT</span>
          </div>
          <div className="home-floating-chip chip-code">CODE</div>
          <div className="home-floating-chip chip-docs">DOCS</div>
          <div className="home-floating-chip chip-admin">ADMIN</div>
        </div>
      </AnimatedSection>

      <AnimatedSection className="home-marquee" aria-label="런봇 핵심 가치" delay={0.08}>
        <span>RUNBOT</span>
        <span>KNOWLEDGE</span>
        <span>GROUNDING</span>
        <span>WORKSPACE</span>
        <span>RUNBOT</span>
      </AnimatedSection>

      <AnimatedSection className="home-feature-grid" aria-label="런봇 기능 진입" delay={0.12}>
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

      <AnimatedSection ref={proofRef} className="home-proof" delay={0.16}>
        <div>
          <span className="home-kicker">OPERATING MODEL</span>
          <h2>로컬환경 지원</h2>
        </div>
        <div className="home-proof-list">
          <span>공간별 데이터 분리</span>
          <span>근거 기반 답변</span>
          <span>권한과 감사 로그</span>
          <span>Export / Import 이관</span>
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
    <AnimatedPage className="login-screen">
      <AnimatedSection className="login-panel panel">
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
          <label className="checkbox-row login-remember" htmlFor="login-remember">
            <input id="login-remember" type="checkbox" checked={rememberLogin} onChange={(event) => setRememberLogin(event.target.checked)} />
            자동 로그인
          </label>
          <button disabled={!loginId || !password || busy}>
            {busy ? <Loader2 className="spin" size={16} /> : <LockKeyhole size={16} />}
            로그인
          </button>
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
}) {
  return (
    <AnimatedPage className={sidebarCollapsed ? 'shell shell-sidebar-collapsed' : 'shell'}>
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
          <button className={activeView === 'code' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => navigateTo(routePaths.code)}>
            <Code2 size={16} />
            코드
          </button>
          <button className={activeView === 'docs' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => navigateTo(routePaths.docs)}>
            <Database size={16} />
            문서
          </button>
          {user.role === 'ADMIN' && (
            <button className={activeView === 'admin' ? 'tab-button active' : 'tab-button'} type="button" onClick={() => navigateTo(routePaths.admin)}>
              <ShieldCheck size={16} />
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
}) {
  const userLabel = formatBrandText(user.displayName || user.loginId || user.email);
  return (
    <aside className={collapsed ? 'sidebar collapsed' : 'sidebar'}>
      <div className="brand">
        <button className="brand-home-button" type="button" title="메인 대시보드로 이동" onClick={() => navigateTo(routePaths.home)}>
          <span className="brand-mark brand-home-mark">
            <Bot size={20} />
          </span>
          <span className="brand-copy">
            <span>런봇</span>
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

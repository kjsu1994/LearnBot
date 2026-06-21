import { Suspense, lazy, useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, Eye, EyeOff, Info, Loader2, Search } from 'lucide-react';
import { IconBook, IconCode, IconDatabase, IconFileText, IconLock, IconLogout, IconRefresh, IconSearch, IconShieldCheck, IconSparkles } from '@tabler/icons-react';
import { routePaths } from '../../config/constants.js';
import { formatBrandText, formatDate, getSourceLabel } from '../../lib/formatters.js';
import { AnimatedContent, AnimatedPage, AnimatedSection } from '../common/Common.jsx';
import { ShaderBackground } from '../effects/ShaderBackground.jsx';
import { Badge } from '../ui/badge.jsx';
import { Button } from '../ui/button.jsx';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../ui/card.jsx';
import { MetricBarChart } from '../ui/metric-chart.jsx';

const Spline = lazy(() => import('@splinetool/react-spline'));
const ROBOT_SCENE_READY_KEY = 'learnbot:spline-robot-ready';

function DeckGlyph() {
  return (
    <svg viewBox="0 0 120 120" className="deck-glyph" aria-hidden="true">
      <circle cx="60" cy="60" r="46" fill="none" stroke="currentColor" strokeWidth="1.4" className="deck-glyph-orbit" style={{ strokeDasharray: '18 14' }} />
      <rect x="34" y="34" width="52" height="52" rx="14" fill="currentColor" fillOpacity="0.08" stroke="currentColor" strokeWidth="1.2" className="deck-glyph-grid" />
      <circle cx="60" cy="60" r="7" fill="currentColor" />
      <path d="M60 30v10M60 80v10M30 60h10M80 60h10" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" className="deck-glyph-pulse" />
    </svg>
  );
}

function RobotSceneFallback() {
  return (
    <div className="hero3-robot-fallback" aria-hidden="true">
      <span className="hero3-robot-orbit hero3-robot-orbit-one" />
      <span className="hero3-robot-orbit hero3-robot-orbit-two" />
      <span className="hero3-robot-platform" />
    </div>
  );
}

function SplineRobotScene() {
  const [ready, setReady] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const hasLoadedBefore = window.localStorage.getItem(ROBOT_SCENE_READY_KEY) === '1';
    const delay = hasLoadedBefore ? 650 : 1600;
    let idleId;
    const timer = window.setTimeout(() => {
      if ('requestIdleCallback' in window) {
        idleId = window.requestIdleCallback(() => setReady(true), { timeout: 3000 });
        return;
      }
      setReady(true);
    }, delay);

    return () => {
      window.clearTimeout(timer);
      if (idleId && 'cancelIdleCallback' in window) {
        window.cancelIdleCallback(idleId);
      }
    };
  }, []);

  useEffect(() => {
    if (!ready || loaded || failed) {
      return undefined;
    }

    const timer = window.setTimeout(() => {
      setFailed(true);
    }, 12000);

    return () => window.clearTimeout(timer);
  }, [failed, loaded, ready]);

  function handleLoad() {
    window.localStorage.setItem(ROBOT_SCENE_READY_KEY, '1');
    setLoaded(true);
  }

  return (
    <div className="hero3-robot-stage" aria-label="LearnBot 3D assistant">
      {!loaded && <RobotSceneFallback />}
      {ready && !failed && (
        <Suspense fallback={null}>
          <Spline
            scene="https://prod.spline.design/kZDDjO5HuC9GJUM2/scene.splinecode"
            className="hero3-spline-scene"
            onLoad={handleLoad}
            onError={() => setFailed(true)}
          />
        </Suspense>
      )}
    </div>
  );
}

function HomePage({ user, bootstrapping, navigateTo, logout }) {
  const shellRef = useRef(null);
  const [mode, setMode] = useState('docs');
  const supportedSources = ['PDF', 'DOCX', 'PPTX', 'Markdown', 'Excel', 'CSV', 'Web crawl', 'Git repository', 'Saved answers'];
  const activeMode = {
    docs: {
      label: '문서 RAG',
      title: '문서 지식 운영',
      description: '업로드 문서, 웹 수집 자료, 표 데이터를 근거 중심 지식으로 정리하고 답변 품질을 관리합니다.',
      items: ['원문 청크와 출처 연결', '문서 상태와 진단 추적', 'PDF, Excel, Web crawl 통합'],
      action: () => navigateTo(routePaths.docs),
    },
    code: {
      label: '코드 RAG',
      title: '코드 근거 분석',
      description: 'Git 저장소의 파일, 청크, 변경 흐름을 기반으로 코드 질문에 검증 가능한 답변을 제공합니다.',
      items: ['저장소 인덱싱 상태 확인', '파일 미리보기와 검색 연동', '코드 근거 기반 답변'],
      action: () => navigateTo(routePaths.code),
    },
  }[mode];
  const metrics = [
    { label: 'Knowledge sources', value: 'Docs + Code' },
    { label: 'Answer policy', value: 'Grounded' },
    { label: 'Operations', value: 'Audit ready' },
  ];
  const protocols = [
    { name: 'Source intake', detail: '문서, 웹, Git 저장소를 안전하게 가져오고 실패 사유를 추적합니다.', status: 'Ready' },
    { name: 'Index pipeline', detail: '구조, 메타데이터, 청크, 근거 연결을 검색 가능한 상태로 정리합니다.', status: 'Running' },
    { name: 'Answer guard', detail: '질문 의도에 맞는 근거만 사용해 출처가 남는 답변을 제공합니다.', status: 'Grounded' },
  ];

  function setSpotlight(event) {
    const target = event.currentTarget;
    const rect = target.getBoundingClientRect();
    target.style.setProperty('--hero3-x', `${event.clientX - rect.left}px`);
    target.style.setProperty('--hero3-y', `${event.clientY - rect.top}px`);
  }

  function clearSpotlight(event) {
    const target = event.currentTarget;
    target.style.removeProperty('--hero3-x');
    target.style.removeProperty('--hero3-y');
  }

  return (
    <AnimatedPage ref={shellRef} className="home-shell commercial-shell landing-shell hero3-shell min-h-screen bg-slate-950 text-slate-50">
      <ShaderBackground className="landing-shader-canvas command-deck-shader" />
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

      <section className="hero3-section">
        <header className="hero3-header-grid">
          <div className="hero3-copy">
            <div className="hero3-badges">
              <span className="hero3-pill"><IconSparkles size={14} /> Private Knowledge RAG</span>
              <span className="hero3-pill hero3-pill-muted">Source-grounded answers</span>
            </div>
            <div className="hero3-title-block">
              <h1>LearnBot: 사내 문서와 코드를 근거 중심 지식으로 운영하는</h1>
              <h1>로컬 AI 워크스페이스</h1>
              <p>
                문서 RAG, 코드 RAG, 저장 답변, 관리자 진단을 하나의 제품 경험으로 연결합니다.
                모든 답변은 원문 근거와 운영 상태를 함께 남겨 팀이 검증 가능한 지식으로 활용할 수 있습니다.
              </p>
            </div>
            <div className="hero3-action-row">
              <div className="hero3-status-pill">
                <span><span className="hero3-pulse-dot" /> 운영 준비 완료</span>
                <span>·</span>
                <span>로컬 중심 지식 운영</span>
              </div>
              <div className="hero3-metrics">
                {metrics.map((metric) => (
                  <div key={metric.label}>
                    <span>{metric.label}</span>
                    <strong>{metric.value}</strong>
                  </div>
                ))}
              </div>
            </div>
            <div className="home-hero-actions hero3-primary-actions">
              <Button type="button" onClick={() => navigateTo(routePaths.docs)}>
                <IconFileText size={17} />
                문서 RAG 시작
              </Button>
              <Button variant="outline" type="button" onClick={() => navigateTo(routePaths.code)}>
                <IconCode size={17} />
                코드 분석 열기
              </Button>
            </div>
          </div>

          <div className="hero3-mode-card">
            <div className="hero3-mode-head">
              <div>
                <p>Mode</p>
                <h2>{activeMode.title}</h2>
              </div>
              <DeckGlyph />
            </div>
            <p>{activeMode.description}</p>
            <div className="hero3-mode-toggle">
              <button className={mode === 'docs' ? 'active' : ''} type="button" onClick={() => setMode('docs')}>Document</button>
              <button className={mode === 'code' ? 'active' : ''} type="button" onClick={() => setMode('code')}>Code</button>
            </div>
            <ul>
              {activeMode.items.map((item) => (
                <li key={item}><span />{item}</li>
              ))}
            </ul>
          </div>
        </header>

        <div className="hero3-main-grid">
          <div className="hero3-control-card">
            <div className="hero3-card-head">
              <h3>Control stack</h3>
              <span>v3.0</span>
            </div>
            <p>
              문서와 코드 지식을 수집, 인덱싱, 진단, 검색, 답변까지 한 화면의 운영 흐름으로 관리합니다.
            </p>
            <div className="hero3-stack-list">
              {['근거 출처 연결', '인덱싱 진단 표시', '감사 로그 기반 운영'].map((item) => (
                <div key={item}>{item}</div>
              ))}
            </div>
          </div>

          <figure className="hero3-visual-card">
            <div className="hero3-visual-frame">
              <SplineRobotScene />
            </div>
            <figcaption>
              <span>Private RAG workspace</span>
              <span><span /> Source-grounded</span>
            </figcaption>
          </figure>

          <aside className="hero3-protocol-card">
            <div className="hero3-card-head">
              <h3>Launch protocols</h3>
              <span>Indexed</span>
            </div>
            <ul>
              {protocols.map((protocol) => (
                <li key={protocol.name} onMouseMove={setSpotlight} onMouseLeave={clearSpotlight}>
                  <div>
                    <h4>{protocol.name}</h4>
                    <span>{protocol.status}</span>
                  </div>
                  <p>{protocol.detail}</p>
                </li>
              ))}
            </ul>
          </aside>
        </div>
      </section>

      <AnimatedSection className="home-marquee landing-marquee hero3-marquee" aria-label="지원 데이터 소스" delay={0.08}>
        <div className="landing-marquee-track">
          {[...supportedSources, ...supportedSources].map((source, index) => (
            <span key={`${source}-${index}`}>{source}</span>
          ))}
        </div>
      </AnimatedSection>

      <AnimatedSection className="landing-cta-section hero3-cta" delay={0.18}>
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

function LegacyHomePage({ user, bootstrapping, navigateTo, logout }) {
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
              <img className="launch-logo-icon" src="/LearnBot_Mark.png" alt="LearnBot" />
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
  const [showPassword, setShowPassword] = useState(false);

  function submit(event) {
    event.preventDefault();
    onLogin({ loginId, password, rememberLogin });
  }

  return (
    <AnimatedPage className="login-screen commercial-shell commercial-login-screen bg-slate-950">
      <div className="commercial-login-ambient" aria-hidden="true" />
      <AnimatedSection className="commercial-login-layout">
        <aside className="commercial-login-showcase" aria-hidden="true">
          <div className="commercial-login-showcase-mark">
            <img src="/LearnBot_Mark.png" alt="" />
          </div>
          <div>
            <span>Private Knowledge RAG</span>
            <h2>Learn Bot</h2>
            <p>문서 RAG, 코드 RAG, 저장 답변, 관리자 진단까지 하나의 워크스페이스에서 운영합니다.</p>
          </div>
          <div className="commercial-login-signal">
            <span>Docs</span>
            <span>Code</span>
            <span>Audit</span>
          </div>
        </aside>

        <section className="login-panel panel commercial-login-card">
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
          <form className="stack commercial-login-form" onSubmit={submit} autoComplete="off">
            <label htmlFor="login-id">ID</label>
            <input id="login-id" value={loginId} onChange={(event) => setLoginId(event.target.value)} autoComplete="off" spellCheck="false" />
            <label htmlFor="login-password">비밀번호</label>
            <div className="commercial-password-field">
              <input id="login-password" type={showPassword ? 'text' : 'password'} value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="new-password" />
              <button type="button" onClick={() => setShowPassword((current) => !current)} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}>
                {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
              </button>
            </div>
            <label className="checkbox-row login-remember" htmlFor="login-remember">
              <input id="login-remember" type="checkbox" checked={rememberLogin} onChange={(event) => setRememberLogin(event.target.checked)} />
              자동 로그인
            </label>
            <Button disabled={!loginId || !password || busy}>
              {busy ? <Loader2 className="spin" size={16} /> : <IconLock size={16} />}
              로그인
            </Button>
          </form>
        </section>
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
  openDocumentPreview,
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
        openDocumentPreview={openDocumentPreview}
        loading={loading}
      />

      <section className="content">
        <header className="topbar workspace-topbar-v3">
          <div>
            <Badge className="mb-2 w-fit" variant="outline">Private RAG Workspace</Badge>
            <h1>LearnBot</h1>
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

function normalizeCodeFilePath(filePath) {
  return String(filePath || '').replace(/\\/g, '/').replace(/^\/+/, '') || 'untitled';
}

function sortCodeFileTreeNodes(nodes) {
  return nodes
    .map((node) => (
      node.type === 'folder'
        ? { ...node, children: sortCodeFileTreeNodes(node.children) }
        : node
    ))
    .sort((a, b) => {
      if (a.type !== b.type) return a.type === 'folder' ? -1 : 1;
      return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
    });
}

function buildCodeFileTree(files) {
  const root = [];
  const folders = new Map();

  files.forEach((fileItem) => {
    const filePath = normalizeCodeFilePath(fileItem.filePath);
    const segments = filePath.split('/').filter(Boolean);
    const fileName = segments.pop() || filePath;
    let children = root;
    let currentPath = '';

    segments.forEach((segment) => {
      currentPath = currentPath ? `${currentPath}/${segment}` : segment;
      let folderNode = folders.get(currentPath);

      if (!folderNode) {
        folderNode = {
          type: 'folder',
          key: `folder:${currentPath}`,
          name: segment,
          path: currentPath,
          children: [],
        };
        folders.set(currentPath, folderNode);
        children.push(folderNode);
      }

      children = folderNode.children;
    });

    children.push({
      type: 'file',
      key: `file:${fileItem.id ?? filePath}`,
      name: fileName,
      path: filePath,
      directory: segments.join('/'),
      extension: fileName.includes('.') ? fileName.split('.').pop()?.toLowerCase() : 'default',
      fileItem,
    });
  });

  return sortCodeFileTreeNodes(root);
}

function getCodeFileIcon(extension) {
  const iconMap = {
    tsx: { className: 'tsx', icon: '⚛' },
    ts: { className: 'ts', icon: '◆' },
    jsx: { className: 'jsx', icon: '⚛' },
    js: { className: 'js', icon: '◆' },
    css: { className: 'css', icon: '◈' },
    json: { className: 'json', icon: '{}' },
    md: { className: 'md', icon: '◊' },
    svg: { className: 'svg', icon: '◐' },
    png: { className: 'png', icon: '◑' },
    yml: { className: 'yaml', icon: '◇' },
    yaml: { className: 'yaml', icon: '◇' },
    html: { className: 'html', icon: '◇' },
    pdf: { className: 'pdf', icon: '◇' },
    doc: { className: 'doc', icon: '◇' },
    docx: { className: 'doc', icon: '◇' },
    ppt: { className: 'ppt', icon: '◇' },
    pptx: { className: 'ppt', icon: '◇' },
    xls: { className: 'xls', icon: '◇' },
    xlsx: { className: 'xls', icon: '◇' },
    default: { className: 'default', icon: '◇' },
  };
  return iconMap[extension || 'default'] || iconMap.default;
}

function compactCodeDirectory(directory) {
  if (!directory) return '';
  const segments = directory.split('/').filter(Boolean);
  if (segments.length <= 3) return directory;
  return `${segments[0]}/.../${segments.slice(-2).join('/')}`;
}

function CodeFileTree({ files, selectedRepositoryId, openCodeFile }) {
  const [collapsedFolders, setCollapsedFolders] = useState(() => new Set());
  const tree = buildCodeFileTree(files);

  function toggleFolder(path) {
    setCollapsedFolders((current) => {
      const next = new Set(current);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  }

  function renderNode(node, depth = 0) {
    const visualDepth = Math.min(depth, 3);
    const indent = `${visualDepth * 12 + 8}px`;
    const lineLeft = `${Math.max(visualDepth - 1, 0) * 12 + 14}px`;

    if (node.type === 'folder') {
      const collapsed = collapsedFolders.has(node.path);
      return (
        <div className="sidebar-file-tree-item-wrap" key={node.key}>
          <button
            className="sidebar-file-tree-item sidebar-file-tree-folder"
            style={{ '--tree-indent': indent, '--tree-line-left': lineLeft }}
            type="button"
            title={node.path}
            onClick={() => toggleFolder(node.path)}
          >
            {depth > 0 && <span className="sidebar-file-tree-line" />}
            <span className={collapsed ? 'sidebar-file-tree-caret' : 'sidebar-file-tree-caret open'}>
              <svg width="6" height="8" viewBox="0 0 6 8" fill="none" aria-hidden="true">
                <path d="M1 1L5 4L1 7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </span>
            <span className="sidebar-file-tree-folder-icon" aria-hidden="true">
              <svg width="16" height="14" viewBox="0 0 16 14" fill="currentColor">
                <path d="M1.5 1C0.671573 1 0 1.67157 0 2.5V11.5C0 12.3284 0.671573 13 1.5 13H14.5C15.3284 13 16 12.3284 16 11.5V4.5C16 3.67157 15.3284 3 14.5 3H8L6.5 1H1.5Z" />
              </svg>
            </span>
            <span className="sidebar-file-tree-name">{node.name}</span>
            <span className="sidebar-file-tree-hover-dot" />
          </button>
          {!collapsed && (
            <div className="sidebar-file-tree-children">
              {node.children.map((child) => renderNode(child, depth + 1))}
            </div>
          )}
        </div>
      );
    }

    const { fileItem } = node;
    const fileIcon = getCodeFileIcon(node.extension);
    const compactDirectory = compactCodeDirectory(node.directory);
    return (
      <button
        className="sidebar-file-tree-item sidebar-file-tree-file"
        key={node.key}
        style={{ '--tree-indent': indent, '--tree-line-left': lineLeft }}
        type="button"
        title={node.path}
        onClick={() => openCodeFile(fileItem.repositoryId, fileItem.id)}
      >
        {depth > 0 && <span className="sidebar-file-tree-line" />}
        <span className={`sidebar-file-tree-file-mark ${fileIcon.className}`}>{fileIcon.icon}</span>
        <span className={`sidebar-file-tree-file-icon ${fileIcon.className}`} aria-hidden="true">
          <svg width="14" height="16" viewBox="0 0 14 16" fill="currentColor" opacity="0.8">
            <path d="M1.5 0C0.671573 0 0 0.671573 0 1.5V14.5C0 15.3284 0.671573 16 1.5 16H12.5C13.3284 16 14 15.3284 14 14.5V4.5L9.5 0H1.5Z" />
            <path d="M9 0V4.5H14" fill="currentColor" fillOpacity="0.5" />
          </svg>
        </span>
        <span className="sidebar-file-tree-text">
          <span className="sidebar-file-tree-name">{node.name}</span>
          {compactDirectory && <span className="sidebar-file-tree-path">{compactDirectory}</span>}
        </span>
        <span className="sidebar-file-tree-hover-dot" />
      </button>
    );
  }

  return (
    <div className="sidebar-file-tree-card">
      <div className="sidebar-file-tree-header">
        <div className="sidebar-file-tree-window-dots" aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <span>explorer</span>
      </div>
      <div className="sidebar-file-tree">
        {tree.map((node) => renderNode(node))}
        {selectedRepositoryId && !files.length && (
          <p className="empty sidebar-empty">파일이 없습니다.</p>
        )}
      </div>
    </div>
  );
}

function getDocumentExtension(doc) {
  const candidate = doc.title || doc.sourceUri || doc.contentType || '';
  const fromName = String(candidate).split(/[?#]/)[0].split('.').pop()?.toLowerCase();
  if (fromName && fromName !== candidate.toLowerCase() && fromName.length <= 8) return fromName;

  const contentType = String(doc.contentType || '').toLowerCase();
  if (contentType.includes('pdf')) return 'pdf';
  if (contentType.includes('word')) return 'docx';
  if (contentType.includes('presentation')) return 'pptx';
  if (contentType.includes('spreadsheet') || contentType.includes('excel')) return 'xlsx';
  if (contentType.includes('json')) return 'json';
  if (contentType.includes('markdown')) return 'md';
  if (contentType.includes('html')) return 'html';
  return 'default';
}

function DocumentFileList({
  documents,
  selectedDocumentId,
  openDocumentPreview,
  loading,
}) {
  const [documentQuery, setDocumentQuery] = useState('');
  const normalizedQuery = documentQuery.trim().toLowerCase();
  const filteredDocuments = normalizedQuery
    ? documents.filter((doc) => (
      [
        doc.title,
        doc.sourceUri,
        doc.contentType,
        getSourceLabel(doc.sourceType),
      ]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(normalizedQuery))
    ))
    : documents;

  return (
    <>
      <form
        className="sidebar-file-search sidebar-document-search"
        onSubmit={(event) => event.preventDefault()}
      >
        <input
          value={documentQuery}
          onChange={(event) => setDocumentQuery(event.target.value)}
          placeholder="문서 검색..."
          disabled={!documents.length}
        />
        <button type="submit" disabled={!documents.length}>
          <Search size={14} />
        </button>
      </form>

      <div className="sidebar-file-tree-card sidebar-document-tree-card">
        <div className="sidebar-file-tree-header">
          <div className="sidebar-file-tree-window-dots" aria-hidden="true">
            <span />
            <span />
            <span />
          </div>
          <span>documents</span>
        </div>
        <div className="sidebar-file-tree sidebar-document-tree">
          {filteredDocuments.map((doc) => {
            const fileIcon = getCodeFileIcon(getDocumentExtension(doc));
            const selected = doc.id === selectedDocumentId;
            return (
              <article
                className={selected ? 'sidebar-document-tree-row selected' : 'sidebar-document-tree-row'}
                key={doc.id}
                title={doc.title}
                onClick={() => openDocumentPreview(doc.id)}
              >
                <span className={`sidebar-file-tree-file-mark ${fileIcon.className}`}>{fileIcon.icon}</span>
                <span className={`sidebar-file-tree-file-icon ${fileIcon.className}`} aria-hidden="true">
                  <svg width="14" height="16" viewBox="0 0 14 16" fill="currentColor" opacity="0.8">
                    <path d="M1.5 0C0.671573 0 0 0.671573 0 1.5V14.5C0 15.3284 0.671573 16 1.5 16H12.5C13.3284 16 14 15.3284 14 14.5V4.5L9.5 0H1.5Z" />
                    <path d="M9 0V4.5H14" fill="currentColor" fillOpacity="0.5" />
                  </svg>
                </span>
                <span className="sidebar-file-tree-text">
                  <span className="sidebar-file-tree-name">{doc.title}</span>
                </span>
                <span className="sidebar-file-tree-hover-dot" />
              </article>
            );
          })}

          {documents.length === 0 && (
            <p className="empty sidebar-empty">웹 URL이나 파일을 추가하면 여기에 표시됩니다.</p>
          )}
          {documents.length > 0 && filteredDocuments.length === 0 && (
            <p className="empty sidebar-empty">검색 결과가 없습니다.</p>
          )}
        </div>
      </div>
    </>
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
  openDocumentPreview,
  loading,
}) {
  const userLabel = formatBrandText(user.displayName || user.loginId || user.email);
  const sidebarViews = [
    { key: 'code', label: '코드', detail: `${indexedRepoCount} repos`, icon: <IconCode size={16} />, path: routePaths.code },
    { key: 'docs', label: '문서', detail: `${indexedCount} docs`, icon: <IconFileText size={16} />, path: routePaths.docs },
    { key: 'saved', label: '저장됨', detail: 'Library', icon: <IconDatabase size={16} />, path: routePaths.saved },
    ...(user.role === 'ADMIN'
      ? [{ key: 'admin', label: '관리자', detail: 'Console', icon: <IconShieldCheck size={16} />, path: routePaths.admin }]
      : []),
  ];
  return (
    <aside className={collapsed ? 'sidebar learnbot-sidebar-v4 collapsed' : 'sidebar learnbot-sidebar-v4'}>
      <div className="brand">
        <button className="brand-home-button" type="button" title="메인 대시보드로 이동" onClick={() => navigateTo(routePaths.home)}>
          <span className="brand-mark brand-home-mark bg-white">
            <img src="/LearnBot_Mark.png" alt="" />
          </span>
          <span className="brand-copy">
            <span>LearnBot</span>
            <small>Private Knowledge RAG</small>
          </span>
        </button>
        <button className="icon-button sidebar-toggle" type="button" title={collapsed ? '사이드바 펼치기' : '사이드바 접기'} onClick={() => setCollapsed((current) => !current)}>
          {collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
        </button>
      </div>

      <nav className="sidebar-view-nav-v4" aria-label="작업 화면">
        {sidebarViews.map((item) => (
          <button
            className={activeView === item.key ? 'active' : ''}
            key={item.key}
            type="button"
            title={item.label}
            onClick={() => navigateTo(item.path)}
          >
            <span className="sidebar-view-icon-v4">{item.icon}</span>
            <span className="sidebar-view-copy-v4">
              <strong>{item.label}</strong>
              <small>{item.detail}</small>
            </span>
          </button>
        ))}
      </nav>

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

            <CodeFileTree
              files={codeFiles}
              selectedRepositoryId={selectedRepositoryId}
              openCodeFile={openCodeFile}
            />
          </div>
      )}
      {activeView === 'docs' && !collapsed && (
          <div className="side-section sidebar-documents">
            <span className="section-label">문서 목록</span>

            <small className="sidebar-note">
              {documents.length ? `${documents.length}개 문서` : '인덱싱된 문서가 없습니다.'}
            </small>

            <DocumentFileList
              documents={documents}
              selectedDocumentId={selectedDocumentId}
              openDocumentPreview={openDocumentPreview}
              loading={loading}
            />
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

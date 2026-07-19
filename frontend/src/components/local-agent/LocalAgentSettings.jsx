import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle, CheckCircle2, Clock3, Download, ExternalLink, Laptop,
  Loader2, PlugZap, RefreshCw, ShieldCheck, Trash2, XCircle,
} from 'lucide-react';
import { Badge } from '../ui/badge.jsx';
import { Button } from '../ui/button.jsx';
import {
  LOCAL_AGENT_ENDPOINTS, decideEnrollment, fetchDevices, fetchReleaseMetadata,
  lookupEnrollment, normalizeUserCode, revokeDevice, selectDevice,
} from '../../features/local-agent/localAgentApi.js';

const POLL_INTERVAL_MS = 10_000;

function formatTimestamp(value) {
  if (!value) return '아직 연결 기록 없음';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleString('ko-KR');
}

function isConnected(device) {
  return ['CONNECTED', 'ONLINE', 'ACTIVE'].includes(device?.state);
}

function needsUpdate(device) {
  return ['REQUIRED', 'UPDATE_REQUIRED', 'BELOW_MINIMUM', 'BLOCKED'].includes(device?.updateState);
}

function hasOptionalUpdate(device) {
  return ['AVAILABLE', 'UPDATE_AVAILABLE'].includes(device?.updateState);
}

function DeviceStateBadge({ device }) {
  if (needsUpdate(device)) return <Badge variant="destructive">업데이트 필요</Badge>;
  if (hasOptionalUpdate(device)) return <Badge variant="secondary">업데이트 있음</Badge>;
  if (isConnected(device)) return <Badge variant="outline">연결됨</Badge>;
  return <Badge variant="secondary">오프라인</Badge>;
}

function EnrollmentApproval({ request, userCode, onFinished }) {
  const [enrollment, setEnrollment] = useState(null);
  const [loading, setLoading] = useState(true);
  const [decisionLoading, setDecisionLoading] = useState('');
  const [error, setError] = useState('');
  const [completed, setCompleted] = useState('');

  useEffect(() => {
    let active = true;
    lookupEnrollment(request, userCode)
      .then((result) => active && setEnrollment(result))
      .catch((err) => active && setError(err.message || '연결 요청을 확인하지 못했습니다.'))
      .finally(() => active && setLoading(false));
    return () => { active = false; };
  }, [userCode]);

  async function submitDecision(decision) {
    if (!enrollment?.id) return;
    setDecisionLoading(decision);
    setError('');
    try {
      await decideEnrollment(request, enrollment.id, decision);
      setCompleted(decision);
      onFinished?.();
    } catch (err) {
      setError(err.message || '승인 결정을 저장하지 못했습니다.');
    } finally {
      setDecisionLoading('');
    }
  }

  return (
    <section className="local-agent-panel local-agent-approval" aria-labelledby="local-agent-approval-title">
      <div className="local-agent-heading">
        <span className="local-agent-icon warning"><ShieldCheck size={22} /></span>
        <div>
          <span className="local-agent-eyebrow">보안 확인</span>
          <h2 id="local-agent-approval-title">이 PC의 연결을 승인할까요?</h2>
          <p>직접 설치하고 실행한 PC인지 아래 정보를 확인하세요.</p>
        </div>
      </div>
      {loading && <div className="local-agent-notice"><Loader2 className="spin" size={18} />연결 요청을 확인하는 중입니다.</div>}
      {error && <div className="local-agent-notice error"><AlertTriangle size={18} />{error}</div>}
      {completed && (
        <div className={`local-agent-notice ${completed === 'APPROVE' ? 'success' : ''}`}>
          {completed === 'APPROVE' ? <CheckCircle2 size={18} /> : <XCircle size={18} />}
          {completed === 'APPROVE' ? '연결을 승인했습니다. Local Agent에서 설정을 계속하세요.' : '연결 요청을 거부했습니다.'}
        </div>
      )}
      {!loading && enrollment?.id && !completed && (
        <>
          <dl className="local-agent-detail-grid">
            <div><dt>PC 이름</dt><dd>{enrollment.machineName}</dd></div>
            <div><dt>환경</dt><dd>{enrollment.platform} · {enrollment.architecture}</dd></div>
            <div><dt>Agent 버전</dt><dd>{enrollment.version}</dd></div>
            <div><dt>요청 코드</dt><dd>{normalizeUserCode(userCode)}</dd></div>
            {enrollment.fingerprint && <div><dt>설치 식별자</dt><dd>{enrollment.fingerprint}</dd></div>}
            {enrollment.requestedAt && <div><dt>요청 시각</dt><dd>{formatTimestamp(enrollment.requestedAt)}</dd></div>}
            <div><dt>만료</dt><dd>{formatTimestamp(enrollment.expiresAt)}</dd></div>
          </dl>
          <p className="local-agent-security-note">이 요청을 시작하지 않았다면 거부하세요. 승인해도 선택한 작업 폴더 밖에는 접근할 수 없습니다.</p>
          <div className="local-agent-actions">
            <Button type="button" onClick={() => submitDecision('APPROVE')} disabled={Boolean(decisionLoading)}>
              {decisionLoading === 'APPROVE' ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />} 이 PC 승인
            </Button>
            <Button variant="outline" type="button" onClick={() => submitDecision('DENY')} disabled={Boolean(decisionLoading)}>
              {decisionLoading === 'DENY' ? <Loader2 className="spin" size={16} /> : <XCircle size={16} />} 거부
            </Button>
          </div>
        </>
      )}
    </section>
  );
}

export function LocalAgentSettings({ request, approvalMode = false }) {
  const [devices, setDevices] = useState([]);
  const [release, setRelease] = useState(null);
  const [releaseChecked, setReleaseChecked] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [releaseError, setReleaseError] = useState('');
  const [revokingId, setRevokingId] = useState('');
  const [selectingId, setSelectingId] = useState('');
  const [connectHint, setConnectHint] = useState(false);
  const userCode = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    return normalizeUserCode(params.get('user_code') || params.get('userCode') || '');
  }, []);

  async function refreshDevices({ quiet = false } = {}) {
    if (!quiet) setRefreshing(true);
    try {
      setDevices(await fetchDevices(request));
      setError('');
    } catch (err) {
      if (!quiet) setError(err.message || 'Local Agent 상태를 불러오지 못했습니다.');
    } finally {
      if (!quiet) setRefreshing(false);
      setLoading(false);
    }
  }

  useEffect(() => {
    let active = true;
    refreshDevices();
    fetchReleaseMetadata()
      .then((metadata) => {
        if (active) setRelease(metadata);
      })
      .catch((err) => {
        if (active) setReleaseError(err.message || '설치 파일 정보를 확인하지 못했습니다.');
      })
      .finally(() => {
        if (active) setReleaseChecked(true);
      });
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') refreshDevices({ quiet: true });
    }, POLL_INTERVAL_MS);
    return () => { active = false; window.clearInterval(timer); };
  }, []);

  function openAgent() {
    setConnectHint(true);
    window.location.assign(LOCAL_AGENT_ENDPOINTS.connectProtocol);
  }

  async function handleRevoke(device) {
    if (!window.confirm(`${device.machineName}의 LearnBot 연결을 해제할까요?`)) return;
    setRevokingId(device.id);
    try {
      await revokeDevice(request, device.agentId || device.id);
      await refreshDevices({ quiet: true });
    } catch (err) {
      setError(err.message || 'PC 연결을 해제하지 못했습니다.');
    } finally {
      setRevokingId('');
    }
  }

  async function handleSelect(device) {
    setSelectingId(device.id);
    setError('');
    try {
      await selectDevice(request, device.agentId || device.id);
      await refreshDevices({ quiet: true });
    } catch (err) {
      setError(err.message || '작업 PC를 변경하지 못했습니다.');
    } finally {
      setSelectingId('');
    }
  }

  const connectedCount = devices.filter(isConnected).length;
  const updateRequiredCount = devices.filter(needsUpdate).length;
  const installerUrl = release?.installerUrl;

  return (
    <main className="local-agent-page">
      <header className="local-agent-hero">
        <div>
          <span className="local-agent-eyebrow">LearnBot for Windows</span>
          <h1>내 PC를 안전하게 연결하세요</h1>
          <p>Local Agent는 사용자가 허용한 작업 폴더에서만 승인된 작업을 실행합니다. PC는 서버로 암호화된 outbound 연결만 시작합니다.</p>
        </div>
        <div className="local-agent-summary" aria-label="Local Agent 연결 요약">
          <div><strong>{devices.length}</strong><span>등록된 PC</span></div>
          <div><strong>{connectedCount}</strong><span>현재 연결</span></div>
          <div><strong>{updateRequiredCount}</strong><span>업데이트 필요</span></div>
        </div>
      </header>

      {approvalMode && (userCode
        ? <EnrollmentApproval request={request} userCode={userCode} onFinished={() => refreshDevices({ quiet: true })} />
        : <div className="local-agent-notice error"><AlertTriangle size={18} />승인 코드가 없습니다. Local Agent에서 연결을 다시 시작하세요.</div>)}

      <section className="local-agent-steps" aria-label="Local Agent 설치 단계">
        <article className="local-agent-step">
          <b>1</b><span className="local-agent-icon"><Download size={22} /></span><h2>Windows 앱 설치</h2>
          <p>설치 파일을 내려받아 Windows App Installer에서 설치하세요. 관리자 권한이나 .NET 설치는 필요하지 않습니다.</p>
          {installerUrl
            ? <a className="local-agent-download" href={installerUrl} download><Download size={17} /> Windows 11 x64용 다운로드</a>
            : <Button type="button" disabled><Download size={17} /> {releaseChecked ? '설치 파일 준비 중' : '설치 파일 확인 중'}</Button>}
          <small>{release ? `최신 버전 ${release.version} · 다운로드한 .appinstaller 파일을 열고 설치를 누르세요.` : '서명된 운영 패키지가 게시되면 다운로드할 수 있습니다.'}</small>
          {releaseError && <small className="local-agent-error-text">{releaseError}</small>}
        </article>
        <article className="local-agent-step">
          <b>2</b><span className="local-agent-icon"><PlugZap size={22} /></span><h2>이 PC 연결</h2>
          <p>설치가 끝나면 LearnBot을 실행해 이 계정과 연결하세요.</p>
          <Button type="button" onClick={openAgent}><ExternalLink size={16} /> 이 PC 연결</Button>
          {connectHint && <small>앱이 열리지 않으면 Windows 시작 메뉴에서 LearnBot을 실행하세요.</small>}
        </article>
        <article className="local-agent-step">
          <b>3</b><span className="local-agent-icon"><ShieldCheck size={22} /></span><h2>승인과 폴더 선택</h2>
          <p>PC 정보를 확인해 승인한 뒤 접근을 허용할 폴더만 선택합니다.</p>
          <ul><li>사용자별 암호화 자격 증명</li><li>PC로 들어오는 네트워크 포트 없음</li><li>임의 셸 명령 실행 없음</li></ul>
        </article>
      </section>

      <section className="local-agent-panel" aria-labelledby="local-agent-devices-title">
        <div className="local-agent-heading device-heading">
          <div><span className="local-agent-eyebrow">연결 관리</span><h2 id="local-agent-devices-title">등록된 PC</h2><p>10초마다 자동으로 갱신됩니다.</p></div>
          <Button variant="outline" type="button" onClick={() => refreshDevices()} disabled={refreshing}>
            {refreshing ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />} 새로고침
          </Button>
        </div>
        {error && <div className="local-agent-notice error"><AlertTriangle size={18} />{error}</div>}
        {loading && <div className="local-agent-notice"><Loader2 className="spin" size={18} />등록된 PC를 확인하는 중입니다.</div>}
        {!loading && !devices.length && <div className="local-agent-empty"><Laptop size={28} /><strong>아직 연결된 PC가 없습니다.</strong><p>위의 세 단계를 완료하면 연결 상태가 표시됩니다.</p></div>}
        <div className="local-agent-devices">
          {devices.map((device) => (
            <article className="local-agent-device" key={device.id}>
              <div className="local-agent-device-title"><span className={`local-agent-icon ${isConnected(device) ? 'online' : ''}`}><Laptop size={21} /></span><div><h3>{device.machineName}</h3><p>{device.platform || 'Windows'} {device.architecture || device.arch || 'x64'} · Agent {device.version}</p></div>{device.selected && <Badge>현재 작업 PC</Badge>}<DeviceStateBadge device={device} /></div>
              <dl className="local-agent-detail-grid device-meta">
                <div><dt>최근 접속</dt><dd><Clock3 size={14} />{formatTimestamp(device.lastSeenAt)}</dd></div>
                <div><dt>연결 방식</dt><dd>{device.transport}</dd></div>
                <div><dt>허용 폴더</dt><dd>{device.workspaces.length}개</dd></div>
                <div><dt>업데이트</dt><dd>{needsUpdate(device) ? '필수 업데이트 필요' : hasOptionalUpdate(device) ? '선택 업데이트 가능' : '최신 상태'}</dd></div>
              </dl>
              {device.workspaces.length > 0 && <div className="local-agent-workspaces">{device.workspaces.map((workspace) => <span key={workspace.id || workspace.path || workspace.name}>{workspace.name || workspace.path || workspace.id}</span>)}</div>}
              {needsUpdate(device) && <div className="local-agent-notice warning"><AlertTriangle size={17} />업데이트 전까지 새 작업을 받을 수 없습니다.</div>}
              <div className="local-agent-actions end">{!device.selected && <Button variant="outline" type="button" onClick={() => handleSelect(device)} disabled={selectingId === device.id}>{selectingId === device.id ? <Loader2 className="spin" size={15} /> : <Laptop size={15} />}작업 PC로 선택</Button>}<Button variant="outline" type="button" onClick={openAgent}><ExternalLink size={15} />설정 열기</Button><Button variant="ghost" type="button" onClick={() => handleRevoke(device)} disabled={revokingId === device.id}>{revokingId === device.id ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}연결 해제</Button></div>
            </article>
          ))}
        </div>
      </section>
    </main>
  );
}

import { useEffect, useState } from 'react';
import { AlertTriangle, Bot, CheckCircle2, Database, Download, FileUp, Globe, Info, Loader2, LockKeyhole, RefreshCw, Search, ShieldCheck, Trash2, UserPlus, Users, X } from 'lucide-react';
import { defaultSpaceId } from '../../config/constants.js';
import { formatBrandText, formatDate, formatFileSize, formatTransferCounts } from '../../lib/formatters.js';
import { IconButton, StatusBadge } from '../common/Common.jsx';
import { CodeSourceManagementPanel } from '../code/CodeWorkspace.jsx';
import { DocumentSourcePanel } from '../documents/DocumentWorkspace.jsx';

function AdminWorkspace({
  currentUser,
  users,
  adminSettings,
  spaces,
  selectedSpaceId,
  auditLogs,
  inviteForm,
  setInviteForm,
  spaceForm,
  setSpaceForm,
  createSpace,
  inviteUser,
  deleteAdminUser,
  updateAdminUser,
  resetAdminUserPassword,
  saveAdminUserSpaceRoles,
  updateSpace,
  deleteSpace,
  exportSpaceArchive,
  importSpaceArchive,
  downloadSpaceArchive,
  spaceTransferResult,
  updateAdminSettings,
  testAdminLlmSettings,
  refreshAdmin,
  loading,
  codeSourceProps,
  documentSourceProps,
}) {
  const [activeAdminTab, setActiveAdminTab] = useState('settings');
  const [editingSpaceId, setEditingSpaceId] = useState('');
  const [spaceEditForm, setSpaceEditForm] = useState({ name: '', description: '' });
  const [allowedDomainText, setAllowedDomainText] = useState(() => (adminSettings?.allowedDomains || []).join('\n'));
  const [allowedDomainsOpen, setAllowedDomainsOpen] = useState(false);
  const [editingUser, setEditingUser] = useState(null);
  const [userEditForm, setUserEditForm] = useState({ loginId: '', displayName: '', role: 'USER' });
  const [passwordUser, setPasswordUser] = useState(null);
  const [passwordForm, setPasswordForm] = useState({ newPassword: '', confirmPassword: '' });
  const [permissionsUser, setPermissionsUser] = useState(null);
  const [permissionDraft, setPermissionDraft] = useState({});
  const [importSpace, setImportSpace] = useState(null);
  const [importFile, setImportFile] = useState(null);
  const [modalError, setModalError] = useState('');
  const [llmForm, setLlmForm] = useState({
    ollamaBaseUrl: adminSettings?.ollamaBaseUrl || '',
    primaryChatModel: adminSettings?.primaryChatModel || adminSettings?.chatModel || '',
    auxiliaryChatModel: adminSettings?.auxiliaryChatModel || '',
  });
  const [llmTestResult, setLlmTestResult] = useState(null);

  useEffect(() => {
    setAllowedDomainText((adminSettings?.allowedDomains || []).join('\n'));
  }, [adminSettings?.allowedDomains]);

  useEffect(() => {
    setLlmForm({
      ollamaBaseUrl: adminSettings?.ollamaBaseUrl || '',
      primaryChatModel: adminSettings?.primaryChatModel || adminSettings?.chatModel || '',
      auxiliaryChatModel: adminSettings?.auxiliaryChatModel || '',
    });
    setLlmTestResult(null);
  }, [adminSettings?.ollamaBaseUrl, adminSettings?.chatModel, adminSettings?.primaryChatModel, adminSettings?.auxiliaryChatModel]);

  function beginEditSpace(space) {
    setEditingSpaceId(space.id);
    setSpaceEditForm({ name: space.name || '', description: space.description || '' });
  }

  function closeUserModals() {
    setEditingUser(null);
    setPasswordUser(null);
    setPermissionsUser(null);
    setModalError('');
    setUserEditForm({ loginId: '', displayName: '', role: 'USER' });
    setPasswordForm({ newPassword: '', confirmPassword: '' });
    setPermissionDraft({});
  }

  function beginEditUser(item) {
    setModalError('');
    setEditingUser(item);
    setUserEditForm({ loginId: item.loginId || item.email || '', displayName: item.displayName || '', role: item.role || 'USER' });
  }

  function beginPasswordReset(item) {
    setModalError('');
    setPasswordUser(item);
    setPasswordForm({ newPassword: '', confirmPassword: '' });
  }

  function beginPermissionEdit(item) {
    if (item.role === 'ADMIN') {
      return;
    }
    const draft = {};
    (item.spaces || []).forEach((space) => {
      draft[space.id] = space.role;
    });
    setModalError('');
    setPermissionsUser(item);
    setPermissionDraft(draft);
  }

  async function submitUserEdit(event) {
    event.preventDefault();
    if (!editingUser) return;
    const saved = await updateAdminUser(editingUser.id, userEditForm);
    if (saved) closeUserModals();
  }

  async function submitPasswordReset(event) {
    event.preventDefault();
    if (!passwordUser) return;
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      setModalError('비밀번호 확인이 일치하지 않습니다.');
      return;
    }
    const saved = await resetAdminUserPassword(passwordUser.id, passwordForm.newPassword);
    if (saved) closeUserModals();
  }

  async function submitPermissionEdit(event) {
    event.preventDefault();
    if (!permissionsUser) return;
    const currentRoles = new Map((permissionsUser.spaces || []).map((space) => [space.id, space.role]));
    const operations = spaces
      .map((space) => {
        const currentRole = currentRoles.get(space.id) || '';
        const nextRole = permissionDraft[space.id] || '';
        if (currentRole === nextRole) return null;
        return { spaceId: space.id, role: nextRole };
      })
      .filter(Boolean);
    if (!operations.length) {
      closeUserModals();
      return;
    }
    const saved = await saveAdminUserSpaceRoles(permissionsUser.id, operations);
    if (saved) closeUserModals();
  }

  async function submitSpaceEdit(event, spaceId) {
    event.preventDefault();
    await updateSpace(spaceId, spaceEditForm);
    setEditingSpaceId('');
    setSpaceEditForm({ name: '', description: '' });
  }

  function openAllowedDomains() {
    setAllowedDomainText((adminSettings?.allowedDomains || []).join('\n'));
    setAllowedDomainsOpen(true);
  }

  function closeAllowedDomains() {
    setAllowedDomainText((adminSettings?.allowedDomains || []).join('\n'));
    setAllowedDomainsOpen(false);
  }

  async function submitAllowedDomains(event) {
    event.preventDefault();
    const allowedDomains = allowedDomainText
      .split(/[,\n]+/)
      .map((item) => item.trim())
      .filter(Boolean);
    const saved = await updateAdminSettings({
      respectRobotsTxt: adminSettings?.respectRobotsTxt ?? true,
      allowedDomains,
    });
    if (saved) {
      setAllowedDomainsOpen(false);
    }
  }

  async function submitLlmSettings(event) {
    event.preventDefault();
    await updateAdminSettings({
      ollamaBaseUrl: llmForm.ollamaBaseUrl,
      primaryChatModel: llmForm.primaryChatModel,
      auxiliaryChatModel: llmForm.auxiliaryChatModel,
    });
  }

  async function testLlmSettings() {
    const result = await testAdminLlmSettings({
      ollamaBaseUrl: llmForm.ollamaBaseUrl,
      primaryChatModel: llmForm.primaryChatModel,
      auxiliaryChatModel: llmForm.auxiliaryChatModel,
    });
    if (result && typeof result === 'object') {
      setLlmTestResult(result);
    }
  }

  function beginSpaceImport(space) {
    setImportSpace(space);
    setImportFile(null);
    setModalError('');
  }

  function closeSpaceImport() {
    setImportSpace(null);
    setImportFile(null);
    setModalError('');
  }

  async function submitSpaceImport(event) {
    event.preventDefault();
    if (!importSpace || !importFile) return;
    const result = await importSpaceArchive(importSpace.id, importFile);
    if (result) closeSpaceImport();
  }

  const transferSpaceName = spaces.find((space) => space.id === spaceTransferResult?.spaceId)?.name || '';
  const allowedDomains = adminSettings?.allowedDomains || [];
  const allowedDomainPreview = allowedDomains.slice(0, 6);
  const adminTabs = (
    <div className="admin-tabs" role="tablist" aria-label="관리자 메뉴">
      <button
        className={activeAdminTab === 'settings' ? 'mode-button active' : 'mode-button'}
        type="button"
        role="tab"
        aria-selected={activeAdminTab === 'settings'}
        onClick={() => setActiveAdminTab('settings')}
      >
        관리자 설정
      </button>
      <button
        className={activeAdminTab === 'sources' ? 'mode-button active' : 'mode-button'}
        type="button"
        role="tab"
        aria-selected={activeAdminTab === 'sources'}
        onClick={() => setActiveAdminTab('sources')}
      >
        코드/문서 등록
      </button>
    </div>
  );

  if (activeAdminTab === 'sources') {
    return (
      <>
        {adminTabs}
        <section className="workspace-grid admin-source-grid">
          <div className="left-column">
          <CodeSourceManagementPanel {...(codeSourceProps || {})} loading={loading} />
          </div>
          <div className="right-column">
            <DocumentSourcePanel {...(documentSourceProps || {})} loading={loading} />
          </div>
        </section>
      </>
    );
  }

  return (
    <>
    {adminTabs}
    <section className="workspace-grid">
      <div className="left-column">
        <section className="panel">
          <div className="panel-title">
            <ShieldCheck size={18} />
            <div>
              <h2>크롤링 정책</h2>
              <p>전체 사용자의 웹 인덱싱 정책을 관리합니다.</p>
            </div>
          </div>
          <label className="switch-row" htmlFor="respect-robots">
            <input
              id="respect-robots"
              type="checkbox"
              checked={adminSettings?.respectRobotsTxt ?? true}
              disabled={loading('admin-settings')}
              onChange={(event) => updateAdminSettings({ respectRobotsTxt: event.target.checked })}
            />
            <span className="switch-track" aria-hidden="true">
              <span />
            </span>
            <span>
              <strong>robots.txt 정책 준수</strong>
              <small>
                {(adminSettings?.respectRobotsTxt ?? true)
                  ? '켜짐 · robots.txt에서 차단한 URL은 인덱싱하지 않습니다.'
                  : '꺼짐 · robots.txt에서 차단한 URL도 인덱싱을 진행합니다.'}
              </small>
            </span>
          </label>
          <div className="allowed-domain-card">
            <div>
              <strong>허용 URL / 도메인</strong>
              <small>{allowedDomains.length ? `${allowedDomains.length}개 등록됨` : '등록된 허용 도메인이 없습니다.'}</small>
            </div>
            {allowedDomains.length > 0 ? (
              <div className="allowed-domain-chips">
                {allowedDomainPreview.map((domain) => (
                  <span key={domain} title={domain}>{domain}</span>
                ))}
                {allowedDomains.length > allowedDomainPreview.length && <span>+{allowedDomains.length - allowedDomainPreview.length}</span>}
              </div>
            ) : (
              <p className="field-help">허용 목록을 등록하면 해당 도메인과 하위 도메인만 웹 인덱싱할 수 있습니다.</p>
            )}
            <div className="action-row">
              <button className="ghost-button" type="button" disabled={loading('admin-settings')} onClick={openAllowedDomains}>
                <Globe size={16} />
                허용 목록 편집
              </button>
            </div>
          </div>
        </section>

        <form className="panel" onSubmit={submitLlmSettings}>
          <div className="panel-title">
            <Bot size={18} />
            <div>
              <h2>LLM 설정</h2>
              <p>{adminSettings?.llmUsingDefaults ? '시스템 기본값 사용 중' : '관리자 설정 사용 중'}</p>
            </div>
          </div>
          <div className="form-grid">
            <div className="stack">
              <label htmlFor="llm-ollama-url">Ollama 주소 / 포트</label>
              <input
                id="llm-ollama-url"
                value={llmForm.ollamaBaseUrl}
                onChange={(event) => setLlmForm((current) => ({ ...current, ollamaBaseUrl: event.target.value }))}
                placeholder={adminSettings?.effectiveOllamaBaseUrl || 'http://ollama:11434'}
                spellCheck="false"
              />
            </div>
          </div>
          <div className="form-grid two">
            <div className="stack">
              <label htmlFor="llm-primary-model">메인 모델</label>
              <input
                id="llm-primary-model"
                value={llmForm.primaryChatModel}
                onChange={(event) => setLlmForm((current) => ({ ...current, primaryChatModel: event.target.value }))}
                placeholder={adminSettings?.effectivePrimaryChatModel || 'qwen3:8b-q4_K_M'}
                spellCheck="false"
              />
            </div>
            <div className="stack">
              <label htmlFor="llm-auxiliary-model">보조 모델</label>
              <input
                id="llm-auxiliary-model"
                value={llmForm.auxiliaryChatModel}
                onChange={(event) => setLlmForm((current) => ({ ...current, auxiliaryChatModel: event.target.value }))}
                placeholder={adminSettings?.effectiveAuxiliaryChatModel || 'qwen3.5:2b-q4_K_M'}
                spellCheck="false"
              />
            </div>
          </div>
          <small className="field-help">
            빈 값은 기본 메인/보조 모델을 사용합니다. 포트만 입력하면 host.docker.internal 기준으로 연결합니다.
          </small>
          <div className="detail-box compact-box llm-effective-box">
            <strong>현재 적용값</strong>
            <small>주소: {adminSettings?.effectiveOllamaBaseUrl || '-'}</small>
            <small>메인: {adminSettings?.effectivePrimaryChatModel || adminSettings?.effectiveChatModel || '-'}</small>
            <small>보조: {adminSettings?.effectiveAuxiliaryChatModel || '-'}</small>
          </div>
          {llmTestResult && (
            <div className={llmTestResult.success ? 'success-note llm-test-result' : 'danger-note llm-test-result'}>
              {llmTestResult.message}
              <small>메인: {llmTestResult.primaryModel || llmTestResult.model || '-'}</small>
              <small>보조: {llmTestResult.auxiliaryModel || '-'}</small>
              {llmTestResult.availableModels?.length > 0 && (
                <small>사용 가능 모델: {llmTestResult.availableModels.slice(0, 5).join(', ')}</small>
              )}
            </div>
          )}
          <div className="action-row">
            <button type="button" className="ghost-button" disabled={loading('admin-llm-test') || loading('admin-settings')} onClick={testLlmSettings}>
              {loading('admin-llm-test') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              연결 테스트
            </button>
            <button disabled={loading('admin-settings') || loading('admin-llm-test')}>
              {loading('admin-settings') ? <Loader2 className="spin" size={16} /> : <Bot size={16} />}
              LLM 설정 저장
            </button>
          </div>
        </form>

        <form className="panel" onSubmit={inviteUser}>
          <div className="panel-title">
            <UserPlus size={18} />
            <div>
              <h2>사용자 초대</h2>
              <p>현재 선택된 공간에 사용자를 추가합니다.</p>
            </div>
          </div>
          <div className="form-grid two">
            <div className="stack">
              <label htmlFor="invite-login-id">ID</label>
              <input id="invite-login-id" value={inviteForm.loginId} onChange={(event) => setInviteForm((current) => ({ ...current, loginId: event.target.value }))} autoComplete="off" spellCheck="false" />
            </div>
            <div className="stack">
              <label htmlFor="invite-name">표시 이름</label>
              <input id="invite-name" value={inviteForm.displayName} onChange={(event) => setInviteForm((current) => ({ ...current, displayName: event.target.value }))} />
            </div>
          </div>
          <div className="form-grid two">
            <div className="stack">
              <label htmlFor="invite-password">초기 비밀번호</label>
              <input id="invite-password" type="password" value={inviteForm.initialPassword} onChange={(event) => setInviteForm((current) => ({ ...current, initialPassword: event.target.value }))} />
            </div>
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="invite-role">시스템 권한</label>
                <select id="invite-role" value={inviteForm.role} onChange={(event) => setInviteForm((current) => ({ ...current, role: event.target.value }))}>
                  <option value="USER">USER</option>
                  <option value="ADMIN">ADMIN</option>
                </select>
              </div>
              <div className="stack">
                <label htmlFor="invite-space-role">공간 권한</label>
                <select id="invite-space-role" value={inviteForm.spaceRole} onChange={(event) => setInviteForm((current) => ({ ...current, spaceRole: event.target.value }))}>
                  <option value="MEMBER">MEMBER</option>
                  <option value="OWNER">OWNER</option>
                </select>
              </div>
            </div>
          </div>
          <div className="detail-box compact-box">
            <strong>{spaces.find((space) => space.id === selectedSpaceId)?.name || '선택된 공간'}</strong>
            <small>초대 사용자는 이 공간의 자료만 접근합니다.</small>
          </div>
          <div className="action-row">
            <button disabled={!inviteForm.loginId || !inviteForm.displayName || !inviteForm.initialPassword || loading('user-invite')}>
              {loading('user-invite') ? <Loader2 className="spin" size={16} /> : <UserPlus size={16} />}
              초대
            </button>
          </div>
        </form>

        <form className="panel" onSubmit={createSpace}>
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>공간 생성</h2>
              <p>팀, 제품, 보안 등 접근 범위별로 RAG 데이터를 분리합니다.</p>
            </div>
          </div>
          <div className="stack">
            <label htmlFor="space-name">공간 이름</label>
            <input id="space-name" value={spaceForm.name} onChange={(event) => setSpaceForm((current) => ({ ...current, name: event.target.value }))} />
          </div>
          <div className="stack">
            <label htmlFor="space-description">설명</label>
            <textarea id="space-description" value={spaceForm.description} onChange={(event) => setSpaceForm((current) => ({ ...current, description: event.target.value }))} />
          </div>
          <div className="action-row">
            <button disabled={!spaceForm.name || loading('space-create')}>
              {loading('space-create') ? <Loader2 className="spin" size={16} /> : <Database size={16} />}
              공간 생성
            </button>
          </div>
        </form>
      </div>

      <div className="right-column">
        <section className="panel">
          <div className="panel-title">
            <Users size={18} />
            <div>
              <h2>사용자</h2>
              <p>{users.length}개 계정</p>
            </div>
          </div>
          <div className="document-list">
            {users.map((item) => (
              <article className="document-row" key={item.id}>
                <div className="document-main">
                  <strong>{formatBrandText(item.displayName)}</strong>
                  <small>{item.loginId || item.email}</small>
                  <small>{item.role === 'ADMIN' ? '전체 공간 접근' : `${item.spaces?.length || 0}개 공간 권한`}</small>
                </div>
                <div className="document-meta">
                  <StatusBadge status={item.status} />
                  <small>{item.role}</small>
                </div>
                <div className="document-actions">
                  <IconButton title="계정 편집" onClick={() => beginEditUser(item)}>
                    <Info size={15} />
                  </IconButton>
                  <IconButton
                    title={item.id === currentUser?.id ? '현재 로그인한 계정의 비밀번호는 여기서 재설정할 수 없습니다.' : '비밀번호 재설정'}
                    disabled={item.id === currentUser?.id || loading(`user-password-${item.id}`)}
                    onClick={() => beginPasswordReset(item)}
                  >
                    {loading(`user-password-${item.id}`) ? <Loader2 className="spin" size={15} /> : <LockKeyhole size={15} />}
                  </IconButton>
                  <IconButton
                    title={item.role === 'ADMIN' ? 'ADMIN 계정은 모든 공간에 접근합니다.' : '공간 권한 관리'}
                    disabled={item.role === 'ADMIN' || loading(`user-spaces-${item.id}`)}
                    onClick={() => beginPermissionEdit(item)}
                  >
                    {loading(`user-spaces-${item.id}`) ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}
                  </IconButton>
                  <IconButton
                    danger
                    title={item.id === currentUser?.id ? '현재 로그인한 계정은 삭제할 수 없습니다.' : '사용자 삭제'}
                    disabled={item.id === currentUser?.id || loading(`user-delete-${item.id}`)}
                    onClick={() => deleteAdminUser(item.id, formatBrandText(item.displayName))}
                  >
                    {loading(`user-delete-${item.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                  </IconButton>
                </div>
              </article>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>공간 관리</h2>
              <p>{spaces.length}개 공간</p>
            </div>
          </div>
          {spaceTransferResult?.type === 'export' && (
            <div className="transfer-note success-note">
              <strong>{transferSpaceName || '공간'} Export 완료</strong>
              <span>{spaceTransferResult.result?.relativePath || './export'}</span>
              <small>{formatFileSize(spaceTransferResult.result?.sizeBytes)} · {formatTransferCounts(spaceTransferResult.result?.counts)}</small>
              {spaceTransferResult.result?.fileName && (
                <button
                  className="ghost-button compact-action"
                  type="button"
                  disabled={loading(`space-download-${spaceTransferResult.spaceId}`)}
                  onClick={() => downloadSpaceArchive(spaceTransferResult.spaceId, spaceTransferResult.result.fileName)}
                >
                  {loading(`space-download-${spaceTransferResult.spaceId}`) ? <Loader2 className="spin" size={14} /> : <Download size={14} />}
                  다운로드
                </button>
              )}
            </div>
          )}
          {spaceTransferResult?.type === 'import' && (
            <div className="transfer-note success-note">
              <strong>{transferSpaceName || '공간'} Import 완료</strong>
              <span>가져온 데이터: {formatTransferCounts(spaceTransferResult.result?.imported)}</span>
              <small>건너뜀: {formatTransferCounts(spaceTransferResult.result?.skipped)}</small>
            </div>
          )}
          <div className="document-list">
            {spaces.map((space) => (
              <article className="document-row space-admin-row" key={space.id}>
                {editingSpaceId === space.id ? (
                  <form className="space-edit-form" onSubmit={(event) => submitSpaceEdit(event, space.id)}>
                    <input
                      value={spaceEditForm.name}
                      onChange={(event) => setSpaceEditForm((current) => ({ ...current, name: event.target.value }))}
                      placeholder="공간 이름"
                    />
                    <textarea
                      value={spaceEditForm.description}
                      onChange={(event) => setSpaceEditForm((current) => ({ ...current, description: event.target.value }))}
                      placeholder="설명"
                    />
                    <div className="action-row">
                      <button disabled={!spaceEditForm.name || loading(`space-update-${space.id}`)}>
                        {loading(`space-update-${space.id}`) ? <Loader2 className="spin" size={15} /> : <CheckCircle2 size={15} />}
                        저장
                      </button>
                      <button className="ghost-button" type="button" onClick={() => setEditingSpaceId('')}>취소</button>
                    </div>
                  </form>
                ) : (
                  <>
                    <div className="document-main">
                      <strong>{space.name}</strong>
                      <small>{space.description || '설명 없음'}</small>
                    </div>
                    <div className="document-meta">
                      <small>{space.role} · {formatDate(space.createdAt)}</small>
                    </div>
                    <div className="document-actions">
                      <IconButton
                        title="RAG 데이터 Export"
                        disabled={loading(`space-export-${space.id}`)}
                        onClick={() => exportSpaceArchive(space.id)}
                      >
                        {loading(`space-export-${space.id}`) ? <Loader2 className="spin" size={15} /> : <Download size={15} />}
                      </IconButton>
                      <IconButton
                        title="RAG 데이터 Import"
                        disabled={loading(`space-import-${space.id}`)}
                        onClick={() => beginSpaceImport(space)}
                      >
                        {loading(`space-import-${space.id}`) ? <Loader2 className="spin" size={15} /> : <FileUp size={15} />}
                      </IconButton>
                      <IconButton title="공간 이름/설명 편집" onClick={() => beginEditSpace(space)}>
                        <Info size={15} />
                      </IconButton>
                      <IconButton
                        danger
                        title={space.id === defaultSpaceId ? '기본 공간은 삭제할 수 없습니다.' : '공간 삭제'}
                        disabled={space.id === defaultSpaceId || loading(`space-delete-${space.id}`)}
                        onClick={() => deleteSpace(space.id, space.name)}
                      >
                        {loading(`space-delete-${space.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                      </IconButton>
                    </div>
                  </>
                )}
              </article>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-title">
            <ShieldCheck size={18} />
            <div>
              <h2>감사 로그</h2>
              <p>핵심 작업 이력을 추적합니다.</p>
            </div>
          </div>
          <div className="top-actions">
            <button className="ghost-button compact-action" type="button" onClick={refreshAdmin}>
              <RefreshCw size={14} />
              새로고침
            </button>
          </div>
          <div className="results audit-list">
            {auditLogs.map((log) => (
              <article className="result" key={log.id}>
                <div className="result-heading">
                  <strong>{log.action}</strong>
                  <span>{formatDate(log.createdAt)}</span>
                </div>
                <small>{log.actorLoginId || log.actorEmail || 'system'} · {log.spaceName || 'global'}</small>
                <p>{log.message}</p>
              </article>
            ))}
          </div>
        </section>
      </div>

      {editingUser && (
        <AdminUserModal title="계정 편집" subtitle={editingUser.loginId || editingUser.email} icon={<Info size={18} />} onClose={closeUserModals}>
          <form className="admin-modal-form" onSubmit={submitUserEdit}>
            <div className="stack">
              <label htmlFor="edit-user-login-id">ID</label>
              <input
                id="edit-user-login-id"
                value={userEditForm.loginId}
                disabled={editingUser.id === currentUser?.id}
                onChange={(event) => setUserEditForm((current) => ({ ...current, loginId: event.target.value }))}
                autoComplete="off"
                spellCheck="false"
              />
              {editingUser.id === currentUser?.id && <small className="field-help">현재 로그인한 관리자 계정의 ID는 이 화면에서 변경할 수 없습니다.</small>}
            </div>
            <div className="stack">
              <label htmlFor="edit-user-name">표시 이름</label>
              <input
                id="edit-user-name"
                value={userEditForm.displayName}
                onChange={(event) => setUserEditForm((current) => ({ ...current, displayName: event.target.value }))}
              />
            </div>
            <div className="stack">
              <label htmlFor="edit-user-role">시스템 권한</label>
              <select
                id="edit-user-role"
                value={userEditForm.role}
                disabled={editingUser.id === currentUser?.id}
                onChange={(event) => setUserEditForm((current) => ({ ...current, role: event.target.value }))}
              >
                <option value="USER">USER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
              {editingUser.id === currentUser?.id && <small className="field-help">현재 로그인한 관리자 계정의 시스템 권한은 이 화면에서 변경할 수 없습니다.</small>}
            </div>
            <div className="action-row">
              <button disabled={!userEditForm.loginId || !userEditForm.displayName || loading(`user-update-${editingUser.id}`)}>
                {loading(`user-update-${editingUser.id}`) ? <Loader2 className="spin" size={16} /> : <CheckCircle2 size={16} />}
                저장
              </button>
              <button className="ghost-button" type="button" onClick={closeUserModals}>취소</button>
            </div>
          </form>
        </AdminUserModal>
      )}

      {passwordUser && (
        <AdminUserModal title="비밀번호 재설정" subtitle={passwordUser.loginId || passwordUser.email} icon={<LockKeyhole size={18} />} onClose={closeUserModals}>
          <form className="admin-modal-form" onSubmit={submitPasswordReset}>
            {modalError && <div className="failure-line"><AlertTriangle size={14} />{modalError}</div>}
            <div className="stack">
              <label htmlFor="reset-password">새 임시 비밀번호</label>
              <input
                id="reset-password"
                type="password"
                value={passwordForm.newPassword}
                onChange={(event) => setPasswordForm((current) => ({ ...current, newPassword: event.target.value }))}
              />
            </div>
            <div className="stack">
              <label htmlFor="reset-password-confirm">새 임시 비밀번호 확인</label>
              <input
                id="reset-password-confirm"
                type="password"
                value={passwordForm.confirmPassword}
                onChange={(event) => setPasswordForm((current) => ({ ...current, confirmPassword: event.target.value }))}
              />
              <small className="field-help">저장하면 해당 사용자의 기존 로그인 세션이 만료됩니다.</small>
            </div>
            <div className="action-row">
              <button disabled={!passwordForm.newPassword || !passwordForm.confirmPassword || loading(`user-password-${passwordUser.id}`)}>
                {loading(`user-password-${passwordUser.id}`) ? <Loader2 className="spin" size={16} /> : <LockKeyhole size={16} />}
                재설정
              </button>
              <button className="ghost-button" type="button" onClick={closeUserModals}>취소</button>
            </div>
          </form>
        </AdminUserModal>
      )}

      {permissionsUser && (
        <AdminUserModal title="공간 권한 관리" subtitle={permissionsUser.loginId || permissionsUser.email} icon={<ShieldCheck size={18} />} onClose={closeUserModals}>
          <form className="admin-modal-form" onSubmit={submitPermissionEdit}>
            {permissionsUser.role === 'ADMIN' && (
              <div className="detail-box compact-box">
                <strong>시스템 관리자</strong>
                <small>ADMIN 계정은 모든 공간에 접근할 수 있습니다. 아래 멤버십은 공간별 표시 권한으로 관리됩니다.</small>
              </div>
            )}
            <div className="permission-grid">
              {spaces.map((space) => (
                <label className="permission-row" key={space.id}>
                  <span>
                    <strong>{space.name}</strong>
                    <small>{space.description || '설명 없음'}</small>
                  </span>
                  <select
                    value={permissionDraft[space.id] || ''}
                    onChange={(event) => setPermissionDraft((current) => ({ ...current, [space.id]: event.target.value }))}
                  >
                    <option value="">없음</option>
                    <option value="MEMBER">MEMBER</option>
                    <option value="OWNER">OWNER</option>
                  </select>
                </label>
              ))}
            </div>
            <div className="action-row">
              <button disabled={loading(`user-spaces-${permissionsUser.id}`)}>
                {loading(`user-spaces-${permissionsUser.id}`) ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
                저장
              </button>
              <button className="ghost-button" type="button" onClick={closeUserModals}>취소</button>
            </div>
          </form>
        </AdminUserModal>
      )}

      {importSpace && (
        <AdminUserModal title="RAG 데이터 Import" subtitle={importSpace.name} icon={<FileUp size={18} />} onClose={closeSpaceImport}>
          <form className="admin-modal-form" onSubmit={submitSpaceImport}>
            {modalError && <div className="failure-line"><AlertTriangle size={14} />{modalError}</div>}
            <div className="detail-box compact-box">
              <strong>병합 가져오기</strong>
              <small>기존 데이터는 삭제하지 않고, 같은 문서 출처나 같은 Git 커밋 저장소는 건너뜁니다.</small>
              <small>Export 파일은 작업폴더 하위의 상대경로 `./export`에 생성된 ZIP을 선택하면 됩니다.</small>
            </div>
            <label className="file-picker import-file-picker" htmlFor="space-import-file">
              <FileUp size={16} />
              <span>{importFile?.name || 'ZIP 파일 선택'}</span>
            </label>
            <input
              className="visually-hidden"
              id="space-import-file"
              type="file"
              accept=".zip,application/zip,application/x-zip-compressed"
              onChange={(event) => setImportFile(event.target.files?.[0] || null)}
            />
            <div className="action-row">
              <button disabled={!importFile || loading(`space-import-${importSpace.id}`)}>
                {loading(`space-import-${importSpace.id}`) ? <Loader2 className="spin" size={16} /> : <FileUp size={16} />}
                Import
              </button>
              <button className="ghost-button" type="button" onClick={closeSpaceImport}>취소</button>
            </div>
          </form>
        </AdminUserModal>
      )}
      {allowedDomainsOpen && (
        <AdminUserModal
          title="허용 URL / 도메인 편집"
          subtitle={`${allowedDomains.length}개 등록됨`}
          icon={<Globe size={18} />}
          className="allowed-domain-modal"
          bodyClassName="allowed-domain-modal-body"
          onClose={closeAllowedDomains}
        >
          <form className="admin-modal-form allowed-domain-form" onSubmit={submitAllowedDomains}>
            <div className="allowed-domain-editor-head">
              <strong>웹 인덱싱 허용 목록</strong>
              <small>
                한 줄에 하나씩 입력하거나 쉼표로 구분하세요. 전체 URL을 입력해도 서버에서 호스트만 저장합니다.
              </small>
            </div>
            <textarea
              id="allowed-domains-modal"
              value={allowedDomainText}
              onChange={(event) => setAllowedDomainText(event.target.value)}
              placeholder={'example.com\nhttps://docs.example.com/guide\nintranet.local'}
              spellCheck="false"
              autoFocus
            />
            <small className="field-help">
              예: `https://docs.example.com/guide` → `docs.example.com`. 등록한 도메인과 그 하위 도메인만 웹 인덱싱할 수 있습니다.
            </small>
            <div className="allowed-domain-footer">
              <button className="ghost-button" type="button" disabled={loading('admin-settings')} onClick={closeAllowedDomains}>
                취소
              </button>
              <button disabled={!allowedDomainText.trim() || loading('admin-settings')}>
                {loading('admin-settings') ? <Loader2 className="spin" size={16} /> : <Globe size={16} />}
                허용 목록 저장
              </button>
            </div>
          </form>
        </AdminUserModal>
      )}
    </section>
    </>
  );
}

function AdminUserModal({ title, subtitle, icon, children, onClose, className = '', bodyClassName = '' }) {
  return (
    <div className="code-modal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className={`code-modal document-preview-modal admin-user-modal ${className}`.trim()} role="dialog" aria-modal="true" aria-labelledby="admin-user-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            {icon}
            <div>
              <h2 id="admin-user-modal-title">{title}</h2>
              <p>{subtitle}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="닫기" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>
        <div className={`admin-user-modal-body ${bodyClassName}`.trim()}>
          {children}
        </div>
      </section>
    </div>
  );
}

export { AdminWorkspace };

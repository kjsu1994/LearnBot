import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Bot, CheckCircle2, Cpu, Database, Download, FileUp, Globe, Info, Loader2, LockKeyhole, RefreshCw, Search, ShieldCheck, SlidersHorizontal, Trash2, UserPlus, Users, X } from 'lucide-react';
import { IconActivity, IconDatabase, IconFiles, IconRefresh, IconSettings, IconShieldLock, IconUsersGroup, IconWorld } from '@tabler/icons-react';
import { defaultSpaceId } from '../../config/constants.js';
import { formatBrandText, formatDate, formatFileSize, formatTransferCounts } from '../../lib/formatters.js';
import { IconButton, StatusBadge } from '../common/Common.jsx';
import { CodeSourceManagementPanel } from '../code/CodeWorkspace.jsx';
import { DocumentSourcePanel } from '../documents/DocumentWorkspace.jsx';

const tuningText = {
  LLM_CONTEXT_WINDOW: ['LLM 문맥 길이', '질문, 근거, 답변 지시문을 모델에 넣을 수 있는 최대 토큰 수입니다.', '높을수록 긴 문서를 더 잘 보지만 메모리 사용량이 증가합니다.'],
  OLLAMA_CONTEXT_LENGTH: ['Ollama 컨텍스트 길이', 'Ollama 데몬이 모델에 허용하는 컨텍스트 길이입니다.', '저장 후 컨테이너 재시작이 필요합니다. LLM 문맥 길이와 맞추는 것을 권장합니다.'],
  RAG_PIPELINE_PROMPT_TOKEN_BUDGET_BALANCED: ['문서 답변 프롬프트 예산', '문서 답변에서 근거 청크와 질문에 배정할 토큰 예산입니다.', '높을수록 근거를 많이 넣지만 응답 속도가 느려질 수 있습니다.'],
  RAG_PIPELINE_CODE_CONTEXT_LIMIT: ['코드 답변에 넣을 청크 수', '코드 질문 답변에 사용할 코드 근거 청크 개수입니다.', '높을수록 관련 파일과 메서드를 더 많이 봅니다.'],
  RAG_PIPELINE_DOCUMENT_CONTEXT_LIMIT: ['문서 답변에 넣을 청크 수', '문서 질문 답변에 사용할 문서 근거 청크 개수입니다.', '높을수록 답변 근거가 늘고 속도는 느려질 수 있습니다.'],
  LEARNBOT_RAG_OVERVIEW_MAX_DOCUMENTS: ['전체 요약 탐색 문서 수', '개요/요약형 질문에서 서로 다른 문서를 최대 몇 개까지 포함할지 정합니다.', '높을수록 전체 맥락은 좋아지고 답변 시간이 늘어납니다.'],
  LEARNBOT_RAG_OVERVIEW_MAX_CODE_CATEGORIES: ['코드 개요 카테고리 수', '코드 구조/흐름 질문에서 서로 다른 범주의 근거를 최대 몇 개까지 쓸지 정합니다.', '높을수록 구조 답변이 풍부해지지만 잡음도 늘 수 있습니다.'],
  LEARNBOT_RAG_OVERVIEW_MAX_RECURSIVE_ITERATIONS: ['개요 탐색 반복 횟수', '근거가 부족할 때 추가 탐색을 몇 번까지 허용할지 정합니다.', '높을수록 품질은 좋아질 수 있지만 지연 시간이 늘어납니다.'],
  LLM_MAX_OUTPUT_TOKENS: ['답변 최대 길이', '모델이 생성할 수 있는 답변 토큰 상한입니다. 0은 자동값입니다.', '0은 기존 자동 길이 정책을 사용한다는 의미입니다. 너무 낮으면 답변이 잘릴 수 있고, 너무 높으면 응답이 느려질 수 있습니다.'],
  OLLAMA_MAX_LOADED_MODELS: ['동시 로드 모델 수', 'Ollama가 메모리에 동시에 올려둘 모델 수입니다.', 'VRAM/RAM이 충분하지 않으면 1을 권장합니다.'],
  OLLAMA_NUM_PARALLEL: ['Ollama 병렬 요청 수', 'Ollama가 한 모델에서 동시에 처리할 요청 수입니다.', '작은 장비는 1, 큰 GPU 서버만 2 이상을 권장합니다.'],
};

const presetText = {
  default: ['현재 기본값', '현재 서버 설정값을 그대로 사용합니다.'],
  performance: ['고성능', '품질을 올리되 일반적인 고사양 장비에서 무리하지 않는 권장값입니다.'],
  lab: ['실험실', '큰 컨텍스트와 많은 근거를 사용합니다. 응답 지연과 메모리 사용량이 커질 수 있습니다.'],
};

function AdminWorkspace({
  currentUser,
  isMaster = false,
  users,
  adminSettings,
  adminTuning,
  documentSchemaProfiles = [],
  storageRetention,
  adminTrash = [],
  spaces,
  adminSpaces = [],
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
  updateAdminTuning,
  updateDocumentSchemaProfile,
  refreshStorageRetention,
  runStorageRetention,
  restoreTrashItem,
  testAdminLlmSettings,
  testAdminTuningLlmSettings,
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
  const [tuningValues, setTuningValues] = useState({});
  const [tuningPreset, setTuningPreset] = useState('default');
  const [tuningLlmForm, setTuningLlmForm] = useState({
    ollamaBaseUrl: '',
    primaryChatModel: '',
    auxiliaryChatModel: '',
  });
  const [tuningTestResult, setTuningTestResult] = useState(null);

  useEffect(() => {
    setAllowedDomainText((adminSettings?.allowedDomains || []).join('\n'));
  }, [adminSettings?.allowedDomains]);

  useEffect(() => {
    if (!isMaster && activeAdminTab === 'trash') {
      setActiveAdminTab('settings');
    }
  }, [activeAdminTab, isMaster]);

  useEffect(() => {
    setLlmForm({
      ollamaBaseUrl: adminSettings?.ollamaBaseUrl || '',
      primaryChatModel: adminSettings?.primaryChatModel || adminSettings?.chatModel || '',
      auxiliaryChatModel: adminSettings?.auxiliaryChatModel || '',
    });
    setLlmTestResult(null);
  }, [adminSettings?.ollamaBaseUrl, adminSettings?.chatModel, adminSettings?.primaryChatModel, adminSettings?.auxiliaryChatModel]);

  useEffect(() => {
    const nextValues = {};
    (adminTuning?.settings || []).forEach((setting) => {
      nextValues[setting.key] = setting.value;
    });
    setTuningValues(nextValues);
    setTuningPreset(adminTuning?.activePreset || 'default');
    setTuningLlmForm({
      ollamaBaseUrl: adminTuning?.ollamaBaseUrl || '',
      primaryChatModel: adminTuning?.primaryChatModel || '',
      auxiliaryChatModel: adminTuning?.auxiliaryChatModel || '',
    });
    setTuningTestResult(null);
  }, [adminTuning]);

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
    const draft = {};
    (item.spaces || []).forEach((space) => {
      draft[space.id] = 'MEMBER';
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
      setModalError('鍮꾨?踰덊샇 ?뺤씤???쇱튂?섏? ?딆뒿?덈떎.');
      return;
    }
    const saved = await resetAdminUserPassword(passwordUser.id, passwordForm.newPassword);
    if (saved) closeUserModals();
  }

  async function submitPermissionEdit(event) {
    event.preventDefault();
    if (!permissionsUser) return;
    const assignedCount = manageableSpaces.filter((space) => permissionDraft[space.id]).length;
    if (assignedCount === 0) {
      setModalError('理쒖냼 1媛?怨듦컙 沅뚰븳? ?좎??댁빞 ?⑸땲??');
      return;
    }
    const currentRoles = new Map((permissionsUser.spaces || []).map((space) => [space.id, space.role ? 'MEMBER' : '']));
    const operations = manageableSpaces
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

  function applyTuningPreset(presetId) {
    const preset = (adminTuning?.presets || []).find((item) => item.id === presetId);
    if (!preset) return;
    setTuningPreset(presetId);
    setTuningValues(preset.values || {});
  }

  function updateTuningValue(key, value) {
    const numeric = Number(value);
    setTuningPreset('custom');
    setTuningValues((current) => ({
      ...current,
      [key]: Number.isFinite(numeric) ? numeric : 0,
    }));
  }

  async function submitTuningSettings(event) {
    event.preventDefault();
    const saved = await updateAdminTuning?.({
      preset: 'custom',
      ollamaBaseUrl: tuningLlmForm.ollamaBaseUrl,
      primaryChatModel: tuningLlmForm.primaryChatModel,
      auxiliaryChatModel: tuningLlmForm.auxiliaryChatModel,
      values: tuningValues,
    });
    if (saved) {
      setTuningPreset('custom');
    }
  }

  async function testTuningLlmSettings() {
    const tester = testAdminTuningLlmSettings || testAdminLlmSettings;
    const result = await tester?.({
      ollamaBaseUrl: tuningLlmForm.ollamaBaseUrl,
      primaryChatModel: tuningLlmForm.primaryChatModel,
      auxiliaryChatModel: tuningLlmForm.auxiliaryChatModel,
    });
    if (result && typeof result === 'object') {
      setTuningTestResult(result);
    }
  }

  function parseProfileList(value) {
    return String(value || '')
      .split(/[,\n]+/)
      .map((item) => item.trim())
      .filter(Boolean);
  }

  async function updateSchemaProfile(profile, values) {
    if (!updateDocumentSchemaProfile) return;
    await updateDocumentSchemaProfile(profile.schemaName, values);
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

  const manageableSpaces = adminSpaces.length ? adminSpaces : spaces;
  const canCreateAdmin = isMaster;
  const inviteSpaceId = inviteForm.spaceId || selectedSpaceId || manageableSpaces[0]?.id || '';
  const inviteSpace = manageableSpaces.find((space) => space.id === inviteSpaceId);
  const transferSpaceName = manageableSpaces.find((space) => space.id === spaceTransferResult?.spaceId)?.name || '';
  const allowedDomains = adminSettings?.allowedDomains || [];
  const allowedDomainPreview = allowedDomains.slice(0, 6);
  const retentionAreas = storageRetention?.areas || [];
  const trashItems = adminTrash || [];
  const activeUsers = users.filter((item) => String(item.status || '').toUpperCase() !== 'DISABLED').length;
  const enabledProfiles = documentSchemaProfiles.filter((profile) => profile.enabled).length;
  const retentionCandidateCount = storageRetention?.totalCandidates ?? 0;
  const adminSummary = [
    { label: '사용자', value: users.length, hint: `${activeUsers} active`, icon: <IconUsersGroup size={20} /> },
    { label: '공간', value: manageableSpaces.length, hint: selectedSpaceId ? 'selected scope' : 'global scope', icon: <IconDatabase size={20} /> },
    { label: '허용 도메인', value: allowedDomains.length, hint: adminSettings?.respectRobotsTxt ?? true ? 'robots on' : 'robots off', icon: <IconWorld size={20} /> },
    { label: '정리 후보', value: retentionCandidateCount, hint: formatFileSize(storageRetention?.totalEstimatedBytes), icon: <IconActivity size={20} /> },
  ];
  const adminTabs = (
    <div className="admin-tabs admin-tabler-tabs" role="tablist" aria-label="관리자 메뉴">
      <button
        className={activeAdminTab === 'settings' ? 'mode-button active' : 'mode-button'}
        type="button"
        role="tab"
        aria-selected={activeAdminTab === 'settings'}
        onClick={() => setActiveAdminTab('settings')}
      >
        <IconSettings size={16} />
        {isMaster ? '관리자 설정' : '사용자 관리'}
      </button>
      <button
        className={activeAdminTab === 'sources' ? 'mode-button active' : 'mode-button'}
        type="button"
        role="tab"
        aria-selected={activeAdminTab === 'sources'}
        onClick={() => setActiveAdminTab('sources')}
      >
        <IconFiles size={16} />
        코드/문서 등록
      </button>
      {isMaster && (
        <button
          className={activeAdminTab === 'tuning' ? 'mode-button active' : 'mode-button'}
          type="button"
          role="tab"
          aria-selected={activeAdminTab === 'tuning'}
          onClick={() => setActiveAdminTab('tuning')}
        >
          <SlidersHorizontal size={16} />
          튜닝
        </button>
      )}
      {isMaster && (
        <button
          className={activeAdminTab === 'trash' ? 'mode-button active' : 'mode-button'}
          type="button"
          role="tab"
          aria-selected={activeAdminTab === 'trash'}
          onClick={() => setActiveAdminTab('trash')}
        >
          <Trash2 size={16} />
          휴지통
        </button>
      )}
    </div>
  );
  const adminHeader = (
    <header className="admin-tabler-header">
      <div>
        <span className="admin-tabler-kicker">Admin Console</span>
          <h1>관리자 운영</h1>
          <p>모델, 소스, 사용자, 공간, 감사 로그를 한 곳에서 관리합니다.</p>
      </div>
      <button className="ghost-button compact-action admin-refresh-button" type="button" onClick={refreshAdmin}>
        {loading('admin-refresh') ? <Loader2 className="spin" size={15} /> : <IconRefresh size={15} />}
        새로고침
      </button>
    </header>
  );
  const adminSummaryCards = (
    <section className="admin-summary-grid" aria-label="관리자 요약">
      {adminSummary.map((item) => (
        <article className="admin-summary-card" key={item.label}>
          <span className="admin-summary-icon">{item.icon}</span>
          <div>
            <strong>{item.value}</strong>
            <small>{item.label}</small>
          </div>
          <em>{item.hint}</em>
        </article>
      ))}
      <article className="admin-summary-card">
        <span className="admin-summary-icon"><IconShieldLock size={20} /></span>
        <div>
          <strong>{enabledProfiles}</strong>
          <small>활성 스키마</small>
        </div>
        <em>{documentSchemaProfiles.length || 0} profiles</em>
      </article>
    </section>
  );

  if (activeAdminTab === 'tuning') {
    const tuningSettings = adminTuning?.settings || [];
    const tuningPresets = adminTuning?.presets || [];
    const appliedPresetId = adminTuning?.activePreset || tuningPreset || 'custom';
    const appliedPresetLabel = presetText[appliedPresetId]?.[0] || (appliedPresetId === 'custom' ? '사용자 지정' : appliedPresetId);
    const editingPresetLabel = presetText[tuningPreset]?.[0] || (tuningPreset === 'custom' ? '사용자 지정' : tuningPreset);
    const groupedSettings = tuningSettings.reduce((groups, setting) => {
      const category = setting.category || '기타';
      groups[category] = groups[category] || [];
      groups[category].push(setting);
      return groups;
    }, {});

    return (
      <div className="admin-tabler-shell">
        {adminHeader}
        {adminSummaryCards}
        {adminTabs}
        <form className="panel tuning-panel" onSubmit={submitTuningSettings}>
          <div className="panel-title">
            <SlidersHorizontal size={18} />
            <div>
              <h2>LLM/RAG 튜닝</h2>
              <p>답변 품질, 속도, 하드웨어 부하에 직접 영향을 주는 값만 조정합니다.</p>
            </div>
          </div>

          {!adminTuning && (
            <div className="danger-note">
              튜닝 설정을 불러오지 못했습니다. 기존 서버 기본값으로 서비스는 계속 동작합니다.
            </div>
          )}

          {adminTuning?.warnings?.map((warning) => (
            <div className="danger-note" key={warning}>
              <AlertTriangle size={14} />
              {warning}
            </div>
          ))}

          <div className="tuning-preset-row" role="radiogroup" aria-label="튜닝 프리셋">
            {tuningPresets.map((preset) => (
              <button
                key={preset.id}
                className={tuningPreset === preset.id ? 'mode-button active' : 'mode-button'}
                type="button"
                onClick={() => applyTuningPreset(preset.id)}
                title={presetText[preset.id]?.[1] || preset.description}
              >
                {presetText[preset.id]?.[0] || preset.label}
              </button>
            ))}
            <button className={tuningPreset === 'custom' ? 'mode-button active' : 'mode-button'} type="button" onClick={() => setTuningPreset('custom')}>
              사용자 지정
            </button>
          </div>

          <div className="tuning-active-profile">
            <span>
              <strong>현재 적용 중</strong>
              <em>{appliedPresetLabel}</em>
            </span>
            <small>
              편집 중: {editingPresetLabel}
              {tuningPreset !== appliedPresetId ? ' · 저장하면 사용자 지정으로 적용됩니다.' : ''}
            </small>
          </div>

          <section className="tuning-section">
            <div className="panel-title compact-title">
              <Bot size={18} />
              <div>
                <h2>모델 연결</h2>
                <p>비워두면 서버 기본 모델과 Ollama 주소를 사용합니다.</p>
              </div>
            </div>
            <div className="form-grid">
              <div className="stack">
                <label htmlFor="tuning-ollama-url">Ollama 주소 / 포트</label>
                <input
                  id="tuning-ollama-url"
                  value={tuningLlmForm.ollamaBaseUrl}
                  onChange={(event) => setTuningLlmForm((current) => ({ ...current, ollamaBaseUrl: event.target.value }))}
                  placeholder={adminTuning?.effectiveOllamaBaseUrl || 'http://ollama:11434'}
                  spellCheck="false"
                />
              </div>
            </div>
            <div className="form-grid two">
              <div className="stack">
                <label htmlFor="tuning-primary-model">메인 모델</label>
                <input
                  id="tuning-primary-model"
                  value={tuningLlmForm.primaryChatModel}
                  onChange={(event) => setTuningLlmForm((current) => ({ ...current, primaryChatModel: event.target.value }))}
                  placeholder={adminTuning?.effectivePrimaryChatModel || 'qwen3:8b-q4_K_M'}
                  spellCheck="false"
                />
              </div>
              <div className="stack">
                <label htmlFor="tuning-aux-model">보조 모델</label>
                <input
                  id="tuning-aux-model"
                  value={tuningLlmForm.auxiliaryChatModel}
                  onChange={(event) => setTuningLlmForm((current) => ({ ...current, auxiliaryChatModel: event.target.value }))}
                  placeholder={adminTuning?.effectiveAuxiliaryChatModel || 'qwen3.5:2b-q4_K_M'}
                  spellCheck="false"
                />
              </div>
            </div>
            <div className="detail-box compact-box llm-effective-box">
              <strong>현재 적용값</strong>
              <small>주소: {adminTuning?.effectiveOllamaBaseUrl || '-'}</small>
              <small>메인: {adminTuning?.effectivePrimaryChatModel || '-'}</small>
              <small>보조: {adminTuning?.effectiveAuxiliaryChatModel || '-'}</small>
            </div>
            {tuningTestResult && (
              <div className={tuningTestResult.success ? 'success-note llm-test-result' : 'danger-note llm-test-result'}>
                {tuningTestResult.message}
                <small>메인: {tuningTestResult.primaryModel || tuningTestResult.model || '-'}</small>
                <small>보조: {tuningTestResult.auxiliaryModel || '-'}</small>
              </div>
            )}
          </section>

          {Object.entries(groupedSettings).map(([category, items]) => (
            <section className="tuning-section" key={category}>
              <div className="panel-title compact-title">
                <Cpu size={18} />
                <div>
                  <h2>{category}</h2>
                  <p>{category === 'Ollama' ? '저장 후 컨테이너 재시작이 필요한 항목입니다.' : '저장 즉시 새 요청부터 반영됩니다.'}</p>
                </div>
              </div>
              <div className="tuning-grid">
                {items.map((setting) => {
                  const value = tuningValues[setting.key] ?? setting.value ?? setting.defaultValue;
                  const isSelect = setting.control === 'select';
                  const text = tuningText[setting.key] || [setting.label, setting.description, setting.impact];
                  return (
                    <label className="tuning-control" key={setting.key} title={`${text[1]} ${text[2]}`}>
                      <span className="tuning-control-head">
                        <strong>{text[0]}</strong>
                        {setting.restartRequired && <em>재시작 필요</em>}
                      </span>
                      <small>{text[2]}</small>
                      {isSelect ? (
                        <select value={value} onChange={(event) => updateTuningValue(setting.key, event.target.value)}>
                          {Array.from({ length: Math.floor((setting.max - setting.min) / setting.step) + 1 }, (_, index) => setting.min + index * setting.step).map((option) => (
                            <option value={option} key={option}>{option}</option>
                          ))}
                        </select>
                      ) : (
                        <div className="tuning-range-row">
                          <input
                            type="range"
                            min={setting.min}
                            max={setting.max}
                            step={setting.step}
                            value={value}
                            onChange={(event) => updateTuningValue(setting.key, event.target.value)}
                          />
                          <input
                            type="number"
                            min={setting.min}
                            max={setting.max}
                            step={setting.step}
                            value={value}
                            onChange={(event) => updateTuningValue(setting.key, event.target.value)}
                          />
                        </div>
                      )}
                      <span className="tuning-meta">기본값 {setting.defaultValue} · {setting.envKey}</span>
                    </label>
                  );
                })}
              </div>
            </section>
          ))}

          <div className="action-row">
            <button type="button" className="ghost-button" disabled={loading('admin-tuning-llm-test') || loading('admin-tuning')} onClick={testTuningLlmSettings}>
              {loading('admin-tuning-llm-test') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
              연결 테스트
            </button>
            <button disabled={loading('admin-tuning') || !adminTuning}>
              {loading('admin-tuning') ? <Loader2 className="spin" size={16} /> : <SlidersHorizontal size={16} />}
              튜닝 저장
            </button>
          </div>
        </form>
      </div>
    );
  }

  if (activeAdminTab === 'trash') {
    return (
      <div className="admin-tabler-shell">
        {adminHeader}
        {adminSummaryCards}
        {adminTabs}
        <section className="panel">
          <div className="panel-title">
            <Trash2 size={18} />
            <div>
              <h2>휴지통</h2>
                <p>영구 삭제되기 전에 삭제된 항목을 복구할 수 있습니다.</p>
            </div>
          </div>
          <div className="results audit-list">
            {trashItems.map((item) => {
              const loadingKey = `trash-restore-${item.type}-${item.id}`;
              return (
                <article className="result" key={`${item.type}-${item.id}`}>
                  <div className="result-heading">
                    <strong>{item.title || item.id}</strong>
                    <span>{item.type}</span>
                  </div>
                  <small>{item.subtitle || '-'}</small>
                  <small>
                      삭제일 {item.deletedAt ? formatDate(item.deletedAt) : '-'} · 영구삭제 예정일 {item.expiresAt ? formatDate(item.expiresAt) : '-'}
                  </small>
                  <p>{item.message}</p>
                  <div className="action-row">
                    <button
                      type="button"
                      disabled={!item.restorable || loading(loadingKey)}
                      onClick={() => restoreTrashItem?.(item.type, item.id)}
                    >
                      {loading(loadingKey) ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
                      복구
                    </button>
                  </div>
                </article>
              );
            })}
            {!trashItems.length && (
              <p className="empty compact-empty">현재 범위에서 복구 가능한 삭제 항목이 없습니다.</p>
            )}
          </div>
        </section>
      </div>
    );
  }

  if (activeAdminTab === 'sources') {
    return (
      <div className="admin-tabler-shell">
        {adminHeader}
        {adminSummaryCards}
        {adminTabs}
        <section className="workspace-grid admin-source-grid">
          <div className="left-column">
          <CodeSourceManagementPanel {...(codeSourceProps || {})} loading={loading} />
          </div>
          <div className="right-column">
            <DocumentSourcePanel {...(documentSourceProps || {})} loading={loading} />
          </div>
        </section>
      </div>
    );
  }

  return (
    <div className="admin-tabler-shell">
    {adminHeader}
    {adminSummaryCards}
    {adminTabs}
    <section className="workspace-grid">
      {isMaster && (
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


        <section className="panel">
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>문서 그래프 스키마</h2>
              <p>문서 유형별 스키마 프로필을 관리하고, 문서 그래프 분석에 사용할 엔티티와 관계를 확인합니다.</p>
            </div>
          </div>
          <div className="schema-profile-list">
            {(documentSchemaProfiles || []).map((profile) => {
              const loadingKey = `schema-profile-${profile.schemaName}`;
              return (
                <article className="schema-profile-card" key={profile.schemaName}>
                  <div className="result-heading">
                    <strong>{profile.schemaName}</strong>
                    <span>{profile.defaultProfile ? '기본' : profile.enabled ? '활성' : '비활성'}</span>
                  </div>
                  <small>{profile.description}</small>
                  <div className="form-grid two">
                    <label className="checkbox-row" htmlFor={`schema-enabled-${profile.schemaName}`}>
                      <input
                        id={`schema-enabled-${profile.schemaName}`}
                        type="checkbox"
                        checked={profile.enabled}
                        disabled={loading(loadingKey) || profile.defaultProfile}
                        onChange={(event) => updateSchemaProfile(profile, { enabled: event.target.checked })}
                      />
                      <span>활성화</span>
                    </label>
                    <label className="checkbox-row" htmlFor={`schema-default-${profile.schemaName}`}>
                      <input
                        id={`schema-default-${profile.schemaName}`}
                        type="checkbox"
                        checked={profile.defaultProfile}
                        disabled={loading(loadingKey) || profile.defaultProfile || !profile.enabled}
                        onChange={(event) => updateSchemaProfile(profile, { defaultProfile: event.target.checked })}
                      />
                      <span>기본 프로필</span>
                    </label>
                  </div>
                  <label htmlFor={`schema-doc-types-${profile.schemaName}`}>문서 유형</label>
                  <textarea
                    id={`schema-doc-types-${profile.schemaName}`}
                    rows={4}
                    defaultValue={(profile.documentTypes || []).join('\n')}
                    disabled={loading(loadingKey)}
                    onBlur={(event) => updateSchemaProfile(profile, { documentTypes: parseProfileList(event.target.value) })}
                  />
                  <details>
                    <summary>Entity / Relation 유형 보기</summary>
                    <small>Entities: {(profile.entityTypes || []).join(', ') || '-'}</small>
                    <small>Relations: {(profile.relationTypes || []).join(', ') || '-'}</small>
                  </details>
                </article>
              );
            })}
            {!documentSchemaProfiles?.length && (
              <p className="empty compact-empty">등록된 문서 그래프 스키마 프로필을 불러오지 못했습니다. 기본 스키마로 동작합니다.</p>
            )}
          </div>
        </section>

        <section className="panel">
          <div className="panel-title">
            <Database size={18} />
            <div>
              <h2>저장소 정리 정책</h2>
              <p>운영 로그, 감사 로그, 임시 파일, 참조 없는 데이터를 안전하게 정리합니다.</p>
            </div>
          </div>
          <div className="detail-box compact-box">
            <strong>정리 미리보기</strong>
            <small>
              정리 후보 {storageRetention?.totalCandidates ?? 0}개 · 예상 용량 {formatFileSize(storageRetention?.totalEstimatedBytes)}
            </small>
            <small>
              생성 시각 {storageRetention?.generatedAt ? formatDate(storageRetention.generatedAt) : '-'} · 기본 실행 모드 {storageRetention?.dryRun ? '모의 실행' : '삭제'}
            </small>
          </div>
          <div className="results audit-list">
            {retentionAreas.map((area) => (
              <article className="result" key={area.key}>
                <div className="result-heading">
                  <strong>{area.label || area.key}</strong>
                  <span>{area.retentionDays ? `${area.retentionDays}d` : '-'}</span>
                </div>
                <small>
                  후보 {area.candidates || 0}개 · 삭제 {area.deleted || 0}개 · {formatFileSize(area.estimatedBytes)}
                </small>
                <p>{area.impact}</p>
              </article>
            ))}
            {!retentionAreas.length && (
              <p className="empty compact-empty">불러온 정리 진단 정보가 없습니다.</p>
            )}
          </div>
          <div className="action-row">
            <button className="ghost-button" type="button" disabled={loading('storage-retention-preview')} onClick={refreshStorageRetention}>
              {loading('storage-retention-preview') ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
              새로고침
            </button>
            <button className="ghost-button" type="button" disabled={loading('storage-retention-dry-run')} onClick={() => runStorageRetention?.(true)}>
              {loading('storage-retention-dry-run') ? <Loader2 className="spin" size={16} /> : <Info size={16} />}
              모의 실행
            </button>
            <button type="button" disabled={loading('storage-retention-run')} onClick={() => runStorageRetention?.(false)}>
              {loading('storage-retention-run') ? <Loader2 className="spin" size={16} /> : <Trash2 size={16} />}
              후보 데이터 삭제
            </button>
          </div>
        </section>

      </div>
      )}

      <div className="right-column">
        <form className="panel" onSubmit={inviteUser}>
          <div className="panel-title">
            <UserPlus size={18} />
            <div>
              <h2>사용자 초대</h2>
              <p>현재 선택한 공간에 사용자를 추가합니다.</p>
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
                <select id="invite-role" value={canCreateAdmin ? inviteForm.role : 'USER'} disabled={!canCreateAdmin} onChange={(event) => setInviteForm((current) => ({ ...current, role: event.target.value }))}>
                  <option value="USER">USER</option>
                  {canCreateAdmin && <option value="ADMIN">ADMIN</option>}
                </select>
              </div>
            </div>
          </div>
          <div className="stack">
            <label htmlFor="invite-space">초대 공간</label>
            <select
              id="invite-space"
              value={inviteSpaceId}
              onChange={(event) => setInviteForm((current) => ({ ...current, spaceId: event.target.value }))}
            >
              {manageableSpaces.map((space) => (
                <option key={space.id} value={space.id}>{space.name}</option>
              ))}
            </select>
          </div>
          <div className="detail-box compact-box">
            <strong>{inviteSpace?.name || '선택한 공간'}</strong>
            <small>초대 사용자는 이 공간의 자료에만 접근합니다.</small>
          </div>
          <div className="action-row">
            <button disabled={!inviteForm.loginId || !inviteForm.displayName || !inviteForm.initialPassword || !inviteSpaceId || loading('user-invite')}>
              {loading('user-invite') ? <Loader2 className="spin" size={16} /> : <UserPlus size={16} />}
              초대
            </button>
          </div>
        </form>

        {isMaster && (
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
        )}

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
                  <small>{item.spaces?.length || 0}개 공간 권한</small>
                </div>
                <div className="document-meta">
                  <StatusBadge status={item.status} />
                  <small>{item.role}</small>
                </div>
                <div className="document-actions">
                  <IconButton title="계정 편집" disabled={item.role === 'MASTER' || (!isMaster && item.role !== 'USER')} onClick={() => beginEditUser(item)}>
                    <Info size={15} />
                  </IconButton>
                  <IconButton
                    title={item.id === currentUser?.id ? '현재 로그인한 계정의 비밀번호는 여기서 재설정할 수 없습니다.' : '비밀번호 재설정'}
                    disabled={item.id === currentUser?.id || item.role === 'MASTER' || (!isMaster && item.role !== 'USER') || loading(`user-password-${item.id}`)}
                    onClick={() => beginPasswordReset(item)}
                  >
                    {loading(`user-password-${item.id}`) ? <Loader2 className="spin" size={15} /> : <LockKeyhole size={15} />}
                  </IconButton>
                  <IconButton
                    title="공간 권한 관리"
                    disabled={item.role === 'MASTER' || (!isMaster && item.role !== 'USER') || loading(`user-spaces-${item.id}`)}
                    onClick={() => beginPermissionEdit(item)}
                  >
                    {loading(`user-spaces-${item.id}`) ? <Loader2 className="spin" size={15} /> : <ShieldCheck size={15} />}
                  </IconButton>
                  <IconButton
                    danger
                    title={item.id === currentUser?.id ? '현재 로그인한 계정은 삭제할 수 없습니다.' : '사용자 삭제'}
                    disabled={item.id === currentUser?.id || item.role === 'MASTER' || (!isMaster && item.role !== 'USER') || loading(`user-delete-${item.id}`)}
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
              <p>{manageableSpaces.length}개 공간</p>
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
              <span>가져온 데이터 {formatTransferCounts(spaceTransferResult.result?.imported)}</span>
              <small>건너뜀: {formatTransferCounts(spaceTransferResult.result?.skipped)}</small>
            </div>
          )}
          <div className="document-list">
            {manageableSpaces.map((space) => (
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
                      {isMaster && (
                      <IconButton title="공간 이름/설명 편집" onClick={() => beginEditSpace(space)}>
                        <Info size={15} />
                      </IconButton>
                      )}
                      {isMaster && (
                      <IconButton
                        danger
                        title={space.id === defaultSpaceId ? '기본 공간은 삭제할 수 없습니다.' : '공간 삭제'}
                        disabled={space.id === defaultSpaceId || loading(`space-delete-${space.id}`)}
                        onClick={() => deleteSpace(space.id, space.name)}
                      >
                        {loading(`space-delete-${space.id}`) ? <Loader2 className="spin" size={15} /> : <Trash2 size={15} />}
                      </IconButton>
                      )}
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
              <p>주요 작업 이력을 추적합니다.</p>
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
        <AdminUserModal title="怨꾩젙 ?몄쭛" subtitle={editingUser.loginId || editingUser.email} icon={<Info size={18} />} onClose={closeUserModals}>
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
              {editingUser.id === currentUser?.id && <small className="field-help">?꾩옱 濡쒓렇?명븳 愿由ъ옄 怨꾩젙??ID?????붾㈃?먯꽌 蹂寃쏀븷 ???놁뒿?덈떎.</small>}
            </div>
            <div className="stack">
              <label htmlFor="edit-user-name">?쒖떆 ?대쫫</label>
              <input
                id="edit-user-name"
                value={userEditForm.displayName}
                onChange={(event) => setUserEditForm((current) => ({ ...current, displayName: event.target.value }))}
              />
            </div>
            <div className="stack">
              <label htmlFor="edit-user-role">?쒖뒪??沅뚰븳</label>
              <select
                id="edit-user-role"
                value={isMaster ? userEditForm.role : 'USER'}
                disabled={editingUser.id === currentUser?.id || !isMaster}
                onChange={(event) => setUserEditForm((current) => ({ ...current, role: event.target.value }))}
              >
                <option value="USER">USER</option>
                {isMaster && <option value="ADMIN">ADMIN</option>}
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
              <label htmlFor="reset-password">새 비밀번호</label>
              <input
                id="reset-password"
                type="password"
                value={passwordForm.newPassword}
                onChange={(event) => setPasswordForm((current) => ({ ...current, newPassword: event.target.value }))}
              />
            </div>
            <div className="stack">
              <label htmlFor="reset-password-confirm">새 비밀번호 확인</label>
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
            {modalError && <div className="failure-line"><AlertTriangle size={14} />{modalError}</div>}
            {permissionsUser.role === 'ADMIN' && (
              <div className="detail-box compact-box">
                <strong>시스템 관리자</strong>
                <small>ADMIN 계정은 선택한 공간에만 접근합니다. 최소 1개 공간 권한이 필요합니다.</small>
              </div>
            )}
            <div className="permission-grid">
              {manageableSpaces.map((space) => (
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
                    <option value="MEMBER">배정</option>
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
              <small>Export로 생성된 ZIP 파일을 선택하면 됩니다.</small>
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
              <button className="ghost-button" type="button" onClick={closeSpaceImport}>痍⑥냼</button>
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
                한 줄에 하나씩 입력하거나 쉼표로 구분하세요. 전체 URL을 입력해도 서버에서는 호스트만 저장합니다.
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
              예: https://docs.example.com/guide 는 docs.example.com 으로 저장됩니다. 등록된 도메인과 하위 도메인만 웹 인덱싱할 수 있습니다.
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
    </div>
  );
}

function AdminUserModal({ title, subtitle, icon, children, onClose, className = '', bodyClassName = '' }) {
  const modal = (
    <div className="code-modal-backdrop admin-modal-portal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className={`code-modal document-preview-modal admin-user-modal ${className}`.trim()} role="dialog" aria-modal="true" aria-labelledby="admin-user-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            {icon}
            <div>
              <h2 id="admin-user-modal-title">{title}</h2>
              <p>{subtitle}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title="?リ린" onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>
        <div className={`admin-user-modal-body ${bodyClassName}`.trim()}>
          {children}
        </div>
      </section>
    </div>
  );

  if (typeof document === 'undefined') return modal;
  return createPortal(modal, document.body);
}

export { AdminWorkspace };

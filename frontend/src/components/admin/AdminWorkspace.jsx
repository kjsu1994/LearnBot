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
  OLLAMA_CONTEXT_LENGTH: ['Ollama 컨텍스트 길이', 'Ollama 요청에 전달하는 num_ctx 값입니다.', '저장 즉시 새 질문부터 적용됩니다. LLM 문맥 길이와 맞추는 것을 권장합니다.'],
  RAG_PIPELINE_PROMPT_TOKEN_BUDGET_BALANCED: ['문서 답변 프롬프트 예산', '문서 답변에서 근거 청크와 질문에 배정할 토큰 예산입니다.', '높을수록 근거를 많이 넣지만 응답 속도가 느려질 수 있습니다.'],
  RAG_PIPELINE_CODE_CONTEXT_LIMIT: ['코드 답변에 넣을 청크 수', '코드 질문 답변에 사용할 코드 근거 청크 개수입니다.', '높을수록 관련 파일과 메서드를 더 많이 봅니다.'],
  RAG_PIPELINE_DOCUMENT_CONTEXT_LIMIT: ['문서 답변에 넣을 청크 수', '문서 질문 답변에 사용할 문서 근거 청크 개수입니다.', '높을수록 답변 근거가 늘고 속도는 느려질 수 있습니다.'],
  LEARNBOT_RAG_OVERVIEW_MAX_DOCUMENTS: ['전체 요약 탐색 문서 수', '개요/요약형 질문에서 서로 다른 문서를 최대 몇 개까지 포함할지 정합니다.', '높을수록 전체 맥락은 좋아지고 답변 시간이 늘어납니다.'],
  LEARNBOT_RAG_OVERVIEW_MAX_CODE_CATEGORIES: ['코드 개요 카테고리 수', '코드 구조/흐름 질문에서 서로 다른 범주의 근거를 최대 몇 개까지 쓸지 정합니다.', '높을수록 구조 답변이 풍부해지지만 잡음도 늘 수 있습니다.'],
  LEARNBOT_RAG_OVERVIEW_MAX_RECURSIVE_ITERATIONS: ['개요 탐색 반복 횟수', '근거가 부족할 때 추가 탐색을 몇 번까지 허용할지 정합니다.', '높을수록 품질은 좋아질 수 있지만 지연 시간이 늘어납니다.'],
  LLM_MAX_OUTPUT_TOKENS: ['답변 최대 길이', '모델이 생성할 수 있는 답변 토큰 상한입니다. 0은 제한 없음입니다.', '[0 = 제한 없음] 모델에 최대 생성 길이 옵션을 보내지 않습니다. 값이 낮으면 답변이 잘릴 수 있고, 높으면 응답이 느려질 수 있습니다.'],
  OLLAMA_MAX_LOADED_MODELS: ['동시 로드 모델 수', 'Ollama가 메모리에 동시에 올려둘 모델 수입니다.', 'VRAM/RAM이 충분하지 않으면 1을 권장합니다.'],
  OLLAMA_NUM_PARALLEL: ['Ollama 병렬 요청 수', 'Ollama가 한 모델에서 동시에 처리할 요청 수입니다.', '작은 장비는 1, 큰 GPU 서버만 2 이상을 권장합니다.'],
};

const presetText = {
  default: ['현재 기본값', '현재 서버 설정값을 그대로 사용합니다.'],
  slightly_high: ['약간 높음', '기본값보다 답변 품질과 근거 범위를 조금 올린 권장값입니다.'],
  performance: ['고성능', '큰 컨텍스트와 많은 근거를 사용합니다. 응답 지연과 메모리 사용량이 늘 수 있습니다.'],
};

function AdminWorkspace({
  currentUser,
  isMaster = false,
  users,
  adminSettings,
  adminTuning,
  adminTuningMetrics,
  adminTuningRecommendations,
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
  createDocumentSchemaProfile,
  updateDocumentSchemaProfile,
  refreshStorageRetention,
  runStorageRetention,
  restoreTrashItem,
  testAdminLlmSettings,
  testAdminTuningLlmSettings,
  updateAdminTuningReranker,
  refreshAdminTuningMetrics,
  resetAdminTuningMetrics,
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
  const [schemaHelpOpen, setSchemaHelpOpen] = useState(false);
  const [schemaCreateOpen, setSchemaCreateOpen] = useState(false);
  const [schemaCreateForm, setSchemaCreateForm] = useState({
    schemaName: '',
    description: '',
    documentTypes: '',
    entityTypes: '',
    relationTypes: '',
    enabled: true,
    defaultProfile: false,
  });
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
  const [recommendedTuningKeys, setRecommendedTuningKeys] = useState([]);

  useEffect(() => {
    setAllowedDomainText((adminSettings?.allowedDomains || []).join('\n'));
  }, [adminSettings?.allowedDomains]);

  useEffect(() => {
    if (!isMaster && ['schema', 'tuning', 'trash'].includes(activeAdminTab)) {
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
    setRecommendedTuningKeys([]);
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

  function previewRecommendedTuning() {
    const changes = adminTuningRecommendations?.changes || [];
    if (!changes.length) return;
    setTuningPreset('custom');
    setRecommendedTuningKeys(changes.map((change) => change.key));
    setTuningValues((current) => {
      const next = { ...current };
      changes.forEach((change) => {
        next[change.key] = change.recommendedValue;
      });
      return next;
    });
  }

  async function submitTuningSettings(event) {
    event.preventDefault();
    const selectedPreset = (adminTuning?.presets || []).find((item) => item.id === tuningPreset);
    const presetUnchanged = selectedPreset && Object.entries(selectedPreset.values || {}).every(([key, value]) => Number(tuningValues[key]) === Number(value));
    const nextPreset = presetUnchanged ? selectedPreset.id : 'custom';
    const saved = await updateAdminTuning?.({
      preset: nextPreset,
      ollamaBaseUrl: tuningLlmForm.ollamaBaseUrl,
      primaryChatModel: tuningLlmForm.primaryChatModel,
      auxiliaryChatModel: tuningLlmForm.auxiliaryChatModel,
      values: nextPreset === 'custom' ? tuningValues : null,
    });
    if (saved) {
      setTuningPreset(nextPreset);
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

  async function toggleReranker(enabled) {
    await updateAdminTuningReranker?.(enabled);
  }

  function parseProfileList(value) {
    return String(value || '')
      .split(/[,\n]+/)
      .map((item) => item.trim())
      .filter(Boolean);
  }

  async function updateSchemaProfile(profile, values) {
    if (!updateDocumentSchemaProfile) return;
    if (values.documentTypes && hasSchemaDocumentTypeConflict(profile.schemaName, values.documentTypes, true)) {
      window.alert('이미 활성화된 다른 스키마에서 사용하는 문서 유형이 있습니다. 여러 산업군 운영 시 문서 유형은 스키마마다 겹치지 않아야 합니다.');
      return;
    }
    if (values.enabled === true && hasSchemaDocumentTypeConflict(profile.schemaName, profile.documentTypes || [], true)) {
      window.alert('이 스키마의 문서 유형이 이미 활성화된 다른 스키마와 겹칩니다. 문서 유형을 먼저 변경한 뒤 활성화하세요.');
      return;
    }
    await updateDocumentSchemaProfile(profile.schemaName, values);
  }

  function hasSchemaDocumentTypeConflict(schemaName, documentTypes, enabled = true) {
    if (!enabled) return false;
    const requested = new Set(parseProfileList(documentTypes).map((item) => item.toUpperCase()));
    if (!requested.size) return false;
    return (documentSchemaProfiles || []).some((profile) => (
      profile.enabled
      && profile.schemaName !== schemaName
      && (profile.documentTypes || []).some((type) => requested.has(String(type || '').trim().toUpperCase()))
    ));
  }

  function openSchemaCreate() {
    setModalError('');
    setSchemaCreateForm({
      schemaName: '',
      description: '',
      documentTypes: '',
      entityTypes: 'DOCUMENT\nSECTION\nTOPIC\nTERM\nREQUIREMENT\nPROCEDURE',
      relationTypes: 'DOCUMENT_HAS_SECTION\nSECTION_HAS_TOPIC\nTOPIC_RELATED_TO_TERM',
      enabled: true,
      defaultProfile: false,
    });
    setSchemaCreateOpen(true);
  }

  function closeSchemaCreate() {
    setSchemaCreateOpen(false);
    setModalError('');
  }

  async function submitSchemaCreate(event) {
    event.preventDefault();
    const documentTypes = parseProfileList(schemaCreateForm.documentTypes);
    if (!schemaCreateForm.schemaName.trim()) {
      setModalError('스키마 이름을 입력하세요. 예: FINANCE_REPORT, MEDICAL_MANUAL');
      return;
    }
    if (!documentTypes.length) {
      setModalError('문서 유형을 최소 1개 입력하세요. 예: FINANCE_REPORT, MANUFACTURING_MANUAL');
      return;
    }
    if (hasSchemaDocumentTypeConflict(schemaCreateForm.schemaName, documentTypes, schemaCreateForm.enabled)) {
      setModalError('이미 활성화된 다른 스키마에서 사용하는 문서 유형이 있습니다. 산업군별 문서 유형은 서로 겹치지 않게 입력하세요.');
      return;
    }
    const saved = await createDocumentSchemaProfile?.({
      schemaName: schemaCreateForm.schemaName,
      description: schemaCreateForm.description,
      documentTypes,
      entityTypes: parseProfileList(schemaCreateForm.entityTypes),
      relationTypes: parseProfileList(schemaCreateForm.relationTypes),
      enabled: schemaCreateForm.enabled,
      defaultProfile: schemaCreateForm.defaultProfile,
    });
    if (saved) {
      closeSchemaCreate();
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
          className={activeAdminTab === 'schema' ? 'mode-button active' : 'mode-button'}
          type="button"
          role="tab"
          aria-selected={activeAdminTab === 'schema'}
          onClick={() => setActiveAdminTab('schema')}
        >
          <Database size={16} />
          스키마
        </button>
      )}
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
          복구
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

  if (activeAdminTab === 'schema') {
    return (
      <div className="admin-tabler-shell admin-schema-shell">
        {adminHeader}
        {adminTabs}
        {adminSummaryCards}

        <section className="panel schema-admin-panel">
          <div className="panel-title schema-panel-title">
            <Database size={18} />
            <div>
              <h2>문서 그래프 스키마</h2>
              <p>사업군별 문서 유형, 엔티티, 관계를 관리합니다. 삭제 대신 비활성화해서 기존 인덱스 회귀를 방지합니다.</p>
            </div>
            <div className="top-actions schema-title-actions">
              <button className="ghost-button compact-action" type="button" onClick={() => setSchemaHelpOpen(true)}>
                <Info size={14} />
                도움말
              </button>
              <button className="compact-action" type="button" onClick={openSchemaCreate}>
                <UserPlus size={14} />
                스키마 추가
              </button>
            </div>
          </div>

          <div className="detail-box compact-box schema-operator-note">
            <strong>운영 원칙</strong>
            <small>기본값은 CORE 공통 스키마입니다. 새 사업군은 별도 스키마를 추가한 뒤 문서 유형을 매핑하고, 관련 문서는 재인덱싱해야 새 스키마가 분석에 반영됩니다.</small>
          </div>

          <div className="schema-profile-list">
            {(documentSchemaProfiles || []).map((profile) => {
              const loadingKey = `schema-profile-${profile.schemaName}`;
              const isExampleProfile = profile.schemaName === 'SATELLITE_GSE' && !profile.enabled;
              const isCoreProfile = profile.schemaName === 'CORE';
              return (
                <article className="schema-profile-card schema-editor-card" key={profile.schemaName}>
                  <div className="result-heading">
                    <strong>{profile.schemaName}</strong>
                    <span>{isExampleProfile ? '예시' : profile.defaultProfile ? '기본' : profile.enabled ? '활성' : '비활성'}</span>
                  </div>
                  <small>{profile.description}</small>
                  <div className="form-grid two">
                    <label className="checkbox-row" htmlFor={`schema-enabled-${profile.schemaName}`}>
                      <input
                        id={`schema-enabled-${profile.schemaName}`}
                        type="checkbox"
                        checked={profile.enabled}
                        disabled={loading(loadingKey) || profile.defaultProfile || isCoreProfile}
                        onChange={(event) => updateSchemaProfile(profile, { enabled: event.target.checked })}
                      />
                      <span>활성화</span>
                    </label>
                    {isCoreProfile ? (
                      <label className="checkbox-row" htmlFor={`schema-default-${profile.schemaName}`}>
                        <input
                          id={`schema-default-${profile.schemaName}`}
                          type="checkbox"
                          checked={profile.defaultProfile}
                          disabled
                          readOnly
                        />
                        <span>기본 프로필 고정</span>
                      </label>
                    ) : (
                      <div className="schema-fixed-default-note">
                        <span>기본 프로필은 CORE로 고정됩니다.</span>
                      </div>
                    )}
                  </div>
                  <label htmlFor={`schema-doc-types-${profile.schemaName}`}>문서 유형</label>
                  <textarea
                    id={`schema-doc-types-${profile.schemaName}`}
                    rows={4}
                    defaultValue={(profile.documentTypes || []).join('\n')}
                    disabled={loading(loadingKey) || isCoreProfile}
                    onBlur={(event) => updateSchemaProfile(profile, { documentTypes: parseProfileList(event.target.value) })}
                    spellCheck="false"
                  />
                  <details className="schema-detail-editor">
                    <summary>엔티티 / 관계 유형 편집</summary>
                    <label htmlFor={`schema-entities-${profile.schemaName}`}>엔티티 유형</label>
                    <textarea
                      id={`schema-entities-${profile.schemaName}`}
                      rows={5}
                      defaultValue={(profile.entityTypes || []).join('\n')}
                      disabled={loading(loadingKey) || isCoreProfile}
                      onBlur={(event) => updateSchemaProfile(profile, { entityTypes: parseProfileList(event.target.value) })}
                      spellCheck="false"
                    />
                    <label htmlFor={`schema-relations-${profile.schemaName}`}>관계 유형</label>
                    <textarea
                      id={`schema-relations-${profile.schemaName}`}
                      rows={5}
                      defaultValue={(profile.relationTypes || []).join('\n')}
                      disabled={loading(loadingKey) || isCoreProfile}
                      onBlur={(event) => updateSchemaProfile(profile, { relationTypes: parseProfileList(event.target.value) })}
                      spellCheck="false"
                    />
                  </details>
                  <small className="field-help">
                    {isCoreProfile
                      ? 'CORE는 모든 문서의 안전한 기본 폴백 스키마라 편집할 수 없습니다. 사업군별 변경은 새 스키마를 추가해서 적용하세요.'
                      : '스키마 변경 후 이미 인덱싱된 문서에는 자동 소급 적용되지 않습니다. 해당 문서를 재인덱싱하세요.'}
                  </small>
                </article>
              );
            })}
            {!documentSchemaProfiles?.length && (
              <p className="empty compact-empty">등록된 스키마를 불러오지 못했습니다. 서버는 CORE 기본 스키마 폴백으로 동작합니다.</p>
            )}
          </div>
        </section>

        {schemaHelpOpen && (
          <AdminUserModal title="스키마 추가 도움말" subtitle="사업군별 문서 RAG를 설정하는 방법" icon={<Info size={18} />} onClose={() => setSchemaHelpOpen(false)}>
            <div className="admin-modal-form schema-help-content">
              <div className="detail-box compact-box">
                <strong>스키마를 왜 추가하나요?</strong>
                <small>스키마는 문서를 읽을 때 "무엇을 중요한 항목으로 보고, 항목끼리 어떤 관계로 연결할지" 알려주는 업무 사전입니다.</small>
                <small>기본 CORE 스키마는 어느 문서에나 무난하게 동작하지만, 제조/금융/의료/장비 매뉴얼처럼 업무 용어가 뚜렷한 문서는 전용 스키마를 쓰면 더 정확한 요약, 원인 파악, 영향 범위 분석을 기대할 수 있습니다.</small>
                <small>예를 들어 장애 매뉴얼에서 ERROR_CODE, FAULT, RESOLUTION을 엔티티로 넣으면 "오류 코드가 어떤 고장을 의미하고 어떤 절차로 해결되는지"를 그래프로 더 잘 묶을 수 있습니다.</small>
              </div>
              <div className="schema-help-grid">
                <article>
                  <strong>스키마 이름</strong>
                  <small>관리자가 구분하기 위한 이름입니다. 업무명이나 문서군을 영문 대문자와 밑줄로 적습니다.</small>
                  <small>예: FINANCE_REPORT, MEDICAL_MANUAL, MANUFACTURING_QA, CUSTOMER_SUPPORT_GUIDE</small>
                  <small>왜 이렇게 넣나요? 나중에 로그, 분석 결과, 재인덱싱 대상에서 어떤 업무 스키마가 쓰였는지 명확히 구분하기 위해서입니다.</small>
                </article>
                <article>
                  <strong>문서 유형</strong>
                  <small>이 스키마를 적용할 문서 분류입니다. 한 줄에 하나씩 입력합니다.</small>
                  <small>처음에는 GENERAL_DOCUMENT를 그대로 둬도 됩니다. 운영이 익숙해지면 REQUIREMENT_SPEC, OPERATION_MANUAL, FAQ, INCIDENT_REPORT처럼 문서 종류를 나눌 수 있습니다.</small>
                  <small>왜 필요하나요? 문서가 어떤 스키마로 분석되어야 하는지 매칭하는 기준이기 때문입니다.</small>
                </article>
                <article>
                  <strong>엔티티 유형</strong>
                  <small>문서 안에서 "중요한 명사"로 뽑고 싶은 항목입니다. 한 줄에 하나씩 입력합니다.</small>
                  <small>예: REQUIREMENT, PRODUCT, CUSTOMER, POLICY, ERROR_CODE, PROCEDURE, COMPONENT, RISK, REGULATION</small>
                  <small>왜 필요하나요? RAG가 단순 문장 검색을 넘어서 "어떤 개념들이 문서에 반복되고 연결되는지" 파악하는 기준이 됩니다.</small>
                </article>
                <article>
                  <strong>관계 유형</strong>
                  <small>엔티티끼리 어떤 의미로 연결되는지 적습니다. 보통 A_VERB_B 형태의 영문 대문자와 밑줄을 사용합니다.</small>
                  <small>예: PRODUCT_HAS_COMPONENT, ERROR_CODE_INDICATES_FAULT, POLICY_APPLIES_TO_REGION, REQUIREMENT_VERIFIED_BY_TEST</small>
                  <small>왜 필요하나요? "무엇이 무엇을 포함하는지", "무엇이 무엇의 원인인지", "무엇이 어떤 절차로 해결되는지" 같은 질문에 답하기 쉬워집니다.</small>
                </article>
              </div>
              <div className="detail-box compact-box">
                <strong>어떤 식으로 넣으면 되나요?</strong>
                <small>개발 지식이 없어도 업무 문서를 보면서 반복적으로 나오는 중요 단어를 엔티티로 고르고, 그 단어들 사이의 관계를 짧은 영문 규칙으로 적으면 됩니다.</small>
                <small>너무 많이 넣기보다 처음에는 엔티티 5~12개, 관계 3~8개 정도로 시작하세요. 실제 질문 결과를 보고 부족한 항목을 추가하는 방식이 안전합니다.</small>
                <small>입력은 쉼표로 구분하거나 한 줄에 하나씩 넣을 수 있습니다. 화면에서는 한 줄에 하나씩 넣는 방식을 권장합니다.</small>
              </div>
              <div className="detail-box compact-box">
                <strong>사업군별 예시</strong>
                <small>제조: PRODUCT, COMPONENT, PROCESS, DEFECT / PRODUCT_HAS_COMPONENT, DEFECT_CAUSED_BY_PROCESS</small>
                <small>금융: REPORT, ACCOUNT, RISK, REGULATION / REPORT_MENTIONS_RISK, REGULATION_APPLIES_TO_ACCOUNT</small>
                <small>의료/매뉴얼: PROCEDURE, SYMPTOM, DEVICE, WARNING / PROCEDURE_USES_DEVICE, WARNING_RELATED_TO_SYMPTOM</small>
                <small>고객지원: ISSUE, PRODUCT, CUSTOMER_TYPE, SOLUTION / ISSUE_RESOLVED_BY_SOLUTION, PRODUCT_HAS_ISSUE</small>
              </div>
              <div className="detail-box compact-box">
                <strong>운영 순서</strong>
                <small>1. 스키마 이름을 업무 기준으로 정합니다. 예: MANUFACTURING_QA</small>
                <small>2. 문서 유형을 입력합니다. 모르면 GENERAL_DOCUMENT로 시작합니다.</small>
                <small>3. 문서에서 반복되는 핵심 단어를 엔티티로 입력합니다. 예: PRODUCT, DEFECT, PROCESS</small>
                <small>4. 엔티티 사이의 의미를 관계로 입력합니다. 예: DEFECT_CAUSED_BY_PROCESS</small>
                <small>5. 활성화한 뒤 관련 문서를 재인덱싱합니다. 이미 인덱싱된 문서는 자동으로 새 스키마가 소급 적용되지 않습니다.</small>
                <small>6. 결과가 이상하면 삭제하지 말고 비활성화하세요. 기존 문서 그래프와 감사 추적을 안전하게 유지할 수 있습니다.</small>
              </div>
              <div className="detail-box compact-box">
                <strong>언제 기본 프로필로 지정하나요?</strong>
                <small>대부분의 문서가 특정 사업군 문서라면 해당 스키마를 기본 프로필로 지정할 수 있습니다.</small>
                <small>여러 사업군 문서가 섞여 있거나 아직 확신이 없으면 CORE를 기본값으로 유지하고, 새 스키마는 활성화만 해두는 것을 권장합니다.</small>
              </div>
            </div>
          </AdminUserModal>
        )}

        {schemaCreateOpen && (
          <AdminUserModal title="문서 그래프 스키마 추가" subtitle="새 사업군 또는 문서 유형용 프로필" icon={<Database size={18} />} className="schema-create-modal" onClose={closeSchemaCreate}>
            <form className="admin-modal-form" onSubmit={submitSchemaCreate}>
              {modalError && <div className="failure-line"><AlertTriangle size={14} />{modalError}</div>}
              <div className="stack">
                <label htmlFor="schema-create-name">스키마 이름</label>
                <input
                  id="schema-create-name"
                  value={schemaCreateForm.schemaName}
                  onChange={(event) => setSchemaCreateForm((current) => ({ ...current, schemaName: event.target.value }))}
                  placeholder="예: FINANCE_REPORT"
                  spellCheck="false"
                  autoFocus
                />
                <small className="field-help">저장 시 영문 대문자/밑줄 형식으로 정규화됩니다.</small>
              </div>
              <div className="stack">
                <label htmlFor="schema-create-description">설명</label>
                <textarea
                  id="schema-create-description"
                  rows={3}
                  value={schemaCreateForm.description}
                  onChange={(event) => setSchemaCreateForm((current) => ({ ...current, description: event.target.value }))}
                  placeholder="어떤 문서와 사업군에 쓰는 스키마인지 적어주세요."
                />
              </div>
              <div className="form-grid two">
                <div className="stack">
                  <label htmlFor="schema-create-doc-types">문서 유형</label>
                  <textarea
                    id="schema-create-doc-types"
                    rows={5}
                    value={schemaCreateForm.documentTypes}
                    onChange={(event) => setSchemaCreateForm((current) => ({ ...current, documentTypes: event.target.value }))}
                    spellCheck="false"
                  />
                </div>
                <div className="stack">
                  <label htmlFor="schema-create-entities">엔티티 유형</label>
                  <textarea
                    id="schema-create-entities"
                    rows={5}
                    value={schemaCreateForm.entityTypes}
                    onChange={(event) => setSchemaCreateForm((current) => ({ ...current, entityTypes: event.target.value }))}
                    spellCheck="false"
                  />
                </div>
              </div>
              <div className="stack">
                <label htmlFor="schema-create-relations">관계 유형</label>
                <textarea
                  id="schema-create-relations"
                  rows={5}
                  value={schemaCreateForm.relationTypes}
                  onChange={(event) => setSchemaCreateForm((current) => ({ ...current, relationTypes: event.target.value }))}
                  spellCheck="false"
                />
              </div>
              <div className="form-grid two">
                <label className="checkbox-row" htmlFor="schema-create-enabled">
                  <input
                    id="schema-create-enabled"
                    type="checkbox"
                    checked={schemaCreateForm.enabled}
                    onChange={(event) => setSchemaCreateForm((current) => ({ ...current, enabled: event.target.checked }))}
                  />
                  <span>생성 후 활성화</span>
                </label>
                <div className="schema-fixed-default-note">
                  <span>기본 프로필은 CORE로 고정됩니다.</span>
                </div>
              </div>
              <small className="field-help">기본 프로필은 문서 유형이 명확히 매칭되지 않을 때 사용하는 폴백 스키마입니다. 처음에는 CORE를 유지하는 것을 권장합니다.</small>
              <div className="action-row">
                <button disabled={!schemaCreateForm.schemaName.trim() || loading(`schema-profile-create-${schemaCreateForm.schemaName || 'new'}`)}>
                  {loading(`schema-profile-create-${schemaCreateForm.schemaName || 'new'}`) ? <Loader2 className="spin" size={16} /> : <CheckCircle2 size={16} />}
                  추가
                </button>
                <button className="ghost-button" type="button" onClick={closeSchemaCreate}>취소</button>
              </div>
            </form>
          </AdminUserModal>
        )}
      </div>
    );
  }

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
    const metricsSummary = adminTuningMetrics?.summary || {};
    const ollamaStatus = adminTuningMetrics?.ollama || {};
    const rerankerStatus = adminTuningMetrics?.reranker || {};
    const rerankerEnabled = Boolean(rerankerStatus.enabled);
    const hasMetrics = Number(metricsSummary.requestCount || 0) > 0;
    const metricValue = (value, suffix = 'ms') => (hasMetrics ? `${value || 0}${suffix}` : '수집 대기');
    const metricHint = (value, suffix = 'ms', label = '') => (hasMetrics ? `${label}${value || 0}${suffix}` : '질문 실행 후 표시');
    const metricCards = [
      { label: '평균 응답', value: metricValue(metricsSummary.avgTotalMs), hint: metricHint(metricsSummary.p95TotalMs, 'ms', 'p95 ') },
      { label: 'LLM 비중', value: hasMetrics ? `${metricsSummary.avgLlmSharePercent || 0}%` : '수집 대기', hint: hasMetrics ? '생성 단계 비율' : '답변 생성 후 계산' },
      { label: '검색/임베딩', value: metricValue(metricsSummary.avgSearchMs), hint: metricHint(metricsSummary.avgEmbeddingMs, 'ms', 'embedding ') },
      { label: 'rerank', value: metricValue(metricsSummary.avgRerankMs), hint: hasMetrics ? '문서 재정렬' : 'rerank 사용 시 표시' },
      { label: '토큰 예산', value: hasMetrics ? `${metricsSummary.avgPromptTokens || 0}` : '수집 대기', hint: hasMetrics ? `budget ${metricsSummary.promptTokenBudget || '-'}` : `budget ${metricsSummary.promptTokenBudget || adminTuning?.settings?.find((item) => item.key === 'RAG_PIPELINE_PROMPT_TOKEN_BUDGET_BALANCED')?.value || '-'}` },
      { label: 'Ollama 대기', value: hasMetrics ? `${ollamaStatus.estimatedQueue || 0}` : '대기 없음', hint: `parallel ${ollamaStatus.configuredParallel || '-'}` },
      { label: 'GPU 상태', value: ollamaStatus.gpuMode === 'UNKNOWN' ? '확인 대기' : (ollamaStatus.gpuMode || '확인 대기'), hint: ollamaStatus.primaryModel || adminTuning?.effectivePrimaryChatModel || '-' },
    ];

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

          <section className="tuning-section tuning-metrics-section">
            <div className="panel-title compact-title">
              <Cpu size={18} />
              <div>
                <h2>모델 상태 / 성능 계측</h2>
                <p>최근 요청 기준으로 병목과 튜닝 후보를 확인합니다.</p>
              </div>
              <div className="tuning-metrics-actions">
                <button className="ghost-button compact-action" type="button" disabled={loading('admin-tuning-metrics')} onClick={refreshAdminTuningMetrics}>
                  {loading('admin-tuning-metrics') ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
                  갱신
                </button>
                <button className="ghost-button compact-action" type="button" disabled={loading('admin-tuning-metrics-reset')} onClick={resetAdminTuningMetrics}>
                  {loading('admin-tuning-metrics-reset') ? <Loader2 className="spin" size={14} /> : <Trash2 size={14} />}
                  리셋
                </button>
              </div>
            </div>
            <div className="tuning-metric-grid">
              {metricCards.map((card) => (
                <article className="tuning-metric-card" key={card.label}>
                  <small>{card.label}</small>
                  <strong>{card.value}</strong>
                  <span>{card.hint}</span>
                </article>
              ))}
            </div>
            <div className="tuning-recommendation-card reranker-control-card">
              <div>
                <span className="tuning-recommendation-kicker">문서 reranker</span>
                <strong>{rerankerEnabled ? '켜짐' : '꺼짐'} · {rerankerStatus.serviceStatus || '상태 확인 대기'}</strong>
                <small>
                  {rerankerStatus.modelLoaded ? '모델 로드됨' : '모델 미로드'}
                  {' · '}
                  idle unload {rerankerStatus.idleUnloadSeconds ?? 300}s
                  {rerankerStatus.cudaReservedBytes ? ` · reserved ${Math.round(Number(rerankerStatus.cudaReservedBytes) / 1024 / 1024)}MB` : ''}
                </small>
              </div>
              <div className="tuning-metrics-actions">
                <button
                  className={rerankerEnabled ? 'ghost-button compact-action active' : 'ghost-button compact-action'}
                  disabled={loading('admin-tuning-reranker')}
                  type="button"
                  onClick={() => toggleReranker(!rerankerEnabled)}
                >
                  {loading('admin-tuning-reranker') ? <Loader2 className="spin" size={14} /> : <Bot size={14} />}
                  {rerankerEnabled ? '끄기' : '켜기'}
                </button>
                <button
                  className="ghost-button compact-action"
                  disabled={!rerankerEnabled || loading('admin-tuning-reranker') || loading('admin-tuning-metrics')}
                  type="button"
                  onClick={refreshAdminTuningMetrics}
                >
                  <RefreshCw size={14} />
                  상태 확인
                </button>
              </div>
            </div>
            <div className="tuning-recommendation-card">
              <div>
                <span className="tuning-recommendation-kicker">{hasMetrics ? '튜닝 추천' : '계측 준비 중'}</span>
                <strong>{adminTuningRecommendations?.summary || '최근 계측 데이터가 아직 부족합니다.'}</strong>
                <small>{hasMetrics ? `추천 신뢰도 ${adminTuningRecommendations?.confidence || 'LOW'} · 최근 ${metricsSummary.requestCount || 0}건 기준` : '문서 또는 코드 질문을 실행하면 이 영역에 병목과 추천값이 표시됩니다.'}</small>
              </div>
              <button
                type="button"
                className="ghost-button compact-action"
                disabled={!(adminTuningRecommendations?.changes || []).length}
                onClick={previewRecommendedTuning}
              >
                추천값 미리보기
              </button>
            </div>
            {!!(adminTuningRecommendations?.changes || []).length && (
              <div className="tuning-recommendation-list">
                {adminTuningRecommendations.changes.map((change) => (
                  <article key={change.key}>
                    <strong>{tuningText[change.key]?.[0] || change.key}</strong>
                    <span>{change.currentValue} → {change.recommendedValue}</span>
                    <small title={change.risk}>{change.reason}</small>
                  </article>
                ))}
              </div>
            )}
            {!!(adminTuningRecommendations?.notes || []).length && (
              <div className="tuning-note-list">
                {adminTuningRecommendations.notes.map((note) => (
                  <small key={note}>{note}</small>
                ))}
              </div>
            )}
            {!!(adminTuningMetrics?.recent || []).length && (
              <div className="tuning-recent-table">
                <table>
                  <thead>
                    <tr>
                      <th>영역</th>
                      <th>모드</th>
                      <th>총 시간</th>
                      <th>LLM</th>
                      <th>검색</th>
                      <th>임베딩</th>
                      <th>청크</th>
                      <th>토큰</th>
                    </tr>
                  </thead>
                  <tbody>
                    {adminTuningMetrics.recent.slice(0, 8).map((item, index) => (
                      <tr key={`${item.createdAt}-${index}`}>
                        <td>{item.domain}</td>
                        <td>{item.mode}</td>
                        <td>{item.totalMs}ms</td>
                        <td>{item.llmMs}ms</td>
                        <td>{item.searchMs}ms</td>
                        <td>{item.embeddingMs}ms</td>
                        <td>{item.contextChunkCount}</td>
                        <td>{item.promptEvalTokens || 0}/{item.promptTokenBudget || '-'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

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
                  placeholder={adminTuning?.effectiveAuxiliaryChatModel || 'qwen3:4b-instruct'}
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
                  <p>{category === 'Ollama' ? 'Ollama 데몬 시작값입니다. 병렬/로드 모델 수 변경은 컨테이너 재시작 후 적용됩니다.' : '저장 즉시 새 요청부터 반영됩니다.'}</p>
                </div>
              </div>
              <div className="tuning-grid">
                {items.map((setting) => {
                  const value = tuningValues[setting.key] ?? setting.value ?? setting.defaultValue;
                  const restartPending = setting.restartRequired && Number(setting.value) !== Number(setting.effectiveValue);
                  const isSelect = setting.control === 'select';
                  const inputStep = setting.key === 'RAG_PIPELINE_PROMPT_TOKEN_BUDGET_BALANCED' ? 1 : setting.step;
                  const text = tuningText[setting.key] || [setting.label, setting.description, setting.impact];
                  return (
                    <label className="tuning-control" key={setting.key} title={`${text[1]} ${text[2]}`}>
                      <span className="tuning-control-head">
                        <strong>{text[0]}</strong>
                        {recommendedTuningKeys.includes(setting.key) && <em>추천값</em>}
                        {restartPending ? <em>적용 대기</em> : setting.restartRequired && <em>재시작 항목</em>}
                      </span>
                      <small>{text[2]}</small>
                      {restartPending && (
                        <small className="field-help">저장값 {setting.value} · 현재 적용값 {setting.effectiveValue}</small>
                      )}
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
                            step={inputStep}
                            value={value}
                            onChange={(event) => updateTuningValue(setting.key, event.target.value)}
                          />
                          <input
                            type="number"
                            min={setting.min}
                            max={setting.max}
                            step={inputStep}
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
              <h2>복구</h2>
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

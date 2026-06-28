import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, ChevronDown, ChevronUp, Eye, FileArchive, FileCode2, GitBranch, Info, Loader2, Play, RefreshCw, RotateCcw, Search, ShieldCheck, Trash2, X } from 'lucide-react';
import { codeModes, evidencePreviewLimit } from '../../config/constants.js';
import { formatDate, getCodeModeGuide, getCodeModeLabel, getStatusLabel, jobChangeText, jobPercent, submitFormOnShortcut } from '../../lib/formatters.js';
import { escapeHtml, highlightLanguage, highlightedLineHtml } from '../../lib/highlight.js';
import { useStreamingAutoScroll } from '../../lib/useStreamingAutoScroll.js';
import { IconButton, ModeControl, StatusBadge } from '../common/Common.jsx';
import { RagChatPanel } from '../common/RagChatPanel.jsx';
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
    codeAgentInstruction = '',
    setCodeAgentInstruction = () => {},
    codeAgentPlan,
    codeAgentPatch,
    codeAgentApplyResult,
    codeAgentTestResult,
    codeAgentMutationPolicy,
    codeAgentLocalPatchRequest,
    codeAgentLocalPatchReadiness,
    codeAgentLocalPatchDryRunRequest,
    codeAgentLocalPatchDryRunResult,
    codeAgentLocalRepositoryObservationRequest,
    codeAgentLocalRepositoryObservationResult,
    localAgentStatus,
    codeConversations = [],
    codeConversationId = '',
    codeConversationTurns = [],
    refreshCodeConversations = () => {},
    loadCodeConversation = () => {},
    startNewCodeConversation = () => {},
    codeSearchQuery = '',
    setCodeSearchQuery = () => {},
    codeSearchResults = [],
    referenceSymbol = '',
    setReferenceSymbol = () => {},
    referenceResult,
    openCodeFile = () => {},
    askCode = (event) => event.preventDefault(),
    generateCodeAgentPlan = (event) => event.preventDefault(),
    generateCodeAgentPatch = () => {},
    prepareCodeAgentLocalPatchRequest = () => {},
    decideCodeAgentLocalPatchApproval = () => {},
    refreshCodeAgentLocalPatchReadiness = () => {},
    queueCodeAgentLocalPatchDryRun = () => {},
    refreshCodeAgentLocalPatchDryRunResult = () => {},
    queueCodeAgentLocalRepositoryObservation = () => {},
    refreshCodeAgentLocalRepositoryObservationResult = () => {},
    refreshLocalAgentStatus = () => {},
    applyCodeAgentPatch = () => {},
    rollbackCodeAgentPatch = () => {},
    runCodeAgentTest = () => {},
    searchCode = (event) => event.preventDefault(),
    findReferences = (event) => event.preventDefault(),
    loading = () => false,
    codeFileLoading = false,
    showSourceManagement = true,
  } = props;
  const activeCodeModeGuide = getCodeModeGuide(codeMode);
  const answerStreamAnchorRef = useRef(null);
  const chatTurns = props.pendingCodeTurn
    ? [...(codeConversationTurns || []), props.pendingCodeTurn]
    : (codeConversationTurns || []);
  const latestAnswer = props.pendingCodeTurn || codeAnswer;
  useStreamingAutoScroll(answerStreamAnchorRef, latestAnswer?.streaming, latestAnswer?.answer);

  return (
    <section className="workspace-grid code-grid workspace-product code-workspace-product">
      <div className="workspace-product-hero code-product-hero">
        <div>
          <Badge variant="secondary">Code RAG</Badge>
          <h1>코드 어시스턴트</h1>
          <p>저장소의 실제 파일, 라인, 참조 위치를 근거로 코드 질문에 답합니다.</p>
        </div>
        <div className="workspace-product-metrics" aria-label="코드 RAG 상태 요약">
          <span><strong>{repositories.length}</strong> repositories</span>
          <span><strong>{codeSearchResults.length}</strong> search hits</span>
          <span><strong>{referenceResult ? (referenceResult.definitions?.length || 0) + (referenceResult.references?.length || 0) : 0}</strong> references</span>
        </div>
      </div>
      {showSourceManagement && <CodeSourceManagementPanel {...props} />}

      <div className={showSourceManagement ? 'right-column' : 'right-column full-column'}>
        <RagChatPanel
          domain="CODE"
          turns={chatTurns}
          question={codeQuestion}
          setQuestion={setCodeQuestion}
          onSubmit={askCode}
          onKeyDown={(event) => submitFormOnShortcut(event, Boolean(codeQuestion.trim()) && !loading('code-ask'))}
          placeholder={activeCodeModeGuide.placeholder}
          loading={loading('code-ask')}
          disabled={!codeQuestion.trim()}
          submitLabel={codeConversationId ? '추가 질문' : '코드 질문'}
          emptyTitle="코드에게 질문하기"
          emptyDescription="저장소의 실제 파일, 라인, 참조 근거를 바탕으로 코드 질문에 답합니다."
          controls={(
            <>
              <RepositorySelect
                repositories={repositories}
                selectedRepository={selectedRepository}
                selectedRepositoryId={selectedRepositoryId}
                setSelectedRepositoryId={setSelectedRepositoryId}
              />
              <ModeControl modes={codeModes} value={codeMode} setValue={setCodeMode} className="code-mode-control" />
            </>
          )}
          guide={(
            <ConversationInlineActions
              activeConversationId={codeConversationId}
              turnCount={codeConversationTurns.length}
              loading={loading}
              loadingKey="code-conversations"
              onRefresh={refreshCodeConversations}
              onNew={startNewCodeConversation}
            />
          )}
          templates={[
            { label: '구조 요약', prompt: '선택한 저장소의 주요 구조와 진입점을 근거와 함께 요약해줘.' },
            { label: '오류 원인', prompt: '이 오류가 발생할 수 있는 코드 경로와 수정 후보를 근거와 함께 알려줘.' },
            { label: '참조 추적', prompt: '이 기능을 호출하는 위치와 영향 범위를 파일/라인 근거와 함께 추적해줘.' },
            { label: '변경 영향', prompt: '이 코드를 변경하면 영향을 받을 수 있는 모듈과 테스트 포인트를 알려줘.' },
          ]}
          evidenceRenderer={(turn) => <CodeEvidenceList evidence={turn.evidence} onOpenEvidence={openCodeFile} />}
          onSaveAnswer={props.saveAnswer}
          onCancel={props.cancelCodeAsk}
          answerSavedId={props.answerSavedId}
          saveLoading={loading('save-code-answer')}
          streamAnchorRef={answerStreamAnchorRef}
        />
        <CodeAgentPanel
          instruction={codeAgentInstruction}
          setInstruction={setCodeAgentInstruction}
          plan={codeAgentPlan}
          patch={codeAgentPatch}
          applyResult={codeAgentApplyResult}
          testResult={codeAgentTestResult}
          mutationPolicy={codeAgentMutationPolicy}
          localPatchRequest={codeAgentLocalPatchRequest}
          localPatchReadiness={codeAgentLocalPatchReadiness}
          localPatchDryRunRequest={codeAgentLocalPatchDryRunRequest}
          localPatchDryRunResult={codeAgentLocalPatchDryRunResult}
          localRepositoryObservationRequest={codeAgentLocalRepositoryObservationRequest}
          localRepositoryObservationResult={codeAgentLocalRepositoryObservationResult}
          localAgentStatus={localAgentStatus}
          localAgentTokens={props.localAgentTokens}
          selectedRepositoryId={selectedRepositoryId}
          loading={loading}
          onPlan={generateCodeAgentPlan}
          onPatch={generateCodeAgentPatch}
          onPrepareLocalPatchRequest={prepareCodeAgentLocalPatchRequest}
          onLocalPatchApproval={decideCodeAgentLocalPatchApproval}
          onRefreshLocalPatchReadiness={refreshCodeAgentLocalPatchReadiness}
          onQueueLocalPatchDryRun={queueCodeAgentLocalPatchDryRun}
          onRefreshLocalPatchDryRunResult={refreshCodeAgentLocalPatchDryRunResult}
          onQueueLocalRepositoryObservation={queueCodeAgentLocalRepositoryObservation}
          onRefreshLocalRepositoryObservationResult={refreshCodeAgentLocalRepositoryObservationResult}
          onRefreshLocalAgent={refreshLocalAgentStatus}
          onRefreshLocalAgentTokens={props.refreshLocalAgentTokens}
          onRevokeLocalAgentToken={props.revokeLocalAgentToken}
          onApply={applyCodeAgentPatch}
          onRollback={rollbackCodeAgentPatch}
          onTest={runCodeAgentTest}
        />
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
              <p>메서드, 클래스, 컴포넌트 이름으로 정의와 사용 위치를 확인합니다.</p>
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

function isExpectedDryRunRefusal(result) {
  return result?.status === 'REJECTED'
    && result?.failureCode === 'UNSAFE_TOOL'
    && result?.output?.dryRun === true
    && result?.output?.preflightPassed === true
    && result?.output?.mutationApplied === false;
}

function summarizeDryRunObservationFiles(files = []) {
  return files
    .map((file) => `${file.path || '(unknown)'}:${file.hashMatches ? 'hash-ok' : 'hash-check'}/${file.contextMatches ? 'context-ok' : 'context-blocked'}`)
    .join(', ');
}

function findReadinessCheck(readiness, key) {
  return (readiness?.checks || []).find((check) => check.key === key) || null;
}

function formatReadinessCheck(check) {
  return `${check.passed ? 'pass' : 'blocked'} / ${check.key}: ${check.message}`;
}

function formatObservationLinkage(value) {
  const linkage = value?.observationLinkage;
  if (!linkage?.status) {
    return null;
  }
  const parts = [`observation linkage: ${linkage.status}`];
  if (linkage.releaseAttemptLinked !== undefined) {
    parts.push(`release-attempt linked: ${String(linkage.releaseAttemptLinked)}`);
  }
  if (linkage.sourceOnlyFallback !== undefined) {
    parts.push(`source-only fallback: ${String(linkage.sourceOnlyFallback)}`);
  }
  if (linkage.releaseAttemptId) {
    parts.push(`attempt ${String(linkage.releaseAttemptId).slice(0, 8)}`);
  }
  if (linkage.sourceRequestId) {
    parts.push(`source ${String(linkage.sourceRequestId).slice(0, 8)}`);
  }
  return parts.join(' / ');
}

function compareRepositoryObservation(source = {}, observationResult = {}) {
  if (observationResult?.output?.repositoryVerification) {
    return observationResult.output.repositoryVerification;
  }
  const identity = observationResult?.output?.repositoryIdentity || {};
  const checks = [];
  addRepositoryCheck(checks, 'branch', source.branch, identity.branch, { skipHead: true });
  addRepositoryCheck(checks, 'head', source.lastIndexedCommit, identity.headCommit);
  addRepositoryCheck(checks, 'remote', source.gitUrl, identity.remoteUrl, { normalizeUrl: true });
  const considered = checks.filter((check) => check.status !== 'SKIPPED');
  if (!observationResult || observationResult.status !== 'SUCCEEDED') {
    return { status: 'UNVERIFIED', checks, message: 'Local repository observation has not completed successfully.' };
  }
  if (!considered.length || considered.some((check) => check.status === 'UNKNOWN')) {
    return { status: 'UNVERIFIED', checks, message: 'Not enough local repository identity data to verify this workspace.' };
  }
  if (considered.some((check) => check.status === 'MISMATCH')) {
    return { status: 'MISMATCH', checks, message: 'Local workspace identity does not match the indexed repository metadata.' };
  }
  return { status: 'MATCH', checks, message: 'Observed local repository identity matches available indexed metadata.' };
}

function addRepositoryCheck(checks, key, expected, actual, options = {}) {
  const expectedText = String(expected || '').trim();
  const actualText = String(actual || '').trim();
  if (options.skipHead && (!expectedText || expectedText.toUpperCase() === 'HEAD')) {
    checks.push({ key, status: 'SKIPPED', expected: expectedText, actual: actualText });
    return;
  }
  if (!expectedText || !actualText) {
    checks.push({ key, status: 'UNKNOWN', expected: expectedText, actual: actualText });
    return;
  }
  const left = options.normalizeUrl ? normalizeRepositoryUrl(expectedText) : expectedText.toLowerCase();
  const right = options.normalizeUrl ? normalizeRepositoryUrl(actualText) : actualText.toLowerCase();
  const matched = key === 'head'
    ? left === right || left.startsWith(right) || right.startsWith(left)
    : left === right;
  checks.push({ key, status: matched ? 'MATCH' : 'MISMATCH', expected: expectedText, actual: actualText });
}

function normalizeRepositoryUrl(value = '') {
  return String(value || '')
    .trim()
    .replace(/^git@([^:]+):/i, 'https://$1/')
    .replace(/^ssh:\/\/git@/i, 'https://')
    .replace(/^https?:\/\/([^@/]+@)/i, 'https://')
    .replace(/\.git$/i, '')
    .replace(/\/+$/g, '')
    .toLowerCase();
}

function CodeAgentPanel({
  instruction = '',
  setInstruction = () => {},
  plan,
  patch,
  applyResult,
  testResult,
  mutationPolicy,
  localPatchRequest,
  localPatchReadiness,
  localPatchDryRunRequest,
  localPatchDryRunResult,
  localRepositoryObservationRequest,
  localRepositoryObservationResult,
  localAgentStatus,
  localAgentTokens = [],
  selectedRepositoryId = '',
  loading = () => false,
  onPlan = (event) => event.preventDefault(),
  onPatch = () => {},
  onPrepareLocalPatchRequest = () => {},
  onLocalPatchApproval = () => {},
  onRefreshLocalPatchReadiness = () => {},
  onQueueLocalPatchDryRun = () => {},
  onRefreshLocalPatchDryRunResult = () => {},
  onQueueLocalRepositoryObservation = () => {},
  onRefreshLocalRepositoryObservationResult = () => {},
  onRefreshLocalAgent = () => {},
  onRefreshLocalAgentTokens = () => {},
  onRevokeLocalAgentToken = () => {},
  onApply = () => {},
  onRollback = () => {},
  onTest = () => {},
}) {
  const targetFiles = plan?.targetFiles || [];
  const canPlan = Boolean(selectedRepositoryId && instruction.trim()) && !loading('code-agent-plan');
  const canPatch = Boolean(selectedRepositoryId && instruction.trim() && targetFiles.length && !loading('code-agent-patch'));
  const canApply = false;
  const canRollback = false;
  const activeTokenCount = localAgentTokens.filter((token) => token.active).length;
  const tokenRefreshLoading = loading('local-agent-tokens');
  const configuredTransport = localAgentStatus?.configuredTransport;
  const activeTransport = localAgentStatus?.activeTransport;
  const transportLabel = activeTransport || configuredTransport;
  const retryAt = localAgentStatus?.nextWebSocketRetryAt;
  const mutationTarget = mutationPolicy?.intendedExecutionTarget || 'USER_LOCAL_AGENT';
  const approvedWorkspace = (localAgentStatus?.workspaces || []).find((workspace) => workspace.approved);
  const canPrepareLocalPatchRequest = Boolean(
    patch?.valid
    && localAgentStatus?.state === 'CONNECTED'
    && localAgentStatus?.agentId
    && approvedWorkspace?.workspaceId
    && !loading('code-agent-local-patch-request')
  );
  const canDecideLocalPatchRequest = localPatchRequest?.status === 'APPROVAL_REQUIRED';
  const canRefreshReadiness = localPatchRequest?.status === 'APPROVED_HELD'
    && !loading(`code-agent-local-patch-readiness-${localPatchRequest.requestId}`);
  const canQueueDryRun = localPatchRequest?.status === 'APPROVED_HELD'
    && !loading(`code-agent-local-patch-dry-run-${localPatchRequest.requestId}`);
  const canRefreshDryRun = Boolean(localPatchDryRunRequest?.requestId)
    && !loading(`code-agent-local-patch-dry-run-result-${localPatchDryRunRequest.requestId}`);
  const localObservationWorkspaceId = localPatchRequest?.workspaceId || localPatchRequest?.input?.localWorkspace?.workspaceId;
  const canQueueRepositoryObservation = Boolean(localPatchRequest?.agentId && localObservationWorkspaceId)
    && !loading(`code-agent-local-repository-observation-${localObservationWorkspaceId}`);
  const canRefreshRepositoryObservation = Boolean(localRepositoryObservationRequest?.requestId)
    && !loading(`code-agent-local-repository-observation-result-${localRepositoryObservationRequest.requestId}`);
  const visibleReadiness = localPatchReadiness?.requestId === localPatchRequest?.requestId
    ? localPatchReadiness
    : null;
  const visibleDryRun = localPatchDryRunResult?.requestId === localPatchDryRunRequest?.requestId
    ? localPatchDryRunResult
    : null;
  const visibleRepositoryObservation = localRepositoryObservationResult?.requestId === localRepositoryObservationRequest?.requestId
    ? localRepositoryObservationResult
    : null;
  const repositoryObservationComparison = compareRepositoryObservation(
    localPatchRequest?.input?.sourceRepository,
    visibleRepositoryObservation
  );
  const readinessRepositoryVerification = visibleReadiness?.repositoryVerification || null;
  const readinessWorkspaceVerification = visibleReadiness?.workspaceVerification || null;
  const readinessPatchRelease = visibleReadiness?.patchReleaseReadiness || null;
  const readinessPatchExecutionGate = visibleReadiness?.patchExecutionGate || null;
  const readinessReleaseAttemptModel = visibleReadiness?.releaseAttemptModel || readinessPatchExecutionGate?.releaseAttemptModel || null;
  const readinessFreshObservationRequestPlan = Array.isArray(readinessReleaseAttemptModel?.latestAttempt?.freshObservationRequestPlan)
    ? readinessReleaseAttemptModel.latestAttempt.freshObservationRequestPlan
    : [];
  const readinessFreshObservationEvidenceStatus = Array.isArray(readinessReleaseAttemptModel?.latestAttempt?.freshObservationEvidenceStatus)
    ? readinessReleaseAttemptModel.latestAttempt.freshObservationEvidenceStatus
    : [];
  const readinessFreshObservationEvidenceCompleteness = readinessReleaseAttemptModel?.latestAttempt?.freshObservationEvidenceCompleteness || null;
  const readinessReleaseAttemptFinalReadiness = readinessReleaseAttemptModel?.latestAttempt?.releaseAttemptFinalReadiness || null;
  const readinessLocalAgentMutationExecutionSequencePlan = Array.isArray(readinessReleaseAttemptModel?.latestAttempt?.localAgentMutationExecutionSequencePlan)
    ? readinessReleaseAttemptModel.latestAttempt.localAgentMutationExecutionSequencePlan
    : [];
  const readinessPostMutationResultContract = readinessReleaseAttemptModel?.latestAttempt?.postMutationResultContract || null;
  const readinessPostMutationExpectedOutcomes = Array.isArray(readinessPostMutationResultContract?.expectedOutcomes)
    ? readinessPostMutationResultContract.expectedOutcomes
    : [];
  const readinessMutationResultIntakeBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.mutationResultIntakeBoundary || null;
  const readinessMutationResultIntakeRequirements = Array.isArray(readinessMutationResultIntakeBoundary?.requirements)
    ? readinessMutationResultIntakeBoundary.requirements
    : [];
  const readinessMutationResultIntakeAcceptedStatuses = Array.isArray(readinessMutationResultIntakeBoundary?.acceptedTerminalStatuses)
    ? readinessMutationResultIntakeBoundary.acceptedTerminalStatuses
    : [];
  const readinessMutationResultAggregationPlan =
    readinessReleaseAttemptModel?.latestAttempt?.mutationResultAggregationPlan || null;
  const readinessMutationResultAggregationSteps = Array.isArray(readinessMutationResultAggregationPlan?.steps)
    ? readinessMutationResultAggregationPlan.steps
    : [];
  const readinessFinalMutationReportContract = readinessReleaseAttemptModel?.latestAttempt?.finalMutationReportContract || null;
  const readinessFinalMutationReportSections = Array.isArray(readinessFinalMutationReportContract?.requiredSections)
    ? readinessFinalMutationReportContract.requiredSections
    : [];
  const readinessFinalMutationReportGuardrails = Array.isArray(readinessFinalMutationReportContract?.answerQualityGuardrails)
    ? readinessFinalMutationReportContract.answerQualityGuardrails
    : [];
  const readinessFinalMutationReportFinalizationBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.finalMutationReportFinalizationBoundary || null;
  const readinessFinalMutationReportFinalizationRequirements = Array.isArray(
    readinessFinalMutationReportFinalizationBoundary?.requirements
  )
    ? readinessFinalMutationReportFinalizationBoundary.requirements
    : [];
  const readinessFinalAnswerPublicationBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.finalAnswerPublicationBoundary || null;
  const readinessFinalAnswerPublicationRequirements = Array.isArray(
    readinessFinalAnswerPublicationBoundary?.requirements
  )
    ? readinessFinalAnswerPublicationBoundary.requirements
    : [];
  const readinessFinalAnswerPublicationGuardrails = Array.isArray(
    readinessFinalAnswerPublicationBoundary?.answerQualityGuardrails
  )
    ? readinessFinalAnswerPublicationBoundary.answerQualityGuardrails
    : [];
  const readinessReleaseEnablementChecklist = readinessReleaseAttemptModel?.latestAttempt?.releaseEnablementChecklist || null;
  const readinessReleaseEnablementChecklistItems = Array.isArray(readinessReleaseEnablementChecklist?.items)
    ? readinessReleaseEnablementChecklist.items
    : [];
  const readinessMutationDispatchEnvelopeContract =
    readinessReleaseAttemptModel?.latestAttempt?.mutationDispatchEnvelopeContract || null;
  const readinessMutationDispatchOrderedToolSequence = Array.isArray(
    readinessMutationDispatchEnvelopeContract?.orderedToolSequence
  )
    ? readinessMutationDispatchEnvelopeContract.orderedToolSequence
    : [];
  const readinessMutationDispatchRequiredApprovals = Array.isArray(
    readinessMutationDispatchEnvelopeContract?.requiredApprovals
  )
    ? readinessMutationDispatchEnvelopeContract.requiredApprovals
    : [];
  const readinessMutationDispatchPreflightBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.mutationDispatchPreflightBoundary || null;
  const readinessMutationDispatchPreflightCapabilityChecks = Array.isArray(
    readinessMutationDispatchPreflightBoundary?.capabilityChecks
  )
    ? readinessMutationDispatchPreflightBoundary.capabilityChecks
    : [];
  const readinessMutationDispatchDecisionModel =
    readinessReleaseAttemptModel?.latestAttempt?.mutationDispatchDecisionModel || null;
  const readinessMutationDispatchDecisionInputs = Array.isArray(
    readinessMutationDispatchDecisionModel?.readinessInputs
  )
    ? readinessMutationDispatchDecisionModel.readinessInputs
    : [];
  const readinessMutationRequestBlueprint =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestBlueprint || null;
  const readinessMutationRequestBlueprintToolRequests = Array.isArray(
    readinessMutationRequestBlueprint?.orderedToolRequests
  )
    ? readinessMutationRequestBlueprint.orderedToolRequests
    : [];
  const readinessMutationRequestBlueprintApprovalStates = Array.isArray(
    readinessMutationRequestBlueprint?.approvalStates
  )
    ? readinessMutationRequestBlueprint.approvalStates
    : [];
  const readinessMutationRequestCreationGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestCreationGate || null;
  const readinessMutationRequestCreationGatePolicyChecks = Array.isArray(
    readinessMutationRequestCreationGate?.policyChecks
  )
    ? readinessMutationRequestCreationGate.policyChecks
    : [];
  const readinessMutationRequestPushGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestPushGate || null;
  const readinessMutationRequestPushGatePolicyChecks = Array.isArray(
    readinessMutationRequestPushGate?.policyChecks
  )
    ? readinessMutationRequestPushGate.policyChecks
    : [];
  const readinessMutationRequestClaimGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestClaimGate || null;
  const readinessMutationRequestClaimGatePolicyChecks = Array.isArray(
    readinessMutationRequestClaimGate?.policyChecks
  )
    ? readinessMutationRequestClaimGate.policyChecks
    : [];
  const readinessMutationCompletionSummary = readinessReleaseAttemptModel?.latestAttempt?.mutationCompletionSummary || null;
  const readinessMutationCompletionSummaryItems = Array.isArray(readinessMutationCompletionSummary?.items)
    ? readinessMutationCompletionSummary.items
    : [];
  const readinessReleaseBoundaryVisibility = localPatchRequest?.status === 'APPROVED_HELD'
    ? {
        status: 'RELEASE_REFUSAL_ONLY_VISIBLE',
        actionMode: 'REFUSAL_ONLY',
        endpoint: `/api/local-agents/tools/${localPatchRequest.requestId}/release`,
        releaseGateEnabled: false,
        requestCreationEnabled: false,
        pushEnabled: false,
        claimEnabled: false,
        writeHelperEnabled: false,
        claimable: false,
        mutationAllowed: false,
        applyEnabled: false,
        testEnabled: false,
        rollbackRestoreEnabled: false,
        ragFreshnessUpdateEnabled: false,
      }
    : null;
  const readinessFreshObservationEnqueueBoundary = readinessReleaseAttemptModel?.latestAttempt?.evidence?.freshObservationEnqueueBoundary || null;
  const readinessFreshObservationBoundaryRequests = Array.isArray(readinessFreshObservationEnqueueBoundary?.plannedRequests)
    ? readinessFreshObservationEnqueueBoundary.plannedRequests
    : [];
  const expectedDryRunRefusal = isExpectedDryRunRefusal(visibleDryRun);
  const dryRunSnapshotObservation = visibleDryRun?.output?.snapshotObservation;
  const dryRunRollbackObservation = visibleDryRun?.output?.rollbackObservation;
  const readinessSnapshot = visibleReadiness?.snapshotReadiness || null;
  const readinessRollback = visibleReadiness?.rollbackReadiness || null;
  const readinessSnapshotManifestCheck = findReadinessCheck(visibleReadiness, 'snapshotManifestPreview');
  const readinessRollbackPreconditionsCheck = findReadinessCheck(visibleReadiness, 'rollbackRestorePreconditions');
  const visibleReadinessChecks = (visibleReadiness?.checks || [])
    .filter((check) => !['snapshotManifestPreview', 'rollbackRestorePreconditions'].includes(check.key));
  return (
    <section className="panel code-agent-panel">
      <div className="panel-title">
        <FileCode2 size={18} />
        <div>
          <h2>Patch Agent v2</h2>
          <p>수정 계획과 검증된 unified diff를 제안합니다. 파일 자동 적용은 하지 않습니다.</p>
        </div>
      </div>
      <div className="code-agent-result compact-result">
        <div className="result-heading">
          <strong>Local Agent</strong>
          <Badge variant={localAgentStatus?.state === 'CONNECTED' ? 'outline' : 'destructive'}>{localAgentStatus?.state || 'DISCONNECTED'}</Badge>
        </div>
        <small>{localAgentStatus?.message || 'No Local Agent is connected. User-owned file changes require a per-user Local Agent.'}</small>
        {transportLabel && (
          <small>
            transport: {configuredTransport && activeTransport && configuredTransport !== activeTransport
              ? `${configuredTransport} -> ${activeTransport}`
              : transportLabel}
            {localAgentStatus?.webSocketFailureCount ? `, websocket failures ${localAgentStatus.webSocketFailureCount}` : ''}
            {retryAt ? `, next retry ${formatDate(retryAt)}` : ''}
          </small>
        )}
        {!!localAgentStatus?.workspaces?.length && (
          <small>{localAgentStatus.workspaces.filter((workspace) => workspace.approved).length}/{localAgentStatus.workspaces.length} approved workspaces</small>
        )}
        <small>
          mutation target: {mutationTarget}
          {mutationPolicy?.localAgentMutationEnabled ? ', enabled' : ', apply/test/rollback disabled'}
          {mutationPolicy?.serverLocalMutationEnabled ? ', server-local prototype enabled' : ''}
        </small>
        {mutationPolicy?.message && <small>{mutationPolicy.message}</small>}
        <button type="button" className="ghost-button compact-action" onClick={() => { onRefreshLocalAgent(); onRefreshLocalAgentTokens(); }}>
          <RefreshCw size={14} />
          refresh
        </button>
        <div className="local-agent-token-list">
          <div className="result-heading">
            <strong>Tokens</strong>
            <Badge variant={activeTokenCount ? 'outline' : 'secondary'}>{activeTokenCount} active</Badge>
          </div>
          {!localAgentTokens.length ? (
            <small>No paired Local Agent tokens.</small>
          ) : (
            localAgentTokens.slice(0, 5).map((token) => (
              <div className="local-agent-token-row" key={token.id}>
                <span>
                  <strong>{token.label || 'Local Agent'}</strong>
                  <small>{token.agentId}</small>
                  <small>{token.lastSeenAt ? `last seen ${formatDate(token.lastSeenAt)}` : `created ${formatDate(token.createdAt)}`}</small>
                </span>
                <Badge variant={token.active ? 'outline' : 'secondary'}>{token.revokedAt ? 'revoked' : token.active ? 'active' : 'expired'}</Badge>
                {!token.revokedAt && (
                  <button
                    type="button"
                    className="ghost-button compact-action"
                    disabled={loading(`local-agent-token-revoke-${token.id}`)}
                    onClick={() => onRevokeLocalAgentToken(token.id)}
                  >
                    {loading(`local-agent-token-revoke-${token.id}`) ? <Loader2 className="spin" size={14} /> : <Trash2 size={14} />}
                    revoke
                  </button>
                )}
              </div>
            ))
          )}
          {localAgentTokens.length > 5 && <small>{localAgentTokens.length - 5} older tokens hidden</small>}
          {tokenRefreshLoading && <small>Refreshing tokens...</small>}
        </div>
      </div>
      <form className="stack" onSubmit={onPlan}>
        <label htmlFor="code-agent-instruction">수정 요청</label>
        <textarea
          id="code-agent-instruction"
          rows={4}
          value={instruction}
          onChange={(event) => setInstruction(event.target.value)}
          placeholder="JWT 만료 시 401 응답을 반환하도록 수정해줘"
        />
        <div className="action-row">
          <button disabled={!canPlan}>
            {loading('code-agent-plan') ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
            계획 생성
          </button>
          <button type="button" className="ghost-button" disabled={!canPatch} onClick={onPatch}>
            {loading('code-agent-patch') ? <Loader2 className="spin" size={16} /> : <FileCode2 size={16} />}
            diff 생성
          </button>
        </div>
      </form>
      {plan && (
        <div className="code-agent-result">
          <div className="result-heading">
            <strong>{plan.summary}</strong>
            <Badge variant={plan.riskLevel === 'high' ? 'destructive' : 'outline'}>{plan.riskLevel || 'medium'}</Badge>
          </div>
          <small>{plan.intent || 'unknown'}{plan.needsMoreContext ? ' · more context needed' : ''}</small>
          <div className="code-agent-targets">
            {targetFiles.map((file) => (
              <article className="evidence-card code-evidence" key={file.path}>
                <strong>{file.path}</strong>
                <p>{file.reason}</p>
              </article>
            ))}
          </div>
          {!!plan.changePlan?.length && (
            <ol className="code-agent-list">
              {plan.changePlan.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}
            </ol>
          )}
          <WarningList warnings={plan.warnings} />
        </div>
      )}
      {patch && (
        <div className="code-agent-result">
          <div className="result-heading">
            <strong>{patch.summary}</strong>
            <Badge variant={patch.valid ? 'outline' : 'destructive'}>{patch.valid ? 'valid' : 'invalid'}</Badge>
          </div>
          <WarningList warnings={patch.warnings} />
          {!!patch.testSuggestions?.length && (
            <div className="code-agent-tests">
              {patch.testSuggestions.map((test) => <Badge variant="secondary" key={test}>{test}</Badge>)}
            </div>
          )}
          {patch.files?.map((file) => (
            <article className="code-agent-diff" key={file.path}>
              <strong>{file.path}</strong>
              <pre><code>{file.diff}</code></pre>
            </article>
          ))}
          <div className="failure-list">
            <div className="failure-item">
              <strong>Local Agent required</strong>
              <span>Diff proposals are available here, but applying files, running tests, and rollback are reserved for the per-user Local Agent path. Server-local execution is prototype/admin-debug only.</span>
            </div>
          </div>
          <div className="action-row">
            <button type="button" className="ghost-button" disabled={!canPrepareLocalPatchRequest} onClick={onPrepareLocalPatchRequest}>
              {loading('code-agent-local-patch-request') ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              Prepare Local Agent review
            </button>
          </div>
          {localPatchRequest && (
            <div className="code-agent-result compact-result">
              <div className="result-heading">
                <strong>Prepared Local Agent request</strong>
                <Badge variant="secondary">{localPatchRequest.status || 'APPROVAL_REQUIRED'}</Badge>
              </div>
              <small>request: {localPatchRequest.requestId}</small>
              <small>tool: {localPatchRequest.toolName} / approval: {localPatchRequest.approvalState}</small>
              {localPatchRequest.status === 'APPROVED_HELD' && (
                <small>Approved for later execution. No files are modified until Local Agent patch execution is enabled.</small>
              )}
              {!!localPatchRequest.input?.targetFiles?.length && <small>targets: {localPatchRequest.input.targetFiles.join(', ')}</small>}
              {!!localPatchRequest.input?.expectedFiles?.length && (
                <small>expected hashes: {localPatchRequest.input.expectedFiles.map((file) => `${file.path}:${String(file.sha256 || '').slice(0, 12)}`).join(', ')}</small>
              )}
              {localPatchRequest.input?.sourceRepository && (
                <small>
                  source: {localPatchRequest.input.sourceRepository.name || localPatchRequest.input.sourceRepository.id}
                  {localPatchRequest.input.sourceRepository.branch ? ` / ${localPatchRequest.input.sourceRepository.branch}` : ''}
                  {localPatchRequest.input.sourceRepository.lastIndexedCommit ? ` / ${String(localPatchRequest.input.sourceRepository.lastIndexedCommit).slice(0, 12)}` : ''}
                </small>
              )}
              {localPatchRequest.input?.localWorkspace && (
                <small>
                  workspace: {localPatchRequest.input.localWorkspace.name || localPatchRequest.input.localWorkspace.workspaceId}
                  {localPatchRequest.input.localWorkspace.rootPath ? ` / ${localPatchRequest.input.localWorkspace.rootPath}` : ''}
                </small>
              )}
              {localPatchRequest.input?.workspaceVerification && (
                <small>
                  workspace verification: {localPatchRequest.input.workspaceVerification.status || 'UNKNOWN'}
                  {localPatchRequest.input.workspaceVerification.blocking ? ' / blocking release' : ''}
                  {localPatchRequest.input.workspaceVerification.reason ? ` / ${localPatchRequest.input.workspaceVerification.reason}` : ''}
                </small>
              )}
              <div className="action-row">
                <button
                  type="button"
                  className="ghost-button"
                  disabled={!canQueueRepositoryObservation}
                  onClick={onQueueLocalRepositoryObservation}
                >
                  {loading(`code-agent-local-repository-observation-${localObservationWorkspaceId}`) ? <Loader2 className="spin" size={16} /> : <GitBranch size={16} />}
                  Queue repository observation
                </button>
                {localRepositoryObservationRequest && (
                  <button
                    type="button"
                    className="ghost-button"
                    disabled={!canRefreshRepositoryObservation}
                    onClick={() => onRefreshLocalRepositoryObservationResult(localRepositoryObservationRequest.requestId)}
                  >
                    {loading(`code-agent-local-repository-observation-result-${localRepositoryObservationRequest.requestId}`) ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
                    Refresh repository observation
                  </button>
                )}
              </div>
              {localRepositoryObservationRequest && (
                <div className="failure-list">
                  <div className="failure-item">
                    <strong>Repository observation request: {localRepositoryObservationRequest.requestId}</strong>
                    <span>tool: {localRepositoryObservationRequest.request?.toolName} / read-only</span>
                  </div>
                  {visibleRepositoryObservation && (
                    <div className="failure-item">
                      <strong>Repository observation: {visibleRepositoryObservation.status}</strong>
                      {visibleRepositoryObservation.error && <span>{visibleRepositoryObservation.error}</span>}
                      {visibleRepositoryObservation.output?.repositoryIdentity && (
                        <span>
                          local: {visibleRepositoryObservation.output.repositoryIdentity.branch || visibleRepositoryObservation.output.branch || 'branch-unknown'}
                          {visibleRepositoryObservation.output.repositoryIdentity.headCommit ? ` / ${String(visibleRepositoryObservation.output.repositoryIdentity.headCommit).slice(0, 12)}` : ''}
                          {visibleRepositoryObservation.output.repositoryIdentity.remoteUrl ? ` / ${visibleRepositoryObservation.output.repositoryIdentity.remoteUrl}` : ''}
                        </span>
                      )}
                      {visibleRepositoryObservation.output?.identityComplete !== undefined && <span>identity complete: {String(visibleRepositoryObservation.output.identityComplete)}</span>}
                      {!!visibleRepositoryObservation.output?.identityWarnings?.length && <span>warnings: {visibleRepositoryObservation.output.identityWarnings.join(', ')}</span>}
                      <span>comparison: {repositoryObservationComparison.status} / {repositoryObservationComparison.message}</span>
                      {repositoryObservationComparison.checks
                        .filter((check) => check.status !== 'SKIPPED')
                        .map((check) => (
                          <span key={check.key}>
                            {check.key}: {check.status}
                            {check.expected ? ` / indexed ${String(check.expected).slice(0, 48)}` : ' / indexed unknown'}
                            {check.actual ? ` / local ${String(check.actual).slice(0, 48)}` : ' / local unknown'}
                          </span>
                        ))}
                    </div>
                  )}
                </div>
              )}
              {localPatchRequest.input?.staleIndexPolicy && <small>stale index: {localPatchRequest.input.staleIndexPolicy}</small>}
              {localPatchRequest.input?.requiresSnapshot && <small>snapshot required before file writes</small>}
              {localPatchRequest.input?.snapshotPolicy && (
                <small>
                  snapshot policy: {localPatchRequest.input.snapshotPolicy.scope || 'TARGET_FILES'}
                  {localPatchRequest.input.snapshotPolicy.location ? ` / ${localPatchRequest.input.snapshotPolicy.location}` : ''}
                  {localPatchRequest.input.snapshotPolicy.createBeforeMutation ? ' / before mutation' : ''}
                </small>
              )}
              {localPatchRequest.input?.rollbackPolicy && (
                <small>
                  rollback policy: {localPatchRequest.input.rollbackPolicy.tool || 'rollback.restore'}
                  {localPatchRequest.input.rollbackPolicy.restoreScope ? ` / ${localPatchRequest.input.rollbackPolicy.restoreScope}` : ''}
                  {localPatchRequest.input.rollbackPolicy.requiresUserApproval ? ' / approval required' : ''}
                </small>
              )}
              <WarningList warnings={localPatchRequest.requestWarnings} />
              <div className="action-row">
                <button
                  type="button"
                  className="ghost-button"
                  disabled={!canDecideLocalPatchRequest || loading('code-agent-local-patch-approval-approve')}
                  onClick={() => onLocalPatchApproval('APPROVE')}
                >
                  {loading('code-agent-local-patch-approval-approve') ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
                  Approve and hold
                </button>
                <button
                  type="button"
                  className="ghost-button"
                  disabled={!canDecideLocalPatchRequest || loading('code-agent-local-patch-approval-deny')}
                  onClick={() => onLocalPatchApproval('DENY')}
                >
                  {loading('code-agent-local-patch-approval-deny') ? <Loader2 className="spin" size={16} /> : <X size={16} />}
                  Deny request
                </button>
              </div>
              {localPatchRequest.status === 'APPROVED_HELD' && (
                <div className="action-row">
                  <button
                    type="button"
                    className="ghost-button"
                    disabled={!canRefreshReadiness}
                    onClick={() => onRefreshLocalPatchReadiness(localPatchRequest.requestId)}
                  >
                    {loading(`code-agent-local-patch-readiness-${localPatchRequest.requestId}`) ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
                    Check execution readiness
                  </button>
                  <button
                    type="button"
                    className="ghost-button"
                    disabled={!canQueueDryRun}
                    onClick={() => onQueueLocalPatchDryRun(localPatchRequest.requestId)}
                  >
                    {loading(`code-agent-local-patch-dry-run-${localPatchRequest.requestId}`) ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
                    Queue Local Agent dry-run
                  </button>
                  {readinessReleaseBoundaryVisibility && (
                    <button
                      type="button"
                      className="ghost-button"
                      disabled
                      title="Release is currently a refusal-only audit boundary. It does not create or push a Local Agent mutation request."
                    >
                      <Play size={16} />
                      Release Local Agent patch disabled
                    </button>
                  )}
                </div>
              )}
              {localPatchDryRunRequest && (
                <div className="failure-list">
                  <div className="failure-item">
                    <strong>Local dry-run request: {localPatchDryRunRequest.requestId}</strong>
                    <span>
                      queued as {localPatchDryRunRequest.request?.toolName}
                      {localPatchDryRunRequest.request?.input?.sourceRequestId ? ` from ${localPatchDryRunRequest.request.input.sourceRequestId}` : ''}
                    </span>
                    <span>No file write is released; this request asks the Local Agent for preflight observations only.</span>
                    <button
                      type="button"
                      className="ghost-button compact-action"
                      disabled={!canRefreshDryRun}
                      onClick={() => onRefreshLocalPatchDryRunResult(localPatchDryRunRequest.requestId)}
                    >
                      {loading(`code-agent-local-patch-dry-run-result-${localPatchDryRunRequest.requestId}`) ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
                      Refresh dry-run result
                    </button>
                  </div>
                  {visibleDryRun && (
                    <div className="failure-item">
                      <strong>{expectedDryRunRefusal ? 'Dry-run completed; mutation refused as expected' : `Dry-run status: ${visibleDryRun.status}`}</strong>
                      {visibleDryRun.error && <span>{visibleDryRun.error}</span>}
                      {visibleDryRun.failureCode && <span>{expectedDryRunRefusal ? 'safety gate' : 'failure'}: {visibleDryRun.failureCode}</span>}
                      {visibleDryRun.output?.preflightPassed !== undefined && <span>preflight passed: {String(visibleDryRun.output.preflightPassed)}</span>}
                      {visibleDryRun.output?.mutationApplied !== undefined && <span>mutation applied: {String(visibleDryRun.output.mutationApplied)}</span>}
                      {visibleDryRun.output?.snapshotCreated !== undefined && <span>snapshot created: {String(visibleDryRun.output.snapshotCreated)}</span>}
                      {dryRunSnapshotObservation && (
                        <span>
                          snapshot would create: {String(dryRunSnapshotObservation.wouldCreate)}
                          {dryRunSnapshotObservation.created !== undefined ? ` / created: ${String(dryRunSnapshotObservation.created)}` : ''}
                          {dryRunSnapshotObservation.scope ? ` / ${dryRunSnapshotObservation.scope}` : ''}
                          {dryRunSnapshotObservation.location ? ` / ${dryRunSnapshotObservation.location}` : ''}
                        </span>
                      )}
                      {dryRunSnapshotObservation?.manifestPreview && (
                        <span>
                          snapshot manifest: {dryRunSnapshotObservation.manifestPreview.id || '(preview)'}
                          {dryRunSnapshotObservation.manifestPreview.relativeManifestPath ? ` / ${dryRunSnapshotObservation.manifestPreview.relativeManifestPath}` : ''}
                          {dryRunSnapshotObservation.manifestPreview.created !== undefined ? ` / manifest created: ${String(dryRunSnapshotObservation.manifestPreview.created)}` : ''}
                          {dryRunSnapshotObservation.manifestPreview.writesPlanned !== undefined ? ` / writes planned: ${String(dryRunSnapshotObservation.manifestPreview.writesPlanned)}` : ''}
                          {dryRunSnapshotObservation.manifestPreview.writesCompleted !== undefined ? ` / writes completed: ${String(dryRunSnapshotObservation.manifestPreview.writesCompleted)}` : ''}
                        </span>
                      )}
                      {dryRunRollbackObservation && (
                        <span>
                          rollback would restore: {String(dryRunRollbackObservation.wouldRestore)}
                          {dryRunRollbackObservation.restored !== undefined ? ` / restored: ${String(dryRunRollbackObservation.restored)}` : ''}
                          {dryRunRollbackObservation.tool ? ` / ${dryRunRollbackObservation.tool}` : ''}
                          {dryRunRollbackObservation.restoreScope ? ` / ${dryRunRollbackObservation.restoreScope}` : ''}
                        </span>
                      )}
                      {!!dryRunSnapshotObservation?.files?.length && (
                        <span>snapshot files: {summarizeDryRunObservationFiles(dryRunSnapshotObservation.files)}</span>
                      )}
                      {!!visibleDryRun.output?.files?.length && (
                        <span>
                          files: {visibleDryRun.output.files.map((file) => `${file.path}:${file.contextMatches ? 'context-ok' : 'context-blocked'}`).join(', ')}
                        </span>
                      )}
                      <WarningList warnings={visibleDryRun.responseWarnings} />
                    </div>
                  )}
                </div>
              )}
              {visibleReadiness && (
                <div className="failure-list">
                  <div className="failure-item">
                    <strong>Execution readiness: {visibleReadiness.readyToRelease ? 'ready' : 'blocked'}</strong>
                    <span>{visibleReadiness.message}</span>
                  </div>
                  {readinessRepositoryVerification && (
                    <div className="failure-item">
                      <strong>Recorded repository verification: {readinessRepositoryVerification.status || 'UNVERIFIED'}</strong>
                      {readinessRepositoryVerification.message && <span>{readinessRepositoryVerification.message}</span>}
                      {formatObservationLinkage(readinessRepositoryVerification) && <span>{formatObservationLinkage(readinessRepositoryVerification)}</span>}
                      {(readinessRepositoryVerification.checks || [])
                        .filter((check) => check.status !== 'SKIPPED')
                        .map((check) => (
                          <span key={check.key}>
                            {check.key}: {check.status}
                            {check.expected ? ` / indexed ${String(check.expected).slice(0, 48)}` : ' / indexed unknown'}
                            {check.actual ? ` / local ${String(check.actual).slice(0, 48)}` : ' / local unknown'}
                          </span>
                        ))}
                    </div>
                  )}
                  {readinessWorkspaceVerification && (
                    <div className="failure-item">
                      <strong>Effective workspace verification: {readinessWorkspaceVerification.status || 'UNVERIFIED'}</strong>
                      {readinessWorkspaceVerification.blocking !== undefined && <span>blocking release: {String(readinessWorkspaceVerification.blocking)}</span>}
                      {readinessWorkspaceVerification.reason && <span>{readinessWorkspaceVerification.reason}</span>}
                      {readinessWorkspaceVerification.source && <span>source: {readinessWorkspaceVerification.source}</span>}
                    </div>
                  )}
                  {readinessPatchRelease && (
                    <div className="failure-item">
                      <strong>Pre-apply release checklist: {readinessPatchRelease.status || 'UNKNOWN'}</strong>
                      {readinessPatchRelease.message && <span>{readinessPatchRelease.message}</span>}
                      <span>
                        preconditions passed: {String(readinessPatchRelease.preconditionsPassed)}
                        {readinessPatchRelease.releaseGateEnabled !== undefined ? ` / release gate: ${String(readinessPatchRelease.releaseGateEnabled)}` : ''}
                        {readinessPatchRelease.mutationEnabled !== undefined ? ` / mutation enabled: ${String(readinessPatchRelease.mutationEnabled)}` : ''}
                      </span>
                      {(readinessPatchRelease.prerequisites || []).map((item) => (
                        <span key={item.key}>
                          {item.passed ? 'pass' : 'blocked'} / {item.key}: {item.message}
                        </span>
                      ))}
                    </div>
                  )}
                  {readinessPatchExecutionGate && (
                    <div className="failure-item">
                      <strong>Internal patch execution gate: {readinessPatchExecutionGate.status || 'UNKNOWN'}</strong>
                      {readinessPatchExecutionGate.message && <span>{readinessPatchExecutionGate.message}</span>}
                      <span>
                        claim enabled: {String(readinessPatchExecutionGate.claimEnabled)}
                        {readinessPatchExecutionGate.writeHelperEnabled !== undefined ? ` / write helper: ${String(readinessPatchExecutionGate.writeHelperEnabled)}` : ''}
                        {readinessPatchExecutionGate.releaseGateEnabled !== undefined ? ` / release gate: ${String(readinessPatchExecutionGate.releaseGateEnabled)}` : ''}
                        {readinessPatchExecutionGate.sourceRequestRelationship ? ` / ${readinessPatchExecutionGate.sourceRequestRelationship}` : ''}
                      </span>
                      {readinessPatchExecutionGate.preReleaseRevalidation && (
                        <span>
                          pre-release revalidation: {readinessPatchExecutionGate.preReleaseRevalidation.status || 'UNKNOWN'}
                          {readinessPatchExecutionGate.preReleaseRevalidation.passed !== undefined ? ` / passed: ${String(readinessPatchExecutionGate.preReleaseRevalidation.passed)}` : ''}
                          {readinessPatchExecutionGate.preReleaseRevalidation.requiresFreshDryRunAfterReleaseAttempt !== undefined ? ` / fresh dry-run: ${String(readinessPatchExecutionGate.preReleaseRevalidation.requiresFreshDryRunAfterReleaseAttempt)}` : ''}
                          {readinessPatchExecutionGate.preReleaseRevalidation.requiresFreshRepositoryVerificationAfterReleaseAttempt !== undefined ? ` / fresh repo check: ${String(readinessPatchExecutionGate.preReleaseRevalidation.requiresFreshRepositoryVerificationAfterReleaseAttempt)}` : ''}
                        </span>
                      )}
                      {readinessReleaseAttemptModel && (
                        <span>
                          release attempt model: {readinessReleaseAttemptModel.status || 'UNKNOWN'}
                          {readinessReleaseAttemptModel.schema ? ` / ${readinessReleaseAttemptModel.schema}` : ''}
                          {readinessReleaseAttemptModel.staleWindowSeconds !== undefined ? ` / stale window ${readinessReleaseAttemptModel.staleWindowSeconds}s` : ''}
                          {readinessReleaseAttemptModel.requiredEvidence?.length ? ` / evidence ${readinessReleaseAttemptModel.requiredEvidence.length}` : ''}
                        </span>
                      )}
                      {!!readinessFreshObservationRequestPlan.length && (
                        <>
                          <span>fresh observation request plan: audit-only / no enqueue / no claim</span>
                          {readinessFreshObservationRequestPlan.map((item) => (
                            <span key={`${item.key}-${item.releaseAttemptId || item.toolName}`}>
                              {item.key}: {item.status || 'PLANNED_DISABLED'}
                              {item.toolName ? ` / ${item.toolName}` : ''}
                              {item.approvalState ? ` / approval ${item.approvalState}` : ''}
                              {item.enqueueEnabled !== undefined ? ` / enqueue ${String(item.enqueueEnabled)}` : ''}
                              {item.claimableAfterEnqueue !== undefined ? ` / claimable ${String(item.claimableAfterEnqueue)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.dryRunOnly !== undefined ? ` / dry-run ${String(item.dryRunOnly)}` : ''}
                              {item.releaseAttemptId ? ` / attempt ${String(item.releaseAttemptId).slice(0, 8)}` : ''}
                              {item.sourceRequestId ? ` / source ${String(item.sourceRequestId).slice(0, 8)}` : ''}
                            </span>
                          ))}
                        </>
                      )}
                      {!!readinessFreshObservationEvidenceStatus.length && (
                        <>
                          <span>fresh observation evidence status: audit-only / no request creation / no push</span>
                          {readinessFreshObservationEvidenceStatus.map((item) => (
                            <span key={`evidence-${item.key}-${item.releaseAttemptId || item.status}`}>
                              {item.key}: {item.status || 'UNKNOWN'}
                              {item.linked !== undefined ? ` / linked ${String(item.linked)}` : ''}
                              {item.sourceOnlyFallback !== undefined ? ` / fallback ${String(item.sourceOnlyFallback)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.releaseAttemptId ? ` / attempt ${String(item.releaseAttemptId).slice(0, 8)}` : ''}
                            </span>
                          ))}
                        </>
                      )}
                      {readinessFreshObservationEvidenceCompleteness && (
                        <>
                          <span>
                            fresh observation evidence completeness: {readinessFreshObservationEvidenceCompleteness.status || 'UNKNOWN'}
                            {readinessFreshObservationEvidenceCompleteness.complete !== undefined ? ` / complete ${String(readinessFreshObservationEvidenceCompleteness.complete)}` : ''}
                            {readinessFreshObservationEvidenceCompleteness.linkedCount !== undefined ? ` / linked ${readinessFreshObservationEvidenceCompleteness.linkedCount}` : ''}
                            {readinessFreshObservationEvidenceCompleteness.missingCount !== undefined ? ` / missing ${readinessFreshObservationEvidenceCompleteness.missingCount}` : ''}
                            {readinessFreshObservationEvidenceCompleteness.sourceOnlyFallbackCount !== undefined ? ` / fallback ${readinessFreshObservationEvidenceCompleteness.sourceOnlyFallbackCount}` : ''}
                            {readinessFreshObservationEvidenceCompleteness.blockingCount !== undefined ? ` / blocking ${readinessFreshObservationEvidenceCompleteness.blockingCount}` : ''}
                          </span>
                          <span>
                            release gate: {String(readinessFreshObservationEvidenceCompleteness.releaseGateEnabled)}
                            {readinessFreshObservationEvidenceCompleteness.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessFreshObservationEvidenceCompleteness.requestCreationEnabled)}` : ''}
                            {readinessFreshObservationEvidenceCompleteness.pushEnabled !== undefined ? ` / push ${String(readinessFreshObservationEvidenceCompleteness.pushEnabled)}` : ''}
                            {readinessFreshObservationEvidenceCompleteness.claimable !== undefined ? ` / claimable ${String(readinessFreshObservationEvidenceCompleteness.claimable)}` : ''}
                            {readinessFreshObservationEvidenceCompleteness.mutationAllowed !== undefined ? ` / mutation ${String(readinessFreshObservationEvidenceCompleteness.mutationAllowed)}` : ''}
                          </span>
                          {!!readinessFreshObservationEvidenceCompleteness.blockingKeys?.length && (
                            <span>blocking evidence: {readinessFreshObservationEvidenceCompleteness.blockingKeys.join(', ')}</span>
                          )}
                          {!!readinessFreshObservationEvidenceCompleteness.missingKeys?.length && (
                            <span>missing evidence: {readinessFreshObservationEvidenceCompleteness.missingKeys.join(', ')}</span>
                          )}
                          {!!readinessFreshObservationEvidenceCompleteness.sourceOnlyFallbackKeys?.length && (
                            <span>fallback-only evidence: {readinessFreshObservationEvidenceCompleteness.sourceOnlyFallbackKeys.join(', ')}</span>
                          )}
                          {readinessFreshObservationEvidenceCompleteness.message && (
                            <span>{readinessFreshObservationEvidenceCompleteness.message}</span>
                          )}
                        </>
                      )}
                      {readinessReleaseAttemptFinalReadiness && (
                        <>
                          <span>
                            release attempt final readiness: {readinessReleaseAttemptFinalReadiness.status || 'UNKNOWN'}
                            {readinessReleaseAttemptFinalReadiness.ready !== undefined ? ` / ready ${String(readinessReleaseAttemptFinalReadiness.ready)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.freshnessStatus ? ` / freshness ${readinessReleaseAttemptFinalReadiness.freshnessStatus}` : ''}
                            {readinessReleaseAttemptFinalReadiness.stale !== undefined ? ` / stale ${String(readinessReleaseAttemptFinalReadiness.stale)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.evidenceComplete !== undefined ? ` / evidence complete ${String(readinessReleaseAttemptFinalReadiness.evidenceComplete)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.patchPreconditionsPassed !== undefined ? ` / patch preconditions ${String(readinessReleaseAttemptFinalReadiness.patchPreconditionsPassed)}` : ''}
                          </span>
                          <span>
                            final release gate: {String(readinessReleaseAttemptFinalReadiness.releaseGateEnabled)}
                            {readinessReleaseAttemptFinalReadiness.claimEnabled !== undefined ? ` / claim ${String(readinessReleaseAttemptFinalReadiness.claimEnabled)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessReleaseAttemptFinalReadiness.writeHelperEnabled)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessReleaseAttemptFinalReadiness.requestCreationEnabled)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.pushEnabled !== undefined ? ` / push ${String(readinessReleaseAttemptFinalReadiness.pushEnabled)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.claimable !== undefined ? ` / claimable ${String(readinessReleaseAttemptFinalReadiness.claimable)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.mutationAllowed !== undefined ? ` / mutation ${String(readinessReleaseAttemptFinalReadiness.mutationAllowed)}` : ''}
                          </span>
                          <span>
                            execution disabled:
                            {readinessReleaseAttemptFinalReadiness.applyEnabled !== undefined ? ` apply ${String(readinessReleaseAttemptFinalReadiness.applyEnabled)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.testEnabled !== undefined ? ` / test ${String(readinessReleaseAttemptFinalReadiness.testEnabled)}` : ''}
                            {readinessReleaseAttemptFinalReadiness.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessReleaseAttemptFinalReadiness.rollbackRestoreEnabled)}` : ''}
                          </span>
                          {!!readinessReleaseAttemptFinalReadiness.blockingReasons?.length && (
                            <span>final blocking reasons: {readinessReleaseAttemptFinalReadiness.blockingReasons.join(', ')}</span>
                          )}
                          {readinessReleaseAttemptFinalReadiness.message && (
                            <span>{readinessReleaseAttemptFinalReadiness.message}</span>
                          )}
                        </>
                      )}
                      {!!readinessLocalAgentMutationExecutionSequencePlan.length && (
                        <>
                          <span>local agent mutation execution sequence: audit-only / no request creation / no push</span>
                          {readinessLocalAgentMutationExecutionSequencePlan.map((item) => (
                            <span key={`mutation-sequence-${item.order || item.key}-${item.releaseAttemptId || item.toolName}`}>
                              {item.order !== undefined ? `${item.order}. ` : ''}{item.key}: {item.status || 'PLANNED_DISABLED'}
                              {item.toolName ? ` / ${item.toolName}` : ''}
                              {item.approvalState ? ` / approval ${item.approvalState}` : ''}
                              {item.executionTarget ? ` / ${item.executionTarget}` : ''}
                              {item.sideEffectful !== undefined ? ` / side-effect ${String(item.sideEffectful)}` : ''}
                              {item.rollbackFallback !== undefined ? ` / rollback fallback ${String(item.rollbackFallback)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimableAfterRelease !== undefined ? ` / claim after release ${String(item.claimableAfterRelease)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.applyEnabled !== undefined ? ` / apply ${String(item.applyEnabled)}` : ''}
                              {item.testEnabled !== undefined ? ` / test ${String(item.testEnabled)}` : ''}
                              {item.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(item.rollbackRestoreEnabled)}` : ''}
                            </span>
                          ))}
                        </>
                      )}
                      {readinessPostMutationResultContract && (
                        <>
                          <span>
                            post-mutation result contract: {readinessPostMutationResultContract.status || 'CONTRACT_DISABLED'}
                            {readinessPostMutationResultContract.schema ? ` / ${readinessPostMutationResultContract.schema}` : ''}
                            {readinessPostMutationResultContract.executionTarget ? ` / ${readinessPostMutationResultContract.executionTarget}` : ''}
                            {readinessPostMutationResultContract.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessPostMutationResultContract.releaseGateEnabled)}` : ''}
                            {readinessPostMutationResultContract.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessPostMutationResultContract.requestCreationEnabled)}` : ''}
                            {readinessPostMutationResultContract.pushEnabled !== undefined ? ` / push ${String(readinessPostMutationResultContract.pushEnabled)}` : ''}
                            {readinessPostMutationResultContract.claimable !== undefined ? ` / claimable ${String(readinessPostMutationResultContract.claimable)}` : ''}
                            {readinessPostMutationResultContract.mutationAllowed !== undefined ? ` / mutation ${String(readinessPostMutationResultContract.mutationAllowed)}` : ''}
                            {readinessPostMutationResultContract.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessPostMutationResultContract.ragFreshnessUpdateEnabled)}` : ''}
                          </span>
                          {readinessPostMutationExpectedOutcomes.map((item) => (
                            <span key={`post-mutation-${item.key}-${item.toolName || item.status}`}>
                              {item.key}: {item.status || 'EXPECTED_DISABLED'}
                              {item.toolName ? ` / ${item.toolName}` : ''}
                              {item.sideEffectful !== undefined ? ` / side-effect ${String(item.sideEffectful)}` : ''}
                              {item.rollbackFallback !== undefined ? ` / rollback fallback ${String(item.rollbackFallback)}` : ''}
                              {item.requiredForSuccess !== undefined ? ` / required for success ${String(item.requiredForSuccess)}` : ''}
                              {item.resultRequired !== undefined ? ` / result required ${String(item.resultRequired)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.applyEnabled !== undefined ? ` / apply ${String(item.applyEnabled)}` : ''}
                              {item.testEnabled !== undefined ? ` / test ${String(item.testEnabled)}` : ''}
                              {item.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(item.rollbackRestoreEnabled)}` : ''}
                              {item.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(item.ragFreshnessUpdateEnabled)}` : ''}
                            </span>
                          ))}
                          {readinessPostMutationResultContract.message && (
                            <span>{readinessPostMutationResultContract.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationResultIntakeBoundary && (
                        <>
                          <span>
                            mutation result intake boundary: {readinessMutationResultIntakeBoundary.status || 'BLOCKED_INTAKE_DISABLED'}
                            {readinessMutationResultIntakeBoundary.schema ? ` / ${readinessMutationResultIntakeBoundary.schema}` : ''}
                            {readinessMutationResultIntakeBoundary.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationResultIntakeBoundary.prerequisitesPassed)}` : ''}
                            {readinessMutationResultIntakeBoundary.executionTarget ? ` / ${readinessMutationResultIntakeBoundary.executionTarget}` : ''}
                            {readinessMutationResultIntakeBoundary.postMutationResultSchema ? ` / source ${readinessMutationResultIntakeBoundary.postMutationResultSchema}` : ''}
                          </span>
                          <span>
                            mutation result intake disabled:
                            {readinessMutationResultIntakeBoundary.releaseGateEnabled !== undefined ? ` release gate ${String(readinessMutationResultIntakeBoundary.releaseGateEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationResultIntakeBoundary.requestCreationEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.pushEnabled !== undefined ? ` / push ${String(readinessMutationResultIntakeBoundary.pushEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.claimEnabled !== undefined ? ` / claim ${String(readinessMutationResultIntakeBoundary.claimEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationResultIntakeBoundary.writeHelperEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.claimable !== undefined ? ` / claimable ${String(readinessMutationResultIntakeBoundary.claimable)}` : ''}
                            {readinessMutationResultIntakeBoundary.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationResultIntakeBoundary.mutationAllowed)}` : ''}
                            {readinessMutationResultIntakeBoundary.applyEnabled !== undefined ? ` / apply ${String(readinessMutationResultIntakeBoundary.applyEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.testEnabled !== undefined ? ` / test ${String(readinessMutationResultIntakeBoundary.testEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationResultIntakeBoundary.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationResultIntakeBoundary.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationResultIntakeBoundary.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationResultIntakeBoundary.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationResultIntakeBoundary.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {!!readinessMutationResultIntakeBoundary.requiredOutcomeKeys?.length && (
                            <span>mutation result required outcomes: {readinessMutationResultIntakeBoundary.requiredOutcomeKeys.join(', ')}</span>
                          )}
                          {!!readinessMutationResultIntakeAcceptedStatuses.length && (
                            <span>mutation result accepted terminal statuses: {readinessMutationResultIntakeAcceptedStatuses.join(', ')}</span>
                          )}
                          {readinessMutationResultIntakeRequirements.map((item) => (
                            <span key={`mutation-result-intake-${item.key}-${item.status || item.passed}`}>
                              {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.releaseGateEnabled !== undefined ? ` / release gate ${String(item.releaseGateEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(item.mutationResultAggregationEnabled)}` : ''}
                              {item.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(item.finalAnswerGenerationEnabled)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationResultIntakeBoundary.blockingKeys?.length && (
                            <span>mutation result intake blocking keys: {readinessMutationResultIntakeBoundary.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationResultIntakeBoundary.message && (
                            <span>{readinessMutationResultIntakeBoundary.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationResultAggregationPlan && (
                        <>
                          <span>
                            mutation result aggregation plan: {readinessMutationResultAggregationPlan.status || 'BLOCKED_AGGREGATION_DISABLED'}
                            {readinessMutationResultAggregationPlan.schema ? ` / ${readinessMutationResultAggregationPlan.schema}` : ''}
                            {readinessMutationResultAggregationPlan.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationResultAggregationPlan.prerequisitesPassed)}` : ''}
                            {readinessMutationResultAggregationPlan.executionTarget ? ` / ${readinessMutationResultAggregationPlan.executionTarget}` : ''}
                            {readinessMutationResultAggregationPlan.postMutationResultSchema ? ` / source ${readinessMutationResultAggregationPlan.postMutationResultSchema}` : ''}
                            {readinessMutationResultAggregationPlan.finalMutationReportSchema ? ` / target ${readinessMutationResultAggregationPlan.finalMutationReportSchema}` : ''}
                          </span>
                          <span>
                            mutation result aggregation disabled:
                            {readinessMutationResultAggregationPlan.releaseGateEnabled !== undefined ? ` release gate ${String(readinessMutationResultAggregationPlan.releaseGateEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationResultAggregationPlan.requestCreationEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.pushEnabled !== undefined ? ` / push ${String(readinessMutationResultAggregationPlan.pushEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.claimEnabled !== undefined ? ` / claim ${String(readinessMutationResultAggregationPlan.claimEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationResultAggregationPlan.writeHelperEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.claimable !== undefined ? ` / claimable ${String(readinessMutationResultAggregationPlan.claimable)}` : ''}
                            {readinessMutationResultAggregationPlan.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationResultAggregationPlan.mutationAllowed)}` : ''}
                            {readinessMutationResultAggregationPlan.applyEnabled !== undefined ? ` / apply ${String(readinessMutationResultAggregationPlan.applyEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.testEnabled !== undefined ? ` / test ${String(readinessMutationResultAggregationPlan.testEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationResultAggregationPlan.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationResultAggregationPlan.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationResultAggregationPlan.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationResultAggregationPlan.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationResultAggregationPlan.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {!!readinessMutationResultAggregationPlan.sourceOutcomeKeys?.length && (
                            <span>aggregation source outcomes: {readinessMutationResultAggregationPlan.sourceOutcomeKeys.join(', ')}</span>
                          )}
                          {!!readinessMutationResultAggregationPlan.targetReportSections?.length && (
                            <span>aggregation target sections: {readinessMutationResultAggregationPlan.targetReportSections.join(', ')}</span>
                          )}
                          {readinessMutationResultAggregationSteps.map((item) => (
                            <span key={`mutation-result-aggregation-${item.order}-${item.targetSectionKey}`}>
                              {item.order}. {item.targetSectionKey}: {item.status || 'PLANNED_DISABLED'}
                              {item.sourceOutcomeKey ? ` / source ${item.sourceOutcomeKey}` : ''}
                              {item.required !== undefined ? ` / required ${String(item.required)}` : ''}
                              {item.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(item.mutationResultAggregationEnabled)}` : ''}
                              {item.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(item.finalAnswerGenerationEnabled)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationResultAggregationPlan.blockingKeys?.length && (
                            <span>mutation result aggregation blocking keys: {readinessMutationResultAggregationPlan.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationResultAggregationPlan.message && (
                            <span>{readinessMutationResultAggregationPlan.message}</span>
                          )}
                        </>
                      )}
                      {readinessFinalMutationReportContract && (
                        <>
                          <span>
                            final mutation report contract: {readinessFinalMutationReportContract.status || 'CONTRACT_DISABLED'}
                            {readinessFinalMutationReportContract.schema ? ` / ${readinessFinalMutationReportContract.schema}` : ''}
                            {readinessFinalMutationReportContract.executionTarget ? ` / ${readinessFinalMutationReportContract.executionTarget}` : ''}
                            {readinessFinalMutationReportContract.postMutationResultSchema ? ` / source ${readinessFinalMutationReportContract.postMutationResultSchema}` : ''}
                            {readinessFinalMutationReportContract.rollbackReadinessStatus ? ` / rollback ${readinessFinalMutationReportContract.rollbackReadinessStatus}` : ''}
                          </span>
                          <span>
                            final report disabled:
                            {readinessFinalMutationReportContract.releaseGateEnabled !== undefined ? ` release gate ${String(readinessFinalMutationReportContract.releaseGateEnabled)}` : ''}
                            {readinessFinalMutationReportContract.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessFinalMutationReportContract.requestCreationEnabled)}` : ''}
                            {readinessFinalMutationReportContract.pushEnabled !== undefined ? ` / push ${String(readinessFinalMutationReportContract.pushEnabled)}` : ''}
                            {readinessFinalMutationReportContract.claimable !== undefined ? ` / claimable ${String(readinessFinalMutationReportContract.claimable)}` : ''}
                            {readinessFinalMutationReportContract.mutationAllowed !== undefined ? ` / mutation ${String(readinessFinalMutationReportContract.mutationAllowed)}` : ''}
                            {readinessFinalMutationReportContract.applyEnabled !== undefined ? ` / apply ${String(readinessFinalMutationReportContract.applyEnabled)}` : ''}
                            {readinessFinalMutationReportContract.testEnabled !== undefined ? ` / test ${String(readinessFinalMutationReportContract.testEnabled)}` : ''}
                            {readinessFinalMutationReportContract.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessFinalMutationReportContract.rollbackRestoreEnabled)}` : ''}
                            {readinessFinalMutationReportContract.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessFinalMutationReportContract.ragFreshnessUpdateEnabled)}` : ''}
                          </span>
                          {!!readinessFinalMutationReportContract.expectedOutcomeKeys?.length && (
                            <span>final report source outcomes: {readinessFinalMutationReportContract.expectedOutcomeKeys.join(', ')}</span>
                          )}
                          {readinessFinalMutationReportSections.map((item) => (
                            <span key={`final-report-${item.key}-${item.sourceOutcomeKey || item.status}`}>
                              {item.key}: {item.status || 'REQUIRED_DISABLED'}
                              {item.sourceOutcomeKey ? ` / source ${item.sourceOutcomeKey}` : ''}
                              {item.required !== undefined ? ` / required ${String(item.required)}` : ''}
                              {item.resultRequired !== undefined ? ` / result required ${String(item.resultRequired)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {readinessFinalMutationReportGuardrails.map((item, index) => (
                            <span key={`final-report-guardrail-${index}`}>final report guardrail: {item}</span>
                          ))}
                          {readinessFinalMutationReportContract.message && (
                            <span>{readinessFinalMutationReportContract.message}</span>
                          )}
                        </>
                      )}
                      {readinessFinalMutationReportFinalizationBoundary && (
                        <>
                          <span>
                            final mutation report finalization boundary: {readinessFinalMutationReportFinalizationBoundary.status || 'BLOCKED_FINALIZATION_DISABLED'}
                            {readinessFinalMutationReportFinalizationBoundary.schema ? ` / ${readinessFinalMutationReportFinalizationBoundary.schema}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessFinalMutationReportFinalizationBoundary.prerequisitesPassed)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.executionTarget ? ` / ${readinessFinalMutationReportFinalizationBoundary.executionTarget}` : ''}
                          </span>
                          <span>
                            final answer generation disabled:
                            {readinessFinalMutationReportFinalizationBoundary.releaseGateEnabled !== undefined ? ` release gate ${String(readinessFinalMutationReportFinalizationBoundary.releaseGateEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessFinalMutationReportFinalizationBoundary.requestCreationEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.pushEnabled !== undefined ? ` / push ${String(readinessFinalMutationReportFinalizationBoundary.pushEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.claimEnabled !== undefined ? ` / claim ${String(readinessFinalMutationReportFinalizationBoundary.claimEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessFinalMutationReportFinalizationBoundary.writeHelperEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.claimable !== undefined ? ` / claimable ${String(readinessFinalMutationReportFinalizationBoundary.claimable)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.mutationAllowed !== undefined ? ` / mutation ${String(readinessFinalMutationReportFinalizationBoundary.mutationAllowed)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.applyEnabled !== undefined ? ` / apply ${String(readinessFinalMutationReportFinalizationBoundary.applyEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.testEnabled !== undefined ? ` / test ${String(readinessFinalMutationReportFinalizationBoundary.testEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessFinalMutationReportFinalizationBoundary.rollbackRestoreEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessFinalMutationReportFinalizationBoundary.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessFinalMutationReportFinalizationBoundary.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessFinalMutationReportFinalizationBoundary.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {readinessFinalMutationReportFinalizationRequirements.map((item) => (
                            <span key={`final-report-finalization-${item.key}-${item.status || item.passed}`}>
                              {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.releaseGateEnabled !== undefined ? ` / release gate ${String(item.releaseGateEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(item.finalAnswerGenerationEnabled)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessFinalMutationReportFinalizationBoundary.blockingKeys?.length && (
                            <span>final report finalization blocking keys: {readinessFinalMutationReportFinalizationBoundary.blockingKeys.join(', ')}</span>
                          )}
                          {readinessFinalMutationReportFinalizationBoundary.message && (
                            <span>{readinessFinalMutationReportFinalizationBoundary.message}</span>
                          )}
                        </>
                      )}
                      {readinessFinalAnswerPublicationBoundary && (
                        <>
                          <span>
                            final answer publication boundary: {readinessFinalAnswerPublicationBoundary.status || 'BLOCKED_PUBLICATION_DISABLED'}
                            {readinessFinalAnswerPublicationBoundary.schema ? ` / ${readinessFinalAnswerPublicationBoundary.schema}` : ''}
                            {readinessFinalAnswerPublicationBoundary.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessFinalAnswerPublicationBoundary.prerequisitesPassed)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.executionTarget ? ` / ${readinessFinalAnswerPublicationBoundary.executionTarget}` : ''}
                            {readinessFinalAnswerPublicationBoundary.finalMutationReportSchema ? ` / report ${readinessFinalAnswerPublicationBoundary.finalMutationReportSchema}` : ''}
                            {readinessFinalAnswerPublicationBoundary.aggregationPlanSchema ? ` / aggregation ${readinessFinalAnswerPublicationBoundary.aggregationPlanSchema}` : ''}
                          </span>
                          <span>
                            final answer publication disabled:
                            {readinessFinalAnswerPublicationBoundary.releaseGateEnabled !== undefined ? ` release gate ${String(readinessFinalAnswerPublicationBoundary.releaseGateEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessFinalAnswerPublicationBoundary.requestCreationEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.pushEnabled !== undefined ? ` / push ${String(readinessFinalAnswerPublicationBoundary.pushEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.claimEnabled !== undefined ? ` / claim ${String(readinessFinalAnswerPublicationBoundary.claimEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessFinalAnswerPublicationBoundary.writeHelperEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.claimable !== undefined ? ` / claimable ${String(readinessFinalAnswerPublicationBoundary.claimable)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.mutationAllowed !== undefined ? ` / mutation ${String(readinessFinalAnswerPublicationBoundary.mutationAllowed)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.applyEnabled !== undefined ? ` / apply ${String(readinessFinalAnswerPublicationBoundary.applyEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.testEnabled !== undefined ? ` / test ${String(readinessFinalAnswerPublicationBoundary.testEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessFinalAnswerPublicationBoundary.rollbackRestoreEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessFinalAnswerPublicationBoundary.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessFinalAnswerPublicationBoundary.mutationResultAggregationEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.publicationEnabled !== undefined ? ` / publication ${String(readinessFinalAnswerPublicationBoundary.publicationEnabled)}` : ''}
                            {readinessFinalAnswerPublicationBoundary.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessFinalAnswerPublicationBoundary.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {!!readinessFinalAnswerPublicationBoundary.requiredReportSections?.length && (
                            <span>publication required report sections: {readinessFinalAnswerPublicationBoundary.requiredReportSections.join(', ')}</span>
                          )}
                          {readinessFinalAnswerPublicationGuardrails.map((item, index) => (
                            <span key={`final-answer-publication-guardrail-${index}`}>publication guardrail: {item}</span>
                          ))}
                          {readinessFinalAnswerPublicationRequirements.map((item) => (
                            <span key={`final-answer-publication-${item.key}-${item.status || item.passed}`}>
                              {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.releaseGateEnabled !== undefined ? ` / release gate ${String(item.releaseGateEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(item.mutationResultAggregationEnabled)}` : ''}
                              {item.publicationEnabled !== undefined ? ` / publication ${String(item.publicationEnabled)}` : ''}
                              {item.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(item.finalAnswerGenerationEnabled)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessFinalAnswerPublicationBoundary.blockingKeys?.length && (
                            <span>final answer publication blocking keys: {readinessFinalAnswerPublicationBoundary.blockingKeys.join(', ')}</span>
                          )}
                          {readinessFinalAnswerPublicationBoundary.message && (
                            <span>{readinessFinalAnswerPublicationBoundary.message}</span>
                          )}
                        </>
                      )}
                      {readinessReleaseEnablementChecklist && (
                        <>
                          <span>
                            release enablement checklist: {readinessReleaseEnablementChecklist.status || 'BLOCKED_ENABLEMENT_DISABLED'}
                            {readinessReleaseEnablementChecklist.schema ? ` / ${readinessReleaseEnablementChecklist.schema}` : ''}
                            {readinessReleaseEnablementChecklist.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessReleaseEnablementChecklist.prerequisitesPassed)}` : ''}
                            {readinessReleaseEnablementChecklist.executionTarget ? ` / ${readinessReleaseEnablementChecklist.executionTarget}` : ''}
                            {readinessReleaseEnablementChecklist.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessReleaseEnablementChecklist.releaseGateEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.claimEnabled !== undefined ? ` / claim ${String(readinessReleaseEnablementChecklist.claimEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessReleaseEnablementChecklist.writeHelperEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessReleaseEnablementChecklist.requestCreationEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.pushEnabled !== undefined ? ` / push ${String(readinessReleaseEnablementChecklist.pushEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.claimable !== undefined ? ` / claimable ${String(readinessReleaseEnablementChecklist.claimable)}` : ''}
                            {readinessReleaseEnablementChecklist.mutationAllowed !== undefined ? ` / mutation ${String(readinessReleaseEnablementChecklist.mutationAllowed)}` : ''}
                          </span>
                          <span>
                            release execution disabled:
                            {readinessReleaseEnablementChecklist.applyEnabled !== undefined ? ` apply ${String(readinessReleaseEnablementChecklist.applyEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.testEnabled !== undefined ? ` / test ${String(readinessReleaseEnablementChecklist.testEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessReleaseEnablementChecklist.rollbackRestoreEnabled)}` : ''}
                            {readinessReleaseEnablementChecklist.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessReleaseEnablementChecklist.ragFreshnessUpdateEnabled)}` : ''}
                          </span>
                          {readinessReleaseEnablementChecklistItems.map((item) => (
                            <span key={`release-enablement-${item.key}-${item.status || item.passed}`}>
                              {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.releaseGateEnabled !== undefined ? ` / release gate ${String(item.releaseGateEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessReleaseEnablementChecklist.blockingKeys?.length && (
                            <span>release enablement blocking keys: {readinessReleaseEnablementChecklist.blockingKeys.join(', ')}</span>
                          )}
                          {readinessReleaseEnablementChecklist.message && (
                            <span>{readinessReleaseEnablementChecklist.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationDispatchEnvelopeContract && (
                        <>
                          <span>
                            mutation dispatch envelope contract: {readinessMutationDispatchEnvelopeContract.status || 'BLOCKED_DISPATCH_DISABLED'}
                            {readinessMutationDispatchEnvelopeContract.schema ? ` / ${readinessMutationDispatchEnvelopeContract.schema}` : ''}
                            {readinessMutationDispatchEnvelopeContract.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationDispatchEnvelopeContract.prerequisitesPassed)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.executionTarget ? ` / ${readinessMutationDispatchEnvelopeContract.executionTarget}` : ''}
                            {readinessMutationDispatchEnvelopeContract.dispatchMode ? ` / ${readinessMutationDispatchEnvelopeContract.dispatchMode}` : ''}
                          </span>
                          <span>
                            mutation dispatch ids:
                            {readinessMutationDispatchEnvelopeContract.sourceRequestId ? ` source ${readinessMutationDispatchEnvelopeContract.sourceRequestId}` : ''}
                            {readinessMutationDispatchEnvelopeContract.releaseAttemptId ? ` / release ${readinessMutationDispatchEnvelopeContract.releaseAttemptId}` : ''}
                            {readinessMutationDispatchEnvelopeContract.sessionId ? ` / session ${readinessMutationDispatchEnvelopeContract.sessionId}` : ''}
                            {readinessMutationDispatchEnvelopeContract.agentId ? ` / agent ${readinessMutationDispatchEnvelopeContract.agentId}` : ''}
                            {readinessMutationDispatchEnvelopeContract.workspaceId ? ` / workspace ${readinessMutationDispatchEnvelopeContract.workspaceId}` : ''}
                          </span>
                          <span>
                            mutation dispatch disabled:
                            {readinessMutationDispatchEnvelopeContract.releaseGateEnabled !== undefined ? ` release gate ${String(readinessMutationDispatchEnvelopeContract.releaseGateEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationDispatchEnvelopeContract.requestCreationEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.pushEnabled !== undefined ? ` / push ${String(readinessMutationDispatchEnvelopeContract.pushEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.claimEnabled !== undefined ? ` / claim ${String(readinessMutationDispatchEnvelopeContract.claimEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationDispatchEnvelopeContract.writeHelperEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.claimable !== undefined ? ` / claimable ${String(readinessMutationDispatchEnvelopeContract.claimable)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationDispatchEnvelopeContract.mutationAllowed)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.applyEnabled !== undefined ? ` / apply ${String(readinessMutationDispatchEnvelopeContract.applyEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.testEnabled !== undefined ? ` / test ${String(readinessMutationDispatchEnvelopeContract.testEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationDispatchEnvelopeContract.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationDispatchEnvelopeContract.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationDispatchEnvelopeContract.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationDispatchEnvelopeContract.publicationEnabled)}` : ''}
                            {readinessMutationDispatchEnvelopeContract.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationDispatchEnvelopeContract.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {!!readinessMutationDispatchEnvelopeContract.expectedOutcomeKeys?.length && (
                            <span>mutation dispatch expected outcomes: {readinessMutationDispatchEnvelopeContract.expectedOutcomeKeys.join(', ')}</span>
                          )}
                          {readinessMutationDispatchOrderedToolSequence.map((item) => (
                            <span key={`mutation-dispatch-tool-${item.order || item.key}-${item.toolName}`}>
                              {item.order !== undefined ? `${item.order}. ` : ''}{item.key}: {item.toolName || 'tool pending'}
                              {item.approvalState ? ` / approval ${item.approvalState}` : ''}
                              {item.sideEffectful !== undefined ? ` / side-effect ${String(item.sideEffectful)}` : ''}
                              {item.rollbackFallback !== undefined ? ` / rollback fallback ${String(item.rollbackFallback)}` : ''}
                            </span>
                          ))}
                          {readinessMutationDispatchRequiredApprovals.map((item) => (
                            <span key={`mutation-dispatch-approval-${item.key}-${item.toolName}`}>
                              approval {item.key}: {item.approvalState || 'UNKNOWN'}
                              {item.toolName ? ` / ${item.toolName}` : ''}
                              {item.sideEffectful !== undefined ? ` / side-effect ${String(item.sideEffectful)}` : ''}
                            </span>
                          ))}
                          {readinessMutationDispatchEnvelopeContract.rollbackObligation && (
                            <span>
                              rollback obligation:
                              {readinessMutationDispatchEnvelopeContract.rollbackObligation.status ? ` ${readinessMutationDispatchEnvelopeContract.rollbackObligation.status}` : ''}
                              {readinessMutationDispatchEnvelopeContract.rollbackObligation.toolName ? ` / ${readinessMutationDispatchEnvelopeContract.rollbackObligation.toolName}` : ''}
                              {readinessMutationDispatchEnvelopeContract.rollbackObligation.required !== undefined ? ` / required ${String(readinessMutationDispatchEnvelopeContract.rollbackObligation.required)}` : ''}
                              {readinessMutationDispatchEnvelopeContract.rollbackObligation.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationDispatchEnvelopeContract.rollbackObligation.rollbackRestoreEnabled)}` : ''}
                            </span>
                          )}
                          {readinessMutationDispatchEnvelopeContract.ragFreshnessObligation && (
                            <span>
                              RAG freshness obligation:
                              {readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.status ? ` ${readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.status}` : ''}
                              {readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.required !== undefined ? ` / required ${String(readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.required)}` : ''}
                              {readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.ragFreshnessUpdateEnabled)}` : ''}
                              {readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.message ? ` / ${readinessMutationDispatchEnvelopeContract.ragFreshnessObligation.message}` : ''}
                            </span>
                          )}
                          {!!readinessMutationDispatchEnvelopeContract.blockingKeys?.length && (
                            <span>mutation dispatch blocking keys: {readinessMutationDispatchEnvelopeContract.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationDispatchEnvelopeContract.message && (
                            <span>{readinessMutationDispatchEnvelopeContract.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationDispatchPreflightBoundary && (
                        <>
                          <span>
                            mutation dispatch preflight boundary: {readinessMutationDispatchPreflightBoundary.status || 'BLOCKED_PREFLIGHT_DISABLED'}
                            {readinessMutationDispatchPreflightBoundary.schema ? ` / ${readinessMutationDispatchPreflightBoundary.schema}` : ''}
                            {readinessMutationDispatchPreflightBoundary.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationDispatchPreflightBoundary.prerequisitesPassed)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.executionTarget ? ` / ${readinessMutationDispatchPreflightBoundary.executionTarget}` : ''}
                            {readinessMutationDispatchPreflightBoundary.dispatchEnvelopeStatus ? ` / envelope ${readinessMutationDispatchPreflightBoundary.dispatchEnvelopeStatus}` : ''}
                            {readinessMutationDispatchPreflightBoundary.dispatchEnvelopePrerequisitesPassed !== undefined ? ` / envelope prerequisites ${String(readinessMutationDispatchPreflightBoundary.dispatchEnvelopePrerequisitesPassed)}` : ''}
                          </span>
                          <span>
                            mutation dispatch preflight agent:
                            {readinessMutationDispatchPreflightBoundary.connectionState ? ` ${readinessMutationDispatchPreflightBoundary.connectionState}` : ''}
                            {readinessMutationDispatchPreflightBoundary.agentConnected !== undefined ? ` / connected ${String(readinessMutationDispatchPreflightBoundary.agentConnected)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.agentMatches !== undefined ? ` / matches ${String(readinessMutationDispatchPreflightBoundary.agentMatches)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.requestedAgentId ? ` / requested ${readinessMutationDispatchPreflightBoundary.requestedAgentId}` : ''}
                            {readinessMutationDispatchPreflightBoundary.connectedAgentId ? ` / connected id ${readinessMutationDispatchPreflightBoundary.connectedAgentId}` : ''}
                            {readinessMutationDispatchPreflightBoundary.agentVersion ? ` / version ${readinessMutationDispatchPreflightBoundary.agentVersion}` : ''}
                          </span>
                          <span>
                            mutation dispatch preflight workspace:
                            {readinessMutationDispatchPreflightBoundary.workspaceId ? ` ${readinessMutationDispatchPreflightBoundary.workspaceId}` : ''}
                            {readinessMutationDispatchPreflightBoundary.approvedWorkspaceReady !== undefined ? ` / approved ready ${String(readinessMutationDispatchPreflightBoundary.approvedWorkspaceReady)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.workspaceApproved !== undefined ? ` / approved ${String(readinessMutationDispatchPreflightBoundary.workspaceApproved)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.workspaceName ? ` / ${readinessMutationDispatchPreflightBoundary.workspaceName}` : ''}
                            {readinessMutationDispatchPreflightBoundary.workspaceIdentityStatus ? ` / identity ${readinessMutationDispatchPreflightBoundary.workspaceIdentityStatus}` : ''}
                            {readinessMutationDispatchPreflightBoundary.workspaceIdentityVerified !== undefined ? ` / verified ${String(readinessMutationDispatchPreflightBoundary.workspaceIdentityVerified)}` : ''}
                          </span>
                          {!!readinessMutationDispatchPreflightBoundary.requiredCapabilities?.length && (
                            <span>mutation dispatch required capabilities: {readinessMutationDispatchPreflightBoundary.requiredCapabilities.join(', ')}</span>
                          )}
                          {!!readinessMutationDispatchPreflightBoundary.advertisedCapabilities?.length && (
                            <span>mutation dispatch advertised capabilities: {readinessMutationDispatchPreflightBoundary.advertisedCapabilities.join(', ')}</span>
                          )}
                          {readinessMutationDispatchPreflightCapabilityChecks.map((item) => (
                            <span key={`mutation-dispatch-preflight-capability-${item.toolName}`}>
                              capability {item.toolName}: {item.available !== undefined ? `available ${String(item.available)}` : 'availability unknown'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.sideEffectful !== undefined ? ` / side-effect ${String(item.sideEffectful)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationDispatchPreflightBoundary.missingCapabilities?.length && (
                            <span>mutation dispatch missing capabilities: {readinessMutationDispatchPreflightBoundary.missingCapabilities.join(', ')}</span>
                          )}
                          <span>
                            mutation dispatch preflight disabled:
                            {readinessMutationDispatchPreflightBoundary.dispatchPreflightEnabled !== undefined ? ` dispatch preflight ${String(readinessMutationDispatchPreflightBoundary.dispatchPreflightEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessMutationDispatchPreflightBoundary.releaseGateEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationDispatchPreflightBoundary.requestCreationEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.pushEnabled !== undefined ? ` / push ${String(readinessMutationDispatchPreflightBoundary.pushEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.claimEnabled !== undefined ? ` / claim ${String(readinessMutationDispatchPreflightBoundary.claimEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationDispatchPreflightBoundary.writeHelperEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.claimable !== undefined ? ` / claimable ${String(readinessMutationDispatchPreflightBoundary.claimable)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationDispatchPreflightBoundary.mutationAllowed)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.applyEnabled !== undefined ? ` / apply ${String(readinessMutationDispatchPreflightBoundary.applyEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.testEnabled !== undefined ? ` / test ${String(readinessMutationDispatchPreflightBoundary.testEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationDispatchPreflightBoundary.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationDispatchPreflightBoundary.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationDispatchPreflightBoundary.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationDispatchPreflightBoundary.publicationEnabled)}` : ''}
                            {readinessMutationDispatchPreflightBoundary.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationDispatchPreflightBoundary.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {!!readinessMutationDispatchPreflightBoundary.blockingKeys?.length && (
                            <span>mutation dispatch preflight blocking keys: {readinessMutationDispatchPreflightBoundary.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationDispatchPreflightBoundary.message && (
                            <span>{readinessMutationDispatchPreflightBoundary.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationDispatchDecisionModel && (
                        <>
                          <span>
                            mutation dispatch decision model: {readinessMutationDispatchDecisionModel.status || 'BLOCKED_DISPATCH_DISABLED'}
                            {readinessMutationDispatchDecisionModel.schema ? ` / ${readinessMutationDispatchDecisionModel.schema}` : ''}
                            {readinessMutationDispatchDecisionModel.decision ? ` / decision ${readinessMutationDispatchDecisionModel.decision}` : ''}
                            {readinessMutationDispatchDecisionModel.readinessInputsPassed !== undefined ? ` / readiness inputs ${String(readinessMutationDispatchDecisionModel.readinessInputsPassed)}` : ''}
                            {readinessMutationDispatchDecisionModel.executionTarget ? ` / ${readinessMutationDispatchDecisionModel.executionTarget}` : ''}
                            {readinessMutationDispatchDecisionModel.dispatchEnvelopeStatus ? ` / envelope ${readinessMutationDispatchDecisionModel.dispatchEnvelopeStatus}` : ''}
                            {readinessMutationDispatchDecisionModel.dispatchPreflightStatus ? ` / preflight ${readinessMutationDispatchDecisionModel.dispatchPreflightStatus}` : ''}
                          </span>
                          <span>
                            mutation dispatch decision ids:
                            {readinessMutationDispatchDecisionModel.sourceRequestId ? ` source ${readinessMutationDispatchDecisionModel.sourceRequestId}` : ''}
                            {readinessMutationDispatchDecisionModel.releaseAttemptId ? ` / release ${String(readinessMutationDispatchDecisionModel.releaseAttemptId).slice(0, 8)}` : ''}
                            {readinessMutationDispatchDecisionModel.sessionId ? ` / session ${readinessMutationDispatchDecisionModel.sessionId}` : ''}
                            {readinessMutationDispatchDecisionModel.agentId ? ` / agent ${readinessMutationDispatchDecisionModel.agentId}` : ''}
                            {readinessMutationDispatchDecisionModel.workspaceId ? ` / workspace ${readinessMutationDispatchDecisionModel.workspaceId}` : ''}
                          </span>
                          <span>
                            mutation dispatch decision disabled:
                            {readinessMutationDispatchDecisionModel.dispatchDecisionEnabled !== undefined ? ` dispatch decision ${String(readinessMutationDispatchDecisionModel.dispatchDecisionEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessMutationDispatchDecisionModel.releaseGateEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationDispatchDecisionModel.requestCreationEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.pushEnabled !== undefined ? ` / push ${String(readinessMutationDispatchDecisionModel.pushEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.claimEnabled !== undefined ? ` / claim ${String(readinessMutationDispatchDecisionModel.claimEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationDispatchDecisionModel.writeHelperEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.claimable !== undefined ? ` / claimable ${String(readinessMutationDispatchDecisionModel.claimable)}` : ''}
                            {readinessMutationDispatchDecisionModel.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationDispatchDecisionModel.mutationAllowed)}` : ''}
                            {readinessMutationDispatchDecisionModel.applyEnabled !== undefined ? ` / apply ${String(readinessMutationDispatchDecisionModel.applyEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.testEnabled !== undefined ? ` / test ${String(readinessMutationDispatchDecisionModel.testEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationDispatchDecisionModel.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationDispatchDecisionModel.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationDispatchDecisionModel.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationDispatchDecisionModel.publicationEnabled)}` : ''}
                            {readinessMutationDispatchDecisionModel.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationDispatchDecisionModel.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {readinessMutationDispatchDecisionInputs.map((item) => (
                            <span key={`mutation-dispatch-decision-input-${item.key}`}>
                              decision input {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.releaseGateEnabled !== undefined ? ` / release gate ${String(item.releaseGateEnabled)}` : ''}
                              {item.dispatchDecisionEnabled !== undefined ? ` / dispatch decision ${String(item.dispatchDecisionEnabled)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationDispatchDecisionModel.blockingKeys?.length && (
                            <span>mutation dispatch decision blocking keys: {readinessMutationDispatchDecisionModel.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationDispatchDecisionModel.userVisibleRefusalMessage && (
                            <span>dispatch refusal: {readinessMutationDispatchDecisionModel.userVisibleRefusalMessage}</span>
                          )}
                          {readinessMutationDispatchDecisionModel.message && (
                            <span>{readinessMutationDispatchDecisionModel.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestBlueprint && (
                        <>
                          <span>
                            mutation request blueprint: {readinessMutationRequestBlueprint.status || 'BLOCKED_REQUEST_BLUEPRINT_DISABLED'}
                            {readinessMutationRequestBlueprint.schema ? ` / ${readinessMutationRequestBlueprint.schema}` : ''}
                            {readinessMutationRequestBlueprint.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationRequestBlueprint.prerequisitesPassed)}` : ''}
                            {readinessMutationRequestBlueprint.executionTarget ? ` / ${readinessMutationRequestBlueprint.executionTarget}` : ''}
                            {readinessMutationRequestBlueprint.requestCreationMode ? ` / ${readinessMutationRequestBlueprint.requestCreationMode}` : ''}
                            {readinessMutationRequestBlueprint.sourceDecisionStatus ? ` / decision ${readinessMutationRequestBlueprint.sourceDecisionStatus}` : ''}
                            {readinessMutationRequestBlueprint.sourceEnvelopeStatus ? ` / envelope ${readinessMutationRequestBlueprint.sourceEnvelopeStatus}` : ''}
                          </span>
                          <span>
                            mutation request blueprint ids:
                            {readinessMutationRequestBlueprint.sourceRequestId ? ` source ${readinessMutationRequestBlueprint.sourceRequestId}` : ''}
                            {readinessMutationRequestBlueprint.releaseAttemptId ? ` / release ${String(readinessMutationRequestBlueprint.releaseAttemptId).slice(0, 8)}` : ''}
                            {readinessMutationRequestBlueprint.sessionId ? ` / session ${readinessMutationRequestBlueprint.sessionId}` : ''}
                            {readinessMutationRequestBlueprint.agentId ? ` / agent ${readinessMutationRequestBlueprint.agentId}` : ''}
                            {readinessMutationRequestBlueprint.workspaceId ? ` / workspace ${readinessMutationRequestBlueprint.workspaceId}` : ''}
                          </span>
                          <span>
                            mutation request blueprint disabled:
                            {readinessMutationRequestBlueprint.requestBlueprintEnabled !== undefined ? ` request blueprint ${String(readinessMutationRequestBlueprint.requestBlueprintEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.dispatchDecisionEnabled !== undefined ? ` / dispatch decision ${String(readinessMutationRequestBlueprint.dispatchDecisionEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessMutationRequestBlueprint.releaseGateEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationRequestBlueprint.requestCreationEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.pushEnabled !== undefined ? ` / push ${String(readinessMutationRequestBlueprint.pushEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.claimEnabled !== undefined ? ` / claim ${String(readinessMutationRequestBlueprint.claimEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationRequestBlueprint.writeHelperEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.claimable !== undefined ? ` / claimable ${String(readinessMutationRequestBlueprint.claimable)}` : ''}
                            {readinessMutationRequestBlueprint.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationRequestBlueprint.mutationAllowed)}` : ''}
                            {readinessMutationRequestBlueprint.applyEnabled !== undefined ? ` / apply ${String(readinessMutationRequestBlueprint.applyEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.testEnabled !== undefined ? ` / test ${String(readinessMutationRequestBlueprint.testEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationRequestBlueprint.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationRequestBlueprint.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationRequestBlueprint.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationRequestBlueprint.publicationEnabled)}` : ''}
                            {readinessMutationRequestBlueprint.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationRequestBlueprint.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {!!readinessMutationRequestBlueprint.expectedInputKeys?.length && (
                            <span>mutation request expected inputs: {readinessMutationRequestBlueprint.expectedInputKeys.join(', ')}</span>
                          )}
                          {!!readinessMutationRequestBlueprint.expectedOutputKeys?.length && (
                            <span>mutation request expected outputs: {readinessMutationRequestBlueprint.expectedOutputKeys.join(', ')}</span>
                          )}
                          {readinessMutationRequestBlueprintToolRequests.map((item) => (
                            <span key={`mutation-request-blueprint-tool-${item.order || item.key}-${item.toolName}`}>
                              {item.order !== undefined ? `${item.order}. ` : ''}{item.key}: {item.toolName || 'tool pending'}
                              {item.status ? ` / ${item.status}` : ''}
                              {item.approvalState ? ` / approval ${item.approvalState}` : ''}
                              {item.sideEffectful !== undefined ? ` / side-effect ${String(item.sideEffectful)}` : ''}
                              {item.rollbackFallback !== undefined ? ` / rollback fallback ${String(item.rollbackFallback)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimEnabled !== undefined ? ` / claim ${String(item.claimEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.expectedOutputKeys?.length ? ` / outputs ${item.expectedOutputKeys.join(', ')}` : ''}
                            </span>
                          ))}
                          {readinessMutationRequestBlueprintApprovalStates.map((item) => (
                            <span key={`mutation-request-blueprint-approval-${item.key}-${item.toolName}`}>
                              blueprint approval {item.key}: {item.approvalState || 'UNKNOWN'}
                              {item.toolName ? ` / ${item.toolName}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationRequestBlueprint.blockingKeys?.length && (
                            <span>mutation request blueprint blocking keys: {readinessMutationRequestBlueprint.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationRequestBlueprint.message && (
                            <span>{readinessMutationRequestBlueprint.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestCreationGate && (
                        <>
                          <span>
                            mutation request creation gate: {readinessMutationRequestCreationGate.status || 'BLOCKED_CREATION_DISABLED'}
                            {readinessMutationRequestCreationGate.schema ? ` / ${readinessMutationRequestCreationGate.schema}` : ''}
                            {readinessMutationRequestCreationGate.blueprintReady !== undefined ? ` / blueprint ready ${String(readinessMutationRequestCreationGate.blueprintReady)}` : ''}
                            {readinessMutationRequestCreationGate.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationRequestCreationGate.prerequisitesPassed)}` : ''}
                            {readinessMutationRequestCreationGate.executionTarget ? ` / ${readinessMutationRequestCreationGate.executionTarget}` : ''}
                            {readinessMutationRequestCreationGate.releaseGateState ? ` / release gate ${readinessMutationRequestCreationGate.releaseGateState}` : ''}
                            {readinessMutationRequestCreationGate.requestCreationPolicy ? ` / policy ${readinessMutationRequestCreationGate.requestCreationPolicy}` : ''}
                          </span>
                          <span>
                            mutation request creation gate ids:
                            {readinessMutationRequestCreationGate.sourceRequestId ? ` source ${readinessMutationRequestCreationGate.sourceRequestId}` : ''}
                            {readinessMutationRequestCreationGate.releaseAttemptId ? ` / release ${String(readinessMutationRequestCreationGate.releaseAttemptId).slice(0, 8)}` : ''}
                            {readinessMutationRequestCreationGate.sessionId ? ` / session ${readinessMutationRequestCreationGate.sessionId}` : ''}
                            {readinessMutationRequestCreationGate.agentId ? ` / agent ${readinessMutationRequestCreationGate.agentId}` : ''}
                            {readinessMutationRequestCreationGate.workspaceId ? ` / workspace ${readinessMutationRequestCreationGate.workspaceId}` : ''}
                          </span>
                          <span>
                            mutation request creation counts:
                            {readinessMutationRequestCreationGate.expectedRequestCount !== undefined ? ` expected ${String(readinessMutationRequestCreationGate.expectedRequestCount)}` : ''}
                            {readinessMutationRequestCreationGate.persistedRequestCount !== undefined ? ` / persisted ${String(readinessMutationRequestCreationGate.persistedRequestCount)}` : ''}
                            {readinessMutationRequestCreationGate.pushedRequestCount !== undefined ? ` / pushed ${String(readinessMutationRequestCreationGate.pushedRequestCount)}` : ''}
                            {readinessMutationRequestCreationGate.claimableRequestCount !== undefined ? ` / claimable ${String(readinessMutationRequestCreationGate.claimableRequestCount)}` : ''}
                          </span>
                          <span>
                            mutation request creation disabled:
                            {readinessMutationRequestCreationGate.requestCreationGateEnabled !== undefined ? ` creation gate ${String(readinessMutationRequestCreationGate.requestCreationGateEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessMutationRequestCreationGate.releaseGateEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationRequestCreationGate.requestCreationEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.pushEnabled !== undefined ? ` / push ${String(readinessMutationRequestCreationGate.pushEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.claimEnabled !== undefined ? ` / claim ${String(readinessMutationRequestCreationGate.claimEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationRequestCreationGate.writeHelperEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.claimable !== undefined ? ` / claimable ${String(readinessMutationRequestCreationGate.claimable)}` : ''}
                            {readinessMutationRequestCreationGate.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationRequestCreationGate.mutationAllowed)}` : ''}
                            {readinessMutationRequestCreationGate.applyEnabled !== undefined ? ` / apply ${String(readinessMutationRequestCreationGate.applyEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.testEnabled !== undefined ? ` / test ${String(readinessMutationRequestCreationGate.testEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationRequestCreationGate.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationRequestCreationGate.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationRequestCreationGate.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationRequestCreationGate.publicationEnabled)}` : ''}
                            {readinessMutationRequestCreationGate.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationRequestCreationGate.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {readinessMutationRequestCreationGatePolicyChecks.map((item) => (
                            <span key={`mutation-request-creation-policy-${item.key}`}>
                              creation policy {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimEnabled !== undefined ? ` / claim ${String(item.claimEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationRequestCreationGate.blockingKeys?.length && (
                            <span>mutation request creation blocking keys: {readinessMutationRequestCreationGate.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationRequestCreationGate.message && (
                            <span>{readinessMutationRequestCreationGate.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestPushGate && (
                        <>
                          <span>
                            mutation request push gate: {readinessMutationRequestPushGate.status || 'BLOCKED_PUSH_DISABLED'}
                            {readinessMutationRequestPushGate.schema ? ` / ${readinessMutationRequestPushGate.schema}` : ''}
                            {readinessMutationRequestPushGate.creationGateReady !== undefined ? ` / creation gate ready ${String(readinessMutationRequestPushGate.creationGateReady)}` : ''}
                            {readinessMutationRequestPushGate.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationRequestPushGate.prerequisitesPassed)}` : ''}
                            {readinessMutationRequestPushGate.executionTarget ? ` / ${readinessMutationRequestPushGate.executionTarget}` : ''}
                            {readinessMutationRequestPushGate.transportPushPolicy ? ` / transport ${readinessMutationRequestPushGate.transportPushPolicy}` : ''}
                            {readinessMutationRequestPushGate.pusherInvocationEnabled !== undefined ? ` / pusher ${String(readinessMutationRequestPushGate.pusherInvocationEnabled)}` : ''}
                            {readinessMutationRequestPushGate.sourceCreationGateStatus ? ` / creation status ${readinessMutationRequestPushGate.sourceCreationGateStatus}` : ''}
                          </span>
                          <span>
                            mutation request push gate ids:
                            {readinessMutationRequestPushGate.sourceRequestId ? ` source ${readinessMutationRequestPushGate.sourceRequestId}` : ''}
                            {readinessMutationRequestPushGate.releaseAttemptId ? ` / release ${String(readinessMutationRequestPushGate.releaseAttemptId).slice(0, 8)}` : ''}
                            {readinessMutationRequestPushGate.sessionId ? ` / session ${readinessMutationRequestPushGate.sessionId}` : ''}
                            {readinessMutationRequestPushGate.agentId ? ` / agent ${readinessMutationRequestPushGate.agentId}` : ''}
                            {readinessMutationRequestPushGate.workspaceId ? ` / workspace ${readinessMutationRequestPushGate.workspaceId}` : ''}
                          </span>
                          <span>
                            mutation request push counts:
                            {readinessMutationRequestPushGate.expectedRequestCount !== undefined ? ` expected ${String(readinessMutationRequestPushGate.expectedRequestCount)}` : ''}
                            {readinessMutationRequestPushGate.persistedRequestCount !== undefined ? ` / persisted ${String(readinessMutationRequestPushGate.persistedRequestCount)}` : ''}
                            {readinessMutationRequestPushGate.pushedRequestCount !== undefined ? ` / pushed ${String(readinessMutationRequestPushGate.pushedRequestCount)}` : ''}
                            {readinessMutationRequestPushGate.claimableRequestCount !== undefined ? ` / claimable ${String(readinessMutationRequestPushGate.claimableRequestCount)}` : ''}
                          </span>
                          <span>
                            mutation request push disabled:
                            {readinessMutationRequestPushGate.pushGateEnabled !== undefined ? ` push gate ${String(readinessMutationRequestPushGate.pushGateEnabled)}` : ''}
                            {readinessMutationRequestPushGate.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessMutationRequestPushGate.releaseGateEnabled)}` : ''}
                            {readinessMutationRequestPushGate.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationRequestPushGate.requestCreationEnabled)}` : ''}
                            {readinessMutationRequestPushGate.pushEnabled !== undefined ? ` / push ${String(readinessMutationRequestPushGate.pushEnabled)}` : ''}
                            {readinessMutationRequestPushGate.claimEnabled !== undefined ? ` / claim ${String(readinessMutationRequestPushGate.claimEnabled)}` : ''}
                            {readinessMutationRequestPushGate.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationRequestPushGate.writeHelperEnabled)}` : ''}
                            {readinessMutationRequestPushGate.claimable !== undefined ? ` / claimable ${String(readinessMutationRequestPushGate.claimable)}` : ''}
                            {readinessMutationRequestPushGate.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationRequestPushGate.mutationAllowed)}` : ''}
                            {readinessMutationRequestPushGate.applyEnabled !== undefined ? ` / apply ${String(readinessMutationRequestPushGate.applyEnabled)}` : ''}
                            {readinessMutationRequestPushGate.testEnabled !== undefined ? ` / test ${String(readinessMutationRequestPushGate.testEnabled)}` : ''}
                            {readinessMutationRequestPushGate.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationRequestPushGate.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationRequestPushGate.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationRequestPushGate.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationRequestPushGate.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationRequestPushGate.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationRequestPushGate.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationRequestPushGate.publicationEnabled)}` : ''}
                            {readinessMutationRequestPushGate.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationRequestPushGate.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {readinessMutationRequestPushGatePolicyChecks.map((item) => (
                            <span key={`mutation-request-push-policy-${item.key}`}>
                              push policy {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimEnabled !== undefined ? ` / claim ${String(item.claimEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationRequestPushGate.blockingKeys?.length && (
                            <span>mutation request push blocking keys: {readinessMutationRequestPushGate.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationRequestPushGate.message && (
                            <span>{readinessMutationRequestPushGate.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestClaimGate && (
                        <>
                          <span>
                            mutation request claim gate: {readinessMutationRequestClaimGate.status || 'BLOCKED_CLAIM_DISABLED'}
                            {readinessMutationRequestClaimGate.schema ? ` / ${readinessMutationRequestClaimGate.schema}` : ''}
                            {readinessMutationRequestClaimGate.pushGateReady !== undefined ? ` / push gate ready ${String(readinessMutationRequestClaimGate.pushGateReady)}` : ''}
                            {readinessMutationRequestClaimGate.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationRequestClaimGate.prerequisitesPassed)}` : ''}
                            {readinessMutationRequestClaimGate.executionTarget ? ` / ${readinessMutationRequestClaimGate.executionTarget}` : ''}
                            {readinessMutationRequestClaimGate.claimPolicy ? ` / policy ${readinessMutationRequestClaimGate.claimPolicy}` : ''}
                            {readinessMutationRequestClaimGate.claimNextInvocationEnabled !== undefined ? ` / claimNext ${String(readinessMutationRequestClaimGate.claimNextInvocationEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.sourcePushGateStatus ? ` / push status ${readinessMutationRequestClaimGate.sourcePushGateStatus}` : ''}
                          </span>
                          <span>
                            mutation request claim gate ids:
                            {readinessMutationRequestClaimGate.sourceRequestId ? ` source ${readinessMutationRequestClaimGate.sourceRequestId}` : ''}
                            {readinessMutationRequestClaimGate.releaseAttemptId ? ` / release ${String(readinessMutationRequestClaimGate.releaseAttemptId).slice(0, 8)}` : ''}
                            {readinessMutationRequestClaimGate.sessionId ? ` / session ${readinessMutationRequestClaimGate.sessionId}` : ''}
                            {readinessMutationRequestClaimGate.agentId ? ` / agent ${readinessMutationRequestClaimGate.agentId}` : ''}
                            {readinessMutationRequestClaimGate.workspaceId ? ` / workspace ${readinessMutationRequestClaimGate.workspaceId}` : ''}
                          </span>
                          <span>
                            mutation request claim counts:
                            {readinessMutationRequestClaimGate.expectedRequestCount !== undefined ? ` expected ${String(readinessMutationRequestClaimGate.expectedRequestCount)}` : ''}
                            {readinessMutationRequestClaimGate.persistedRequestCount !== undefined ? ` / persisted ${String(readinessMutationRequestClaimGate.persistedRequestCount)}` : ''}
                            {readinessMutationRequestClaimGate.pushedRequestCount !== undefined ? ` / pushed ${String(readinessMutationRequestClaimGate.pushedRequestCount)}` : ''}
                            {readinessMutationRequestClaimGate.claimableRequestCount !== undefined ? ` / claimable ${String(readinessMutationRequestClaimGate.claimableRequestCount)}` : ''}
                            {readinessMutationRequestClaimGate.runningRequestCount !== undefined ? ` / running ${String(readinessMutationRequestClaimGate.runningRequestCount)}` : ''}
                          </span>
                          <span>
                            mutation request claim disabled:
                            {readinessMutationRequestClaimGate.claimGateEnabled !== undefined ? ` claim gate ${String(readinessMutationRequestClaimGate.claimGateEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.releaseGateEnabled !== undefined ? ` / release gate ${String(readinessMutationRequestClaimGate.releaseGateEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationRequestClaimGate.requestCreationEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.pushEnabled !== undefined ? ` / push ${String(readinessMutationRequestClaimGate.pushEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.claimEnabled !== undefined ? ` / claim ${String(readinessMutationRequestClaimGate.claimEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationRequestClaimGate.writeHelperEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.claimable !== undefined ? ` / claimable ${String(readinessMutationRequestClaimGate.claimable)}` : ''}
                            {readinessMutationRequestClaimGate.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationRequestClaimGate.mutationAllowed)}` : ''}
                            {readinessMutationRequestClaimGate.applyEnabled !== undefined ? ` / apply ${String(readinessMutationRequestClaimGate.applyEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.testEnabled !== undefined ? ` / test ${String(readinessMutationRequestClaimGate.testEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationRequestClaimGate.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationRequestClaimGate.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationRequestClaimGate.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationRequestClaimGate.publicationEnabled)}` : ''}
                            {readinessMutationRequestClaimGate.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationRequestClaimGate.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {readinessMutationRequestClaimGatePolicyChecks.map((item) => (
                            <span key={`mutation-request-claim-policy-${item.key}`}>
                              claim policy {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimEnabled !== undefined ? ` / claim ${String(item.claimEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.running !== undefined ? ` / running ${String(item.running)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationRequestClaimGate.blockingKeys?.length && (
                            <span>mutation request claim blocking keys: {readinessMutationRequestClaimGate.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationRequestClaimGate.message && (
                            <span>{readinessMutationRequestClaimGate.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationCompletionSummary && (
                        <>
                          <span>
                            mutation completion summary: {readinessMutationCompletionSummary.status || 'BLOCKED_COMPLETION_DISABLED'}
                            {readinessMutationCompletionSummary.schema ? ` / ${readinessMutationCompletionSummary.schema}` : ''}
                            {readinessMutationCompletionSummary.prerequisitesPassed !== undefined ? ` / prerequisites ${String(readinessMutationCompletionSummary.prerequisitesPassed)}` : ''}
                            {readinessMutationCompletionSummary.executionTarget ? ` / ${readinessMutationCompletionSummary.executionTarget}` : ''}
                          </span>
                          <span>
                            mutation completion disabled:
                            {readinessMutationCompletionSummary.releaseGateEnabled !== undefined ? ` release gate ${String(readinessMutationCompletionSummary.releaseGateEnabled)}` : ''}
                            {readinessMutationCompletionSummary.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessMutationCompletionSummary.requestCreationEnabled)}` : ''}
                            {readinessMutationCompletionSummary.pushEnabled !== undefined ? ` / push ${String(readinessMutationCompletionSummary.pushEnabled)}` : ''}
                            {readinessMutationCompletionSummary.claimEnabled !== undefined ? ` / claim ${String(readinessMutationCompletionSummary.claimEnabled)}` : ''}
                            {readinessMutationCompletionSummary.writeHelperEnabled !== undefined ? ` / write helper ${String(readinessMutationCompletionSummary.writeHelperEnabled)}` : ''}
                            {readinessMutationCompletionSummary.claimable !== undefined ? ` / claimable ${String(readinessMutationCompletionSummary.claimable)}` : ''}
                            {readinessMutationCompletionSummary.mutationAllowed !== undefined ? ` / mutation ${String(readinessMutationCompletionSummary.mutationAllowed)}` : ''}
                            {readinessMutationCompletionSummary.applyEnabled !== undefined ? ` / apply ${String(readinessMutationCompletionSummary.applyEnabled)}` : ''}
                            {readinessMutationCompletionSummary.testEnabled !== undefined ? ` / test ${String(readinessMutationCompletionSummary.testEnabled)}` : ''}
                            {readinessMutationCompletionSummary.rollbackRestoreEnabled !== undefined ? ` / rollback restore ${String(readinessMutationCompletionSummary.rollbackRestoreEnabled)}` : ''}
                            {readinessMutationCompletionSummary.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessMutationCompletionSummary.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessMutationCompletionSummary.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(readinessMutationCompletionSummary.mutationResultAggregationEnabled)}` : ''}
                            {readinessMutationCompletionSummary.publicationEnabled !== undefined ? ` / publication ${String(readinessMutationCompletionSummary.publicationEnabled)}` : ''}
                            {readinessMutationCompletionSummary.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessMutationCompletionSummary.finalAnswerGenerationEnabled)}` : ''}
                          </span>
                          {readinessMutationCompletionSummaryItems.map((item) => (
                            <span key={`mutation-completion-${item.key}-${item.status || item.passed}`}>
                              {item.key}: {item.status || 'UNKNOWN'}
                              {item.passed !== undefined ? ` / passed ${String(item.passed)}` : ''}
                              {item.blocking !== undefined ? ` / blocking ${String(item.blocking)}` : ''}
                              {item.releaseGateEnabled !== undefined ? ` / release gate ${String(item.releaseGateEnabled)}` : ''}
                              {item.requestCreationEnabled !== undefined ? ` / request creation ${String(item.requestCreationEnabled)}` : ''}
                              {item.pushEnabled !== undefined ? ` / push ${String(item.pushEnabled)}` : ''}
                              {item.claimable !== undefined ? ` / claimable ${String(item.claimable)}` : ''}
                              {item.mutationAllowed !== undefined ? ` / mutation ${String(item.mutationAllowed)}` : ''}
                              {item.mutationResultAggregationEnabled !== undefined ? ` / result aggregation ${String(item.mutationResultAggregationEnabled)}` : ''}
                              {item.publicationEnabled !== undefined ? ` / publication ${String(item.publicationEnabled)}` : ''}
                              {item.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(item.finalAnswerGenerationEnabled)}` : ''}
                              {item.message ? ` / ${item.message}` : ''}
                            </span>
                          ))}
                          {!!readinessMutationCompletionSummary.blockingKeys?.length && (
                            <span>mutation completion blocking keys: {readinessMutationCompletionSummary.blockingKeys.join(', ')}</span>
                          )}
                          {readinessMutationCompletionSummary.message && (
                            <span>{readinessMutationCompletionSummary.message}</span>
                          )}
                        </>
                      )}
                      {readinessReleaseBoundaryVisibility && (
                        <>
                          <span>
                            release action boundary: {readinessReleaseBoundaryVisibility.status}
                            {' / '}
                            {readinessReleaseBoundaryVisibility.actionMode}
                            {' / endpoint '}
                            {readinessReleaseBoundaryVisibility.endpoint}
                          </span>
                          <span>
                            release action disabled:
                            {' release gate '}
                            {String(readinessReleaseBoundaryVisibility.releaseGateEnabled)}
                            {' / request creation '}
                            {String(readinessReleaseBoundaryVisibility.requestCreationEnabled)}
                            {' / push '}
                            {String(readinessReleaseBoundaryVisibility.pushEnabled)}
                            {' / claim '}
                            {String(readinessReleaseBoundaryVisibility.claimEnabled)}
                            {' / write helper '}
                            {String(readinessReleaseBoundaryVisibility.writeHelperEnabled)}
                            {' / claimable '}
                            {String(readinessReleaseBoundaryVisibility.claimable)}
                            {' / mutation '}
                            {String(readinessReleaseBoundaryVisibility.mutationAllowed)}
                          </span>
                          <span>
                            release execution remains disabled:
                            {' apply '}
                            {String(readinessReleaseBoundaryVisibility.applyEnabled)}
                            {' / test '}
                            {String(readinessReleaseBoundaryVisibility.testEnabled)}
                            {' / rollback restore '}
                            {String(readinessReleaseBoundaryVisibility.rollbackRestoreEnabled)}
                            {' / rag freshness '}
                            {String(readinessReleaseBoundaryVisibility.ragFreshnessUpdateEnabled)}
                          </span>
                          <span>No release control is enabled; the future release endpoint is visible only as an audit/refusal boundary.</span>
                        </>
                      )}
                      {readinessFreshObservationEnqueueBoundary && (
                        <>
                          <span>
                            fresh observation enqueue boundary: {readinessFreshObservationEnqueueBoundary.status || 'DISABLED'}
                            {readinessFreshObservationEnqueueBoundary.requestCreationEnabled !== undefined ? ` / request creation ${String(readinessFreshObservationEnqueueBoundary.requestCreationEnabled)}` : ''}
                            {readinessFreshObservationEnqueueBoundary.pushEnabled !== undefined ? ` / push ${String(readinessFreshObservationEnqueueBoundary.pushEnabled)}` : ''}
                            {readinessFreshObservationEnqueueBoundary.enqueueEnabled !== undefined ? ` / enqueue ${String(readinessFreshObservationEnqueueBoundary.enqueueEnabled)}` : ''}
                            {readinessFreshObservationEnqueueBoundary.claimableAfterEnqueue !== undefined ? ` / claimable ${String(readinessFreshObservationEnqueueBoundary.claimableAfterEnqueue)}` : ''}
                            {readinessFreshObservationEnqueueBoundary.mutationAllowed !== undefined ? ` / mutation ${String(readinessFreshObservationEnqueueBoundary.mutationAllowed)}` : ''}
                          </span>
                          {readinessFreshObservationBoundaryRequests.map((item) => (
                            <span key={`boundary-${item.key}-${item.releaseAttemptId || item.toolName}`}>
                              boundary planned {item.key}: {item.status || 'TEMPLATE_DISABLED'}
                              {item.toolName ? ` / ${item.toolName}` : ''}
                              {item.approvalState ? ` / approval ${item.approvalState}` : ''}
                              {item.enqueueEnabled !== undefined ? ` / enqueue ${String(item.enqueueEnabled)}` : ''}
                              {item.claimableAfterEnqueue !== undefined ? ` / claimable ${String(item.claimableAfterEnqueue)}` : ''}
                              {item.releaseAttemptId ? ` / attempt ${String(item.releaseAttemptId).slice(0, 8)}` : ''}
                            </span>
                          ))}
                        </>
                      )}
                      {(readinessPatchExecutionGate.requiredBeforeEnablement || []).slice(0, 5).map((item) => (
                        <span key={item}>{item}</span>
                      ))}
                    </div>
                  )}
                  {(readinessSnapshot || readinessSnapshotManifestCheck || readinessRollbackPreconditionsCheck) && (
                    <div className="failure-item">
                      <strong>Snapshot readiness: {readinessSnapshot?.status || (readinessSnapshotManifestCheck?.passed && readinessRollbackPreconditionsCheck?.passed ? 'observed' : 'blocked')}</strong>
                      {readinessSnapshot?.message && <span>{readinessSnapshot.message}</span>}
                      {formatObservationLinkage(readinessSnapshot) && <span>{formatObservationLinkage(readinessSnapshot)}</span>}
                      {readinessSnapshot && (
                        <span>
                          snapshot created: {String(readinessSnapshot.snapshotCreated)}
                          {readinessSnapshot.manifestCreated !== undefined ? ` / manifest created: ${String(readinessSnapshot.manifestCreated)}` : ''}
                          {readinessSnapshot.writesPlanned !== undefined ? ` / writes planned: ${String(readinessSnapshot.writesPlanned)}` : ''}
                          {readinessSnapshot.writesCompleted !== undefined ? ` / writes completed: ${String(readinessSnapshot.writesCompleted)}` : ''}
                        </span>
                      )}
                      {readinessSnapshot?.relativeManifestPath && (
                        <span>
                          manifest: {readinessSnapshot.manifestId || '(snapshot)'} / {readinessSnapshot.relativeManifestPath}
                          {readinessSnapshot.fileCount !== undefined ? ` / files ${readinessSnapshot.fileCount}` : ''}
                        </span>
                      )}
                      {readinessSnapshotManifestCheck && <span>{formatReadinessCheck(readinessSnapshotManifestCheck)}</span>}
                      {readinessRollbackPreconditionsCheck && <span>{formatReadinessCheck(readinessRollbackPreconditionsCheck)}</span>}
                      {dryRunSnapshotObservation?.manifestPreview && (
                        <span>
                          latest dry-run manifest: {dryRunSnapshotObservation.manifestPreview.id || '(preview)'}
                          {dryRunSnapshotObservation.manifestPreview.relativeManifestPath ? ` / ${dryRunSnapshotObservation.manifestPreview.relativeManifestPath}` : ''}
                          {dryRunSnapshotObservation.manifestPreview.created !== undefined ? ` / manifest created: ${String(dryRunSnapshotObservation.manifestPreview.created)}` : ''}
                          {dryRunSnapshotObservation.manifestPreview.writesPlanned !== undefined ? ` / writes planned: ${String(dryRunSnapshotObservation.manifestPreview.writesPlanned)}` : ''}
                          {dryRunSnapshotObservation.manifestPreview.writesCompleted !== undefined ? ` / writes completed: ${String(dryRunSnapshotObservation.manifestPreview.writesCompleted)}` : ''}
                        </span>
                      )}
                      {!dryRunSnapshotObservation?.manifestPreview && <span>Queue and refresh a Local Agent dry-run to provide snapshot manifest evidence.</span>}
                    </div>
                  )}
                  {readinessRollback && (
                    <div className="failure-item">
                      <strong>Rollback manifest readiness: {readinessRollback.status || 'UNKNOWN'}</strong>
                      {readinessRollback.message && <span>{readinessRollback.message}</span>}
                      {formatObservationLinkage(readinessRollback) && <span>{formatObservationLinkage(readinessRollback)}</span>}
                      <span>
                        blocking release: {String(readinessRollback.blocking)}
                        {readinessRollback.fileCount !== undefined ? ` / files ${readinessRollback.fileCount}` : ''}
                        {readinessRollback.requiresUserApproval !== undefined ? ` / user approval: ${String(readinessRollback.requiresUserApproval)}` : ''}
                      </span>
                      {(readinessRollback.fileChecks || []).slice(0, 5).map((check) => (
                        <span key={`${check.path}-${check.snapshotRelativePath}`}>
                          {check.path || '(target)'} {'->'} {check.snapshotRelativePath || '(snapshot)'}
                          {check.targetPathSafe !== undefined ? ` / target safe: ${String(check.targetPathSafe)}` : ''}
                          {check.snapshotPathSafe !== undefined ? ` / snapshot safe: ${String(check.snapshotPathSafe)}` : ''}
                        </span>
                      ))}
                      {(readinessRollback.fileChecks || []).length > 5 && <span>{readinessRollback.fileChecks.length - 5} more rollback file checks hidden</span>}
                    </div>
                  )}
                  {visibleReadinessChecks.map((check) => (
                    <div className="failure-item" key={check.key}>
                      <strong>{check.passed ? 'pass' : 'blocked'} · {check.key}</strong>
                      <span>{check.message}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
          <div className="action-row" hidden>
            <button type="button" disabled={!canApply} onClick={onApply}>
              {loading('code-agent-apply') ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
              적용
            </button>
            <button type="button" className="ghost-button" disabled={!applyResult?.patchSessionId || loading('code-agent-test-backend-test')} onClick={() => onTest('backend-test')}>
              {loading('code-agent-test-backend-test') ? <Loader2 className="spin" size={16} /> : <Play size={16} />}
              backend test
            </button>
            <button type="button" className="ghost-button" disabled={!applyResult?.patchSessionId || loading('code-agent-test-frontend-build')} onClick={() => onTest('frontend-build')}>
              {loading('code-agent-test-frontend-build') ? <Loader2 className="spin" size={16} /> : <Play size={16} />}
              frontend build
            </button>
            <button type="button" className="ghost-button" disabled={!canRollback} onClick={onRollback}>
              {loading('code-agent-rollback') ? <Loader2 className="spin" size={16} /> : <RotateCcw size={16} />}
              rollback
            </button>
          </div>
          {applyResult && (
            <div className="code-agent-result compact-result">
              <div className="result-heading">
                <strong>{applyResult.applied ? '적용 완료' : '적용 실패'}</strong>
                <Badge variant={applyResult.applied ? 'outline' : 'destructive'}>{applyResult.patchSessionId || 'no-session'}</Badge>
              </div>
              {!!applyResult.changedFiles?.length && <small>{applyResult.changedFiles.join(', ')}</small>}
              <WarningList warnings={applyResult.warnings} />
              {applyResult.rollback && (
                <small>{applyResult.rollback.rolledBack ? `rollback 완료: ${applyResult.rollback.restoredFiles?.join(', ') || ''}` : 'rollback 실패'}</small>
              )}
            </div>
          )}
          {testResult && (
            <div className="code-agent-result compact-result">
              <div className="result-heading">
                <strong>{testResult.commandKey}</strong>
                <Badge variant={testResult.exitCode === 0 ? 'outline' : 'destructive'}>{testResult.allowed ? `exit ${testResult.exitCode}` : 'blocked'}</Badge>
              </div>
              <WarningList warnings={testResult.warnings} />
              {testResult.summary && <pre><code>{testResult.summary}</code></pre>}
            </div>
          )}
        </div>
      )}
    </section>
  );
}

function WarningList({ warnings = [] }) {
  if (!warnings.length) return null;
  return (
    <div className="failure-list">
      {warnings.map((warning, index) => (
        <div className="failure-item" key={`${index}-${warning}`}>
          <strong>Warning</strong>
          <span>{warning}</span>
        </div>
      ))}
    </div>
  );
}

function ConversationInlineActions({
  activeConversationId = '',
  turnCount = 0,
  loading = () => false,
  loadingKey = 'conversations',
  onRefresh = () => {},
  onNew = () => {},
}) {
  return (
    <div className="rag-conversation-inline-actions">
      <button className="ghost-button compact-action" type="button" onClick={onNew}>+ 새 대화</button>
      <button className="ghost-button compact-action" type="button" disabled={loading(loadingKey)} onClick={onRefresh}>
        {loading(loadingKey) ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
        새로고침
      </button>
      {activeConversationId && <span>현재 {turnCount}턴</span>}
    </div>
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
                <span>토큰을 암호화해 저장하고 다음 인덱싱에 사용</span>
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
            <h2>ZIP 코드 업로드</h2>
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
            <label htmlFor="replace-zip-file">새 ZIP 파일</label>
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
        {getStatusLabel(job.status)} {'·'} {job.processedFiles}/{job.totalFiles || '-'} files {'·'} {job.totalChunks} chunks
        {job.failedFiles > 0 ? ` · ${'실패'} ${job.failedFiles}` : ''}
      </span>
      {jobChangeText(job) && <small className="job-change-line">{jobChangeText(job)}</small>}
      <EnrichmentStatusLine job={job} />
      <div className="progress-track" aria-label={'인덱싱 진행률'}>
        <span style={{ width: `${jobPercent(job)}%` }} />
      </div>
      {job.errorMessage && <div className="failure-line"><AlertTriangle size={14} />{job.errorMessage}</div>}
      {canShowFailures && (
        <button className="ghost-button compact-action" type="button" onClick={(event) => { event.stopPropagation(); loadFailures(repoId, job.id); }}>
          {loading ? <Loader2 className="spin" size={14} /> : <Eye size={14} />}
          {'실패 사유'}
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
            files {diagnostic.analyzedFiles}/{diagnostic.attemptedFiles} · relations {diagnostic.resolvedRelations}
            {diagnostic.unresolvedRelations > 0 ? ` · unresolved ${diagnostic.unresolvedRelations}` : ''}
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
    return <p className="empty compact-empty">{'기록된 파일별 실패 사유가 없습니다. 저장소 수준 오류 메시지를 확인하세요.'}</p>;
  }
  return (
    <div className="failure-list">
      {failures.map((failure) => (
        <div className="failure-item" key={failure.id}>
          <strong>{failure.filePath || 'repository'}</strong>
          <small>{failure.stage} {'·'} {formatDate(failure.createdAt)}</small>
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
    PENDING: '대기',
    RUNNING: '실행 중',
    RETRYING: '재시도 예정',
    SUCCEEDED: '완료',
    FAILED: '실패',
    SKIPPED: '건너뜀',
    NOT_STARTED: '',
  };
  return labels[status] ?? status ?? '';
}

function RepositorySelect({ repositories, selectedRepository, selectedRepositoryId, setSelectedRepositoryId }) {
  const repositoryMeta = selectedRepository
    ? `${selectedRepository.name}${selectedRepository.lastIndexedCommit ? ` · commit ${selectedRepository.lastIndexedCommit.slice(0, 12)}` : ''}`
    : '전체 저장소';
  return (
    <div className="stack">
      <label className="rag-repo-label-row" htmlFor="repo-select">
        <span>질문 대상</span>
        <small className="rag-repo-inline-meta">{repositoryMeta}</small>
      </label>
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
  if (!evidence.length) return <p className="empty compact-empty">{'표시할 코드 근거가 없습니다.'}</p>;
  const groupedEvidence = groupCodeEvidence(evidence);
  const visibleEvidence = expanded ? groupedEvidence : groupedEvidence.slice(0, evidencePreviewLimit);
  const hiddenCount = Math.max(groupedEvidence.length - visibleEvidence.length, 0);
  return (
    <div className={expanded ? 'evidence-section evidence-section-expanded' : 'evidence-section'}>
      <div className="evidence-header">
        <strong>{'코드 근거'}</strong>
        <small>{visibleEvidence.length}/{groupedEvidence.length}{'개 파일 표시'}</small>
      </div>
      <div className="evidence-list">
        {visibleEvidence.map((group) => {
          const item = group.primary;
          const canOpen = Boolean(item.repositoryId && item.fileId);
          const primaryRange = codeEvidenceRange(item);
          const groupRanges = codeEvidenceRanges(group.items);
          const openRanges = groupRanges.length ? groupRanges : primaryRange;
          const metaText = group.items.length > 1
            ? `${group.items.length}개 근거 · ${group.locationSummary}`
            : codeEvidenceMetaText(item);
          return (
            <article className="evidence-card code-evidence" key={group.evidenceKey}>
              <div className="result-heading">
                <strong title={item.filePath}>[{group.citationNumbers.join(', ')}] {item.filePath}</strong>
                {canOpen && (
                  <button className="ghost-button compact-action" type="button" onClick={() => onOpenEvidence?.(item.repositoryId, item.fileId, openRanges)}>
                    <Eye size={14} />
                    {'열기'}
                  </button>
                )}
              </div>
              <small>{metaText}</small>
              <p>{item.preview}</p>
              {group.items.length > 1 && (
                <div className="code-evidence-locations">
                  {group.items.map((part) => {
                    const partRange = codeEvidenceRange(part);
                    return (
                      <button
                        className="ghost-button compact-action"
                        disabled={!part.repositoryId || !part.fileId}
                        key={`${part.citationNumber}-${part.chunkId || part.lineStart || part.metadata?.changeType || 'part'}`}
                        type="button"
                        onClick={() => onOpenEvidence?.(part.repositoryId, part.fileId, partRange)}
                      >
                        [{part.citationNumber}] {codeEvidenceMetaText(part)}
                      </button>
                    );
                  })}
                </div>
              )}
            </article>
          );
        })}
      </div>
      {groupedEvidence.length > evidencePreviewLimit && (
        <button className="ghost-button compact-action evidence-toggle" type="button" onClick={() => setExpanded((current) => !current)}>
          {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
          {expanded ? '핵심 근거만 보기' : `전체 근거 파일 ${groupedEvidence.length}개 보기`}
          {!expanded && hiddenCount > 0 ? <span>+{hiddenCount}</span> : null}
        </button>
      )}
    </div>
  );
}

function groupCodeEvidence(evidence = []) {
  const grouped = new Map();
  evidence.forEach((item, index) => {
    const key = codeEvidenceGroupKey(item, index);
    const current = grouped.get(key);
    if (!current) {
      grouped.set(key, {
        evidenceKey: key,
        primary: item,
        items: [item],
        citationNumbers: [item.citationNumber],
        locationSummary: codeEvidenceLocationSummary([item]),
      });
      return;
    }
    current.items.push(item);
    if (!current.citationNumbers.includes(item.citationNumber)) {
      current.citationNumbers.push(item.citationNumber);
    }
    if (Number(item.score || 0) > Number(current.primary.score || 0)) {
      current.primary = item;
    }
    current.locationSummary = codeEvidenceLocationSummary(current.items);
  });
  return Array.from(grouped.values());
}

function codeEvidenceGroupKey(item = {}, index = 0) {
  if (item.repositoryId && item.fileId) return `${item.repositoryId}:${item.fileId}`;
  if (item.repositoryName || item.filePath) return `${item.repositoryName || ''}:${item.filePath || ''}`;
  return item.chunkId || `code-evidence-${index}`;
}

function codeEvidenceRange(item = {}) {
  return item.lineStart > 0
    ? { start: item.lineStart, end: item.lineEnd || item.lineStart }
    : null;
}

function codeEvidenceRanges(items = []) {
  const seen = new Set();
  return items
    .map(codeEvidenceRange)
    .filter(Boolean)
    .filter((range) => {
      const key = `${range.start}-${range.end}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .sort((left, right) => left.start - right.start || left.end - right.end);
}

function codeEvidenceMetaText(item = {}) {
  const isCommitDiff = item.metadata?.kind === 'commit_diff';
  if (isCommitDiff) {
    return `${item.metadata?.changeType || item.chunkType} · +${item.metadata?.insertions ?? 0}/-${item.metadata?.deletions ?? 0}`;
  }
  const location = item.lineStart > 0 ? `${item.lineStart}-${item.lineEnd || item.lineStart}` : 'lines -';
  return `${location} · ${item.chunkType || 'code'}`;
}

function codeEvidenceLocationSummary(items = []) {
  const values = items
    .map((item) => item.lineStart > 0 ? `${item.lineStart}-${item.lineEnd || item.lineStart}` : item.metadata?.changeType || item.chunkType)
    .filter(Boolean);
  return values.slice(0, 4).join(', ') + (values.length > 4 ? ` +${values.length - 4}` : '');
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
          {'열기'}
        </Button>
      ),
    },
  ];
  return (
    <DataTable
      className="code-search-table"
      columns={columns}
      data={results}
      empty={'코드 검색 결과가 없습니다.'}
      onRowClick={(item) => onOpenEvidence?.(item.repositoryId, item.fileId, { start: item.lineStart, end: item.lineEnd })}
    />
  );
}

function CodeReferenceResults({ result, onOpenEvidence }) {
  return (
    <div className="reference-results">
      <ReferenceGroup title={'정의'} items={result.definitions || []} onOpenEvidence={onOpenEvidence} />
      <ReferenceGroup title={'참조'} items={result.references || []} onOpenEvidence={onOpenEvidence} />
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
              {'열기'}
            </button>
          </div>
          <small>{item.lineStart}-{item.lineEnd} {'·'} {item.chunkType}</small>
          <p>{item.content}</p>
        </article>
      ))}
      {!items.length && <p className="empty compact-empty">{'결과 없음'}</p>}
    </div>
  );
}

function CodeFileModal({ detail, highlightRange, loading, onClose }) {
  const highlightedLineRef = useRef(null);
  const lines = detail?.content ? detail.content.split(/\r?\n/) : [];
  const highlightRanges = normalizeHighlightRanges(highlightRange);
  const firstHighlightRange = highlightRanges[0] || null;
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
    if (loading || !firstHighlightRange?.start || !highlightedLineRef.current) return undefined;
    const timer = window.setTimeout(() => {
      highlightedLineRef.current?.scrollIntoView({ block: 'center' });
    }, 80);
    return () => window.clearTimeout(timer);
  }, [detail?.id, firstHighlightRange?.start, firstHighlightRange?.end, loading]);

  const modal = (
    <div className="code-modal-backdrop source-modal-portal-backdrop" role="presentation" onMouseDown={() => onClose?.()}>
      <section className="code-modal" role="dialog" aria-modal="true" aria-labelledby="code-modal-title" onMouseDown={(event) => event.stopPropagation()}>
        <header className="code-modal-header">
          <div className="code-modal-title">
            <FileCode2 size={18} />
            <div>
              <h2 id="code-modal-title">{fileName}</h2>
              <p>{detail?.filePath || '코드 파일을 불러오는 중입니다.'}</p>
            </div>
          </div>
          <button className="icon-button code-modal-close" type="button" title={'닫기'} onClick={() => onClose?.()}>
            <X size={18} />
          </button>
        </header>

        <div className="code-modal-tabs" aria-hidden="true">
          <span className="active-tab">{fileName}</span>
          <span>{language}</span>
          {highlightRanges.length > 0 && <span>{highlightRangeLabel(highlightRanges)}</span>}
        </div>

        <div className="code-modal-body">
          {loading && (
            <div className="code-modal-state">
              <Loader2 className="spin" size={22} />
              <strong>{'코드 파일을 불러오는 중입니다.'}</strong>
            </div>
          )}

          {!loading && !detail && (
            <div className="code-modal-state">
              <FileCode2 size={22} />
              <strong>{'표시할 코드가 없습니다.'}</strong>
            </div>
          )}

          {!loading && detail && (
            <pre className="ide-code-viewer">
              <code>
                {lines.map((line, index) => {
                  const lineNumber = index + 1;
                  const highlighted = isLineHighlighted(lineNumber, highlightRanges);
                  return (
                    <div
                      className={highlighted ? 'ide-code-line highlighted-line' : 'ide-code-line'}
                      key={lineNumber}
                      ref={lineNumber === firstHighlightRange?.start ? highlightedLineRef : null}
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

  if (typeof document === 'undefined') return modal;
  return createPortal(modal, document.body);
}

function CodeFileViewer({ detail, highlightRange, loading }) {
  if (loading) {
    return (
      <section className="panel">
        <div className="panel-title">
          <FileCode2 size={18} />
          <div>
            <h2>{'코드 미리보기'}</h2>
            <p>{'파일을 불러오는 중입니다.'}</p>
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
            <h2>{'코드 미리보기'}</h2>
            <p>{'파일이나 근거를 선택하면 원문 코드를 확인할 수 있습니다.'}</p>
          </div>
        </div>
      </section>
    );
  }
  const lines = detail.content.split(/\r?\n/);
  const highlightRanges = normalizeHighlightRanges(highlightRange);
  return (
    <section className="panel">
      <div className="panel-title">
        <FileCode2 size={18} />
        <div>
          <h2>{detail.filePath}</h2>
          <p>{detail.language} {'·'} {detail.chunks?.length || 0} chunks</p>
        </div>
      </div>
      <pre className="code-viewer">
        <code>
          {lines.map((line, index) => {
            const lineNumber = index + 1;
            const highlighted = isLineHighlighted(lineNumber, highlightRanges);
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

function normalizeHighlightRanges(highlightRange) {
  const ranges = Array.isArray(highlightRange) ? highlightRange : highlightRange ? [highlightRange] : [];
  return ranges
    .map((range) => ({
      start: Number(range?.start || 0),
      end: Number(range?.end || range?.start || 0),
    }))
    .filter((range) => range.start > 0 && range.end >= range.start)
    .sort((left, right) => left.start - right.start || left.end - right.end);
}

function isLineHighlighted(lineNumber, ranges = []) {
  return ranges.some((range) => lineNumber >= range.start && lineNumber <= range.end);
}

function highlightRangeLabel(ranges = []) {
  if (!ranges.length) return '';
  if (ranges.length === 1) return `lines ${ranges[0].start}-${ranges[0].end}`;
  return `${ranges.length} ranges · ${ranges.slice(0, 3).map((range) => `${range.start}-${range.end}`).join(', ')}${ranges.length > 3 ? ` +${ranges.length - 3}` : ''}`;
}

export { CodeSourceManagementPanel, CodeWorkspace };





import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Eye, FileArchive, FileCode2, GitBranch, Info, Loader2, Play, RefreshCw, RotateCcw, Search, ShieldCheck, Trash2, X } from 'lucide-react';
import { codeModes } from '../../config/constants.js';
import { formatDate, getCodeModeGuide, getCodeModeLabel, getStatusLabel, jobChangeText, jobPercent, submitFormOnShortcut } from '../../lib/formatters.js';
import { useStreamingAutoScroll } from '../../lib/useStreamingAutoScroll.js';
import { IconButton, ModeControl, StatusBadge } from '../common/Common.jsx';
import { RagChatPanel } from '../common/RagChatPanel.jsx';
import { MarkdownAnswer } from '../markdown/MarkdownAnswer.jsx';
import { Badge } from '../ui/badge.jsx';
import { CodeEvidenceList, CodeReferenceResults, CodeSearchResults } from './CodeEvidencePanels.jsx';
import { CodeFileModal } from './CodeFilePanels.jsx';
import { buildMutationExecutionReadinessBoundaryView } from './mutationExecutionReadinessBoundary.js';
import { buildMutationHandoffSummaryView } from './mutationHandoffSummary.js';
import { buildMutationResultIntakeBoundaryView } from './mutationResultIntakeBoundary.js';
import { buildMutationResultCompletionBoundaryView } from './mutationResultCompletionBoundary.js';
import { buildMutationResultIntakePersistenceGateView } from './mutationResultIntakePersistenceGate.js';
import { buildMutationRollbackFallbackGateView } from './mutationRollbackFallbackGate.js';
import { buildMutationRagFreshnessGateView } from './mutationRagFreshnessGate.js';
import { buildAcceptedMutationObservationSummaryText } from './mutationObservationSummary.js';
import { buildMutationResultAggregationGateView } from './mutationResultAggregationGate.js';
import { buildMutationFinalReportDraftView } from './mutationFinalReportDraft.js';
import { buildMutationPublicationGateView } from './mutationPublicationGate.js';
import { buildMutationFinalAnswerGenerationGateView } from './mutationFinalAnswerGenerationGate.js';
import { buildMutationFinalAnswerCompletionGateView } from './mutationFinalAnswerCompletionGate.js';
import { buildMutationFinalAnswerPersistenceGateView } from './mutationFinalAnswerPersistenceGate.js';
import { buildMutationFinalAnswerConversationSaveGateView } from './mutationFinalAnswerConversationSaveGate.js';
import { buildMutationFinalAnswerUserVisibleCompletionGateView } from './mutationFinalAnswerUserVisibleCompletionGate.js';
import { buildMutationFinalResponseHandoffGateView } from './mutationFinalResponseHandoffGate.js';
import { buildMutationFinalAnswerDeliveryGateView } from './mutationFinalAnswerDeliveryGate.js';
import { buildMutationFinalAnswerDeliveryReceiptGateView } from './mutationFinalAnswerDeliveryReceiptGate.js';
import { buildMutationCompletionSummaryView } from './mutationCompletionSummary.js';
import { buildMutationDispatchEnvelopeContractView } from './mutationDispatchEnvelopeContract.js';
import { buildMutationDispatchPreflightBoundaryView } from './mutationDispatchPreflightBoundary.js';
import { buildMutationDispatchDecisionModelView } from './mutationDispatchDecisionModel.js';
import { buildMutationRequestBlueprintView } from './mutationRequestBlueprint.js';
import { buildMutationRequestCreationGateView } from './mutationRequestCreationGate.js';
import { buildMutationRequestPushGateView } from './mutationRequestPushGate.js';
import { buildMutationRequestClaimGateView } from './mutationRequestClaimGate.js';
import { buildMutationExecutionGateView } from './mutationExecutionGate.js';
import { buildMutationWriteHelperSafetyGateView } from './mutationWriteHelperSafetyGate.js';
import { buildMutationPostExecutionObservationGateView } from './mutationPostExecutionObservationGate.js';
import { buildMutationObservationAcceptanceGateView } from './mutationObservationAcceptanceGate.js';
import { buildMutationToolRunnerBoundaryView } from './mutationToolRunnerBoundary.js';
import { buildReleaseAttemptModelSummaryView } from './releaseAttemptModelSummary.js';
import { buildFreshObservationRequestPlanView } from './freshObservationRequestPlan.js';
import { buildFreshObservationEnqueueBoundaryView } from './freshObservationEnqueueBoundary.js';
import { buildFreshObservationEvidenceSummaryView } from './freshObservationEvidenceSummary.js';
import { buildReleaseAttemptDisplaySummaryView } from './releaseAttemptDisplaySummary.js';
import { buildPatchExecutionGateSummaryView } from './patchExecutionGateSummary.js';
import { buildPatchReleaseReadinessSummaryView } from './patchReleaseReadinessSummary.js';
import { buildRepositoryVerificationSummaryView } from './repositoryVerificationSummary.js';
import { buildWorkspaceVerificationSummaryView } from './workspaceVerificationSummary.js';
import { buildSnapshotReadinessSummaryView } from './snapshotReadinessSummary.js';
import { buildRollbackReadinessSummaryView } from './rollbackReadinessSummary.js';
import { buildReadinessChecksSummaryView } from './readinessChecksSummary.js';
import { buildDryRunRollbackObservationSummaryView } from './dryRunRollbackObservationSummary.js';
import { buildDryRunSnapshotObservationSummaryView } from './dryRunSnapshotObservationSummary.js';
import { buildDryRunResultSummaryView } from './dryRunResultSummary.js';
import { buildDryRunPatchFilesSummaryView } from './dryRunPatchFilesSummary.js';
import { buildAgentLoopPreviewSummaryView } from './agentLoopPreviewSummary.js';
import { buildAgentLoopTimelineHistoryView } from './agentLoopTimelineSummary.js';
import { buildAgentLoopRunnerHandoffSummaryView } from './agentLoopRunnerHandoffSummary.js';
import { buildApprovedExecutionFlowInspectionView } from './approvedExecutionFlowInspectionSummary.js';

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
    codeAgentLoopPreview,
    codeAgentLoopTimelines = [],
    codeAgentLoopRunnerPreview,
    codeAgentLoopRunnerToolSelectionPreview,
    codeAgentLoopRunnerEnqueueResult,
    codeAgentLoopRunnerReleaseReviewResult,
    codeAgentLoopRunnerFinalResultPublicationPreview,
    codeAgentLoopRunnerM8EntryReadiness,
    codeAgentLoopRunnerQueuedObservationResult,
    codeAgentLoopRunnerObservationContinuation,
    codeAgentLocalPatchRequest,
    codeAgentLocalPatchReadiness,
    codeAgentLocalPatchDryRunRequest,
    codeAgentLocalPatchDryRunResult,
    codeAgentLocalRepositoryObservationRequest,
    codeAgentLocalRepositoryObservationResult,
    codeAgentApprovedExecutionFlowInspection,
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
    previewCodeAgentLoop = () => {},
    previewCodeAgentLoopRunner = () => {},
    previewCodeAgentLoopRunnerToolSelection = () => {},
    enqueueCodeAgentLoopRunnerReadOnly = () => {},
    reviewCodeAgentLoopRunnerReleaseGate = () => {},
    previewCodeAgentLoopRunnerFinalResultPublication = () => {},
    previewCodeAgentLoopRunnerM8EntryReadiness = () => {},
    enqueueCodeAgentLoopRunnerSelectedReadOnly = () => {},
    refreshCodeAgentLoopRunnerQueuedObservation = () => {},
    refreshCodeAgentLoopTimelines = () => {},
    generateCodeAgentPatch = () => {},
    prepareCodeAgentLocalPatchRequest = () => {},
    decideCodeAgentLocalPatchApproval = () => {},
    refreshCodeAgentLocalPatchReadiness = () => {},
    queueCodeAgentLocalPatchDryRun = () => {},
    queueCodeAgentReleaseFreshObservations = () => {},
    releaseCodeAgentLocalPatchForExecution = () => {},
    refreshCodeAgentLocalPatchDryRunResult = () => {},
    queueCodeAgentLocalRepositoryObservation = () => {},
    refreshCodeAgentLocalRepositoryObservationResult = () => {},
    inspectCodeAgentApprovedExecutionFlow = () => {},
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
          loopPreview={codeAgentLoopPreview}
          loopTimelines={codeAgentLoopTimelines}
          loopRunnerPreview={codeAgentLoopRunnerPreview}
          loopRunnerToolSelectionPreview={codeAgentLoopRunnerToolSelectionPreview}
          loopRunnerEnqueueResult={codeAgentLoopRunnerEnqueueResult}
          loopRunnerReleaseReviewResult={codeAgentLoopRunnerReleaseReviewResult}
          loopRunnerFinalResultPublicationPreview={codeAgentLoopRunnerFinalResultPublicationPreview}
          loopRunnerM8EntryReadiness={codeAgentLoopRunnerM8EntryReadiness}
          loopRunnerQueuedObservationResult={codeAgentLoopRunnerQueuedObservationResult}
          loopRunnerObservationContinuation={codeAgentLoopRunnerObservationContinuation}
          localPatchRequest={codeAgentLocalPatchRequest}
          localPatchReadiness={codeAgentLocalPatchReadiness}
          localPatchDryRunRequest={codeAgentLocalPatchDryRunRequest}
          localPatchDryRunResult={codeAgentLocalPatchDryRunResult}
          localRepositoryObservationRequest={codeAgentLocalRepositoryObservationRequest}
          localRepositoryObservationResult={codeAgentLocalRepositoryObservationResult}
          approvedExecutionFlowInspection={codeAgentApprovedExecutionFlowInspection}
          localAgentStatus={localAgentStatus}
          localAgentTokens={props.localAgentTokens}
          selectedRepositoryId={selectedRepositoryId}
          loading={loading}
          onPlan={generateCodeAgentPlan}
          onLoopPreview={previewCodeAgentLoop}
          onLoopRunnerPreview={previewCodeAgentLoopRunner}
          onLoopRunnerToolSelectionPreview={previewCodeAgentLoopRunnerToolSelection}
          onLoopRunnerEnqueueReadOnly={enqueueCodeAgentLoopRunnerReadOnly}
          onLoopRunnerReleaseReview={reviewCodeAgentLoopRunnerReleaseGate}
          onLoopRunnerFinalResultPublicationPreview={previewCodeAgentLoopRunnerFinalResultPublication}
          onLoopRunnerM8EntryReadiness={previewCodeAgentLoopRunnerM8EntryReadiness}
          onLoopRunnerEnqueueSelectedReadOnly={enqueueCodeAgentLoopRunnerSelectedReadOnly}
          onRefreshLoopRunnerQueuedObservation={refreshCodeAgentLoopRunnerQueuedObservation}
          onRefreshLoopTimelines={refreshCodeAgentLoopTimelines}
          onPatch={generateCodeAgentPatch}
          onPrepareLocalPatchRequest={prepareCodeAgentLocalPatchRequest}
          onLocalPatchApproval={decideCodeAgentLocalPatchApproval}
          onRefreshLocalPatchReadiness={refreshCodeAgentLocalPatchReadiness}
          onQueueLocalPatchDryRun={queueCodeAgentLocalPatchDryRun}
          onQueueReleaseFreshObservations={queueCodeAgentReleaseFreshObservations}
          onReleaseLocalPatchForExecution={releaseCodeAgentLocalPatchForExecution}
          onRefreshLocalPatchDryRunResult={refreshCodeAgentLocalPatchDryRunResult}
          onQueueLocalRepositoryObservation={queueCodeAgentLocalRepositoryObservation}
          onRefreshLocalRepositoryObservationResult={refreshCodeAgentLocalRepositoryObservationResult}
          onInspectApprovedExecutionFlow={inspectCodeAgentApprovedExecutionFlow}
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

function findReadinessCheck(readiness, key) {
  return (readiness?.checks || []).find((check) => check.key === key) || null;
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

function buildAgentLoopRunnerToolSelectionPreviewView(preview) {
  if (!preview) {
    return null;
  }
  const candidate = preview.candidate || preview.selection?.candidate || {};
  const modelDecision = preview.modelDecision || {};
  const toolName = candidate.toolName || modelDecision.toolName || 'unknown';
  const accepted = Boolean(preview.modelToolSelectionAccepted);
  const selectedByModel = Boolean(preview.selectedByModel);
  const label = selectedByModel ? 'model selected' : 'deterministic fallback';
  return {
    headerText: `agent loop runner model tool preview: ${preview.actionKey || 'unknown'} / ${preview.selectionDecision || preview.runnerDecision || 'unknown'} / ${label}`,
    decisionText: `agent loop runner model decision: attempted ${Boolean(preview.modelToolSelectionAttempted)} / accepted ${accepted} / tool ${toolName} / read-only ${Boolean(candidate.readOnly ?? modelDecision.readOnly)} / approval ${candidate.approvalState || (modelDecision.requiresApproval ? 'REQUIRED' : 'NOT_REQUIRED')} / mutation ${Boolean(candidate.mutationAllowed ?? modelDecision.mutationAllowed)}`,
    controlsText: `agent loop runner model tool controls: request creation ${Boolean(preview.requestCreationEnabled)} / enqueue ${Boolean(preview.enqueueEnabled)} / push ${Boolean(preview.pushEnabled)} / claim ${Boolean(preview.claimEnabled)} / final result ${Boolean(preview.finalResultEnabled)} / publication ${Boolean(preview.publicationEnabled)} / acknowledgement ${Boolean(preview.acknowledgementEnabled)} / mutation ${Boolean(preview.mutationEnabled)}`,
    reasonText: preview.reason || modelDecision.reason || '',
  };
}

function buildAgentLoopRunnerObservationContinuationView(continuation) {
  if (!continuation) {
    return null;
  }
  const selection = continuation.toolSelectionPreview || {};
  return {
    headerText: `agent loop runner observation continuation: ${continuation.status || 'UNKNOWN'} / ${continuation.continuationDecision || 'unknown'}`,
    budgetText: `agent loop runner observation continuation budget: iteration ${continuation.iterationCount ?? 0} / max ${continuation.maxIterations ?? 0} / remaining ${continuation.remainingIterations ?? 0} / limit reached ${Boolean(continuation.iterationLimitReached)}`,
    controlsText: `agent loop runner observation continuation controls: request creation ${Boolean(continuation.requestCreationEnabled)} / enqueue ${Boolean(continuation.enqueueEnabled)} / push ${Boolean(continuation.pushEnabled)} / claim ${Boolean(continuation.claimEnabled)} / final result ${Boolean(continuation.finalResultEnabled)} / publication ${Boolean(continuation.publicationEnabled)} / acknowledgement ${Boolean(continuation.acknowledgementEnabled)} / mutation ${Boolean(continuation.mutationEnabled)}`,
    nextPreviewText: selection.selectionDecision
      ? `agent loop runner observation continuation next model preview: ${selection.actionKey || 'unknown'} / ${selection.selectionDecision} / tool ${selection.candidate?.toolName || selection.modelDecision?.toolName || 'unknown'} / mutation ${Boolean(selection.candidate?.mutationAllowed ?? selection.modelDecision?.mutationAllowed)}`
      : '',
    reasonText: continuation.reason || '',
  };
}

function CodeAgentPanel({
  instruction = '',
  setInstruction = () => {},
  plan,
  patch,
  applyResult,
  testResult,
  mutationPolicy,
  loopPreview,
  loopTimelines = [],
  loopRunnerPreview,
  loopRunnerToolSelectionPreview,
  loopRunnerEnqueueResult,
  loopRunnerReleaseReviewResult,
  loopRunnerFinalResultPublicationPreview,
  loopRunnerM8EntryReadiness,
  loopRunnerQueuedObservationResult,
  loopRunnerObservationContinuation,
  localPatchRequest,
  localPatchReadiness,
  localPatchDryRunRequest,
  localPatchDryRunResult,
  localRepositoryObservationRequest,
  localRepositoryObservationResult,
  approvedExecutionFlowInspection,
  localAgentStatus,
  localAgentTokens = [],
  selectedRepositoryId = '',
  loading = () => false,
  onPlan = (event) => event.preventDefault(),
  onLoopPreview = () => {},
  onLoopRunnerPreview = () => {},
  onLoopRunnerToolSelectionPreview = () => {},
  onLoopRunnerEnqueueReadOnly = () => {},
  onLoopRunnerReleaseReview = () => {},
  onLoopRunnerFinalResultPublicationPreview = () => {},
  onLoopRunnerM8EntryReadiness = () => {},
  onLoopRunnerEnqueueSelectedReadOnly = () => {},
  onRefreshLoopRunnerQueuedObservation = () => {},
  onRefreshLoopTimelines = () => {},
  onPatch = () => {},
  onPrepareLocalPatchRequest = () => {},
  onLocalPatchApproval = () => {},
  onRefreshLocalPatchReadiness = () => {},
  onQueueLocalPatchDryRun = () => {},
  onQueueReleaseFreshObservations = () => {},
  onReleaseLocalPatchForExecution = () => {},
  onRefreshLocalPatchDryRunResult = () => {},
  onQueueLocalRepositoryObservation = () => {},
  onRefreshLocalRepositoryObservationResult = () => {},
  onInspectApprovedExecutionFlow = () => {},
  onRefreshLocalAgent = () => {},
  onRefreshLocalAgentTokens = () => {},
  onRevokeLocalAgentToken = () => {},
  onApply = () => {},
  onRollback = () => {},
  onTest = () => {},
}) {
  const targetFiles = plan?.targetFiles || [];
  const canPlan = Boolean(selectedRepositoryId && instruction.trim()) && !loading('code-agent-plan');
  const canPreviewLoop = Boolean(selectedRepositoryId && instruction.trim()) && !loading('code-agent-loop-preview');
  const canPreviewRunner = Boolean(selectedRepositoryId && loopPreview?.loopId) && !loading('code-agent-loop-runner-preview');
  const canCheckRunnerEnqueueRefusal = Boolean(
    selectedRepositoryId
    && loopPreview?.loopId
    && (
      loopRunnerPreview?.recommendedAction?.actionKey === 'CHECK_ENQUEUE_REFUSAL'
      || loopRunnerPreview?.actionKey === 'READY_HANDOFF_CREATION_DISABLED'
      || loopRunnerPreview?.actionKey === 'WAIT_FOR_RELEASE_GATE'
      || loopRunnerPreview?.actionKey === 'WAIT_FOR_FRESH_OBSERVATION_RESULTS'
      || loopRunnerPreview?.actionKey === 'FRESH_EVIDENCE_COMPLETE_RELEASE_GATED'
      || loopRunnerPreview?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS'
      || loopRunnerPreview?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_OBSERVATION_RESULTS'
      || loopRunnerPreview?.runnerDecision === 'WAIT_RELEASE_GATE_FRESH_EVIDENCE_COMPLETE'
    )
  ) && !loading('code-agent-loop-runner-enqueue-read-only');
  const canReviewRunnerReleaseRefusal = Boolean(
    selectedRepositoryId
    && loopPreview?.loopId
    && (
      loopRunnerPreview?.recommendedAction?.actionKey === 'REVIEW_RELEASE_REFUSAL'
      || loopRunnerPreview?.actionKey === 'RELEASE_READINESS_REFRESHED_RELEASE_GATED'
      || loopRunnerPreview?.runnerDecision === 'WAIT_RELEASE_GATE_READINESS_REFRESHED'
    )
  ) && !loading('code-agent-loop-runner-release-review');
  const canEnqueueSelectedReadOnlyRunner = Boolean(
    selectedRepositoryId
    && loopPreview?.loopId
    && loopRunnerObservationContinuation?.iterationLimitReached !== true
    && (
      loopRunnerPreview?.recommendedAction?.actionKey === 'QUEUE_SELECTED_READ_ONLY'
      || loopRunnerPreview?.runnerDecision === 'PREPARED_READ_ONLY_CANDIDATE'
    )
    && ['git.status', 'git.diff'].includes(loopRunnerPreview?.candidate?.toolName)
    && loopRunnerPreview?.candidate?.approvalState === 'NOT_REQUIRED'
    && loopRunnerPreview?.candidate?.mutationAllowed === false
  ) && !loading('code-agent-loop-runner-enqueue-selected-read-only');
  const canPreviewFinalResultPublication = Boolean(
    selectedRepositoryId
    && loopPreview?.loopId
    && (
      loopRunnerPreview?.recommendedAction?.actionKey === 'STOP_AND_REPORT'
      || loopRunnerPreview?.actionKey === 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED'
      || loopRunnerPreview?.runnerDecision === 'READY_FINAL_RESULT_DISABLED'
    )
  ) && !loading('code-agent-loop-runner-final-result-publication-preview');
  const canPreviewM8EntryReadiness = Boolean(
    selectedRepositoryId
    && loopPreview?.loopId
    && (
      loopRunnerFinalResultPublicationPreview?.finalResultReady === true
      || loopRunnerPreview?.recommendedAction?.actionKey === 'STOP_AND_REPORT'
      || loopRunnerPreview?.actionKey === 'APPROVED_EXECUTION_FLOW_COMPLETED_FINAL_RESULT_DISABLED'
      || loopRunnerPreview?.runnerDecision === 'READY_FINAL_RESULT_DISABLED'
    )
  ) && !loading('code-agent-loop-runner-m8-entry-readiness');
  const canPreviewRunnerToolSelection = Boolean(
    selectedRepositoryId
    && loopPreview?.loopId
    && (
      loopRunnerPreview?.recommendedAction?.actionKey === 'QUEUE_SELECTED_READ_ONLY'
      || loopRunnerPreview?.runnerDecision === 'PREPARED_READ_ONLY_CANDIDATE'
    )
  ) && !loading('code-agent-loop-runner-tool-selection-preview');
  const runnerQueuedRequestId = loopRunnerEnqueueResult?.queuedRequest?.requestId;
  const canRefreshRunnerQueuedObservation = Boolean(runnerQueuedRequestId)
    && !loading(`code-agent-loop-runner-queued-observation-${runnerQueuedRequestId}`);
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
  const agentLoopPreviewSummaryView = buildAgentLoopPreviewSummaryView(loopPreview);
  const agentLoopTimelineHistoryView = buildAgentLoopTimelineHistoryView(loopTimelines);
  const agentLoopRunnerHandoffSummaryView = buildAgentLoopRunnerHandoffSummaryView(
    loopRunnerM8EntryReadiness || loopRunnerFinalResultPublicationPreview || loopRunnerReleaseReviewResult || loopRunnerEnqueueResult || loopRunnerPreview,
    loopRunnerQueuedObservationResult
  );
  const runnerToolSelectionPreviewView = buildAgentLoopRunnerToolSelectionPreviewView(loopRunnerToolSelectionPreview);
  const runnerObservationContinuationView = buildAgentLoopRunnerObservationContinuationView(loopRunnerObservationContinuation);
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
  const readinessRepositoryVerificationSummaryView = buildRepositoryVerificationSummaryView(readinessRepositoryVerification);
  const readinessWorkspaceVerification = visibleReadiness?.workspaceVerification || null;
  const readinessWorkspaceVerificationSummaryView = buildWorkspaceVerificationSummaryView(readinessWorkspaceVerification);
  const readinessPatchRelease = visibleReadiness?.patchReleaseReadiness || null;
  const readinessPatchReleaseSummaryView = buildPatchReleaseReadinessSummaryView(readinessPatchRelease);
  const canQueueReleaseFreshObservations = Boolean(
    localPatchRequest?.status === 'APPROVED_HELD'
    && visibleReadiness
    && readinessPatchRelease?.preconditionsPassed
    && !loading(`code-agent-local-release-fresh-observations-${localPatchRequest.requestId}`)
  );
  const readinessPatchExecutionGate = visibleReadiness?.patchExecutionGate || null;
  const readinessPatchExecutionGateSummaryView = buildPatchExecutionGateSummaryView(readinessPatchExecutionGate);
  const readinessReleaseAttemptModel = visibleReadiness?.releaseAttemptModel || readinessPatchExecutionGate?.releaseAttemptModel || null;
  const readinessFreshObservationRequestPlan = Array.isArray(readinessReleaseAttemptModel?.latestAttempt?.freshObservationRequestPlan)
    ? readinessReleaseAttemptModel.latestAttempt.freshObservationRequestPlan
    : [];
  const readinessFreshObservationRequestPlanView = buildFreshObservationRequestPlanView(readinessFreshObservationRequestPlan);
  const readinessFreshObservationEvidenceStatus = Array.isArray(readinessReleaseAttemptModel?.latestAttempt?.freshObservationEvidenceStatus)
    ? readinessReleaseAttemptModel.latestAttempt.freshObservationEvidenceStatus
    : [];
  const readinessFreshObservationEvidenceCompleteness = readinessReleaseAttemptModel?.latestAttempt?.freshObservationEvidenceCompleteness || null;
  const readinessReleaseAttemptModelSummaryView = buildReleaseAttemptModelSummaryView({
    preReleaseRevalidation: readinessPatchExecutionGate?.preReleaseRevalidation || null,
    releaseAttemptModel: readinessReleaseAttemptModel,
  });
  const readinessFreshObservationEvidenceSummaryView = buildFreshObservationEvidenceSummaryView({
    evidenceStatus: readinessFreshObservationEvidenceStatus,
    evidenceCompleteness: readinessFreshObservationEvidenceCompleteness,
  });
  const readinessReleaseAttemptFinalReadiness = readinessReleaseAttemptModel?.latestAttempt?.releaseAttemptFinalReadiness || null;
  const readinessReleaseAttemptDisplaySummary = readinessReleaseAttemptModel?.latestAttempt?.releaseAttemptDisplaySummary || null;
  const releaseGateEnabledCheck = Array.isArray(visibleReadiness?.checks)
    ? visibleReadiness.checks.find((check) => check.key === 'releaseGateEnabled')
    : null;
  const releaseForExecutionEnabled = Boolean(releaseGateEnabledCheck?.passed);
  const canReleaseLocalPatchForExecution = Boolean(
    localPatchRequest?.status === 'APPROVED_HELD'
    && visibleReadiness
    && releaseForExecutionEnabled
    && readinessReleaseAttemptFinalReadiness?.ready === true
    && !loading(`code-agent-local-release-for-execution-${localPatchRequest.requestId}`)
  );
  const readinessReleaseAttemptDisplaySummaryView = buildReleaseAttemptDisplaySummaryView({
    displaySummary: readinessReleaseAttemptDisplaySummary,
    evidenceCompleteness: readinessFreshObservationEvidenceCompleteness,
    finalReadiness: readinessReleaseAttemptFinalReadiness,
  });
  const readinessLocalAgentMutationExecutionSequencePlan = Array.isArray(readinessReleaseAttemptModel?.latestAttempt?.localAgentMutationExecutionSequencePlan)
    ? readinessReleaseAttemptModel.latestAttempt.localAgentMutationExecutionSequencePlan
    : [];
  const readinessPostMutationResultContract = readinessReleaseAttemptModel?.latestAttempt?.postMutationResultContract || null;
  const readinessPostMutationExpectedOutcomes = Array.isArray(readinessPostMutationResultContract?.expectedOutcomes)
    ? readinessPostMutationResultContract.expectedOutcomes
    : [];
  const readinessMutationResultIntakeBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.mutationResultIntakeBoundary || null;
  const readinessMutationResultIntakeBoundaryView = buildMutationResultIntakeBoundaryView(
    readinessMutationResultIntakeBoundary
  );
  const readinessMutationResultAggregationPlan =
    readinessReleaseAttemptModel?.latestAttempt?.mutationResultAggregationPlan || null;
  const readinessMutationResultAggregationSteps = Array.isArray(readinessMutationResultAggregationPlan?.steps)
    ? readinessMutationResultAggregationPlan.steps
    : [];
  const readinessFinalMutationReportDraft =
    readinessReleaseAttemptModel?.latestAttempt?.finalMutationReportDraft || null;
  const readinessFinalMutationReportDraftView = buildMutationFinalReportDraftView(
    readinessFinalMutationReportDraft
  );
  const readinessFinalMutationReportContract = readinessReleaseAttemptModel?.latestAttempt?.finalMutationReportContract || null;
  const readinessFinalMutationReportSections = Array.isArray(readinessFinalMutationReportContract?.requiredSections)
    ? readinessFinalMutationReportContract.requiredSections
    : [];
  const readinessFinalMutationReportGuardrails = Array.isArray(readinessFinalMutationReportContract?.answerQualityGuardrails)
    ? readinessFinalMutationReportContract.answerQualityGuardrails
    : [];
  const readinessFinalMutationReportFinalizationBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.finalMutationReportFinalizationBoundary || null;
  const readinessFinalMutationReportFinalizationObservationSummaryText =
    buildAcceptedMutationObservationSummaryText(
      readinessFinalMutationReportFinalizationBoundary,
      'final report finalization accepted observations'
    );
  const readinessFinalMutationReportFinalizationRequirements = Array.isArray(
    readinessFinalMutationReportFinalizationBoundary?.requirements
  )
    ? readinessFinalMutationReportFinalizationBoundary.requirements
    : [];
  const readinessFinalAnswerPublicationBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.finalAnswerPublicationBoundary || null;
  const readinessFinalAnswerPublicationObservationSummaryText =
    buildAcceptedMutationObservationSummaryText(
      readinessFinalAnswerPublicationBoundary,
      'final answer publication accepted observations'
    );
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
  const readinessFinalAnswerPublicationHandoff =
    readinessReleaseAttemptModel?.latestAttempt?.finalAnswerPublicationHandoff || null;
  const readinessAcknowledgementSaveHandoff =
    readinessReleaseAttemptModel?.latestAttempt?.acknowledgementSaveHandoff || null;
  const readinessReleaseEnablementChecklist = readinessReleaseAttemptModel?.latestAttempt?.releaseEnablementChecklist || null;
  const readinessReleaseEnablementChecklistItems = Array.isArray(readinessReleaseEnablementChecklist?.items)
    ? readinessReleaseEnablementChecklist.items
    : [];
  const readinessMutationDispatchEnvelopeContract =
    readinessReleaseAttemptModel?.latestAttempt?.mutationDispatchEnvelopeContract || null;
  const readinessMutationDispatchEnvelopeContractView = buildMutationDispatchEnvelopeContractView(
    readinessMutationDispatchEnvelopeContract
  );
  const readinessMutationDispatchPreflightBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.mutationDispatchPreflightBoundary || null;
  const readinessMutationDispatchPreflightBoundaryView = buildMutationDispatchPreflightBoundaryView(
    readinessMutationDispatchPreflightBoundary
  );
  const readinessMutationDispatchDecisionModel =
    readinessReleaseAttemptModel?.latestAttempt?.mutationDispatchDecisionModel || null;
  const readinessMutationDispatchDecisionModelView = buildMutationDispatchDecisionModelView(
    readinessMutationDispatchDecisionModel
  );
  const readinessMutationRequestBlueprint =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestBlueprint || null;
  const readinessMutationRequestBlueprintView = buildMutationRequestBlueprintView(
    readinessMutationRequestBlueprint
  );
  const readinessMutationRequestCreationGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestCreationGate || null;
  const readinessMutationRequestCreationGateView = buildMutationRequestCreationGateView(
    readinessMutationRequestCreationGate
  );
  const readinessMutationRequestPushGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestPushGate || null;
  const readinessMutationRequestPushGateView = buildMutationRequestPushGateView(
    readinessMutationRequestPushGate
  );
  const readinessMutationRequestClaimGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRequestClaimGate || null;
  const readinessMutationRequestClaimGateView = buildMutationRequestClaimGateView(
    readinessMutationRequestClaimGate
  );
  const readinessMutationExecutionGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationExecutionGate || null;
  const readinessMutationExecutionGateView = buildMutationExecutionGateView(
    readinessMutationExecutionGate
  );
  const readinessMutationWriteHelperSafetyGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationWriteHelperSafetyGate || null;
  const readinessMutationWriteHelperSafetyGateView = buildMutationWriteHelperSafetyGateView(
    readinessMutationWriteHelperSafetyGate
  );
  const readinessMutationPostExecutionObservationGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationPostExecutionObservationGate || null;
  const readinessMutationPostExecutionObservationGateView = buildMutationPostExecutionObservationGateView(
    readinessMutationPostExecutionObservationGate
  );
  const readinessMutationObservationAcceptanceGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationObservationAcceptanceGate || null;
  const readinessMutationObservationAcceptanceGateView = buildMutationObservationAcceptanceGateView(
    readinessMutationObservationAcceptanceGate
  );
  const readinessMutationResultIntakePersistenceGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationResultIntakePersistenceGate || null;
  const readinessMutationResultIntakePersistenceGateView = buildMutationResultIntakePersistenceGateView(
    readinessMutationResultIntakePersistenceGate
  );
  const readinessMutationRollbackFallbackGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRollbackFallbackGate || null;
  const readinessMutationRollbackFallbackGateView = buildMutationRollbackFallbackGateView(
    readinessMutationRollbackFallbackGate
  );
  const readinessMutationRagFreshnessGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationRagFreshnessGate || null;
  const readinessMutationRagFreshnessGateView = buildMutationRagFreshnessGateView(
    readinessMutationRagFreshnessGate
  );
  const readinessMutationResultAggregationGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationResultAggregationGate || null;
  const readinessMutationResultAggregationGateView = buildMutationResultAggregationGateView(
    readinessMutationResultAggregationGate
  );
  const readinessMutationPublicationGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationPublicationGate || null;
  const readinessMutationPublicationGateView = buildMutationPublicationGateView(
    readinessMutationPublicationGate
  );
  const readinessMutationFinalAnswerGenerationGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalAnswerGenerationGate || null;
  const readinessMutationFinalAnswerGenerationGateView = buildMutationFinalAnswerGenerationGateView(
    readinessMutationFinalAnswerGenerationGate
  );
  const readinessMutationFinalAnswerCompletionGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalAnswerCompletionGate || null;
  const readinessMutationFinalAnswerCompletionGateView = buildMutationFinalAnswerCompletionGateView(
    readinessMutationFinalAnswerCompletionGate
  );
  const readinessMutationFinalAnswerPersistenceGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalAnswerPersistenceGate || null;
  const readinessMutationFinalAnswerPersistenceGateView = buildMutationFinalAnswerPersistenceGateView(
    readinessMutationFinalAnswerPersistenceGate
  );
  const readinessMutationFinalAnswerConversationSaveGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalAnswerConversationSaveGate || null;
  const readinessMutationFinalAnswerConversationSaveGateView = buildMutationFinalAnswerConversationSaveGateView(
    readinessMutationFinalAnswerConversationSaveGate
  );
  const readinessMutationFinalAnswerUserVisibleCompletionGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalAnswerUserVisibleCompletionGate || null;
  const readinessMutationFinalAnswerUserVisibleCompletionGateView = buildMutationFinalAnswerUserVisibleCompletionGateView(
    readinessMutationFinalAnswerUserVisibleCompletionGate
  );
  const readinessMutationFinalResponseHandoffGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalResponseHandoffGate || null;
  const readinessMutationFinalResponseHandoffGateView = buildMutationFinalResponseHandoffGateView(
    readinessMutationFinalResponseHandoffGate
  );
  const readinessMutationFinalAnswerDeliveryGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalAnswerDeliveryGate || null;
  const readinessMutationFinalAnswerDeliveryGateView = buildMutationFinalAnswerDeliveryGateView(
    readinessMutationFinalAnswerDeliveryGate
  );
  const readinessMutationFinalAnswerDeliveryReceiptGate =
    readinessReleaseAttemptModel?.latestAttempt?.mutationFinalAnswerDeliveryReceiptGate || null;
  const readinessMutationFinalAnswerDeliveryReceiptGateView = buildMutationFinalAnswerDeliveryReceiptGateView(
    readinessMutationFinalAnswerDeliveryReceiptGate
  );
  const readinessMutationCompletionSummary = readinessReleaseAttemptModel?.latestAttempt?.mutationCompletionSummary || null;
  const readinessMutationCompletionSummaryView = buildMutationCompletionSummaryView(readinessMutationCompletionSummary);
  const readinessMutationHandoffSummary = readinessReleaseAttemptModel?.latestAttempt?.mutationHandoffSummary || null;
  const readinessMutationHandoffSummaryView = buildMutationHandoffSummaryView(readinessMutationHandoffSummary);
  const readinessMutationExecutionReadinessBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.mutationExecutionReadinessBoundary || null;
  const readinessMutationExecutionReadinessBoundaryView = buildMutationExecutionReadinessBoundaryView(
    readinessMutationExecutionReadinessBoundary
  );
  const readinessMutationToolRunnerBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.mutationToolRunnerBoundary || null;
  const readinessMutationToolRunnerBoundaryView = buildMutationToolRunnerBoundaryView(
    readinessMutationToolRunnerBoundary
  );
  const readinessMutationResultCompletionBoundary =
    readinessReleaseAttemptModel?.latestAttempt?.mutationResultCompletionBoundary || null;
  const readinessMutationResultCompletionBoundaryView = buildMutationResultCompletionBoundaryView(
    readinessMutationResultCompletionBoundary
  );
  const approvedExecutionFlowRequestIds = visibleReadiness?.approvedExecutionFlowRequestIds
    || readinessReleaseAttemptModel?.latestAttempt?.approvedExecutionFlowRequestIds
    || readinessReleaseAttemptModel?.latestAttempt?.approvedExecutionFlowInspectionRequestIds
    || [];
  const approvedExecutionFlowReleaseAttemptId = readinessReleaseAttemptModel?.latestAttempt?.releaseAttemptId
    || readinessReleaseAttemptModel?.latestAttempt?.id
    || visibleReadiness?.releaseAttemptId
    || null;
  const approvedExecutionFlowInspectionKey = approvedExecutionFlowReleaseAttemptId
    || (Array.isArray(approvedExecutionFlowRequestIds) ? approvedExecutionFlowRequestIds.join('-') : '');
  const canInspectApprovedExecutionFlow = Boolean(approvedExecutionFlowInspectionKey)
    && !loading(`code-agent-approved-execution-flow-inspection-${approvedExecutionFlowInspectionKey}`);
  const approvedExecutionFlowInspectionView = buildApprovedExecutionFlowInspectionView(approvedExecutionFlowInspection);
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
  const readinessFreshObservationEnqueueBoundaryView = buildFreshObservationEnqueueBoundaryView(readinessFreshObservationEnqueueBoundary);
  const expectedDryRunRefusal = isExpectedDryRunRefusal(visibleDryRun);
  const dryRunResultSummaryView = buildDryRunResultSummaryView({
    result: visibleDryRun,
    expectedDryRunRefusal,
  });
  const dryRunSnapshotObservation = visibleDryRun?.output?.snapshotObservation;
  const dryRunSnapshotObservationSummaryView = buildDryRunSnapshotObservationSummaryView(dryRunSnapshotObservation);
  const dryRunRollbackObservation = visibleDryRun?.output?.rollbackObservation;
  const dryRunRollbackObservationSummaryView = buildDryRunRollbackObservationSummaryView(dryRunRollbackObservation);
  const dryRunPatchFilesSummaryView = buildDryRunPatchFilesSummaryView(visibleDryRun?.output?.files || []);
  const readinessSnapshot = visibleReadiness?.snapshotReadiness || null;
  const readinessRollback = visibleReadiness?.rollbackReadiness || null;
  const readinessSnapshotManifestCheck = findReadinessCheck(visibleReadiness, 'snapshotManifestPreview');
  const readinessRollbackPreconditionsCheck = findReadinessCheck(visibleReadiness, 'rollbackRestorePreconditions');
  const readinessSnapshotSummaryView = buildSnapshotReadinessSummaryView({
    snapshot: readinessSnapshot,
    snapshotManifestCheck: readinessSnapshotManifestCheck,
    rollbackPreconditionsCheck: readinessRollbackPreconditionsCheck,
    dryRunSnapshotObservation,
  });
  const readinessRollbackSummaryView = buildRollbackReadinessSummaryView({
    rollback: readinessRollback,
  });
  const readinessChecksSummaryView = buildReadinessChecksSummaryView(visibleReadiness);
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
          <button type="button" className="ghost-button" disabled={!canPreviewLoop} onClick={onLoopPreview}>
            {loading('code-agent-loop-preview') ? <Loader2 className="spin" size={16} /> : <Play size={16} />}
            Preview agent loop
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canPreviewRunner}
            onClick={() => onLoopRunnerPreview(loopPreview)}
            title="Preview-only runner state. This does not enqueue, create, push, claim, or execute any Local Agent request."
          >
            {loading('code-agent-loop-runner-preview') ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
            Preview runner state
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canCheckRunnerEnqueueRefusal}
            onClick={() => onLoopRunnerEnqueueReadOnly(loopPreview)}
            title="Checks the disabled runner handoff boundary for creation-disabled or release-gate states. It does not create, push, claim, or execute a Local Agent request."
          >
            {loading('code-agent-loop-runner-enqueue-read-only') ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
            Check enqueue refusal
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canReviewRunnerReleaseRefusal}
            onClick={() => onLoopRunnerReleaseReview(loopPreview)}
            title="Reviews the refreshed release gate and records the disabled release boundary. It does not release, claim, mutate, publish, deliver, or acknowledge results."
          >
            {loading('code-agent-loop-runner-release-review') ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
            Review release refusal
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canPreviewFinalResultPublication}
            onClick={() => onLoopRunnerFinalResultPublicationPreview(loopPreview)}
            title="Previews the completed-flow final-result report and final-answer publication handoff. It does not publish, deliver, save acknowledgement, update RAG freshness, or mutate."
          >
            {loading('code-agent-loop-runner-final-result-publication-preview') ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
            Preview final result handoff
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canPreviewM8EntryReadiness}
            onClick={() => onLoopRunnerM8EntryReadiness(loopPreview)}
            title="Checks whether the current completed-flow handoff is sufficient to move from M7 into M8 planning. It does not enable CLI packaging, publication, acknowledgement save, RAG updates, or mutation."
          >
            {loading('code-agent-loop-runner-m8-entry-readiness') ? <Loader2 className="spin" size={16} /> : <ShieldCheck size={16} />}
            Check M8 entry
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canEnqueueSelectedReadOnlyRunner}
            onClick={() => onLoopRunnerEnqueueSelectedReadOnly(loopPreview)}
            title="Queues only the prepared read-only Local Agent git.status or git.diff observation. It does not claim, mutate, publish, or acknowledge final results."
          >
            {loading('code-agent-loop-runner-enqueue-selected-read-only') ? <Loader2 className="spin" size={16} /> : <Play size={16} />}
            Queue read-only step
          </button>
          <button
            type="button"
            className="ghost-button"
            disabled={!canPreviewRunnerToolSelection}
            onClick={() => onLoopRunnerToolSelectionPreview(loopPreview)}
            title="Asks the model to select the next allowed read-only Local Agent tool. This is preview-only and does not create, enqueue, push, claim, mutate, publish, deliver, or acknowledge results."
          >
            {loading('code-agent-loop-runner-tool-selection-preview') ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
            Preview model tool
          </button>
          <button type="button" className="ghost-button" disabled={!selectedRepositoryId || loading('code-agent-loop-timelines')} onClick={() => onRefreshLoopTimelines(selectedRepositoryId)}>
            {loading('code-agent-loop-timelines') ? <Loader2 className="spin" size={16} /> : <RefreshCw size={16} />}
            Refresh loop history
          </button>
          <button type="button" className="ghost-button" disabled={!canPatch} onClick={onPatch}>
            {loading('code-agent-patch') ? <Loader2 className="spin" size={16} /> : <FileCode2 size={16} />}
            diff 생성
          </button>
        </div>
      </form>
      {agentLoopPreviewSummaryView && (
        <div className="code-agent-result compact-result">
          <div className="result-heading">
            <strong>{agentLoopPreviewSummaryView.headerText}</strong>
            <Badge variant="outline">preview only</Badge>
          </div>
          <small>{agentLoopPreviewSummaryView.stateText}</small>
          {!!agentLoopPreviewSummaryView.stepLines.length && (
            <ol className="code-agent-list">
              {agentLoopPreviewSummaryView.stepLines.map((line) => <li key={line}>{line}</li>)}
            </ol>
          )}
          {!!agentLoopPreviewSummaryView.stopLines.length && (
            <div className="failure-list">
              {agentLoopPreviewSummaryView.stopLines.map((line) => (
                <div className="failure-item" key={line}>
                  <strong>{line}</strong>
                </div>
              ))}
            </div>
          )}
          <WarningList warnings={agentLoopPreviewSummaryView.warnings} />
        </div>
      )}
      {agentLoopTimelineHistoryView && (
        <div className="code-agent-result compact-result">
          <div className="result-heading">
            <strong>{agentLoopTimelineHistoryView.headerText}</strong>
            <Badge variant="outline">read only</Badge>
          </div>
          {agentLoopTimelineHistoryView.timelines.map((timeline) => (
            <div className="detail-box compact-box" key={timeline.id || timeline.headerText}>
              <strong>{timeline.headerText}</strong>
              <small>{timeline.stateText}</small>
              {timeline.instructionText && <small>{timeline.instructionText}</small>}
              {timeline.createdText && <small>{timeline.createdText}</small>}
              {!!timeline.eventLines.length && (
                <ol className="code-agent-list">
                  {timeline.eventLines.map((line) => <li key={line}>{line}</li>)}
                </ol>
              )}
            </div>
          ))}
        </div>
      )}
      {agentLoopRunnerHandoffSummaryView.show && (
        <div className="code-agent-result compact-result">
          <div className="result-heading">
            <strong>{agentLoopRunnerHandoffSummaryView.headerText}</strong>
            <Badge variant="secondary">{agentLoopRunnerHandoffSummaryView.badgeText}</Badge>
          </div>
          {agentLoopRunnerHandoffSummaryView.countsText && (
            <small>{agentLoopRunnerHandoffSummaryView.countsText}</small>
          )}
          {loopRunnerFinalResultPublicationPreview && (
            <small>
              agent loop runner final-result publication preview: {loopRunnerFinalResultPublicationPreview.publicationDecision || 'UNKNOWN'}
              {' / final result ready '}
              {String(loopRunnerFinalResultPublicationPreview.finalResultReady)}
              {' / publication '}
              {String(loopRunnerFinalResultPublicationPreview.publicationEnabled)}
              {' / acknowledgement save '}
              {String(loopRunnerFinalResultPublicationPreview.acknowledgementSaveEnabled)}
              {' / mutation '}
              {String(loopRunnerFinalResultPublicationPreview.mutationEnabled)}
            </small>
          )}
          {loopRunnerM8EntryReadiness && (
            <small>
              agent loop runner M8 entry readiness: {loopRunnerM8EntryReadiness.m8EntryDecision || 'UNKNOWN'}
              {' / M7 closure '}
              {loopRunnerM8EntryReadiness.m7ClosureDecision || 'UNKNOWN'}
              {' / M8 entry ready '}
              {String(loopRunnerM8EntryReadiness.m8EntryReady)}
              {' / M8 work '}
              {String(loopRunnerM8EntryReadiness.m8WorkEnabled)}
              {' / publication '}
              {String(loopRunnerM8EntryReadiness.publicationEnabled)}
              {' / acknowledgement save '}
              {String(loopRunnerM8EntryReadiness.acknowledgementSaveEnabled)}
              {' / mutation '}
              {String(loopRunnerM8EntryReadiness.mutationEnabled)}
            </small>
          )}
          <small>{agentLoopRunnerHandoffSummaryView.disabledText}</small>
          {agentLoopRunnerHandoffSummaryView.nestedPreviewText && (
            <small>{agentLoopRunnerHandoffSummaryView.nestedPreviewText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.sourceText && (
            <small>{agentLoopRunnerHandoffSummaryView.sourceText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.routeText && (
            <small>{agentLoopRunnerHandoffSummaryView.routeText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.freshObservationText && (
            <small>{agentLoopRunnerHandoffSummaryView.freshObservationText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.readinessText && (
            <small>{agentLoopRunnerHandoffSummaryView.readinessText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.boundaryText && (
            <small>{agentLoopRunnerHandoffSummaryView.boundaryText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.finalResultText && (
            <small>{agentLoopRunnerHandoffSummaryView.finalResultText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.observationText && (
            <small>{agentLoopRunnerHandoffSummaryView.observationText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.recommendedActionText && (
            <small>{agentLoopRunnerHandoffSummaryView.recommendedActionText}</small>
          )}
          {agentLoopRunnerHandoffSummaryView.message && (
            <small>{agentLoopRunnerHandoffSummaryView.message}</small>
          )}
          {runnerQueuedRequestId && (
            <button
              type="button"
              className="ghost-button compact-action"
              disabled={!canRefreshRunnerQueuedObservation}
              onClick={() => onRefreshLoopRunnerQueuedObservation(runnerQueuedRequestId)}
              title="Refreshes the queued read-only Local Agent result and then refreshes the agent-loop timeline. It does not claim, mutate, publish, or acknowledge final results."
            >
              {loading(`code-agent-loop-runner-queued-observation-${runnerQueuedRequestId}`) ? <Loader2 className="spin" size={14} /> : <RefreshCw size={14} />}
              Refresh read-only observation
            </button>
          )}
        </div>
      )}
      {runnerToolSelectionPreviewView && (
        <div className="code-agent-result compact-result">
          <div className="result-heading">
            <strong>{runnerToolSelectionPreviewView.headerText}</strong>
            <Badge variant="outline">model preview</Badge>
          </div>
          <small>{runnerToolSelectionPreviewView.decisionText}</small>
          <small>{runnerToolSelectionPreviewView.controlsText}</small>
          {runnerToolSelectionPreviewView.reasonText && (
            <small>{runnerToolSelectionPreviewView.reasonText}</small>
          )}
        </div>
      )}
      {runnerObservationContinuationView && (
        <div className="code-agent-result compact-result">
          <div className="result-heading">
            <strong>{runnerObservationContinuationView.headerText}</strong>
            <Badge variant="outline">loop continuation</Badge>
          </div>
          <small>{runnerObservationContinuationView.budgetText}</small>
          <small>{runnerObservationContinuationView.controlsText}</small>
          {runnerObservationContinuationView.nextPreviewText && (
            <small>{runnerObservationContinuationView.nextPreviewText}</small>
          )}
          {runnerObservationContinuationView.reasonText && (
            <small>{runnerObservationContinuationView.reasonText}</small>
          )}
          <button
            type="button"
            className="ghost-button compact-action"
            disabled={!canEnqueueSelectedReadOnlyRunner}
            onClick={() => onLoopRunnerEnqueueSelectedReadOnly(loopPreview)}
            title="Queues the next model-previewed read-only Local Agent git.status observation. It does not claim, mutate, publish, deliver, or acknowledge final results."
          >
            {loading('code-agent-loop-runner-enqueue-selected-read-only') ? <Loader2 className="spin" size={14} /> : <Play size={14} />}
            Continue read-only step
          </button>
        </div>
      )}
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
              {localPatchRequest.input?.loopId && <small>loop: {localPatchRequest.input.loopId}</small>}
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
                    <span>
                      tool: {localRepositoryObservationRequest.request?.toolName} / read-only
                      {localRepositoryObservationRequest.request?.input?.freshObservationOnly ? ' / fresh observation only' : ''}
                      {localRepositoryObservationRequest.request?.input?.releaseAttemptId ? ` / release attempt ${String(localRepositoryObservationRequest.request.input.releaseAttemptId).slice(0, 8)}` : ''}
                    </span>
                  </div>
                  {visibleRepositoryObservation && (
                    <div className="failure-item">
                      <strong>Repository observation: {visibleRepositoryObservation.status}</strong>
                      {visibleRepositoryObservation.error && <span>{visibleRepositoryObservation.error}</span>}
                      {visibleRepositoryObservation.input?.releaseAttemptId && (
                        <span>
                          linked release evidence: attempt {String(visibleRepositoryObservation.input.releaseAttemptId).slice(0, 8)}
                          {visibleRepositoryObservation.input?.freshObservationOnly ? ' / fresh observation only' : ''}
                        </span>
                      )}
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
                  <button
                    type="button"
                    className="ghost-button"
                    disabled={!canQueueReleaseFreshObservations}
                    onClick={() => onQueueReleaseFreshObservations(localPatchRequest.requestId)}
                    title="Queues only release-attempt-linked git.status and dry-run patch.apply observations. The held patch remains non-claimable."
                  >
                    {loading(`code-agent-local-release-fresh-observations-${localPatchRequest.requestId}`) ? <Loader2 className="spin" size={16} /> : <GitBranch size={16} />}
                    Queue release fresh observations
                  </button>
                  {readinessReleaseBoundaryVisibility && (
                    <button
                      type="button"
                      className="ghost-button"
                      disabled={!canReleaseLocalPatchForExecution}
                      onClick={() => onReleaseLocalPatchForExecution(localPatchRequest.requestId)}
                      title={canReleaseLocalPatchForExecution
                        ? 'Releases the approved-held patch through the guarded backend path so the Local Agent can claim the approved execution sequence.'
                        : 'Release remains disabled until the backend release flag is enabled and fresh release evidence is ready.'}
                    >
                      {loading(`code-agent-local-release-for-execution-${localPatchRequest.requestId}`) ? <Loader2 className="spin" size={16} /> : <Play size={16} />}
                      {canReleaseLocalPatchForExecution ? 'Release Local Agent patch' : 'Release Local Agent patch disabled'}
                    </button>
                  )}
                  <button
                    type="button"
                    className="ghost-button"
                    disabled={!canInspectApprovedExecutionFlow}
                    onClick={() => onInspectApprovedExecutionFlow(approvedExecutionFlowRequestIds)}
                    title="Inspects already-completed approved Local Agent rows only. It does not create, push, claim, complete, or mutate."
                  >
                    {loading(`code-agent-approved-execution-flow-inspection-${approvedExecutionFlowInspectionKey}`) ? <Loader2 className="spin" size={16} /> : <Eye size={16} />}
                    Inspect approved flow
                  </button>
                </div>
              )}
              {localPatchDryRunRequest && (
                <div className="failure-list">
                  <div className="failure-item">
                    <strong>Local dry-run request: {localPatchDryRunRequest.requestId}</strong>
                    <span>
                      queued as {localPatchDryRunRequest.request?.toolName}
                      {localPatchDryRunRequest.request?.input?.sourceRequestId ? ` from ${localPatchDryRunRequest.request.input.sourceRequestId}` : ''}
                      {localPatchDryRunRequest.request?.input?.freshObservationOnly ? ' / fresh observation only' : ''}
                      {localPatchDryRunRequest.request?.input?.releaseAttemptId ? ` / release attempt ${String(localPatchDryRunRequest.request.input.releaseAttemptId).slice(0, 8)}` : ''}
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
                      <strong>{dryRunResultSummaryView.titleText}</strong>
                      {dryRunResultSummaryView.errorText && <span>{dryRunResultSummaryView.errorText}</span>}
                      {dryRunResultSummaryView.failureText && <span>{dryRunResultSummaryView.failureText}</span>}
                      {dryRunResultSummaryView.releaseEvidenceText && <span>{dryRunResultSummaryView.releaseEvidenceText}</span>}
                      {dryRunResultSummaryView.preflightText && <span>{dryRunResultSummaryView.preflightText}</span>}
                      {dryRunResultSummaryView.mutationText && <span>{dryRunResultSummaryView.mutationText}</span>}
                      {dryRunResultSummaryView.snapshotText && <span>{dryRunResultSummaryView.snapshotText}</span>}
                      {dryRunSnapshotObservationSummaryView.show && <span>{dryRunSnapshotObservationSummaryView.observationText}</span>}
                      {dryRunSnapshotObservationSummaryView.manifestText && <span>{dryRunSnapshotObservationSummaryView.manifestText}</span>}
                      {dryRunRollbackObservationSummaryView.show && <span>{dryRunRollbackObservationSummaryView.text}</span>}
                      {dryRunSnapshotObservationSummaryView.filesText && <span>{dryRunSnapshotObservationSummaryView.filesText}</span>}
                      {dryRunPatchFilesSummaryView.show && <span>{dryRunPatchFilesSummaryView.text}</span>}
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
                  {readinessReleaseAttemptDisplaySummaryView.show && (
                    <div className="failure-item">
                      <strong>{readinessReleaseAttemptDisplaySummaryView.title}</strong>
                      <span>{readinessReleaseAttemptDisplaySummaryView.evidenceText}</span>
                      <span>{readinessReleaseAttemptDisplaySummaryView.readinessText}</span>
                      <span>{readinessReleaseAttemptDisplaySummaryView.disabledGatesText}</span>
                      {readinessReleaseAttemptDisplaySummaryView.whyDisabledText && (
                        <span>{readinessReleaseAttemptDisplaySummaryView.whyDisabledText}</span>
                      )}
                      {readinessReleaseAttemptDisplaySummaryView.message && <span>{readinessReleaseAttemptDisplaySummaryView.message}</span>}
                    </div>
                  )}
                  {approvedExecutionFlowInspectionView.show && (
                    <div className="failure-item">
                      <strong>{approvedExecutionFlowInspectionView.headerText}</strong>
                      <span>{approvedExecutionFlowInspectionView.stateText}</span>
                      <span>{approvedExecutionFlowInspectionView.disabledText}</span>
                      {approvedExecutionFlowInspectionView.requestText && (
                        <span>{approvedExecutionFlowInspectionView.requestText}</span>
                      )}
                      {approvedExecutionFlowInspectionView.stepLines.map((line) => (
                        <span key={`approved-execution-flow-${line}`}>{line}</span>
                      ))}
                      {approvedExecutionFlowInspectionView.message && (
                        <span>{approvedExecutionFlowInspectionView.message}</span>
                      )}
                    </div>
                  )}
                  {readinessRepositoryVerificationSummaryView.show && (
                    <div className="failure-item">
                      <strong>{readinessRepositoryVerificationSummaryView.headerText}</strong>
                      {readinessRepositoryVerificationSummaryView.message && <span>{readinessRepositoryVerificationSummaryView.message}</span>}
                      {readinessRepositoryVerificationSummaryView.linkageText && <span>{readinessRepositoryVerificationSummaryView.linkageText}</span>}
                      {readinessRepositoryVerificationSummaryView.checkLines.map((line) => (
                        <span key={line}>{line}</span>
                      ))}
                    </div>
                  )}
                  {readinessWorkspaceVerificationSummaryView.show && (
                    <div className="failure-item">
                      <strong>{readinessWorkspaceVerificationSummaryView.headerText}</strong>
                      {readinessWorkspaceVerificationSummaryView.blockingText && <span>{readinessWorkspaceVerificationSummaryView.blockingText}</span>}
                      {readinessWorkspaceVerificationSummaryView.reason && <span>{readinessWorkspaceVerificationSummaryView.reason}</span>}
                      {readinessWorkspaceVerificationSummaryView.sourceText && <span>{readinessWorkspaceVerificationSummaryView.sourceText}</span>}
                    </div>
                  )}
                  {readinessPatchReleaseSummaryView.show && (
                    <div className="failure-item">
                      <strong>{readinessPatchReleaseSummaryView.headerText}</strong>
                      {readinessPatchReleaseSummaryView.message && <span>{readinessPatchReleaseSummaryView.message}</span>}
                      <span>{readinessPatchReleaseSummaryView.stateText}</span>
                      {readinessPatchReleaseSummaryView.prerequisiteLines.map((line) => (
                        <span key={line}>
                          {line}
                        </span>
                      ))}
                    </div>
                  )}
                  {readinessPatchExecutionGateSummaryView.show && (
                    <div className="failure-item">
                      <strong>{readinessPatchExecutionGateSummaryView.headerText}</strong>
                      {readinessPatchExecutionGateSummaryView.message && <span>{readinessPatchExecutionGateSummaryView.message}</span>}
                      <span>{readinessPatchExecutionGateSummaryView.controlText}</span>
                      {readinessReleaseAttemptModelSummaryView.showPreReleaseRevalidation && (
                        <span>{readinessReleaseAttemptModelSummaryView.preReleaseRevalidationText}</span>
                      )}
                      {readinessReleaseAttemptModelSummaryView.showReleaseAttemptModel && (
                        <span>{readinessReleaseAttemptModelSummaryView.releaseAttemptModelText}</span>
                      )}
                      {readinessFreshObservationRequestPlanView.show && (
                        <>
                          <span>{readinessFreshObservationRequestPlanView.headerText}</span>
                          {readinessFreshObservationRequestPlanView.requestLines.map((line) => (
                            <span key={line}>{line}</span>
                          ))}
                        </>
                      )}
                      {readinessFreshObservationEvidenceSummaryView.showStatus && (
                        <>
                          <span>{readinessFreshObservationEvidenceSummaryView.statusHeaderText}</span>
                          {readinessFreshObservationEvidenceSummaryView.statusLines.map((line) => (
                            <span key={line}>{line}</span>
                          ))}
                        </>
                      )}
                      {readinessFreshObservationEvidenceSummaryView.showCompleteness && (
                        <>
                          <span>{readinessFreshObservationEvidenceSummaryView.completenessText}</span>
                          <span>{readinessFreshObservationEvidenceSummaryView.releaseGateText}</span>
                          {readinessFreshObservationEvidenceSummaryView.blockingText && <span>{readinessFreshObservationEvidenceSummaryView.blockingText}</span>}
                          {readinessFreshObservationEvidenceSummaryView.missingText && <span>{readinessFreshObservationEvidenceSummaryView.missingText}</span>}
                          {readinessFreshObservationEvidenceSummaryView.fallbackText && <span>{readinessFreshObservationEvidenceSummaryView.fallbackText}</span>}
                          {readinessFreshObservationEvidenceSummaryView.message && <span>{readinessFreshObservationEvidenceSummaryView.message}</span>}
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
                      {readinessMutationResultIntakeBoundaryView.show && (
                        <>
                          <span>{readinessMutationResultIntakeBoundaryView.headerText}</span>
                          <span>{readinessMutationResultIntakeBoundaryView.disabledText}</span>
                          {readinessMutationResultIntakeBoundaryView.requiredOutcomeText && (
                            <span>{readinessMutationResultIntakeBoundaryView.requiredOutcomeText}</span>
                          )}
                          {readinessMutationResultIntakeBoundaryView.acceptedStatusesText && (
                            <span>{readinessMutationResultIntakeBoundaryView.acceptedStatusesText}</span>
                          )}
                          {readinessMutationResultIntakeBoundaryView.requirementLines.map((line) => (
                            <span key={`mutation-result-intake-${line}`}>{line}</span>
                          ))}
                          {readinessMutationResultIntakeBoundaryView.blockingText && (
                            <span>{readinessMutationResultIntakeBoundaryView.blockingText}</span>
                          )}
                          {readinessMutationResultIntakeBoundaryView.message && (
                            <span>{readinessMutationResultIntakeBoundaryView.message}</span>
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
                      {readinessFinalMutationReportDraftView.show && (
                        <>
                          <span>{readinessFinalMutationReportDraftView.headerText}</span>
                          {readinessFinalMutationReportDraftView.observationSummaryText && (
                            <span>{readinessFinalMutationReportDraftView.observationSummaryText}</span>
                          )}
                          <span>{readinessFinalMutationReportDraftView.disabledText}</span>
                          {readinessFinalMutationReportDraftView.sectionLines.map((line) => (
                            <span key={`final-mutation-report-draft-${line}`}>{line}</span>
                          ))}
                          {readinessFinalMutationReportDraftView.blockingText && (
                            <span>{readinessFinalMutationReportDraftView.blockingText}</span>
                          )}
                          {readinessFinalMutationReportDraftView.message && (
                            <span>{readinessFinalMutationReportDraftView.message}</span>
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
                          {readinessFinalMutationReportFinalizationObservationSummaryText && (
                            <span>{readinessFinalMutationReportFinalizationObservationSummaryText}</span>
                          )}
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
                            {readinessFinalAnswerPublicationBoundary.finalMutationReportDraftStatus ? ` / draft ${readinessFinalAnswerPublicationBoundary.finalMutationReportDraftStatus}` : ''}
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
                          {readinessFinalAnswerPublicationObservationSummaryText && (
                            <span>{readinessFinalAnswerPublicationObservationSummaryText}</span>
                          )}
                          {!!readinessFinalAnswerPublicationBoundary.requiredReportSections?.length && (
                            <span>publication required report sections: {readinessFinalAnswerPublicationBoundary.requiredReportSections.join(', ')}</span>
                          )}
                          {!!readinessFinalAnswerPublicationBoundary.finalMutationReportDraftSections?.length && (
                            <span>publication final report draft sections: {readinessFinalAnswerPublicationBoundary.finalMutationReportDraftSections.join(', ')}</span>
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
                      {readinessFinalAnswerPublicationHandoff && (
                        <>
                          <span>
                            final answer publication handoff: {readinessFinalAnswerPublicationHandoff.status || 'BLOCKED_STALE_INDEX_DISCLOSURE_MISSING'}
                            {readinessFinalAnswerPublicationHandoff.schema ? ` / ${readinessFinalAnswerPublicationHandoff.schema}` : ''}
                            {readinessFinalAnswerPublicationHandoff.handoffAvailable !== undefined ? ` / handoff ${String(readinessFinalAnswerPublicationHandoff.handoffAvailable)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.executionTarget ? ` / ${readinessFinalAnswerPublicationHandoff.executionTarget}` : ''}
                            {readinessFinalAnswerPublicationHandoff.sourceFinalMutationReportSummaryStatus ? ` / summary ${readinessFinalAnswerPublicationHandoff.sourceFinalMutationReportSummaryStatus}` : ''}
                            {readinessFinalAnswerPublicationHandoff.sourceRagFreshnessMarkerStatus ? ` / freshness ${readinessFinalAnswerPublicationHandoff.sourceRagFreshnessMarkerStatus}` : ''}
                          </span>
                          <span>
                            final answer handoff disclosure:
                            {readinessFinalAnswerPublicationHandoff.staleIndexDisclosureRequired !== undefined ? ` required ${String(readinessFinalAnswerPublicationHandoff.staleIndexDisclosureRequired)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.staleIndexDisclosureModeled !== undefined ? ` / modeled ${String(readinessFinalAnswerPublicationHandoff.staleIndexDisclosureModeled)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.staleIndexPolicy ? ` / policy ${readinessFinalAnswerPublicationHandoff.staleIndexPolicy}` : ''}
                            {readinessFinalAnswerPublicationHandoff.freshnessAction ? ` / action ${readinessFinalAnswerPublicationHandoff.freshnessAction}` : ''}
                          </span>
                          {!!readinessFinalAnswerPublicationHandoff.targetFiles?.length && (
                            <span>final answer handoff target files: {readinessFinalAnswerPublicationHandoff.targetFiles.join(', ')}</span>
                          )}
                          {!!readinessFinalAnswerPublicationHandoff.finalAnswerSections?.length && (
                            <span>final answer handoff sections: {readinessFinalAnswerPublicationHandoff.finalAnswerSections.join(', ')}</span>
                          )}
                          {readinessFinalAnswerPublicationHandoff.staleIndexDisclosureText && (
                            <span>final answer stale-index disclosure: {readinessFinalAnswerPublicationHandoff.staleIndexDisclosureText}</span>
                          )}
                          <span>
                            final answer handoff disabled:
                            {readinessFinalAnswerPublicationHandoff.publicationEnabled !== undefined ? ` publication ${String(readinessFinalAnswerPublicationHandoff.publicationEnabled)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessFinalAnswerPublicationHandoff.finalAnswerGenerationEnabled)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.finalAnswerDeliveryEnabled !== undefined ? ` / delivery ${String(readinessFinalAnswerPublicationHandoff.finalAnswerDeliveryEnabled)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.acknowledgementSaveEnabled !== undefined ? ` / acknowledgement save ${String(readinessFinalAnswerPublicationHandoff.acknowledgementSaveEnabled)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessFinalAnswerPublicationHandoff.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.partialReindexEnabled !== undefined ? ` / partial reindex ${String(readinessFinalAnswerPublicationHandoff.partialReindexEnabled)}` : ''}
                            {readinessFinalAnswerPublicationHandoff.mutationAllowed !== undefined ? ` / mutation ${String(readinessFinalAnswerPublicationHandoff.mutationAllowed)}` : ''}
                          </span>
                          {!!readinessFinalAnswerPublicationHandoff.blockingKeys?.length && (
                            <span>final answer handoff blocking keys: {readinessFinalAnswerPublicationHandoff.blockingKeys.join(', ')}</span>
                          )}
                          {readinessFinalAnswerPublicationHandoff.message && (
                            <span>{readinessFinalAnswerPublicationHandoff.message}</span>
                          )}
                        </>
                      )}
                      {readinessAcknowledgementSaveHandoff && (
                        <>
                          <span>
                            acknowledgement save handoff: {readinessAcknowledgementSaveHandoff.status || 'BLOCKED_FINAL_ANSWER_HANDOFF_INCOMPLETE'}
                            {readinessAcknowledgementSaveHandoff.schema ? ` / ${readinessAcknowledgementSaveHandoff.schema}` : ''}
                            {readinessAcknowledgementSaveHandoff.handoffAvailable !== undefined ? ` / handoff ${String(readinessAcknowledgementSaveHandoff.handoffAvailable)}` : ''}
                            {readinessAcknowledgementSaveHandoff.executionTarget ? ` / ${readinessAcknowledgementSaveHandoff.executionTarget}` : ''}
                            {readinessAcknowledgementSaveHandoff.sourceFinalAnswerPublicationHandoffStatus ? ` / final answer handoff ${readinessAcknowledgementSaveHandoff.sourceFinalAnswerPublicationHandoffStatus}` : ''}
                          </span>
                          <span>
                            acknowledgement receipt:
                            {readinessAcknowledgementSaveHandoff.acknowledgementReceiptRequired !== undefined ? ` required ${String(readinessAcknowledgementSaveHandoff.acknowledgementReceiptRequired)}` : ''}
                            {readinessAcknowledgementSaveHandoff.acknowledgementReceiptModeled !== undefined ? ` / modeled ${String(readinessAcknowledgementSaveHandoff.acknowledgementReceiptModeled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.staleIndexDisclosureModeled !== undefined ? ` / stale disclosure ${String(readinessAcknowledgementSaveHandoff.staleIndexDisclosureModeled)}` : ''}
                          </span>
                          {!!readinessAcknowledgementSaveHandoff.targetFiles?.length && (
                            <span>acknowledgement handoff target files: {readinessAcknowledgementSaveHandoff.targetFiles.join(', ')}</span>
                          )}
                          {!!readinessAcknowledgementSaveHandoff.finalAnswerSections?.length && (
                            <span>acknowledgement handoff sections: {readinessAcknowledgementSaveHandoff.finalAnswerSections.join(', ')}</span>
                          )}
                          {readinessAcknowledgementSaveHandoff.staleIndexDisclosureText && (
                            <span>acknowledgement stale-index disclosure: {readinessAcknowledgementSaveHandoff.staleIndexDisclosureText}</span>
                          )}
                          <span>
                            acknowledgement handoff disabled:
                            {readinessAcknowledgementSaveHandoff.publicationEnabled !== undefined ? ` publication ${String(readinessAcknowledgementSaveHandoff.publicationEnabled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.finalAnswerGenerationEnabled !== undefined ? ` / final answer ${String(readinessAcknowledgementSaveHandoff.finalAnswerGenerationEnabled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.finalAnswerDeliveryEnabled !== undefined ? ` / delivery ${String(readinessAcknowledgementSaveHandoff.finalAnswerDeliveryEnabled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.conversationSaveEnabled !== undefined ? ` / conversation save ${String(readinessAcknowledgementSaveHandoff.conversationSaveEnabled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.acknowledgementSaveEnabled !== undefined ? ` / acknowledgement save ${String(readinessAcknowledgementSaveHandoff.acknowledgementSaveEnabled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.ragFreshnessUpdateEnabled !== undefined ? ` / rag freshness ${String(readinessAcknowledgementSaveHandoff.ragFreshnessUpdateEnabled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.partialReindexEnabled !== undefined ? ` / partial reindex ${String(readinessAcknowledgementSaveHandoff.partialReindexEnabled)}` : ''}
                            {readinessAcknowledgementSaveHandoff.mutationAllowed !== undefined ? ` / mutation ${String(readinessAcknowledgementSaveHandoff.mutationAllowed)}` : ''}
                          </span>
                          {!!readinessAcknowledgementSaveHandoff.blockingKeys?.length && (
                            <span>acknowledgement handoff blocking keys: {readinessAcknowledgementSaveHandoff.blockingKeys.join(', ')}</span>
                          )}
                          {readinessAcknowledgementSaveHandoff.message && (
                            <span>{readinessAcknowledgementSaveHandoff.message}</span>
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
                      {readinessMutationDispatchEnvelopeContractView.show && (
                        <>
                          <span>{readinessMutationDispatchEnvelopeContractView.headerText}</span>
                          <span>{readinessMutationDispatchEnvelopeContractView.idsText}</span>
                          <span>{readinessMutationDispatchEnvelopeContractView.disabledText}</span>
                          {readinessMutationDispatchEnvelopeContractView.expectedOutcomesText && (
                            <span>{readinessMutationDispatchEnvelopeContractView.expectedOutcomesText}</span>
                          )}
                          {readinessMutationDispatchEnvelopeContractView.toolLines.map((line) => (
                            <span key={`mutation-dispatch-tool-${line}`}>{line}</span>
                          ))}
                          {readinessMutationDispatchEnvelopeContractView.approvalLines.map((line) => (
                            <span key={`mutation-dispatch-approval-${line}`}>{line}</span>
                          ))}
                          {readinessMutationDispatchEnvelopeContractView.rollbackText && (
                            <span>{readinessMutationDispatchEnvelopeContractView.rollbackText}</span>
                          )}
                          {readinessMutationDispatchEnvelopeContractView.ragFreshnessText && (
                            <span>{readinessMutationDispatchEnvelopeContractView.ragFreshnessText}</span>
                          )}
                          {readinessMutationDispatchEnvelopeContractView.blockingText && (
                            <span>{readinessMutationDispatchEnvelopeContractView.blockingText}</span>
                          )}
                          {readinessMutationDispatchEnvelopeContractView.message && (
                            <span>{readinessMutationDispatchEnvelopeContractView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationDispatchPreflightBoundaryView.show && (
                        <>
                          <span>{readinessMutationDispatchPreflightBoundaryView.headerText}</span>
                          <span>{readinessMutationDispatchPreflightBoundaryView.agentText}</span>
                          <span>{readinessMutationDispatchPreflightBoundaryView.workspaceText}</span>
                          {readinessMutationDispatchPreflightBoundaryView.requiredCapabilitiesText && (
                            <span>{readinessMutationDispatchPreflightBoundaryView.requiredCapabilitiesText}</span>
                          )}
                          {readinessMutationDispatchPreflightBoundaryView.advertisedCapabilitiesText && (
                            <span>{readinessMutationDispatchPreflightBoundaryView.advertisedCapabilitiesText}</span>
                          )}
                          {readinessMutationDispatchPreflightBoundaryView.capabilityLines.map((line) => (
                            <span key={`mutation-dispatch-preflight-capability-${line}`}>{line}</span>
                          ))}
                          {readinessMutationDispatchPreflightBoundaryView.missingCapabilitiesText && (
                            <span>{readinessMutationDispatchPreflightBoundaryView.missingCapabilitiesText}</span>
                          )}
                          <span>{readinessMutationDispatchPreflightBoundaryView.disabledText}</span>
                          {readinessMutationDispatchPreflightBoundaryView.blockingText && (
                            <span>{readinessMutationDispatchPreflightBoundaryView.blockingText}</span>
                          )}
                          {readinessMutationDispatchPreflightBoundaryView.message && (
                            <span>{readinessMutationDispatchPreflightBoundaryView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationDispatchDecisionModelView.show && (
                        <>
                          <span>{readinessMutationDispatchDecisionModelView.headerText}</span>
                          <span>{readinessMutationDispatchDecisionModelView.idsText}</span>
                          <span>{readinessMutationDispatchDecisionModelView.disabledText}</span>
                          {readinessMutationDispatchDecisionModelView.inputLines.map((line) => (
                            <span key={`mutation-dispatch-decision-input-${line}`}>{line}</span>
                          ))}
                          {readinessMutationDispatchDecisionModelView.blockingText && (
                            <span>{readinessMutationDispatchDecisionModelView.blockingText}</span>
                          )}
                          {readinessMutationDispatchDecisionModelView.refusalText && (
                            <span>{readinessMutationDispatchDecisionModelView.refusalText}</span>
                          )}
                          {readinessMutationDispatchDecisionModelView.message && (
                            <span>{readinessMutationDispatchDecisionModelView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestBlueprintView.show && (
                        <>
                          <span>{readinessMutationRequestBlueprintView.headerText}</span>
                          <span>{readinessMutationRequestBlueprintView.idsText}</span>
                          <span>{readinessMutationRequestBlueprintView.disabledText}</span>
                          {readinessMutationRequestBlueprintView.expectedInputsText && (
                            <span>{readinessMutationRequestBlueprintView.expectedInputsText}</span>
                          )}
                          {readinessMutationRequestBlueprintView.expectedOutputsText && (
                            <span>{readinessMutationRequestBlueprintView.expectedOutputsText}</span>
                          )}
                          {readinessMutationRequestBlueprintView.toolLines.map((line) => (
                            <span key={`mutation-request-blueprint-tool-${line}`}>{line}</span>
                          ))}
                          {readinessMutationRequestBlueprintView.approvalLines.map((line) => (
                            <span key={`mutation-request-blueprint-approval-${line}`}>{line}</span>
                          ))}
                          {readinessMutationRequestBlueprintView.blockingText && (
                            <span>{readinessMutationRequestBlueprintView.blockingText}</span>
                          )}
                          {readinessMutationRequestBlueprintView.message && (
                            <span>{readinessMutationRequestBlueprintView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestCreationGateView.show && (
                        <>
                          <span>{readinessMutationRequestCreationGateView.headerText}</span>
                          <span>{readinessMutationRequestCreationGateView.idsText}</span>
                          <span>{readinessMutationRequestCreationGateView.countsText}</span>
                          <span>{readinessMutationRequestCreationGateView.disabledText}</span>
                          {readinessMutationRequestCreationGateView.policyLines.map((line) => (
                            <span key={`mutation-request-creation-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationRequestCreationGateView.blockingText && (
                            <span>{readinessMutationRequestCreationGateView.blockingText}</span>
                          )}
                          {readinessMutationRequestCreationGateView.message && (
                            <span>{readinessMutationRequestCreationGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestPushGateView.show && (
                        <>
                          <span>{readinessMutationRequestPushGateView.headerText}</span>
                          <span>{readinessMutationRequestPushGateView.idsText}</span>
                          <span>{readinessMutationRequestPushGateView.countsText}</span>
                          <span>{readinessMutationRequestPushGateView.disabledText}</span>
                          {readinessMutationRequestPushGateView.policyLines.map((line) => (
                            <span key={`mutation-request-push-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationRequestPushGateView.blockingText && (
                            <span>{readinessMutationRequestPushGateView.blockingText}</span>
                          )}
                          {readinessMutationRequestPushGateView.message && (
                            <span>{readinessMutationRequestPushGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRequestClaimGateView.show && (
                        <>
                          <span>{readinessMutationRequestClaimGateView.headerText}</span>
                          <span>{readinessMutationRequestClaimGateView.idsText}</span>
                          <span>{readinessMutationRequestClaimGateView.countsText}</span>
                          <span>{readinessMutationRequestClaimGateView.disabledText}</span>
                          {readinessMutationRequestClaimGateView.policyLines.map((line) => (
                            <span key={`mutation-request-claim-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationRequestClaimGateView.blockingText && (
                            <span>{readinessMutationRequestClaimGateView.blockingText}</span>
                          )}
                          {readinessMutationRequestClaimGateView.message && (
                            <span>{readinessMutationRequestClaimGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationExecutionGateView.show && (
                        <>
                          <span>{readinessMutationExecutionGateView.headerText}</span>
                          <span>{readinessMutationExecutionGateView.idsText}</span>
                          <span>{readinessMutationExecutionGateView.countsText}</span>
                          <span>{readinessMutationExecutionGateView.disabledText}</span>
                          {readinessMutationExecutionGateView.policyLines.map((line) => (
                            <span key={`mutation-execution-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationExecutionGateView.blockingText && (
                            <span>{readinessMutationExecutionGateView.blockingText}</span>
                          )}
                          {readinessMutationExecutionGateView.message && (
                            <span>{readinessMutationExecutionGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationWriteHelperSafetyGateView.show && (
                        <>
                          <span>{readinessMutationWriteHelperSafetyGateView.headerText}</span>
                          <span>{readinessMutationWriteHelperSafetyGateView.idsText}</span>
                          <span>{readinessMutationWriteHelperSafetyGateView.countsText}</span>
                          <span>{readinessMutationWriteHelperSafetyGateView.disabledText}</span>
                          {readinessMutationWriteHelperSafetyGateView.policyLines.map((line) => (
                            <span key={`mutation-write-helper-safety-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationWriteHelperSafetyGateView.blockingText && (
                            <span>{readinessMutationWriteHelperSafetyGateView.blockingText}</span>
                          )}
                          {readinessMutationWriteHelperSafetyGateView.message && (
                            <span>{readinessMutationWriteHelperSafetyGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationPostExecutionObservationGateView.show && (
                        <>
                          <span>{readinessMutationPostExecutionObservationGateView.headerText}</span>
                          <span>{readinessMutationPostExecutionObservationGateView.idsText}</span>
                          <span>{readinessMutationPostExecutionObservationGateView.countsText}</span>
                          <span>{readinessMutationPostExecutionObservationGateView.disabledText}</span>
                          {readinessMutationPostExecutionObservationGateView.policyLines.map((line) => (
                            <span key={`mutation-post-execution-observation-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationPostExecutionObservationGateView.blockingText && (
                            <span>{readinessMutationPostExecutionObservationGateView.blockingText}</span>
                          )}
                          {readinessMutationPostExecutionObservationGateView.message && (
                            <span>{readinessMutationPostExecutionObservationGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationObservationAcceptanceGateView.show && (
                        <>
                          <span>{readinessMutationObservationAcceptanceGateView.headerText}</span>
                          <span>{readinessMutationObservationAcceptanceGateView.idsText}</span>
                          <span>{readinessMutationObservationAcceptanceGateView.countsText}</span>
                          <span>{readinessMutationObservationAcceptanceGateView.disabledText}</span>
                          {readinessMutationObservationAcceptanceGateView.policyLines.map((line) => (
                            <span key={`mutation-observation-acceptance-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationObservationAcceptanceGateView.blockingText && (
                            <span>{readinessMutationObservationAcceptanceGateView.blockingText}</span>
                          )}
                          {readinessMutationObservationAcceptanceGateView.message && (
                            <span>{readinessMutationObservationAcceptanceGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationResultIntakePersistenceGateView.show && (
                        <>
                          <span>{readinessMutationResultIntakePersistenceGateView.headerText}</span>
                          <span>{readinessMutationResultIntakePersistenceGateView.idsText}</span>
                          <span>{readinessMutationResultIntakePersistenceGateView.countsText}</span>
                          {readinessMutationResultIntakePersistenceGateView.sourceContextText && (
                            <span>{readinessMutationResultIntakePersistenceGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationResultIntakePersistenceGateView.disabledText}</span>
                          {readinessMutationResultIntakePersistenceGateView.policyLines.map((line) => (
                            <span key={`mutation-result-intake-persistence-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationResultIntakePersistenceGateView.blockingText && (
                            <span>{readinessMutationResultIntakePersistenceGateView.blockingText}</span>
                          )}
                          {readinessMutationResultIntakePersistenceGateView.message && (
                            <span>{readinessMutationResultIntakePersistenceGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRollbackFallbackGateView.show && (
                        <>
                          <span>{readinessMutationRollbackFallbackGateView.headerText}</span>
                          <span>{readinessMutationRollbackFallbackGateView.idsText}</span>
                          <span>{readinessMutationRollbackFallbackGateView.countsText}</span>
                          {readinessMutationRollbackFallbackGateView.sourceContextText && (
                            <span>{readinessMutationRollbackFallbackGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationRollbackFallbackGateView.disabledText}</span>
                          {readinessMutationRollbackFallbackGateView.policyLines.map((line) => (
                            <span key={`mutation-rollback-fallback-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationRollbackFallbackGateView.blockingText && (
                            <span>{readinessMutationRollbackFallbackGateView.blockingText}</span>
                          )}
                          {readinessMutationRollbackFallbackGateView.message && (
                            <span>{readinessMutationRollbackFallbackGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationRagFreshnessGateView.show && (
                        <>
                          <span>{readinessMutationRagFreshnessGateView.headerText}</span>
                          <span>{readinessMutationRagFreshnessGateView.idsText}</span>
                          <span>{readinessMutationRagFreshnessGateView.countsText}</span>
                          {readinessMutationRagFreshnessGateView.observationSummaryText && (
                            <span>{readinessMutationRagFreshnessGateView.observationSummaryText}</span>
                          )}
                          {readinessMutationRagFreshnessGateView.sourceContextText && (
                            <span>{readinessMutationRagFreshnessGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationRagFreshnessGateView.disabledText}</span>
                          {readinessMutationRagFreshnessGateView.policyLines.map((line) => (
                            <span key={`mutation-rag-freshness-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationRagFreshnessGateView.blockingText && (
                            <span>{readinessMutationRagFreshnessGateView.blockingText}</span>
                          )}
                          {readinessMutationRagFreshnessGateView.message && (
                            <span>{readinessMutationRagFreshnessGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationResultAggregationGateView.show && (
                        <>
                          <span>{readinessMutationResultAggregationGateView.headerText}</span>
                          <span>{readinessMutationResultAggregationGateView.idsText}</span>
                          <span>{readinessMutationResultAggregationGateView.countsText}</span>
                          {readinessMutationResultAggregationGateView.sourceContextText && (
                            <span>{readinessMutationResultAggregationGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationResultAggregationGateView.disabledText}</span>
                          {readinessMutationResultAggregationGateView.policyLines.map((line) => (
                            <span key={`mutation-result-aggregation-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationResultAggregationGateView.blockingText && (
                            <span>{readinessMutationResultAggregationGateView.blockingText}</span>
                          )}
                          {readinessMutationResultAggregationGateView.message && (
                            <span>{readinessMutationResultAggregationGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationPublicationGateView.show && (
                        <>
                          <span>{readinessMutationPublicationGateView.headerText}</span>
                          <span>{readinessMutationPublicationGateView.idsText}</span>
                          <span>{readinessMutationPublicationGateView.countsText}</span>
                          <span>{readinessMutationPublicationGateView.disabledText}</span>
                          {readinessMutationPublicationGateView.sourceContextText && (
                            <span>{readinessMutationPublicationGateView.sourceContextText}</span>
                          )}
                          {readinessMutationPublicationGateView.policyLines.map((line) => (
                            <span key={`mutation-publication-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationPublicationGateView.blockingText && (
                            <span>{readinessMutationPublicationGateView.blockingText}</span>
                          )}
                          {readinessMutationPublicationGateView.message && (
                            <span>{readinessMutationPublicationGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalAnswerGenerationGateView.show && (
                        <>
                          <span>{readinessMutationFinalAnswerGenerationGateView.headerText}</span>
                          <span>{readinessMutationFinalAnswerGenerationGateView.idsText}</span>
                          <span>{readinessMutationFinalAnswerGenerationGateView.countsText}</span>
                          <span>{readinessMutationFinalAnswerGenerationGateView.disabledText}</span>
                          {readinessMutationFinalAnswerGenerationGateView.publicationContextText && (
                            <span>{readinessMutationFinalAnswerGenerationGateView.publicationContextText}</span>
                          )}
                          {readinessMutationFinalAnswerGenerationGateView.sourceContextText && (
                            <span>{readinessMutationFinalAnswerGenerationGateView.sourceContextText}</span>
                          )}
                          {readinessMutationFinalAnswerGenerationGateView.policyLines.map((line) => (
                            <span key={`mutation-final-answer-generation-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationFinalAnswerGenerationGateView.blockingText && (
                            <span>{readinessMutationFinalAnswerGenerationGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalAnswerGenerationGateView.message && (
                            <span>{readinessMutationFinalAnswerGenerationGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalAnswerCompletionGateView.show && (
                        <>
                          <span>{readinessMutationFinalAnswerCompletionGateView.headerText}</span>
                          <span>{readinessMutationFinalAnswerCompletionGateView.idsText}</span>
                          <span>{readinessMutationFinalAnswerCompletionGateView.countsText}</span>
                          {readinessMutationFinalAnswerCompletionGateView.generationContextText && (
                            <span>{readinessMutationFinalAnswerCompletionGateView.generationContextText}</span>
                          )}
                          {readinessMutationFinalAnswerCompletionGateView.sourceContextText && (
                            <span>{readinessMutationFinalAnswerCompletionGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationFinalAnswerCompletionGateView.disabledText}</span>
                          {readinessMutationFinalAnswerCompletionGateView.policyLines.map((line) => (
                            <span key={`mutation-final-answer-completion-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationFinalAnswerCompletionGateView.blockingText && (
                            <span>{readinessMutationFinalAnswerCompletionGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalAnswerCompletionGateView.message && (
                            <span>{readinessMutationFinalAnswerCompletionGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalAnswerPersistenceGateView.show && (
                        <>
                          <span>{readinessMutationFinalAnswerPersistenceGateView.headerText}</span>
                          <span>{readinessMutationFinalAnswerPersistenceGateView.idsText}</span>
                          <span>{readinessMutationFinalAnswerPersistenceGateView.countsText}</span>
                          {readinessMutationFinalAnswerPersistenceGateView.sourceContextText && (
                            <span>{readinessMutationFinalAnswerPersistenceGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationFinalAnswerPersistenceGateView.disabledText}</span>
                          {readinessMutationFinalAnswerPersistenceGateView.policyLines.map((line) => (
                            <span key={`mutation-final-answer-persistence-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationFinalAnswerPersistenceGateView.blockingText && (
                            <span>{readinessMutationFinalAnswerPersistenceGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalAnswerPersistenceGateView.message && (
                            <span>{readinessMutationFinalAnswerPersistenceGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalAnswerConversationSaveGateView.show && (
                        <>
                          <span>{readinessMutationFinalAnswerConversationSaveGateView.headerText}</span>
                          <span>{readinessMutationFinalAnswerConversationSaveGateView.idsText}</span>
                          <span>{readinessMutationFinalAnswerConversationSaveGateView.countsText}</span>
                          {readinessMutationFinalAnswerConversationSaveGateView.sourceContextText && (
                            <span>{readinessMutationFinalAnswerConversationSaveGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationFinalAnswerConversationSaveGateView.disabledText}</span>
                          {readinessMutationFinalAnswerConversationSaveGateView.policyLines.map((line) => (
                            <span key={`mutation-final-answer-conversation-save-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationFinalAnswerConversationSaveGateView.blockingText && (
                            <span>{readinessMutationFinalAnswerConversationSaveGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalAnswerConversationSaveGateView.message && (
                            <span>{readinessMutationFinalAnswerConversationSaveGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalAnswerUserVisibleCompletionGateView.show && (
                        <>
                          <span>{readinessMutationFinalAnswerUserVisibleCompletionGateView.headerText}</span>
                          <span>{readinessMutationFinalAnswerUserVisibleCompletionGateView.idsText}</span>
                          <span>{readinessMutationFinalAnswerUserVisibleCompletionGateView.countsText}</span>
                          {readinessMutationFinalAnswerUserVisibleCompletionGateView.sourceContextText && (
                            <span>{readinessMutationFinalAnswerUserVisibleCompletionGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationFinalAnswerUserVisibleCompletionGateView.disabledText}</span>
                          {readinessMutationFinalAnswerUserVisibleCompletionGateView.policyLines.map((line) => (
                            <span key={`mutation-final-answer-user-visible-completion-policy-${line}`}>{line}</span>
                          ))}
                          {readinessMutationFinalAnswerUserVisibleCompletionGateView.blockingText && (
                            <span>{readinessMutationFinalAnswerUserVisibleCompletionGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalAnswerUserVisibleCompletionGateView.message && (
                            <span>{readinessMutationFinalAnswerUserVisibleCompletionGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalResponseHandoffGateView.show && (
                        <>
                          <span>{readinessMutationFinalResponseHandoffGateView.headerText}</span>
                          <span>{readinessMutationFinalResponseHandoffGateView.idsText}</span>
                          <span>{readinessMutationFinalResponseHandoffGateView.countsText}</span>
                          {readinessMutationFinalResponseHandoffGateView.sourceContextText && (
                            <span>{readinessMutationFinalResponseHandoffGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationFinalResponseHandoffGateView.disabledText}</span>
                          {readinessMutationFinalResponseHandoffGateView.policyLines.map((line) => (
                            <span key={`mutation-final-response-handoff-policy-${line}`}>
                              {line}
                            </span>
                          ))}
                          {readinessMutationFinalResponseHandoffGateView.blockingText && (
                            <span>{readinessMutationFinalResponseHandoffGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalResponseHandoffGateView.message && (
                            <span>{readinessMutationFinalResponseHandoffGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalAnswerDeliveryGateView.show && (
                        <>
                          <span>{readinessMutationFinalAnswerDeliveryGateView.headerText}</span>
                          <span>{readinessMutationFinalAnswerDeliveryGateView.idsText}</span>
                          <span>{readinessMutationFinalAnswerDeliveryGateView.countsText}</span>
                          {readinessMutationFinalAnswerDeliveryGateView.sourceContextText && (
                            <span>{readinessMutationFinalAnswerDeliveryGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationFinalAnswerDeliveryGateView.disabledText}</span>
                          {readinessMutationFinalAnswerDeliveryGateView.policyLines.map((line) => (
                            <span key={`mutation-final-answer-delivery-policy-${line}`}>
                              {line}
                            </span>
                          ))}
                          {readinessMutationFinalAnswerDeliveryGateView.blockingText && (
                            <span>{readinessMutationFinalAnswerDeliveryGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalAnswerDeliveryGateView.message && (
                            <span>{readinessMutationFinalAnswerDeliveryGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationFinalAnswerDeliveryReceiptGateView.show && (
                        <>
                          <span>{readinessMutationFinalAnswerDeliveryReceiptGateView.headerText}</span>
                          <span>{readinessMutationFinalAnswerDeliveryReceiptGateView.idsText}</span>
                          <span>{readinessMutationFinalAnswerDeliveryReceiptGateView.countsText}</span>
                          {readinessMutationFinalAnswerDeliveryReceiptGateView.sourceContextText && (
                            <span>{readinessMutationFinalAnswerDeliveryReceiptGateView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationFinalAnswerDeliveryReceiptGateView.disabledText}</span>
                          {readinessMutationFinalAnswerDeliveryReceiptGateView.policyLines.map((line) => (
                            <span key={`mutation-final-answer-delivery-receipt-policy-${line}`}>
                              {line}
                            </span>
                          ))}
                          {readinessMutationFinalAnswerDeliveryReceiptGateView.blockingText && (
                            <span>{readinessMutationFinalAnswerDeliveryReceiptGateView.blockingText}</span>
                          )}
                          {readinessMutationFinalAnswerDeliveryReceiptGateView.message && (
                            <span>{readinessMutationFinalAnswerDeliveryReceiptGateView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationCompletionSummaryView.show && (
                        <>
                          <span>{readinessMutationCompletionSummaryView.headerText}</span>
                          <span>{readinessMutationCompletionSummaryView.idsText}</span>
                          {readinessMutationCompletionSummaryView.sourceContextText && (
                            <span>{readinessMutationCompletionSummaryView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationCompletionSummaryView.disabledText}</span>
                          {readinessMutationCompletionSummaryView.itemLines.map((line) => (
                            <span key={`mutation-completion-${line}`}>
                              {line}
                            </span>
                          ))}
                          {readinessMutationCompletionSummaryView.blockingText && (
                            <span>{readinessMutationCompletionSummaryView.blockingText}</span>
                          )}
                          {readinessMutationCompletionSummaryView.message && (
                            <span>{readinessMutationCompletionSummaryView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationHandoffSummaryView.show && (
                        <>
                          <span>{readinessMutationHandoffSummaryView.headerText}</span>
                          <span>{readinessMutationHandoffSummaryView.idsText}</span>
                          {readinessMutationHandoffSummaryView.sourceContextText && (
                            <span>{readinessMutationHandoffSummaryView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationHandoffSummaryView.disabledText}</span>
                          {readinessMutationHandoffSummaryView.stageLines.map((line) => (
                            <span key={`mutation-handoff-stage-${line}`}>{line}</span>
                          ))}
                          {readinessMutationHandoffSummaryView.blockingText && (
                            <span>{readinessMutationHandoffSummaryView.blockingText}</span>
                          )}
                          {readinessMutationHandoffSummaryView.message && (
                            <span>{readinessMutationHandoffSummaryView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationExecutionReadinessBoundaryView.show && (
                        <>
                          <span>{readinessMutationExecutionReadinessBoundaryView.headerText}</span>
                          <span>{readinessMutationExecutionReadinessBoundaryView.idsText}</span>
                          {readinessMutationExecutionReadinessBoundaryView.sourceText && (
                            <span>{readinessMutationExecutionReadinessBoundaryView.sourceText}</span>
                          )}
                          {readinessMutationExecutionReadinessBoundaryView.sourceContextText && (
                            <span>{readinessMutationExecutionReadinessBoundaryView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationExecutionReadinessBoundaryView.disabledText}</span>
                          {readinessMutationExecutionReadinessBoundaryView.checkLines.map((line) => (
                            <span key={`mutation-execution-readiness-${line}`}>{line}</span>
                          ))}
                          {readinessMutationExecutionReadinessBoundaryView.blockingText && (
                            <span>{readinessMutationExecutionReadinessBoundaryView.blockingText}</span>
                          )}
                          {readinessMutationExecutionReadinessBoundaryView.message && (
                            <span>{readinessMutationExecutionReadinessBoundaryView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationToolRunnerBoundaryView.show && (
                        <>
                          <span>{readinessMutationToolRunnerBoundaryView.headerText}</span>
                          {readinessMutationToolRunnerBoundaryView.sourceText && (
                            <span>{readinessMutationToolRunnerBoundaryView.sourceText}</span>
                          )}
                          {readinessMutationToolRunnerBoundaryView.sourceContextText && (
                            <span>{readinessMutationToolRunnerBoundaryView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationToolRunnerBoundaryView.disabledText}</span>
                          {readinessMutationToolRunnerBoundaryView.checkLines.map((line) => (
                            <span key={`mutation-tool-runner-${line}`}>{line}</span>
                          ))}
                          {readinessMutationToolRunnerBoundaryView.blockingText && (
                            <span>{readinessMutationToolRunnerBoundaryView.blockingText}</span>
                          )}
                          {readinessMutationToolRunnerBoundaryView.message && (
                            <span>{readinessMutationToolRunnerBoundaryView.message}</span>
                          )}
                        </>
                      )}
                      {readinessMutationResultCompletionBoundaryView.show && (
                        <>
                          <span>{readinessMutationResultCompletionBoundaryView.headerText}</span>
                          {readinessMutationResultCompletionBoundaryView.sourceText && (
                            <span>{readinessMutationResultCompletionBoundaryView.sourceText}</span>
                          )}
                          {readinessMutationResultCompletionBoundaryView.sourceContextText && (
                            <span>{readinessMutationResultCompletionBoundaryView.sourceContextText}</span>
                          )}
                          <span>{readinessMutationResultCompletionBoundaryView.disabledText}</span>
                          {readinessMutationResultCompletionBoundaryView.checkLines.map((line) => (
                            <span key={`mutation-result-completion-${line}`}>{line}</span>
                          ))}
                          {readinessMutationResultCompletionBoundaryView.blockingText && (
                            <span>{readinessMutationResultCompletionBoundaryView.blockingText}</span>
                          )}
                          {readinessMutationResultCompletionBoundaryView.message && (
                            <span>{readinessMutationResultCompletionBoundaryView.message}</span>
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
                      {readinessFreshObservationEnqueueBoundaryView.show && (
                        <>
                          <span>{readinessFreshObservationEnqueueBoundaryView.boundaryText}</span>
                          {readinessFreshObservationEnqueueBoundaryView.plannedRequestLines.map((line) => (
                            <span key={line}>{line}</span>
                          ))}
                        </>
                      )}
                      {(readinessPatchExecutionGate.requiredBeforeEnablement || []).slice(0, 5).map((item) => (
                        <span key={item}>{item}</span>
                      ))}
                    </div>
                  )}
                  {readinessSnapshotSummaryView.show && (
                    <div className="failure-item">
                      <strong>{readinessSnapshotSummaryView.headerText}</strong>
                      {readinessSnapshotSummaryView.message && <span>{readinessSnapshotSummaryView.message}</span>}
                      {readinessSnapshotSummaryView.linkageText && <span>{readinessSnapshotSummaryView.linkageText}</span>}
                      {readinessSnapshotSummaryView.stateText && <span>{readinessSnapshotSummaryView.stateText}</span>}
                      {readinessSnapshotSummaryView.manifestText && <span>{readinessSnapshotSummaryView.manifestText}</span>}
                      {readinessSnapshotSummaryView.checkLines.map((line) => (
                        <span key={line}>{line}</span>
                      ))}
                      {readinessSnapshotSummaryView.latestManifestText && <span>{readinessSnapshotSummaryView.latestManifestText}</span>}
                      {readinessSnapshotSummaryView.emptyText && <span>{readinessSnapshotSummaryView.emptyText}</span>}
                    </div>
                  )}
                  {readinessRollbackSummaryView.show && (
                    <div className="failure-item">
                      <strong>{readinessRollbackSummaryView.headerText}</strong>
                      {readinessRollbackSummaryView.message && <span>{readinessRollbackSummaryView.message}</span>}
                      {readinessRollbackSummaryView.linkageText && <span>{readinessRollbackSummaryView.linkageText}</span>}
                      {readinessRollbackSummaryView.blockingText && <span>{readinessRollbackSummaryView.blockingText}</span>}
                      {readinessRollbackSummaryView.fileCheckLines.map((line) => (
                        <span key={line}>{line}</span>
                      ))}
                      {readinessRollbackSummaryView.overflowText && <span>{readinessRollbackSummaryView.overflowText}</span>}
                    </div>
                  )}
                  {readinessChecksSummaryView.checkRows.map((check) => (
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

export { CodeSourceManagementPanel, CodeWorkspace };





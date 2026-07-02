import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';
import { continueAgentLoopRunnerAfterObservation } from '../../features/code/runner/agentLoopRunnerObservationContinuationClient.js';
import { refreshAgentLoopRunnerQueuedObservation } from '../../features/code/runner/agentLoopRunnerQueuedObservationClient.js';
import { enqueueAgentLoopRunnerSelectedReadOnly } from '../../features/code/runner/agentLoopRunnerSelectedReadOnlyClient.js';
import { previewAgentLoopRunnerToolSelection } from '../../features/code/runner/agentLoopRunnerToolSelectionPreviewClient.js';
import { buildCodeWorkspaceReadinessSmokeProps } from './codeWorkspaceReadinessSmokeHarness.mjs';

const runnerPreparedPreview = {
  status: 'RECORDED',
  actionKey: 'QUEUE_READ_ONLY_OBSERVATION',
  runnerDecision: 'PREPARED_READ_ONLY_CANDIDATE',
  reason: 'Prepared a read-only git.status Local Agent observation.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  candidate: {
    toolName: 'git.status',
    approvalState: 'NOT_REQUIRED',
    sideEffectful: false,
    requiresApproval: false,
    enqueueEnabled: false,
    mutationAllowed: false,
    input: {
      mutationAllowed: false,
      freshObservationOnly: true,
    },
  },
};

const selectedReadOnlyResponse = {
  status: 'RECORDED',
  actionKey: 'QUEUE_READ_ONLY_OBSERVATION',
  runnerDecision: 'ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION',
  reason: 'Queued the model-selected read-only Local Agent git.status observation. Mutation remains disabled.',
  modelToolSelectionAttempted: true,
  modelToolSelectionAccepted: true,
  selectedByModel: true,
  requestCreationEnabled: true,
  enqueueEnabled: true,
  pushEnabled: true,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  selection: {
    runnerDecision: 'MODEL_SELECTED_READ_ONLY_OBSERVATION',
    candidate: {
      toolName: 'git.status',
      approvalState: 'NOT_REQUIRED',
      sideEffectful: false,
      requiresApproval: false,
      mutationAllowed: false,
    },
  },
  queuedRequest: {
    requestId: 'request-selected-read-only-1',
    request: {
      toolName: 'git.status',
      approvalState: 'NOT_REQUIRED',
      input: {
        mutationAllowed: false,
        freshObservationOnly: true,
      },
    },
  },
};

const toolSelectionPreviewResponse = {
  status: 'RECORDED',
  actionKey: 'QUEUE_READ_ONLY_OBSERVATION',
  selectionDecision: 'MODEL_SELECTED_READ_ONLY_CANDIDATE',
  reason: 'Model selected the read-only git.status candidate. Mutation remains disabled.',
  modelToolSelectionAttempted: true,
  modelToolSelectionAccepted: true,
  selectedByModel: true,
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  candidate: {
    toolName: 'git.status',
    approvalState: 'NOT_REQUIRED',
    readOnly: true,
    sideEffectful: false,
    requiresApproval: false,
    mutationAllowed: false,
  },
  modelDecision: {
    toolName: 'git.status',
    readOnly: true,
    requiresApproval: false,
    mutationAllowed: false,
    reason: 'Check current workspace state.',
  },
  guardrails: {
    modelToolSelectionEnabled: true,
    allowedTools: ['git.status'],
    mutationAllowed: false,
  },
};

const queuedObservationResponse = {
  requestId: 'request-selected-read-only-1',
  executionTarget: 'USER_LOCAL_AGENT',
  toolName: 'git.status',
  approvalState: 'NOT_REQUIRED',
  status: 'SUCCEEDED',
  input: {
    mutationAllowed: false,
    freshObservationOnly: true,
  },
  output: {
    repositoryVerification: {
      status: 'MATCH',
    },
  },
  responseWarnings: [],
};

const observationContinuationResponse = {
  requestId: 'request-selected-read-only-1',
  status: 'SUCCEEDED',
  continuationDecision: 'NEXT_MODEL_TOOL_PREVIEW_READY',
  reason: 'The read-only Local Agent observation succeeded; the next model tool-selection preview is ready without enqueueing or mutation.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  mutationEnabled: false,
  iterationCount: 1,
  maxIterations: 6,
  remainingIterations: 5,
  iterationLimitReached: false,
  observation: queuedObservationResponse,
  runnerPreview: runnerPreparedPreview,
  toolSelectionPreview: toolSelectionPreviewResponse,
};

const observationContinuationLimitReachedResponse = {
  requestId: 'request-selected-read-only-1',
  status: 'SUCCEEDED',
  continuationDecision: 'ITERATION_LIMIT_REACHED',
  reason: 'The read-only continuation budget has been reached; no next model decision was previewed.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  mutationEnabled: false,
  iterationCount: 6,
  maxIterations: 6,
  remainingIterations: 0,
  iterationLimitReached: true,
  observation: queuedObservationResponse,
  runnerPreview: null,
  toolSelectionPreview: null,
};

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
});

try {
  const { CodeWorkspace } = await vite.ssrLoadModule('/src/components/code/CodeWorkspace.jsx');
  const enqueueRequests = [];
  const toolSelectionRequests = [];
  const observationRequests = [];
  const continuationRequests = [];
  const timelineRequests = [];
  let enqueueResult = null;
  let toolSelectionPreview = null;
  let queuedObservationResult = null;
  let observationContinuation = null;

  const props = {
    ...buildCodeWorkspaceReadinessSmokeProps({
      requestId: 'request-selected-read-only-route-1',
    }),
    codeAgentLoopRunnerPreview: null,
    codeAgentLoopRunnerToolSelectionPreview: null,
    codeAgentLoopRunnerEnqueueResult: null,
    codeAgentLoopRunnerObservationContinuation: null,
    localAgentStatus: {
      state: 'CONNECTED',
      agentId: 'agent-1',
      message: 'Local Agent connected.',
      workspaces: [
        {
          workspaceId: 'workspace-1',
          approved: true,
          name: 'learnbot',
          path: 'C:/work/learnbot',
        },
      ],
    },
    loading: () => false,
    previewCodeAgentLoopRunnerToolSelection: async (loopPreview) => {
      toolSelectionPreview = await previewAgentLoopRunnerToolSelection({
        request: async (path, options) => {
          toolSelectionRequests.push({ path, options });
          return toolSelectionPreviewResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-tool-selection-preview');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setToolSelectionPreview: (value) => {
          toolSelectionPreview = value;
        },
      });
      return toolSelectionPreview;
    },
    enqueueCodeAgentLoopRunnerSelectedReadOnly: async (loopPreview) => {
      enqueueResult = await enqueueAgentLoopRunnerSelectedReadOnly({
        request: async (path, options) => {
          enqueueRequests.push({ path, options });
          return selectedReadOnlyResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-enqueue-selected-read-only');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setEnqueueResult: (value) => {
          enqueueResult = value;
        },
      });
      return enqueueResult;
    },
    refreshCodeAgentLoopRunnerQueuedObservation: async (requestId) => {
      queuedObservationResult = await refreshAgentLoopRunnerQueuedObservation({
        request: async (path) => {
          observationRequests.push(path);
          return queuedObservationResponse;
        },
        run: async (label, task) => {
          assert.equal(label, `code-agent-loop-runner-queued-observation-${requestId}`);
          return await task();
        },
        requestId,
        setObservationResult: (value) => {
          queuedObservationResult = value;
        },
      });
      timelineRequests.push(`/api/code-agent/loop/timelines?repositoryId=${props.selectedRepositoryId}&limit=5`);
      observationContinuation = await continueAgentLoopRunnerAfterObservation({
        request: async (path, options) => {
          continuationRequests.push({ path, options });
          return observationContinuationResponse;
        },
        run: async (label, task) => {
          assert.equal(label, `code-agent-loop-runner-observation-continuation-${requestId}`);
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: props.codeAgentLoopPreview.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        requestId,
        setContinuation: (value) => {
          observationContinuation = value;
        },
      });
      return queuedObservationResult;
    },
  };

  const initialMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, props));
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Queue read-only step(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Preview model tool(?:(?!<\/button>)[\s\S])*<\/button>/
  );

  const preparedMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreparedPreview,
  }));
  assert.match(
    preparedMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Queue read-only step(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    preparedMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Preview model tool(?:(?!<\/button>)[\s\S])*<\/button>/
  );

  await props.previewCodeAgentLoopRunnerToolSelection(props.codeAgentLoopPreview);

  assert.deepEqual(toolSelectionRequests, [
    {
      path: '/api/code-agent/loop/runner/select-tool-preview',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(enqueueRequests.length, 0);
  assert.equal(toolSelectionPreview.selectionDecision, 'MODEL_SELECTED_READ_ONLY_CANDIDATE');
  assert.equal(toolSelectionPreview.requestCreationEnabled, false);
  assert.equal(toolSelectionPreview.enqueueEnabled, false);
  assert.equal(toolSelectionPreview.pushEnabled, false);
  assert.equal(toolSelectionPreview.claimEnabled, false);
  assert.equal(toolSelectionPreview.mutationEnabled, false);

  const toolSelectionMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreparedPreview,
    codeAgentLoopRunnerToolSelectionPreview: toolSelectionPreview,
  }));
  assert.match(
    toolSelectionMarkup,
    /agent loop runner model tool preview: QUEUE_READ_ONLY_OBSERVATION \/ MODEL_SELECTED_READ_ONLY_CANDIDATE \/ model selected/
  );
  assert.match(
    toolSelectionMarkup,
    /agent loop runner model decision: attempted true \/ accepted true \/ tool git\.status \/ read-only true \/ approval NOT_REQUIRED \/ mutation false/
  );
  assert.match(
    toolSelectionMarkup,
    /agent loop runner model tool controls: request creation false \/ enqueue false \/ push false \/ claim false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );

  await props.enqueueCodeAgentLoopRunnerSelectedReadOnly(props.codeAgentLoopPreview);

  assert.deepEqual(enqueueRequests, [
    {
      path: '/api/code-agent/loop/runner/enqueue-selected-read-only',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
        },
      },
    },
  ]);
  assert.equal(enqueueRequests.some((call) => call.path.includes('enqueue-read-only')), false);
  assert.equal(enqueueResult.runnerDecision, 'ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION');
  assert.equal(enqueueResult.queuedRequest.request.toolName, 'git.status');
  assert.equal(enqueueResult.queuedRequest.request.input.mutationAllowed, false);
  assert.equal(enqueueResult.requestCreationEnabled, true);
  assert.equal(enqueueResult.enqueueEnabled, true);
  assert.equal(enqueueResult.pushEnabled, true);
  assert.equal(enqueueResult.claimEnabled, false);
  assert.equal(enqueueResult.mutationEnabled, false);
  assert.equal(enqueueResult.finalResultEnabled, false);
  assert.equal(enqueueResult.publicationEnabled, false);
  assert.equal(enqueueResult.acknowledgementEnabled, false);

  const queuedMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreparedPreview,
    codeAgentLoopRunnerEnqueueResult: enqueueResult,
  }));
  assert.match(
    queuedMarkup,
    /agent loop runner selected read-only: QUEUE_READ_ONLY_OBSERVATION \/ ENQUEUED_MODEL_SELECTED_READ_ONLY_OBSERVATION \/ model selected/
  );
  assert.match(queuedMarkup, /read-only queued/);
  assert.match(
    queuedMarkup,
    /agent loop runner selected read-only controls: request creation true \/ enqueue true \/ push true \/ claim false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );
  assert.match(
    queuedMarkup,
    /agent loop runner selected read-only tool: git\.status \/ approval NOT_REQUIRED \/ mutation false \/ fresh observation true/
  );
  assert.match(
    queuedMarkup,
    /<button\b(?=[^>]*class="ghost-button compact-action")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Refresh read-only observation(?:(?!<\/button>)[\s\S])*<\/button>/
  );

  await props.refreshCodeAgentLoopRunnerQueuedObservation(enqueueResult.queuedRequest.requestId);

  assert.deepEqual(observationRequests, ['/api/local-agents/tools/request-selected-read-only-1']);
  assert.deepEqual(timelineRequests, ['/api/code-agent/loop/timelines?repositoryId=repo-1&limit=5']);
  assert.deepEqual(continuationRequests, [
    {
      path: '/api/code-agent/loop/runner/continue-after-observation',
      options: {
        method: 'POST',
        json: {
          repositoryId: 'repo-1',
          loopId: 'loop-preview-1',
          agentId: 'agent-1',
          workspaceId: 'workspace-1',
          requestId: 'request-selected-read-only-1',
        },
      },
    },
  ]);
  assert.equal(queuedObservationResult.status, 'SUCCEEDED');
  assert.equal(queuedObservationResult.toolName, 'git.status');
  assert.equal(queuedObservationResult.input.mutationAllowed, false);
  assert.equal(observationContinuation.continuationDecision, 'NEXT_MODEL_TOOL_PREVIEW_READY');
  assert.equal(observationContinuation.requestCreationEnabled, false);
  assert.equal(observationContinuation.enqueueEnabled, false);
  assert.equal(observationContinuation.mutationEnabled, false);
  assert.equal(observationContinuation.iterationCount, 1);
  assert.equal(observationContinuation.maxIterations, 6);
  assert.equal(observationContinuation.remainingIterations, 5);
  assert.equal(observationContinuation.iterationLimitReached, false);

  const observationMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreparedPreview,
    codeAgentLoopRunnerEnqueueResult: enqueueResult,
    codeAgentLoopRunnerQueuedObservationResult: queuedObservationResult,
    codeAgentLoopRunnerObservationContinuation: observationContinuation,
    codeAgentLoopRunnerToolSelectionPreview: observationContinuation.toolSelectionPreview,
  }));
  assert.match(
    observationMarkup,
    /agent loop runner queued observation: SUCCEEDED \/ tool git\.status \/ target USER_LOCAL_AGENT \/ approval NOT_REQUIRED \/ mutation false \/ fresh observation true \/ repository verification MATCH/
  );
  assert.match(
    observationMarkup,
    /agent loop runner observation continuation: SUCCEEDED \/ NEXT_MODEL_TOOL_PREVIEW_READY/
  );
  assert.match(
    observationMarkup,
    /agent loop runner observation continuation controls: request creation false \/ enqueue false \/ push false \/ claim false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );
  assert.match(
    observationMarkup,
    /agent loop runner observation continuation budget: iteration 1 \/ max 6 \/ remaining 5 \/ limit reached false/
  );
  assert.match(
    observationMarkup,
    /agent loop runner observation continuation next model preview: QUEUE_READ_ONLY_OBSERVATION \/ MODEL_SELECTED_READ_ONLY_CANDIDATE \/ tool git\.status \/ mutation false/
  );
  assert.match(
    observationMarkup,
    /<button\b(?=[^>]*class="ghost-button compact-action")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Continue read-only step(?:(?!<\/button>)[\s\S])*<\/button>/
  );

  const limitReachedMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreparedPreview,
    codeAgentLoopRunnerEnqueueResult: enqueueResult,
    codeAgentLoopRunnerQueuedObservationResult: queuedObservationResult,
    codeAgentLoopRunnerObservationContinuation: observationContinuationLimitReachedResponse,
    codeAgentLoopRunnerToolSelectionPreview: null,
  }));
  assert.match(
    limitReachedMarkup,
    /agent loop runner observation continuation: SUCCEEDED \/ ITERATION_LIMIT_REACHED/
  );
  assert.match(
    limitReachedMarkup,
    /agent loop runner observation continuation budget: iteration 6 \/ max 6 \/ remaining 0 \/ limit reached true/
  );
  assert.doesNotMatch(
    limitReachedMarkup,
    /agent loop runner observation continuation next model preview:/
  );
  assert.match(
    limitReachedMarkup,
    /<button\b(?=[^>]*class="ghost-button compact-action")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Continue read-only step(?:(?!<\/button>)[\s\S])*<\/button>/
  );
} finally {
  await vite.close();
}

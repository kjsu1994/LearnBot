import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';
import { refreshAgentLoopRunnerQueuedObservation } from '../../features/code/runner/agentLoopRunnerQueuedObservationClient.js';
import { enqueueAgentLoopRunnerSelectedReadOnly } from '../../features/code/runner/agentLoopRunnerSelectedReadOnlyClient.js';
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

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
});

try {
  const { CodeWorkspace } = await vite.ssrLoadModule('/src/components/code/CodeWorkspace.jsx');
  const enqueueRequests = [];
  const observationRequests = [];
  const timelineRequests = [];
  let enqueueResult = null;
  let queuedObservationResult = null;

  const props = {
    ...buildCodeWorkspaceReadinessSmokeProps({
      requestId: 'request-selected-read-only-route-1',
    }),
    codeAgentLoopRunnerPreview: null,
    codeAgentLoopRunnerEnqueueResult: null,
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
      return queuedObservationResult;
    },
  };

  const initialMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, props));
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Queue read-only step(?:(?!<\/button>)[\s\S])*<\/button>/
  );

  const preparedMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreparedPreview,
  }));
  assert.match(
    preparedMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Queue read-only step(?:(?!<\/button>)[\s\S])*<\/button>/
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
  assert.equal(queuedObservationResult.status, 'SUCCEEDED');
  assert.equal(queuedObservationResult.toolName, 'git.status');
  assert.equal(queuedObservationResult.input.mutationAllowed, false);

  const observationMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreparedPreview,
    codeAgentLoopRunnerEnqueueResult: enqueueResult,
    codeAgentLoopRunnerQueuedObservationResult: queuedObservationResult,
  }));
  assert.match(
    observationMarkup,
    /agent loop runner queued observation: SUCCEEDED \/ tool git\.status \/ target USER_LOCAL_AGENT \/ approval NOT_REQUIRED \/ mutation false \/ fresh observation true \/ repository verification MATCH/
  );
} finally {
  await vite.close();
}

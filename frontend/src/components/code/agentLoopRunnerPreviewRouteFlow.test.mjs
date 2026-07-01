import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';
import { previewAgentLoopRunner } from '../../features/code/agentLoopRunnerPreviewClient.js';
import { enqueueAgentLoopRunnerReadOnly } from '../../features/code/agentLoopRunnerReadOnlyEnqueueClient.js';
import { buildCodeWorkspaceReadinessSmokeProps } from './codeWorkspaceReadinessSmokeHarness.mjs';
import { assertNoForbiddenTrueFlags } from './mutationDisabledFlagGuard.js';

const latestAttempt = {
  mutationRequestCreationGate: {
    status: 'REFUSED_CREATION_DISABLED',
    expectedRequestCount: 4,
    durableMutationExecutionRowCount: 0,
    persistedRequestCount: 0,
    pushedRequestCount: 0,
    claimableRequestCount: 0,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
  },
};

const runnerPreviewResponse = {
  status: 'RECORDED',
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
  reason: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: {
    schema: 'learnbot.code-agent.creation-disabled-handoff-summary.v1',
    status: 'READY_HANDOFF_CREATION_DISABLED',
    sourceBoundaryStatus: 'RELEASE_REFUSED_GATE_DISABLED',
    expectedRequestCount: 4,
    durableMutationExecutionRowCount: 0,
    persistedRequestCount: 0,
    pushedRequestCount: 0,
    claimableRequestCount: 0,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    finalResultEnabled: false,
    publicationEnabled: false,
    acknowledgementEnabled: false,
    runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
    message: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
  },
};

const runnerEnqueueResponse = {
  status: 'RECORDED',
  actionKey: 'READY_HANDOFF_CREATION_DISABLED',
  runnerDecision: 'NOT_ENQUEUED',
  reason: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
  requestCreationEnabled: false,
  enqueueEnabled: false,
  pushEnabled: false,
  claimEnabled: false,
  mutationEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  acknowledgementEnabled: false,
  queuedRequest: null,
  handoffSummary: {
    schema: 'learnbot.code-agent.creation-disabled-handoff-summary.v1',
    status: 'READY_HANDOFF_CREATION_DISABLED',
    sourceBoundaryStatus: 'RELEASE_REFUSED_GATE_DISABLED',
    expectedRequestCount: 4,
    durableMutationExecutionRowCount: 0,
    persistedRequestCount: 0,
    pushedRequestCount: 0,
    claimableRequestCount: 0,
    requestCreationEnabled: false,
    enqueueEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    finalResultEnabled: false,
    publicationEnabled: false,
    acknowledgementEnabled: false,
    runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
    message: 'Mutation handoff is ready, but Local Agent mutation request creation is disabled.',
  },
  preview: {
    runnerDecision: 'WAIT_CREATION_GATE_DISABLED',
    requestCreationEnabled: false,
    pushEnabled: false,
    claimEnabled: false,
    mutationEnabled: false,
    handoffSummary: {
      status: 'READY_HANDOFF_CREATION_DISABLED',
    },
  },
};

const releaseGateRunnerPreviewResponse = {
  status: 'RECORDED',
  actionKey: 'WAIT_FOR_RELEASE_GATE',
  runnerDecision: 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS',
  reason: 'Approved held patch requires fresh Local Agent observations before release.',
  runnerAutoEnqueueEnabled: false,
  freshObservationAutoEnqueueEnabled: false,
  sourcePatchRequestCreationEnabled: false,
  sourcePatchPushEnabled: false,
  sourcePatchClaimEnabled: false,
  mutationEnabled: false,
  verificationCommandExecutionEnabled: false,
  rollbackRestoreEnabled: false,
  ragFreshnessUpdateEnabled: false,
  finalResultEnabled: false,
  publicationEnabled: false,
  finalAnswerGenerationEnabled: false,
  deliveryEnabled: false,
  acknowledgementEnabled: false,
  handoffSummary: {
    schema: 'learnbot.code-agent.release-gate-fresh-observation-handoff.v1',
    status: 'WAIT_FOR_RELEASE_GATE',
    sourceEventType: 'LOCAL_AGENT_APPROVAL_DECISION',
    sourceSequenceNumber: 12,
    sourceRequestId: 'source-request-route-1',
    approvalState: 'APPROVED',
    approvalRequestHeld: true,
    releaseRequired: true,
    readinessRoute: 'GET /api/local-agents/tools/source-request-route-1/readiness',
    freshObservationsRoute: 'POST /api/local-agents/tools/source-request-route-1/fresh-observations',
    releaseBoundaryRoute: 'POST /api/local-agents/tools/source-request-route-1/release',
    runnerAutoEnqueueEnabled: false,
    freshObservationAutoEnqueueEnabled: false,
    sourcePatchRequestCreationEnabled: false,
    sourcePatchPushEnabled: false,
    sourcePatchClaimEnabled: false,
    mutationEnabled: false,
    verificationCommandExecutionEnabled: false,
    rollbackRestoreEnabled: false,
    ragFreshnessUpdateEnabled: false,
    finalResultEnabled: false,
    publicationEnabled: false,
    finalAnswerGenerationEnabled: false,
    deliveryEnabled: false,
    acknowledgementEnabled: false,
    runnerDecision: 'WAIT_RELEASE_GATE_FRESH_OBSERVATIONS',
    message: 'Approved held patch requires fresh Local Agent observations before release.',
  },
};

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
});

try {
  const { CodeWorkspace } = await vite.ssrLoadModule('/src/components/code/CodeWorkspace.jsx');
  const requests = [];
  const enqueueRequests = [];
  let runnerPreview = null;
  let enqueueResult = 'stale';

  const props = {
    ...buildCodeWorkspaceReadinessSmokeProps({
      requestId: 'request-route-flow-1',
      latestAttempt,
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
    previewCodeAgentLoopRunner: async (loopPreview) => {
      runnerPreview = await previewAgentLoopRunner({
        request: async (path, options) => {
          requests.push({ path, options });
          return runnerPreviewResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-preview');
          return await task();
        },
        repositoryId: props.selectedRepositoryId,
        loopId: loopPreview?.loopId,
        agentId: 'agent-1',
        workspaceId: 'workspace-1',
        setPreview: (value) => {
          runnerPreview = value;
        },
        setEnqueueResult: (value) => {
          enqueueResult = value;
        },
      });
      return runnerPreview;
    },
    enqueueCodeAgentLoopRunnerReadOnly: async (loopPreview) => {
      enqueueResult = await enqueueAgentLoopRunnerReadOnly({
        request: async (path, options) => {
          enqueueRequests.push({ path, options });
          return runnerEnqueueResponse;
        },
        run: async (label, task) => {
          assert.equal(label, 'code-agent-loop-runner-enqueue-read-only');
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
  };

  assert.equal(assertNoForbiddenTrueFlags(runnerPreviewResponse, 'runnerPreviewResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(runnerEnqueueResponse, 'runnerEnqueueResponse'), true);
  assert.equal(assertNoForbiddenTrueFlags(releaseGateRunnerPreviewResponse, 'releaseGateRunnerPreviewResponse'), true);
  const initialMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, props));
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]*disabled)[^>]*>(?:(?!<\/button>)[\s\S])*Preview runner state(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    initialMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.doesNotMatch(initialMarkup, /agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED/);

  await props.previewCodeAgentLoopRunner(props.codeAgentLoopPreview);

  assert.deepEqual(requests, [
    {
      path: '/api/code-agent/loop/runner/preview',
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
  assert.equal(requests.some((call) => call.path.includes('enqueue-read-only')), false);
  assert.equal(enqueueResult, null);
  assert.equal(runnerPreview.runnerDecision, 'WAIT_CREATION_GATE_DISABLED');
  assert.equal(runnerPreview.requestCreationEnabled, false);
  assert.equal(runnerPreview.enqueueEnabled, false);
  assert.equal(runnerPreview.pushEnabled, false);
  assert.equal(runnerPreview.claimEnabled, false);
  assert.equal(runnerPreview.mutationEnabled, false);

  const updatedMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreview,
    codeAgentLoopRunnerEnqueueResult: null,
  }));
  assert.match(
    updatedMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    updatedMarkup,
    /agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED \/ learnbot\.code-agent\.creation-disabled-handoff-summary\.v1 \/ runner WAIT_CREATION_GATE_DISABLED \/ boundary RELEASE_REFUSED_GATE_DISABLED/
  );
  assert.match(
    updatedMarkup,
    /agent loop runner handoff counts: expected 4 \/ durable mutation rows 0 \/ persisted 0 \/ pushed 0 \/ claimable 0/
  );
  assert.match(
    updatedMarkup,
    /agent loop runner handoff disabled: request creation false \/ enqueue false \/ push false \/ claim false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );
  assert.match(
    updatedMarkup,
    /Mutation handoff is ready, but Local Agent mutation request creation is disabled/
  );

  await props.enqueueCodeAgentLoopRunnerReadOnly(props.codeAgentLoopPreview);

  assert.deepEqual(enqueueRequests, [
    {
      path: '/api/code-agent/loop/runner/enqueue-read-only',
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
  assert.equal(enqueueRequests.some((call) => call.path.includes('/preview')), false);
  assert.equal(enqueueResult.runnerDecision, 'NOT_ENQUEUED');
  assert.equal(enqueueResult.queuedRequest, null);
  assert.equal(enqueueResult.requestCreationEnabled, false);
  assert.equal(enqueueResult.enqueueEnabled, false);
  assert.equal(enqueueResult.pushEnabled, false);
  assert.equal(enqueueResult.claimEnabled, false);
  assert.equal(enqueueResult.mutationEnabled, false);

  const enqueueMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: runnerPreview,
    codeAgentLoopRunnerEnqueueResult: enqueueResult,
  }));
  assert.match(
    enqueueMarkup,
    /agent loop runner handoff: READY_HANDOFF_CREATION_DISABLED \/ learnbot\.code-agent\.creation-disabled-handoff-summary\.v1 \/ runner NOT_ENQUEUED \/ summary runner WAIT_CREATION_GATE_DISABLED \/ boundary RELEASE_REFUSED_GATE_DISABLED/
  );
  assert.match(
    enqueueMarkup,
    /agent loop runner nested preview: WAIT_CREATION_GATE_DISABLED \/ READY_HANDOFF_CREATION_DISABLED \/ request creation false \/ push false \/ claim false \/ mutation false/
  );
  assert.match(
    enqueueMarkup,
    /agent loop runner handoff disabled: request creation false \/ enqueue false \/ push false \/ claim false \/ final result false \/ publication false \/ acknowledgement false \/ mutation false/
  );

  const releaseGateMarkup = renderToStaticMarkup(React.createElement(CodeWorkspace, {
    ...props,
    codeAgentLoopRunnerPreview: releaseGateRunnerPreviewResponse,
    codeAgentLoopRunnerEnqueueResult: null,
  }));
  assert.match(
    releaseGateMarkup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]* disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Check enqueue refusal(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner handoff: WAIT_FOR_RELEASE_GATE \/ learnbot\.code-agent\.release-gate-fresh-observation-handoff\.v1 \/ runner WAIT_RELEASE_GATE_FRESH_OBSERVATIONS/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner release handoff source: source request source-request-route-1 \/ source event LOCAL_AGENT_APPROVAL_DECISION \/ sequence 12 \/ approval APPROVED \/ held true \/ release required true/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner release handoff routes: readiness GET \/api\/local-agents\/tools\/source-request-route-1\/readiness \/ fresh observations POST \/api\/local-agents\/tools\/source-request-route-1\/fresh-observations \/ release boundary POST \/api\/local-agents\/tools\/source-request-route-1\/release/
  );
  assert.match(
    releaseGateMarkup,
    /agent loop runner handoff disabled: runner auto-enqueue false \/ fresh observation auto-enqueue false \/ source patch request creation false \/ source patch push false \/ source patch claim false \/ verification command execution false \/ rollback restore false \/ RAG freshness update false \/ final result false \/ publication false \/ final answer generation false \/ delivery false \/ acknowledgement false \/ mutation false/
  );
} finally {
  await vite.close();
}

import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';
import { releaseLocalAgentPatchForExecution } from '../../features/code/localAgent/releaseForExecutionClient.js';
import { buildCodeWorkspaceReadinessSmokeProps } from './codeWorkspaceReadinessSmokeHarness.mjs';

const requestId = 'request-release-ui-1';
const releaseAttemptId = 'attempt-release-ui-1';

const latestAttempt = {
  releaseAttemptId,
  releaseAttemptFinalReadiness: {
    status: 'READY_RELEASE_DISABLED',
    ready: true,
    releaseGateEnabled: true,
    claimEnabled: false,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimable: false,
    mutationAllowed: false,
    applyEnabled: false,
    testEnabled: false,
    rollbackRestoreEnabled: false,
    evidenceComplete: true,
    patchPreconditionsPassed: true,
    freshnessStatus: 'FRESH',
    stale: false,
  },
  releaseAttemptDisplaySummary: {
    show: true,
    linkedEvidenceComplete: true,
    releaseReadyButDisabled: true,
    releaseReadinessStatus: 'READY_RELEASE_DISABLED',
    disabledFlags: {
      requestCreationEnabled: false,
      pushEnabled: false,
      claimEnabled: false,
      mutationAllowed: false,
    },
    blockingReasons: [],
  },
  freshObservationEvidenceCompleteness: {
    status: 'ALL_LINKED_RELEASE_DISABLED',
    complete: true,
    requestCreationEnabled: false,
    pushEnabled: false,
    claimable: false,
    mutationAllowed: false,
  },
};

const releasedPatch = {
  requestId,
  status: 'APPROVED',
  approvalState: 'APPROVED',
  toolName: 'patch.apply',
  input: {
    sourceRequestId: requestId,
    releaseAttemptId,
    mutationAllowed: true,
    dryRunOnly: false,
  },
};

const vite = await createServer({
  server: { middlewareMode: true },
  appType: 'custom',
  logLevel: 'silent',
});

try {
  const { CodeWorkspace } = await vite.ssrLoadModule('/src/components/code/CodeWorkspace.jsx');
  const releaseCalls = [];
  let storedPatch = null;
  let storedInspection = { stale: true };

  const props = {
    ...buildCodeWorkspaceReadinessSmokeProps({
      requestId,
      latestAttempt,
      readinessOverrides: {
        readyToRelease: true,
        checks: [
          {
            key: 'releaseGateEnabled',
            passed: true,
            message: 'Patch execution release is enabled for the guarded Local Agent release path.',
          },
        ],
        patchReleaseReadiness: {
          status: 'PRECONDITIONS_READY_RELEASE_ENABLED',
          preconditionsPassed: true,
          releaseGateEnabled: true,
          mutationEnabled: false,
        },
        patchExecutionGate: {
          status: 'READY_RELEASE_ENABLED',
          preconditionsPassed: true,
          releaseGateEnabled: true,
          claimEnabled: false,
          writeHelperEnabled: false,
          mutationEnabled: false,
          releaseAttemptModel: {
            status: 'READY_RELEASE_ATTEMPT_DISABLED',
            latestAttempt,
          },
        },
      },
    }),
    releaseCodeAgentLocalPatchForExecution: async (sourceRequestId) => {
      return await releaseLocalAgentPatchForExecution({
        request: async (path, options) => {
          releaseCalls.push({ path, options });
          return releasedPatch;
        },
        run: async (label, task) => {
          assert.equal(label, `code-agent-local-release-for-execution-${sourceRequestId}`);
          return await task();
        },
        requestId: sourceRequestId,
        setPatchRequest: (value) => {
          storedPatch = value;
        },
        setInspection: (value) => {
          storedInspection = value;
        },
      });
    },
  };

  const markup = renderToStaticMarkup(React.createElement(CodeWorkspace, props));
  assert.match(
    markup,
    /<button\b(?=[^>]*class="ghost-button")(?![^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Release Local Agent patch(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.doesNotMatch(
    markup,
    /<button\b(?=[^>]*disabled="")[^>]*>(?:(?!<\/button>)[\s\S])*Release Local Agent patch disabled(?:(?!<\/button>)[\s\S])*<\/button>/
  );
  assert.match(markup, /release attempt final readiness: READY_RELEASE_DISABLED \/ ready true/);
  assert.match(markup, /final release gate: true/);

  const result = await props.releaseCodeAgentLocalPatchForExecution(requestId);

  assert.deepEqual(releaseCalls, [
    {
      path: `/api/local-agents/tools/${requestId}/release-for-execution`,
      options: { method: 'POST' },
    },
  ]);
  assert.equal(result, releasedPatch);
  assert.equal(storedPatch, releasedPatch);
  assert.equal(storedInspection, null);
  assert.equal(result.input.mutationAllowed, true);
  assert.equal(result.input.dryRunOnly, false);
  assert.equal(result.input.releaseAttemptId, releaseAttemptId);
} finally {
  await vite.close();
}

console.log('codeWorkspaceReleaseForExecutionRouteFlow smoke passed');

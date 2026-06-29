export function buildCodeWorkspaceReadinessSmokeProps({ requestId, latestAttempt }) {
  return {
    repositories: [],
    selectedRepositoryId: 'repo-1',
    codeAgentInstruction: 'test',
    codeAgentPatch: {
      summary: 'patch proposal',
      valid: true,
      files: [],
      warnings: [],
    },
    codeAgentLocalPatchRequest: {
      requestId,
      status: 'APPROVED_HELD',
      toolName: 'patch.apply',
      approvalState: 'APPROVED',
      input: {
        sourceRepository: {},
      },
    },
    codeAgentLocalPatchReadiness: {
      requestId,
      readyToRelease: false,
      message: 'Held patch request is not ready for Local Agent execution.',
      patchExecutionGate: {
        status: 'BLOCKED_RELEASE_DISABLED',
        claimEnabled: false,
        releaseGateEnabled: false,
        message: 'Patch execution remains disabled.',
      },
      releaseAttemptModel: {
        status: 'READY_RELEASE_ATTEMPT_DISABLED',
        latestAttempt,
      },
    },
  };
}

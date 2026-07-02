export async function reviewAgentLoopRunnerReleaseGate({
  request,
  run,
  repositoryId,
  loopId,
  agentId = null,
  workspaceId = null,
  setReleaseReviewResult = () => {},
}) {
  if (!repositoryId || !loopId) {
    setReleaseReviewResult(null);
    return null;
  }
  const result = await run('code-agent-loop-runner-release-review', async () => {
    const reviewResult = await request('/api/code-agent/loop/runner/release-review', {
      method: 'POST',
      json: {
        repositoryId,
        loopId,
        agentId,
        workspaceId,
      },
    });
    setReleaseReviewResult(reviewResult);
    return reviewResult;
  });
  if (result === false) {
    setReleaseReviewResult(null);
    return null;
  }
  return result;
}

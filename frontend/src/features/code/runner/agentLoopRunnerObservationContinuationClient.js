export async function continueAgentLoopRunnerAfterObservation({
  request,
  run,
  repositoryId,
  loopId,
  agentId = null,
  workspaceId = null,
  requestId,
  setContinuation = () => {},
}) {
  if (!repositoryId || !requestId) {
    setContinuation(null);
    return null;
  }
  const result = await run(`code-agent-loop-runner-observation-continuation-${requestId}`, async () => {
    const continuation = await request('/api/code-agent/loop/runner/continue-after-observation', {
      method: 'POST',
      json: {
        repositoryId,
        loopId,
        agentId,
        workspaceId,
        requestId,
      },
    });
    setContinuation(continuation);
    return continuation;
  });
  if (result === false) {
    setContinuation(null);
    return null;
  }
  return result;
}

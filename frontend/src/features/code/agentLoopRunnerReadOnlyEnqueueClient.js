export async function enqueueAgentLoopRunnerReadOnly({
  request,
  run,
  repositoryId,
  loopId,
  agentId = null,
  workspaceId = null,
  setEnqueueResult = () => {},
}) {
  if (!repositoryId || !loopId) {
    setEnqueueResult(null);
    return null;
  }
  const result = await run('code-agent-loop-runner-enqueue-read-only', async () => {
    const enqueueResult = await request('/api/code-agent/loop/runner/enqueue-read-only', {
      method: 'POST',
      json: {
        repositoryId,
        loopId,
        agentId,
        workspaceId,
      },
    });
    setEnqueueResult(enqueueResult);
    return enqueueResult;
  });
  if (result === false) {
    setEnqueueResult(null);
    return null;
  }
  return result;
}

export async function previewAgentLoopRunner({
  request,
  run,
  repositoryId,
  loopId,
  agentId = null,
  workspaceId = null,
  setPreview = () => {},
  setEnqueueResult = () => {},
}) {
  if (!repositoryId || !loopId) {
    setPreview(null);
    setEnqueueResult(null);
    return null;
  }
  const result = await run('code-agent-loop-runner-preview', async () => {
    const preview = await request('/api/code-agent/loop/runner/preview', {
      method: 'POST',
      json: {
        repositoryId,
        loopId,
        agentId,
        workspaceId,
      },
    });
    setPreview(preview);
    setEnqueueResult(null);
    return preview;
  });
  if (result === false) {
    setPreview(null);
    setEnqueueResult(null);
    return null;
  }
  return result;
}

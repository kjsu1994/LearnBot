export async function previewAgentLoopRunnerFinalResultPublication({
  request,
  run,
  repositoryId,
  loopId,
  agentId = null,
  workspaceId = null,
  setFinalResultPublicationPreview = () => {},
}) {
  if (!repositoryId || !loopId) {
    setFinalResultPublicationPreview(null);
    return null;
  }
  const result = await run('code-agent-loop-runner-final-result-publication-preview', async () => {
    const preview = await request('/api/code-agent/loop/runner/final-result-publication-preview', {
      method: 'POST',
      json: {
        repositoryId,
        loopId,
        agentId,
        workspaceId,
      },
    });
    setFinalResultPublicationPreview(preview);
    return preview;
  });
  if (result === false) {
    setFinalResultPublicationPreview(null);
    return null;
  }
  return result;
}

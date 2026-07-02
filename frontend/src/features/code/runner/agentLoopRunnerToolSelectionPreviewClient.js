export async function previewAgentLoopRunnerToolSelection({
  request,
  run,
  repositoryId,
  loopId,
  agentId = null,
  workspaceId = null,
  setToolSelectionPreview = () => {},
}) {
  if (!repositoryId || !loopId) {
    setToolSelectionPreview(null);
    return null;
  }
  const result = await run('code-agent-loop-runner-tool-selection-preview', async () => {
    const preview = await request('/api/code-agent/loop/runner/select-tool-preview', {
      method: 'POST',
      json: {
        repositoryId,
        loopId,
        agentId,
        workspaceId,
      },
    });
    setToolSelectionPreview(preview);
    return preview;
  });
  if (result === false) {
    setToolSelectionPreview(null);
    return null;
  }
  return result;
}

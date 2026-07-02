export async function previewAgentLoopRunnerM8EntryReadiness({
  request,
  run,
  repositoryId,
  loopId,
  agentId = null,
  workspaceId = null,
  setM8EntryReadiness = () => {},
}) {
  if (!repositoryId || !loopId) {
    setM8EntryReadiness(null);
    return null;
  }
  const result = await run('code-agent-loop-runner-m8-entry-readiness', async () => {
    const readiness = await request('/api/code-agent/loop/runner/m8-entry-readiness', {
      method: 'POST',
      json: {
        repositoryId,
        loopId,
        agentId,
        workspaceId,
      },
    });
    setM8EntryReadiness(readiness);
    return readiness;
  });
  if (result === false) {
    setM8EntryReadiness(null);
    return null;
  }
  return result;
}

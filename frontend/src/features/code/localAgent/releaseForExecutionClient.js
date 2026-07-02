export async function releaseLocalAgentPatchForExecution({
  request,
  run,
  requestId,
  setPatchRequest = () => {},
  setInspection = () => {},
}) {
  if (!requestId) return null;
  return await run(`code-agent-local-release-for-execution-${requestId}`, async () => {
    const result = await request(`/api/local-agents/tools/${requestId}/release-for-execution`, {
      method: 'POST',
    });
    setPatchRequest(result);
    setInspection(null);
    return result;
  });
}

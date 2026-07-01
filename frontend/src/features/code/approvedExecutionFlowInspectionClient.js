export async function inspectApprovedExecutionFlow({
  request,
  run,
  setInspection = () => {},
  requestIds = [],
  releaseAttemptId = null,
}) {
  if (releaseAttemptId) {
    return await run(`code-agent-approved-execution-flow-inspection-${releaseAttemptId}`, async () => {
      const result = await request('/api/local-agents/tools/approved-execution-flow/inspection/by-release-attempt', {
        method: 'POST',
        json: { releaseAttemptId },
      });
      setInspection(result);
      return result;
    });
  }
  const safeRequestIds = Array.isArray(requestIds) ? requestIds.filter(Boolean) : [];
  if (!safeRequestIds.length) return null;
  return await run(`code-agent-approved-execution-flow-inspection-${safeRequestIds.join('-')}`, async () => {
    const result = await request('/api/local-agents/tools/approved-execution-flow/inspection', {
      method: 'POST',
      json: { requestIds: safeRequestIds },
    });
    setInspection(result);
    return result;
  });
}

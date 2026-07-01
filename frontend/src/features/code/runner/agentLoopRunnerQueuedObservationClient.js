export async function refreshAgentLoopRunnerQueuedObservation({
  request,
  run,
  requestId,
  setObservationResult = () => {},
}) {
  if (!requestId) {
    setObservationResult(null);
    return null;
  }
  const result = await run(`code-agent-loop-runner-queued-observation-${requestId}`, async () => {
    const observationResult = await request(`/api/local-agents/tools/${requestId}`);
    setObservationResult(observationResult);
    return observationResult;
  });
  if (result === false) {
    setObservationResult(null);
    return null;
  }
  return result;
}

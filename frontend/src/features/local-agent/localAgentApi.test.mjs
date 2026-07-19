import assert from 'node:assert/strict';
import { LOCAL_AGENT_ENDPOINTS, normalizeDeviceResponse, normalizeEnrollment, normalizeUserCode, trustedDownloadPath } from './localAgentApi.js';

const devices = normalizeDeviceResponse({ devices: [{ agentId: 'agent-1', hostname: 'DEV-PC', presenceState: 'connected', agentVersion: '1.2.3', selected: true }] });
assert.equal(devices.length, 1);
assert.equal(devices[0].id, 'agent-1');
assert.equal(devices[0].machineName, 'DEV-PC');
assert.equal(devices[0].state, 'CONNECTED');
assert.equal(devices[0].selected, true);
assert.equal(LOCAL_AGENT_ENDPOINTS.deviceSelection('agent/1'), '/api/local-agents/agent%2F1/selection');
assert.equal(normalizeUserCode('ab12-cd34'), 'AB12CD34');
const enrollment = normalizeEnrollment({ enrollment: { enrollmentId: 'enroll-1', deviceName: 'NOTEBOOK', installationId: '12345678-abcd-efgh', createdAt: '2026-07-19T00:00:00Z' } });
assert.equal(enrollment.id, 'enroll-1');
assert.equal(enrollment.fingerprint, '12345678-ABC');
assert.equal(enrollment.requestedAt, '2026-07-19T00:00:00Z');
assert.equal(trustedDownloadPath('/downloads/local-agent/releases/1.2.3/LearnBot.msix'), '/downloads/local-agent/releases/1.2.3/LearnBot.msix');
assert.equal(trustedDownloadPath('https://example.com/agent.msix'), LOCAL_AGENT_ENDPOINTS.stableInstaller);
assert.equal(trustedDownloadPath('/api/private/token'), LOCAL_AGENT_ENDPOINTS.stableInstaller);
console.log('local-agent-api-adapter-ok');

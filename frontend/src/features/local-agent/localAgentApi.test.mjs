import assert from 'node:assert/strict';
import {
  LOCAL_AGENT_ENDPOINTS, isInsecurePrivateNetworkLocation, isRfc1918Ipv4Literal,
  localAgentConnectUri, localAgentDistributionEndpoints, normalizeDeviceResponse, normalizeEnrollment,
  normalizeUserCode, trustedDownloadPath,
} from './localAgentApi.js';

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
assert.equal(
  trustedDownloadPath('/downloads/local-agent/trust/LearnBotLocalAgentSigning_ABC123.cer', ''),
  '/downloads/local-agent/trust/LearnBotLocalAgentSigning_ABC123.cer',
);
assert.equal(trustedDownloadPath('https://example.com/signing.cer', ''), '');
assert.equal(trustedDownloadPath('https://example.com/agent.msix'), LOCAL_AGENT_ENDPOINTS.stableInstaller);
assert.equal(trustedDownloadPath('/api/private/token'), LOCAL_AGENT_ENDPOINTS.stableInstaller);
assert.equal(isRfc1918Ipv4Literal('192.168.1.72'), true);
assert.equal(isRfc1918Ipv4Literal('172.31.5.10'), true);
assert.equal(isRfc1918Ipv4Literal('172.32.5.10'), false);
assert.equal(isRfc1918Ipv4Literal('203.0.113.10'), false);
assert.equal(isInsecurePrivateNetworkLocation({ protocol: 'http:', hostname: '192.168.1.72' }), true);
assert.equal(isInsecurePrivateNetworkLocation({ protocol: 'https:', hostname: '192.168.1.72' }), false);
assert.equal(isInsecurePrivateNetworkLocation({ protocol: 'http:', hostname: 'learnbot.internal' }), false);
assert.equal(
  localAgentConnectUri({ origin: 'http://10.20.30.40:8083' }),
  'learnbot-local-agent://connect?server=http%3A%2F%2F10.20.30.40%3A8083',
);
assert.deepEqual(
  localAgentDistributionEndpoints({ protocol: 'http:', hostname: '192.168.1.72' }),
  {
    channel: 'pilot',
    release: LOCAL_AGENT_ENDPOINTS.pilotRelease,
    installer: LOCAL_AGENT_ENDPOINTS.pilotInstaller,
  },
);
console.log('local-agent-api-adapter-ok');

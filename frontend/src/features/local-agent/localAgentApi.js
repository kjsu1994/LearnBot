const LOCAL_AGENT_ENDPOINTS = Object.freeze({
  devices: '/api/local-agents',
  deviceSelection: (agentId) => `/api/local-agents/${encodeURIComponent(agentId)}/selection`,
  enrollmentLookup: '/api/local-agents/enrollments/lookup',
  enrollmentDecision: (enrollmentId) => `/api/local-agents/enrollments/${encodeURIComponent(enrollmentId)}/decision`,
  stableRelease: '/downloads/local-agent/stable/release.json',
  stableInstaller: '/downloads/local-agent/stable/LearnBotLocalAgent.appinstaller',
  connectProtocol: 'learnbot-local-agent://connect',
});

function normalizeUserCode(value = '') {
  return String(value).toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 16);
}

function normalizeDeviceResponse(payload) {
  const source = Array.isArray(payload)
    ? payload
    : payload?.devices || payload?.agents || [];

  return source
    .filter(Boolean)
    .map((device) => ({
      ...device,
      id: device.id || device.agentId,
      agentId: device.agentId || device.id,
      machineName: device.machineName || device.deviceName || device.hostname || device.label || '이름 없는 PC',
      platform: device.platform || device.osName || 'Windows',
      state: String(device.presenceState || device.state || device.status || 'OFFLINE').toUpperCase(),
      version: device.version || device.agentVersion || '-',
      lastSeenAt: device.lastSeenAt || device.lastHeartbeatAt || null,
      transport: device.activeTransport || device.transport || device.configuredTransport || '-',
      updateState: String(device.updateState || device.updateStatus || 'CURRENT').toUpperCase(),
      selected: Boolean(device.selected ?? device.isSelected),
      workspaces: Array.isArray(device.workspaces) ? device.workspaces : [],
    }))
    .filter((device) => device.id);
}

function normalizeEnrollment(payload) {
  const enrollment = payload?.enrollment || payload || {};
  const installationIdentity = enrollment.fingerprint || enrollment.installationFingerprint || enrollment.installationId || '';
  return {
    ...enrollment,
    id: enrollment.id || enrollment.enrollmentId,
    machineName: enrollment.machineName || enrollment.deviceName || enrollment.hostname || '이름 없는 PC',
    platform: enrollment.platform || enrollment.osName || enrollment.os || 'Windows',
    architecture: enrollment.architecture || enrollment.arch || 'x64',
    version: enrollment.version || enrollment.agentVersion || '-',
    expiresAt: enrollment.expiresAt || null,
    fingerprint: installationIdentity ? String(installationIdentity).slice(0, 12).toUpperCase() : '',
    requestedAt: enrollment.requestedAt || enrollment.createdAt || null,
    state: String(enrollment.state || enrollment.status || 'PENDING').toUpperCase(),
  };
}

function trustedDownloadPath(candidate, fallback = LOCAL_AGENT_ENDPOINTS.stableInstaller) {
  if (!candidate) return fallback;
  try {
    const currentOrigin = typeof window === 'undefined' ? 'https://learnbot.invalid' : window.location.origin;
    const parsed = new URL(candidate, currentOrigin);
    if (parsed.origin !== currentOrigin) return fallback;
    if (!parsed.pathname.startsWith('/downloads/local-agent/')) return fallback;
    return `${parsed.pathname}${parsed.search}`;
  } catch {
    return fallback;
  }
}

async function fetchReleaseMetadata() {
  const response = await fetch(LOCAL_AGENT_ENDPOINTS.stableRelease, {
    method: 'GET',
    credentials: 'omit',
    headers: { Accept: 'application/json' },
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`설치 파일 정보를 불러오지 못했습니다. (${response.status})`);
  const release = await response.json();
  return {
    ...release,
    version: release.version || release.latestVersion || '-',
    installerUrl: trustedDownloadPath(release.appInstallerUrl || release.installerUrl),
  };
}

async function fetchDevices(request) {
  return normalizeDeviceResponse(await request(LOCAL_AGENT_ENDPOINTS.devices));
}

async function lookupEnrollment(request, userCode) {
  return normalizeEnrollment(await request(LOCAL_AGENT_ENDPOINTS.enrollmentLookup, {
    method: 'POST',
    json: { userCode: normalizeUserCode(userCode) },
  }));
}

async function decideEnrollment(request, enrollmentId, decision) {
  const normalizedDecision = String(decision).toUpperCase();
  if (!['APPROVE', 'DENY'].includes(normalizedDecision)) {
    throw new Error('지원하지 않는 승인 결정입니다.');
  }
  return request(LOCAL_AGENT_ENDPOINTS.enrollmentDecision(enrollmentId), {
    method: 'POST',
    json: { decision: normalizedDecision },
  });
}

async function revokeDevice(request, agentId) {
  return request(`${LOCAL_AGENT_ENDPOINTS.devices}/${encodeURIComponent(agentId)}`, { method: 'DELETE' });
}

async function selectDevice(request, agentId) {
  return request(LOCAL_AGENT_ENDPOINTS.deviceSelection(agentId), { method: 'PUT' });
}

export {
  LOCAL_AGENT_ENDPOINTS,
  decideEnrollment,
  fetchDevices,
  fetchReleaseMetadata,
  lookupEnrollment,
  normalizeDeviceResponse,
  normalizeEnrollment,
  normalizeUserCode,
  revokeDevice,
  selectDevice,
  trustedDownloadPath,
};

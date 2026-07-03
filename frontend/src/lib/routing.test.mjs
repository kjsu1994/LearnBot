import assert from 'node:assert/strict';
import { routePaths } from '../config/constants.js';
import { normalizeRoute, routeToView } from './routing.js';

assert.equal(normalizeRoute('/settings/local-agent/device'), routePaths.localAgentDevice);
assert.equal(routeToView(routePaths.localAgentDevice), 'code');
assert.equal(normalizeRoute('/settings/local-agent/device/'), routePaths.localAgentDevice);
assert.equal(normalizeRoute('/settings/local-agent/device/unknown'), routePaths.home);

console.log('routing-local-agent-device-route-ok');

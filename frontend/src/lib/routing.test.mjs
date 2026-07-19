import assert from 'node:assert/strict';
import { routePaths } from '../config/constants.js';
import { normalizeNavigationTarget, normalizeRoute, postLoginTarget, routeToView, safeReturnTo } from './routing.js';

assert.equal(normalizeRoute('/settings/local-agent/device'), routePaths.localAgentDevice);
assert.equal(routeToView(routePaths.localAgent), 'localAgent');
assert.equal(routeToView(routePaths.localAgentConnect), 'localAgent');
assert.equal(routeToView(routePaths.localAgentDevice), 'code');
assert.equal(normalizeRoute('/settings/local-agent/device/'), routePaths.localAgentDevice);
assert.equal(normalizeRoute('/settings/local-agent/device/unknown'), routePaths.home);
assert.equal(normalizeNavigationTarget('/settings/local-agent/connect?user_code=AB12'), '/settings/local-agent/connect?user_code=AB12');
assert.equal(normalizeNavigationTarget('/settings/local-agent/device?user_code=AB12'), '/settings/local-agent/device?user_code=AB12');
assert.equal(safeReturnTo('?returnTo=%2Fsettings%2Flocal-agent%2Fdevice%3Fuser_code%3DAB12'), '/settings/local-agent/device?user_code=AB12');
assert.equal(safeReturnTo('?returnTo=https%3A%2F%2Fevil.example%2Fsteal'), routePaths.home);
assert.equal(safeReturnTo('?returnTo=%2F%2Fevil.example%2Fsteal'), routePaths.home);
assert.equal(postLoginTarget(routePaths.localAgentDevice, '?user_code=AB12'), '/settings/local-agent/device?user_code=AB12');
assert.equal(postLoginTarget(routePaths.localAgentConnect, '?user_code=AB12'), '/settings/local-agent/connect?user_code=AB12');

console.log('routing-local-agent-device-route-ok');

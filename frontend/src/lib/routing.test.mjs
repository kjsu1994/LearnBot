import assert from 'node:assert/strict';
import { routePaths } from '../config/constants.js';
import { normalizeNavigationTarget, normalizeRoute, postLoginTarget, routeToView, safeReturnTo } from './routing.js';
for (const path of ['/settings/local-agent', '/settings/local-agent/connect', '/settings/local-agent/device']) {
  assert.equal(normalizeRoute(path), routePaths.home);
  assert.equal(normalizeNavigationTarget(path + '?user_code=AB12'), routePaths.home);
}
for (const path of [routePaths.code, routePaths.docs, routePaths.saved]) {
  assert.equal(normalizeRoute(path), path);
  assert.equal(postLoginTarget(path), path);
}
assert.equal(routeToView(routePaths.docs), 'docs');
assert.equal(safeReturnTo('?returnTo=https%3A%2F%2Fevil.example%2Fsteal'), routePaths.home);
assert.equal(safeReturnTo('?returnTo=%2F%2Fevil.example%2Fsteal'), routePaths.home);
console.log('RAG routing and retired route contracts passed');

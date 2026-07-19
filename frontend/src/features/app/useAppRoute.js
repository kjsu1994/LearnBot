import { useEffect, useState } from 'react';
import { routePaths } from '../../config/constants.js';
import { normalizeNavigationTarget, normalizeRoute, routeToView } from '../../lib/routing.js';

export function useAppRoute() {
  const [routePath, setRoutePath] = useState(() => normalizeRoute(window.location.pathname));
  const [activeView, setActiveView] = useState(() => routeToView(normalizeRoute(window.location.pathname)));

  useEffect(() => {
    function handleRouteChange() {
      const normalized = normalizeRoute(window.location.pathname);
      if (window.location.pathname !== normalized) {
        window.history.replaceState({}, '', normalized);
      }
      setRoutePath(normalized);
    }
    handleRouteChange();
    window.addEventListener('popstate', handleRouteChange);
    return () => window.removeEventListener('popstate', handleRouteChange);
  }, []);

  useEffect(() => {
    setActiveView(routeToView(routePath));
  }, [routePath]);

  function navigateTo(path) {
    const nextTarget = normalizeNavigationTarget(path);
    const nextUrl = new URL(nextTarget, window.location.origin);
    if (`${window.location.pathname}${window.location.search}` !== nextTarget) {
      window.history.pushState({}, '', nextTarget);
    }
    setRoutePath(nextUrl.pathname);
  }

  return {
    activeView,
    navigateTo,
    routePath,
    routePaths,
  };
}

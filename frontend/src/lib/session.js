import { tokenKey } from '../config/constants.js';

function readStoredToken() {
  return localStorage.getItem(tokenKey) || sessionStorage.getItem(tokenKey) || '';
}

function storeSessionToken(nextToken, rememberLogin) {
  clearStoredToken();
  if (!nextToken) {
    return;
  }
  const storage = rememberLogin ? localStorage : sessionStorage;
  storage.setItem(tokenKey, nextToken);
}

function clearStoredToken() {
  localStorage.removeItem(tokenKey);
  sessionStorage.removeItem(tokenKey);
}

export { clearStoredToken, readStoredToken, storeSessionToken };

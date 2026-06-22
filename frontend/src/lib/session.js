import { tokenKey } from '../config/constants.js';

const REMEMBER_TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const DEFAULT_TOKEN_TTL_MS = 8 * 60 * 60 * 1000;
let inMemoryToken = '';

function isStorageAvailable(storage) {
  try {
    const key = '__learnbot_session_probe__';
    storage.setItem(key, '1');
    storage.removeItem(key);
    return true;
  } catch {
    return false;
  }
}

function safeGet(storage, key) {
  try {
    return storage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(storage, key, value) {
  try {
    storage.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

function safeRemove(storage, key) {
  try {
    storage.removeItem(key);
  } catch {
    // Intentionally ignore.
  }
}

function parseStoredTokenValue(raw) {
  if (!raw || typeof raw !== 'string') {
    return '';
  }
  const text = raw.trim();
  if (!text) return '';
  try {
    const parsed = JSON.parse(text);
    if (parsed && typeof parsed.token === 'string') {
      const token = parsed.token.trim();
      if (!token) return '';
      if (parsed.expiresAt && Number(parsed.expiresAt) < Date.now()) {
        return '';
      }
      return token;
    }
  } catch {
    // Legacy plain token format.
  }
  return text;
}

function persistToken(storage, token, rememberLogin) {
  if (!isStorageAvailable(storage)) return false;
  const expiresAt = rememberLogin
    ? Date.now() + REMEMBER_TOKEN_TTL_MS
    : Date.now() + DEFAULT_TOKEN_TTL_MS;
  const payload = {
    v: 1,
    token,
    rememberLogin: Boolean(rememberLogin),
    expiresAt,
  };
  return safeSet(storage, tokenKey, JSON.stringify(payload));
}

function readTokenFrom(storage) {
  const raw = safeGet(storage, tokenKey);
  return parseStoredTokenValue(raw);
}

function loadTokenFromAnyStorage() {
  const rawSessionToken = typeof window !== 'undefined' && window.sessionStorage
    ? readTokenFrom(window.sessionStorage)
    : '';
  if (rawSessionToken) {
    return rawSessionToken;
  }
  const rawLocalToken = typeof window !== 'undefined' && window.localStorage
    ? readTokenFrom(window.localStorage)
    : '';
  if (!rawLocalToken) {
    return '';
  }

  // Migrate legacy plain-string values to versioned payload so future reads are explicit.
  if (typeof window !== 'undefined' && isStorageAvailable(window.localStorage)) {
    safeSet(window.localStorage, tokenKey, JSON.stringify({
      v: 1,
      token: rawLocalToken,
      rememberLogin: true,
      expiresAt: Date.now() + REMEMBER_TOKEN_TTL_MS,
    }));
  }

  return rawLocalToken;
}

function readStoredToken() {
  if (inMemoryToken) {
    return inMemoryToken;
  }
  inMemoryToken = loadTokenFromAnyStorage();
  return inMemoryToken;
}

function hasPersistentSession() {
  if (typeof window === 'undefined' || !window.localStorage || !isStorageAvailable(window.localStorage)) {
    return false;
  }
  return Boolean(readTokenFrom(window.localStorage));
}

function storeSessionTokenForExistingMode(nextToken) {
  storeSessionToken(nextToken, hasPersistentSession());
}

function storeSessionToken(nextToken, rememberLogin) {
  if (typeof window === 'undefined') {
    inMemoryToken = nextToken || '';
    return;
  }
  clearStoredToken();
  if (!nextToken) {
    return;
  }
  inMemoryToken = nextToken;
  if (isStorageAvailable(window.sessionStorage)) {
    persistToken(window.sessionStorage, nextToken, false);
  }
  if (rememberLogin && isStorageAvailable(window.localStorage)) {
    persistToken(window.localStorage, nextToken, true);
  }
}

function clearStoredToken() {
  inMemoryToken = '';
  if (typeof window === 'undefined') return;
  if (window.localStorage && isStorageAvailable(window.localStorage)) {
    safeRemove(window.localStorage, tokenKey);
  }
  if (window.sessionStorage && isStorageAvailable(window.sessionStorage)) {
    safeRemove(window.sessionStorage, tokenKey);
  }
}

export { clearStoredToken, readStoredToken, storeSessionToken, hasPersistentSession, storeSessionTokenForExistingMode };

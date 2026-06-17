const apiBase = import.meta.env.VITE_API_BASE_URL ?? '';

async function fetchJson(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.token) {
    headers.set('Authorization', `Bearer ${options.token}`);
  }
  let body = options.body;
  if (Object.prototype.hasOwnProperty.call(options, 'json')) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.json);
  }

  const response = await fetch(`${apiBase}${path}`, {
    method: options.method || 'GET',
    headers,
    body,
  });
  if (!response.ok) {
    const message = await responseMessage(response);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function fetchBlob(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.token) {
    headers.set('Authorization', `Bearer ${options.token}`);
  }

  const response = await fetch(`${apiBase}${path}`, {
    method: options.method || 'GET',
    headers,
  });
  if (!response.ok) {
    const message = await responseMessage(response);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return response.blob();
}

async function responseMessage(response) {
  const text = await response.text();
  if (!text) {
    return `요청 처리에 실패했습니다. (${response.status})`;
  }
  try {
    const data = JSON.parse(text);
    return data.message || data.error || text;
  } catch {
    return text;
  }
}

export { fetchJson, fetchBlob };

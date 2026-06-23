const apiBase = import.meta.env.VITE_API_BASE_URL ?? '';

async function fetchJson(path, options = {}) {
  const headers = new Headers(options.headers || {});
  let body = options.body;
  if (Object.prototype.hasOwnProperty.call(options, 'json')) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.json);
  }

  const response = await fetch(`${apiBase}${path}`, {
    method: options.method || 'GET',
    headers,
    body,
    credentials: 'include',
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
  const response = await fetch(`${apiBase}${path}`, {
    method: options.method || 'GET',
    headers,
    credentials: 'include',
  });
  if (!response.ok) {
    const message = await responseMessage(response);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return response.blob();
}

async function fetchSse(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set('Accept', 'text/event-stream');
  let body = options.body;
  if (Object.prototype.hasOwnProperty.call(options, 'json')) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.json);
  }
  const response = await fetch(`${apiBase}${path}`, {
    method: options.method || 'POST',
    headers,
    body,
    credentials: 'include',
    signal: options.signal,
  });
  if (!response.ok) {
    const message = await responseMessage(response);
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (!response.body) return null;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let completed = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        completed = true;
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() || '';
      for (const frame of frames) {
        const event = parseSseFrame(frame);
        if (event) options.onEvent?.(event);
      }
    }
    buffer += decoder.decode();
    const tail = parseSseFrame(buffer);
    if (tail) options.onEvent?.(tail);
  } finally {
    if (!completed) {
      try {
        await reader.cancel();
      } catch {
        // Ignore reader cancellation errors; the caller already has the real stream failure.
      }
    }
    reader.releaseLock();
  }
  return null;
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

function parseSseFrame(frame = '') {
  const lines = String(frame || '').split(/\r?\n/);
  let event = 'message';
  const data = [];
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart());
    }
  }
  if (!data.length) return null;
  const raw = data.join('\n');
  try {
    return { event, data: JSON.parse(raw) };
  } catch {
    return { event, data: raw };
  }
}

export { fetchJson, fetchBlob, fetchSse };

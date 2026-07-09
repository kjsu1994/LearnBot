import http from "node:http";

const port = Number(process.env.GEMINI_OLLAMA_PROXY_PORT || 11435);
const apiKey = process.env.GEMINI_API_KEY;
const defaultModel = process.env.GEMINI_MODEL || "gemini-3.1-flash-lite";
const endpoint = (process.env.GEMINI_OPENAI_BASE_URL || "https://generativelanguage.googleapis.com/v1beta/openai").replace(/\/+$/, "");

if (!apiKey) {
  console.error("GEMINI_API_KEY is required.");
  process.exit(1);
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === "GET" && req.url === "/api/tags") {
      return sendJson(res, 200, {
        models: [
          { name: defaultModel, model: defaultModel, modified_at: new Date().toISOString(), size: 0 }
        ]
      });
    }

    if (req.method === "POST" && req.url === "/api/chat") {
      const body = await readJson(req);
      return handleChat(body, res);
    }

    sendJson(res, 404, { error: "Only GET /api/tags and POST /api/chat are supported by this test proxy." });
  } catch (error) {
    console.error(error);
    if (!res.headersSent) {
      sendJson(res, 500, { error: safeMessage(error) });
    } else {
      res.end();
    }
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`Gemini Ollama-compatible test proxy listening on http://localhost:${port}`);
  console.log(`Forwarding chat requests to ${endpoint}/chat/completions with model default ${defaultModel}`);
});

async function handleChat(body, res) {
  const model = typeof body.model === "string" && body.model.trim() ? body.model.trim() : defaultModel;
  const stream = body.stream === true;
  const payload = toOpenAiChatPayload(body, model, stream);
  const upstream = await fetch(`${endpoint}/chat/completions`, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${apiKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  if (!upstream.ok) {
    const text = await upstream.text();
    return sendJson(res, upstream.status, { error: text || upstream.statusText });
  }

  if (stream) {
    return streamOpenAiAsOllama(upstream, res, model);
  }

  const data = await upstream.json();
  const content = data?.choices?.[0]?.message?.content || "";
  const usage = data?.usage || {};
  return sendJson(res, 200, {
    model,
    created_at: new Date().toISOString(),
    message: { role: "assistant", content },
    done: true,
    done_reason: normalizeFinishReason(data?.choices?.[0]?.finish_reason || "stop"),
    prompt_eval_count: usage.prompt_tokens || 0,
    eval_count: usage.completion_tokens || 0
  });
}

function toOpenAiChatPayload(body, model, stream) {
  const options = body.options && typeof body.options === "object" ? body.options : {};
  const payload = {
    model,
    messages: Array.isArray(body.messages) ? body.messages.map(toMessage).filter(Boolean) : [],
    stream
  };
  if (typeof options.temperature === "number") {
    payload.temperature = options.temperature;
  }
  if (Number.isInteger(options.num_predict) && options.num_predict > 0) {
    payload.max_tokens = options.num_predict;
  }
  const responseFormat = toOpenAiResponseFormat(body.format);
  if (responseFormat) {
    payload.response_format = responseFormat;
    if (process.env.GEMINI_REASONING_EFFORT) {
      payload.reasoning_effort = process.env.GEMINI_REASONING_EFFORT;
    }
  }
  return payload;
}

function toOpenAiResponseFormat(format) {
  if (!format) {
    return null;
  }
  if (format === "json") {
    return { type: "json_object" };
  }
  if (typeof format === "object") {
    return {
      type: "json_schema",
      json_schema: {
        name: "learnbot_structured_response",
        strict: true,
        schema: format
      }
    };
  }
  return null;
}

function toMessage(message) {
  if (!message || typeof message !== "object") {
    return null;
  }
  const role = typeof message.role === "string" ? message.role : "user";
  const content = typeof message.content === "string" ? message.content : "";
  return { role, content };
}

async function streamOpenAiAsOllama(upstream, res, model) {
  res.writeHead(200, {
    "Content-Type": "application/x-ndjson; charset=utf-8",
    "Cache-Control": "no-cache",
    "Connection": "keep-alive"
  });

  const decoder = new TextDecoder();
  let buffer = "";
  let promptTokens = 0;
  let completionTokens = 0;
  let finishReason = "stop";

  for await (const chunk of upstream.body) {
    buffer += decoder.decode(chunk, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || "";
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.startsWith("data:")) {
        continue;
      }
      const event = trimmed.slice("data:".length).trim();
      if (event === "[DONE]") {
        writeOllamaDone(res, model, finishReason, promptTokens, completionTokens);
        return res.end();
      }
      const data = JSON.parse(event);
      const choice = data?.choices?.[0] || {};
      const delta = choice?.delta?.content || "";
      if (choice.finish_reason) {
        finishReason = normalizeFinishReason(choice.finish_reason);
      }
      if (data.usage) {
        promptTokens = data.usage.prompt_tokens || promptTokens;
        completionTokens = data.usage.completion_tokens || completionTokens;
      }
      if (delta) {
        res.write(JSON.stringify({
          model,
          created_at: new Date().toISOString(),
          message: { role: "assistant", content: delta },
          done: false
        }) + "\n");
      }
    }
  }

  writeOllamaDone(res, model, finishReason, promptTokens, completionTokens);
  res.end();
}

function writeOllamaDone(res, model, finishReason, promptTokens, completionTokens) {
  res.write(JSON.stringify({
    model,
    created_at: new Date().toISOString(),
    message: { role: "assistant", content: "" },
    done: true,
    done_reason: normalizeFinishReason(finishReason || "stop"),
    prompt_eval_count: promptTokens || 0,
    eval_count: completionTokens || 0
  }) + "\n");
}

function normalizeFinishReason(reason) {
  const value = String(reason || "stop");
  return value === "max_tokens" ? "length" : value;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      data += chunk;
      if (data.length > 25_000_000) {
        reject(new Error("Request body too large."));
        req.destroy();
      }
    });
    req.on("end", () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

function safeMessage(error) {
  return error && error.message ? error.message : String(error);
}

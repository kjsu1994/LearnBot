import fs from "node:fs";
import path from "node:path";

function readArg(name, fallback = "") {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

const url = readArg("--url");
const bodyArg = readArg("--body", "{}");
const reportPath = readArg("--report", path.join(".tmp", "quality", `streaming-first-delta-${timestamp()}.json`));
const maxFirstDeltaMs = Number(readArg("--max-first-delta-ms", "5000"));
const timeoutMs = Number(readArg("--timeout-ms", "30000"));

if (!url) {
  console.error("Usage: node scripts\\quality\\streaming\\measure-sse-first-delta.mjs --url <sse-url> [--body <json>] [--max-first-delta-ms 5000] [--report <json>]");
  process.exit(2);
}

const startedAt = Date.now();
const events = [];
const controller = new AbortController();
const timeout = setTimeout(() => controller.abort(), timeoutMs);
let firstDeltaMs = null;
let firstEventMs = null;
let error = null;

try {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Accept": "text/event-stream",
      "Content-Type": "application/json",
    },
    body: bodyArg,
    signal: controller.signal,
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  if (!response.body) {
    throw new Error("response body is empty");
  }

  const decoder = new TextDecoder();
  let buffer = "";
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let boundary = buffer.indexOf("\n\n");
    while (boundary !== -1) {
      const rawEvent = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const event = parseSseEvent(rawEvent, Date.now() - startedAt);
      if (event.name) {
        events.push(event);
        if (firstEventMs === null) {
          firstEventMs = event.elapsedMs;
        }
        if (event.name === "delta" && firstDeltaMs === null) {
          firstDeltaMs = event.elapsedMs;
          controller.abort();
          break;
        }
      }
      boundary = buffer.indexOf("\n\n");
    }
    if (firstDeltaMs !== null) {
      break;
    }
  }
} catch (caught) {
  if (firstDeltaMs === null) {
    error = caught?.name === "AbortError" ? "stream aborted before first delta" : caught.message;
  }
} finally {
  clearTimeout(timeout);
}

const finishedAt = Date.now();
const passed = firstDeltaMs !== null && Number.isFinite(maxFirstDeltaMs) && firstDeltaMs <= maxFirstDeltaMs;
const report = {
  schema: "learnbot.quality.streaming-first-delta.v1",
  url,
  startedAt: new Date(startedAt).toISOString(),
  finishedAt: new Date(finishedAt).toISOString(),
  durationMs: finishedAt - startedAt,
  maxFirstDeltaMs,
  firstEventMs,
  firstDeltaMs,
  eventsSeen: events.map((event) => ({ name: event.name, elapsedMs: event.elapsedMs })),
  error,
  passed,
};

fs.mkdirSync(path.dirname(path.resolve(reportPath)), { recursive: true });
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
console.log(`streaming first-delta report: ${path.resolve(reportPath)}`);
console.log(`streaming first-delta: ${firstDeltaMs ?? "missing"}ms, max=${maxFirstDeltaMs}ms`);

if (!passed) {
  process.exit(1);
}

function parseSseEvent(rawEvent, elapsedMs) {
  let name = "";
  const data = [];
  for (const line of rawEvent.split(/\r?\n/)) {
    if (line.startsWith("event:")) {
      name = line.slice("event:".length).trim();
    } else if (line.startsWith("data:")) {
      data.push(line.slice("data:".length).trimStart());
    }
  }
  return {
    name,
    data: data.join("\n"),
    elapsedMs,
  };
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

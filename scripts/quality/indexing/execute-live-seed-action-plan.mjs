import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, "..", "..", "..");

function readArg(name, fallback = "") {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

function hasFlag(name) {
  return process.argv.includes(name);
}

const planPath = path.resolve(readArg("--plan"));
const outputPath = path.resolve(readArg("--output", path.join(root, ".tmp", "quality", `indexing-live-seed-action-execution-${timestamp()}.json`)));
const execute = hasFlag("--execute");
const onlyActionIds = readArg("--only", "")
  .split(",")
  .map((item) => item.trim())
  .filter(Boolean);
const loginId = readArg("--login-id", process.env.LEARNBOT_QUALITY_LOGIN_ID ?? "");
const password = readArg("--password", process.env.LEARNBOT_QUALITY_PASSWORD ?? "");
const requestTimeoutMs = Number(readArg("--timeout-ms", "30000"));
let authCookie = "";

try {
  if (!planPath || !fs.existsSync(planPath)) {
    throw new Error("--plan must point to an existing seed action plan");
  }
  const plan = JSON.parse(fs.readFileSync(planPath, "utf8"));
  const actions = (plan.actions ?? []).filter((action) => onlyActionIds.length === 0 || onlyActionIds.includes(action.id));
  if (execute) {
    authCookie = await loginIfConfigured(plan.server);
  }
  const results = [];
  for (const action of actions) {
    results.push(await runAction(plan.server, action));
  }
  const report = {
    schema: "learnbot.quality.indexing-live-seed-action-execution.v1",
    generatedAt: new Date().toISOString(),
    planPath,
    server: plan.server,
    execute,
    authenticated: Boolean(authCookie),
    selectedActionIds: onlyActionIds,
    totalActions: results.length,
    succeededActions: results.filter((result) => result.status === "succeeded").length,
    failedActions: results.filter((result) => result.status === "failed").length,
    skippedActions: results.filter((result) => result.status === "skipped").length,
    results,
    passed: execute
      ? results.length > 0 && results.every((result) => result.status === "succeeded")
      : results.every((result) => result.status === "skipped"),
  };
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  console.log(`indexing live seed action execution report: ${outputPath}`);
  console.log(`indexing live seed action execution summary: succeeded=${report.succeededActions}, failed=${report.failedActions}, skipped=${report.skippedActions}, execute=${execute}`);
  if (!report.passed) {
    process.exitCode = 1;
  }
} catch (error) {
  console.error(`indexing live seed action execution failed: ${error.message}`);
  process.exitCode = 1;
}

async function runAction(server, action) {
  if (!action.ready) {
    return {
      id: action.id,
      endpoint: action.endpoint,
      method: action.method,
      status: "skipped",
      reason: action.reason || "action is not ready",
    };
  }
  if (!execute) {
    return {
      id: action.id,
      endpoint: action.endpoint,
      method: action.method,
      status: "skipped",
      reason: "dry-run; pass --execute to run this POST action",
      payload: action.payload ?? {},
    };
  }
  try {
    const payload = await postJson(`${server}${action.endpoint}`, action.payload ?? {});
    return {
      id: action.id,
      endpoint: action.endpoint,
      method: action.method,
      status: "succeeded",
      response: summarizePayload(payload),
    };
  } catch (error) {
    return {
      id: action.id,
      endpoint: action.endpoint,
      method: action.method,
      status: "failed",
      error: error.message,
    };
  }
}

async function postJson(url, body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(url, {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        ...(authCookie ? { Cookie: authCookie } : {}),
      },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return text ? JSON.parse(text) : {};
  } finally {
    clearTimeout(timeout);
  }
}

async function loginIfConfigured(server) {
  if (!loginId && !password) {
    return "";
  }
  if (!loginId || !password) {
    throw new Error("--login-id and --password must be provided together");
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(`${server}/api/auth/login`, {
      method: "POST",
      signal: controller.signal,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ loginId, password, rememberLogin: false }),
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`/api/auth/login returned ${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return cookieHeaderFrom(response);
  } finally {
    clearTimeout(timeout);
  }
}

function cookieHeaderFrom(response) {
  const setCookies = typeof response.headers.getSetCookie === "function"
    ? response.headers.getSetCookie()
    : [response.headers.get("set-cookie")].filter(Boolean);
  return setCookies.map((item) => item.split(";")[0]).filter(Boolean).join("; ");
}

function summarizePayload(payload) {
  if (!payload || typeof payload !== "object") {
    return payload ?? null;
  }
  const summary = {};
  for (const key of ["id", "jobId", "documentId", "repositoryId", "status", "sourceStatus", "name", "sourceUri"]) {
    if (payload[key] !== undefined) {
      summary[key] = payload[key];
    }
  }
  return Object.keys(summary).length > 0 ? summary : payload;
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

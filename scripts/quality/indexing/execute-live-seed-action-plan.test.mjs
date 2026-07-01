import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "execute-live-seed-action-plan.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-indexing-live-seed-action-execution-"));
const planPath = path.join(tempDir, "action-plan.json");
const dryRunReportPath = path.join(tempDir, "dry-run-report.json");
const executionReportPath = path.join(tempDir, "execution-report.json");
const requests = [];

const server = http.createServer((request, response) => {
  response.setHeader("Content-Type", "application/json");
  if (request.method === "POST" && request.url === "/api/auth/login") {
    response.setHeader("Set-Cookie", "learnbot_session=test-session; Path=/; HttpOnly");
    response.end(JSON.stringify({ user: { id: "user-1" } }));
    return;
  }
  if (!String(request.headers.cookie ?? "").includes("learnbot_session=test-session")) {
    response.writeHead(401);
    response.end(JSON.stringify({ message: "login required" }));
    return;
  }
  if (request.method !== "POST") {
    response.writeHead(405);
    response.end(JSON.stringify({ message: "method not allowed" }));
    return;
  }
  let body = "";
  request.on("data", (chunk) => {
    body += chunk;
  });
  request.on("end", () => {
    requests.push({ url: request.url, body: JSON.parse(body || "{}") });
    if (request.url === "/api/sources/web") {
      response.end(JSON.stringify({ documentId: "doc-1", sourceStatus: "SEARCHABLE" }));
      return;
    }
    if (request.url === "/api/code/repositories/repo-1/index") {
      response.end(JSON.stringify({ jobId: "code-job-1", status: "FAILED" }));
      return;
    }
    response.writeHead(404);
    response.end(JSON.stringify({ message: "not found" }));
  });
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
try {
  const { port } = server.address();
  fs.writeFileSync(planPath, `${JSON.stringify({
    schema: "learnbot.quality.indexing-live-seed-action-plan.v1",
    server: `http://127.0.0.1:${port}`,
    actions: [
      {
        id: "ingest-web-seed",
        endpoint: "/api/sources/web",
        method: "POST",
        ready: true,
        payload: { url: "https://example.com/docs", recursive: true },
      },
      {
        id: "run-code-reindex-seed",
        endpoint: "/api/code/repositories/repo-1/index",
        method: "POST",
        ready: true,
        payload: { storeToken: false },
      },
    ],
  }, null, 2)}\n`, "utf8");

  const dryRun = await runNode([
    scriptPath,
    "--plan", planPath,
    "--output", dryRunReportPath,
  ]);
  assert.equal(dryRun.status, 0, dryRun.stderr || dryRun.stdout);
  assert.equal(requests.length, 0);
  const dryRunReport = JSON.parse(fs.readFileSync(dryRunReportPath, "utf8"));
  assert.equal(dryRunReport.execute, false);
  assert.equal(dryRunReport.skippedActions, 2);
  assert.equal(dryRunReport.passed, true);

  const executed = await runNode([
    scriptPath,
    "--plan", planPath,
    "--output", executionReportPath,
    "--execute",
    "--login-id", "admin",
    "--password", "secret",
    "--only", "ingest-web-seed,run-code-reindex-seed",
  ]);
  assert.equal(executed.status, 0, executed.stderr || executed.stdout);
  assert.deepEqual(requests.map((item) => item.url), [
    "/api/sources/web",
    "/api/code/repositories/repo-1/index",
  ]);
  const executionReport = JSON.parse(fs.readFileSync(executionReportPath, "utf8"));
  assert.equal(executionReport.execute, true);
  assert.equal(executionReport.authenticated, true);
  assert.equal(executionReport.succeededActions, 2);
  assert.equal(executionReport.passed, true);
  assert.equal(executionReport.results[0].response.documentId, "doc-1");
} finally {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("execute-live-seed-action-plan tests passed");

function runNode(args) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("close", (status) => {
      resolve({ status, stdout, stderr });
    });
  });
}

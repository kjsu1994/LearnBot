import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const captureScriptPath = path.join(scriptDir, "capture-indexing-audit-fixtures.mjs");
const scoreScriptPath = path.join(scriptDir, "evaluate-indexing-diagnostics-fixtures.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-indexing-live-capture-"));
const fixturesPath = path.join(tempDir, "capture-fixtures.json");
const capturePath = path.join(tempDir, "indexing-live-capture.json");
const authCapturePath = path.join(tempDir, "indexing-live-capture-auth.json");
const learnBotShapeFixturesPath = path.join(tempDir, "learnbot-shape-fixtures.json");
const learnBotShapeCapturePath = path.join(tempDir, "learnbot-shape-capture.json");
const scorePath = path.join(tempDir, "indexing-score.json");

const server = http.createServer((request, response) => {
  response.setHeader("Content-Type", "application/json");
  if (request.method === "POST" && ["/api/auth/login", "/auth/api/auth/login"].includes(request.url)) {
    response.setHeader("Set-Cookie", "learnbot_session=test-session; Path=/; HttpOnly");
    response.end(JSON.stringify({ user: { id: "user-1" } }));
    return;
  }
  const requestPath = request.url.startsWith("/auth") ? request.url.slice("/auth".length) : request.url;
  if (request.url.startsWith("/auth") && !String(request.headers.cookie ?? "").includes("learnbot_session=test-session")) {
    response.writeHead(401);
    response.end(JSON.stringify({ message: "login required" }));
    return;
  }
  if (request.method !== "GET") {
    response.writeHead(405);
    response.end(JSON.stringify({ message: "method not allowed" }));
    return;
  }
  if (requestPath === "/document/job") {
    response.end(JSON.stringify({
      status: "PARTIAL",
      searchable: true,
      originalChunksAvailable: true,
      retryStages: ["DOCUMENT_GRAPH_REBUILD"],
      diagnostics: [
        { stage: "DOCUMENT_BASE_INDEX", status: "SUCCESS" },
        { stage: "DOCUMENT_GRAPH_REBUILD", status: "FAILED" },
      ],
    }));
    return;
  }
  if (requestPath === "/document/succeeded-job") {
    response.end(JSON.stringify({
      status: "SUCCEEDED",
      totalChunks: 4,
      searchableAt: "2026-07-01T13:13:56.505363Z",
      diagnostics: [],
    }));
    return;
  }
  if (requestPath === "/api/document-indexing/jobs/live-doc-job") {
    response.end(JSON.stringify({
      id: "live-doc-job",
      sourceId: "source-1",
      status: "SUCCEEDED",
      totalChunks: 4,
      searchableAt: "2026-07-01T13:13:56.505363Z",
    }));
    return;
  }
  if (requestPath === "/api/document-indexing/jobs/live-doc-job/diagnostics") {
    response.end(JSON.stringify([
      { stage: "DOCUMENT_GRAPH_REBUILD", status: "FAILED", message: "seeded graph failure" },
    ]));
    return;
  }
  if (requestPath === "/code/job") {
    response.end(JSON.stringify({
      previousActiveIndex: { indexVersion: "index-v1" },
      failedJob: { indexVersion: "index-v2", status: "FAILED" },
      activeIndex: { indexVersion: "index-v1" },
      search: { fallbackAvailable: true },
      analysisDiagnostics: [
        { stage: "BASE_CHUNK_INDEX", status: "SUCCESS" },
        { stage: "JAVA_SEMANTIC", status: "PARTIAL" },
      ],
    }));
    return;
  }
  if (requestPath === "/crawler/audit") {
    response.end(JSON.stringify({
      allowedDomain: true,
      robotsAllowed: true,
      request: { maxDepth: 5, maxPages: 100 },
      maxDepth: 2,
      maxPages: 30,
      pages: [
        { url: "https://example.com/docs", depth: 0, documentId: "doc-1", sourceUri: "https://example.com/docs" },
        { url: "https://example.com/docs/setup", depth: 1, documentId: "doc-2", sourceUri: "https://example.com/docs/setup" },
      ],
      skipped: [
        {
          url: "https://evil.example.net/docs",
          reasonCode: "DOMAIN_NOT_ALLOWED",
          category: "POLICY_BLOCK",
          severity: "WARNING",
          indexingBlocked: true,
          userAction: "Add the domain to the crawler allowlist or choose an allowed URL.",
        },
      ],
    }));
    return;
  }
  response.writeHead(404);
  response.end(JSON.stringify({ message: "not found" }));
});

fs.writeFileSync(fixturesPath, `${JSON.stringify({
  schema: "learnbot.quality.indexing-live-capture-template.v1",
  cases: [
    { id: "document-post-processing-partial-keeps-searchable", kind: "document", goal: "Capture document indexing job diagnostics.", endpoint: "/document/job" },
    { id: "code-reindex-failure-keeps-active-index", kind: "code", goal: "Capture code indexing diagnostics.", endpoint: "/code/job" },
    { id: "crawler-allowlist-budget-stores-source-pages", kind: "crawler", goal: "Capture crawler audit summary.", endpoint: "/crawler/audit" },
  ],
}, null, 2)}\n`, "utf8");

fs.writeFileSync(learnBotShapeFixturesPath, `${JSON.stringify({
  schema: "learnbot.quality.indexing-live-capture-template.v1",
  cases: [
    { id: "learnbot-succeeded-document-shape", kind: "document", goal: "Capture LearnBot document job shape.", endpoint: "/document/succeeded-job" },
  ],
}, null, 2)}\n`, "utf8");

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
try {
  const { port } = server.address();
  const captureResult = await runNode([
    captureScriptPath,
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", fixturesPath,
    "--output", capturePath,
  ]);
  assert.equal(captureResult.status, 0, captureResult.stderr || captureResult.stdout);
  assert.match(captureResult.stdout, /indexing live capture summary: 3\/3 cases captured/);

  const capture = JSON.parse(fs.readFileSync(capturePath, "utf8"));
  assert.equal(capture.schema, "learnbot.quality.indexing-live-capture.v1");
  assert.equal(capture.liveCapture.authenticated, false);
  assert.equal(capture.cases[0].observed.sourceStatus, "PARTIAL");
  assert.deepEqual(capture.cases[0].observed.failedStages, ["DOCUMENT_GRAPH_REBUILD"]);
  assert.equal(capture.cases[1].observed.activeIndexVersionAfterFailure, "index-v1");
  assert.equal(capture.cases[2].observed.effectiveMaxDepth, 2);
  assert.equal(capture.cases[2].observed.fetchedPages[1].sourceUri, "https://example.com/docs/setup");
  assert.equal(capture.cases[2].observed.skippedPages[0].category, "POLICY_BLOCK");
  assert.equal(capture.cases[2].observed.skippedPages[0].indexingBlocked, true);
  assert.match(capture.cases[2].observed.skippedPages[0].userAction, /allowlist/);

  const authCaptureResult = await runNode([
    captureScriptPath,
    "--server", `http://127.0.0.1:${port}/auth`,
    "--fixtures", fixturesPath,
    "--output", authCapturePath,
    "--login-id", "admin",
    "--password", "secret",
  ]);
  assert.equal(authCaptureResult.status, 0, authCaptureResult.stderr || authCaptureResult.stdout);
  assert.match(authCaptureResult.stdout, /indexing live capture summary: 3\/3 cases captured/);
  const authCapture = JSON.parse(fs.readFileSync(authCapturePath, "utf8"));
  assert.equal(authCapture.liveCapture.authenticated, true);

  const learnBotShapeResult = await runNode([
    captureScriptPath,
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", learnBotShapeFixturesPath,
    "--output", learnBotShapeCapturePath,
  ]);
  assert.equal(learnBotShapeResult.status, 0, learnBotShapeResult.stderr || learnBotShapeResult.stdout);
  const learnBotShapeCapture = JSON.parse(fs.readFileSync(learnBotShapeCapturePath, "utf8"));
  assert.equal(learnBotShapeCapture.cases[0].observed.baseChunksSearchable, true);
  assert.equal(learnBotShapeCapture.cases[0].observed.originalChunksPreserved, true);

  const diagnosticsShapeFixturesPath = path.join(tempDir, "diagnostics-shape-fixtures.json");
  const diagnosticsShapeCapturePath = path.join(tempDir, "diagnostics-shape-capture.json");
  fs.writeFileSync(diagnosticsShapeFixturesPath, `${JSON.stringify({
    schema: "learnbot.quality.indexing-live-capture-template.v1",
    cases: [
      { id: "live-document-diagnostics-shape", kind: "document", goal: "Capture LearnBot document diagnostics side endpoint.", endpoint: "/api/document-indexing/jobs/live-doc-job" },
    ],
  }, null, 2)}\n`, "utf8");
  const diagnosticsShapeResult = await runNode([
    captureScriptPath,
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", diagnosticsShapeFixturesPath,
    "--output", diagnosticsShapeCapturePath,
  ]);
  assert.equal(diagnosticsShapeResult.status, 0, diagnosticsShapeResult.stderr || diagnosticsShapeResult.stdout);
  const diagnosticsShapeCapture = JSON.parse(fs.readFileSync(diagnosticsShapeCapturePath, "utf8"));
  assert.deepEqual(diagnosticsShapeCapture.cases[0].observed.failedStages, ["DOCUMENT_GRAPH_REBUILD"]);
  assert.deepEqual(diagnosticsShapeCapture.cases[0].observed.retryAvailableStages, ["DOCUMENT_GRAPH_REBUILD"]);

  const scoreResult = await runNode([
    scoreScriptPath,
    "--fixtures", capturePath,
    "--report", scorePath,
  ]);
  assert.equal(scoreResult.status, 0, fs.readFileSync(scorePath, "utf8") || scoreResult.stderr || scoreResult.stdout);
  const score = JSON.parse(fs.readFileSync(scorePath, "utf8"));
  assert.equal(score.passed, true);
  assert.equal(score.summary.passedCases, 3);
  assert.equal(score.summary.crawlInsightPassedCases, 3);
} finally {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("capture-indexing-audit-fixtures tests passed");

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

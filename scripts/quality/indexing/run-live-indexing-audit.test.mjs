import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "run-live-indexing-audit.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-indexing-live-audit-"));
const manifestPath = path.join(tempDir, "live-seed-manifest.json");
const firstSummaryPath = path.join(tempDir, "first-summary.json");
const secondSummaryPath = path.join(tempDir, "second-summary.json");
const discoveredSummaryPath = path.join(tempDir, "discovered-summary.json");
const authDiscoveredSummaryPath = path.join(tempDir, "auth-discovered-summary.json");

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
  if (requestPath === "/api/document-indexing/jobs") {
    response.end(JSON.stringify([
      { id: "doc-job-1", status: "PARTIAL" },
    ]));
    return;
  }
  if (requestPath === "/api/code/repositories") {
    response.end(JSON.stringify([
      { id: "repo-1", name: "fixture repository" },
    ]));
    return;
  }
  if (requestPath === "/api/code/repositories/repo-1/jobs") {
    response.end(JSON.stringify([
      { id: "code-job-1", status: "FAILED" },
    ]));
    return;
  }
  if (requestPath === "/api/documents") {
    response.end(JSON.stringify([
      { id: "crawler-doc-1", sourceUri: "https://example.com/docs", sourceType: "WEB" },
    ]));
    return;
  }
  if (requestPath === "/api/document-indexing/jobs/doc-job-1") {
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
  if (requestPath === "/api/code/repositories/repo-1/jobs/code-job-1/diagnostics") {
    response.end(JSON.stringify({
      previousActiveIndex: { indexVersion: "index-v1" },
      failedJob: { indexVersion: "index-v2", status: "FAILED" },
      activeIndex: { indexVersion: "index-v1" },
      search: { fallbackAvailable: true },
      diagnostics: [
        { stage: "BASE_CHUNK_INDEX", status: "SUCCESS" },
        { stage: "ROSLYN", status: "PARTIAL" },
      ],
    }));
    return;
  }
  if (requestPath === "/api/documents/crawler-doc-1") {
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
    }));
    return;
  }
  response.writeHead(404);
  response.end(JSON.stringify({ message: "not found" }));
});

fs.writeFileSync(manifestPath, `${JSON.stringify({
  schema: "learnbot.quality.indexing-live-seed-manifest.v1",
  document: { jobId: "doc-job-1" },
  code: { repositoryId: "repo-1", jobId: "code-job-1" },
  crawler: { documentId: "crawler-doc-1" },
}, null, 2)}\n`, "utf8");

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
try {
  const { port } = server.address();
  const firstResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}`,
    "--manifest", manifestPath,
    "--output-dir", tempDir,
    "--summary", firstSummaryPath,
  ]);
  assert.equal(firstResult.status, 0, firstResult.stderr || firstResult.stdout);
  assert.match(firstResult.stdout, /indexing live audit report:/);

  const firstSummary = JSON.parse(fs.readFileSync(firstSummaryPath, "utf8"));
  assert.equal(firstSummary.schema, "learnbot.quality.indexing-live-audit-run.v1");
  assert.equal(firstSummary.passed, true);
  assert.ok(fs.existsSync(firstSummary.templatePath));
  assert.ok(fs.existsSync(firstSummary.capturePath));
  assert.ok(fs.existsSync(firstSummary.reportPath));

  const firstReport = JSON.parse(fs.readFileSync(firstSummary.reportPath, "utf8"));
  assert.equal(firstReport.passed, true);
  assert.equal(firstReport.summary.passedCases, 3);

  const secondResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}`,
    "--manifest", manifestPath,
    "--baseline", firstSummary.reportPath,
    "--output-dir", tempDir,
    "--summary", secondSummaryPath,
  ]);
  assert.equal(secondResult.status, 0, secondResult.stderr || secondResult.stdout);

  const secondSummary = JSON.parse(fs.readFileSync(secondSummaryPath, "utf8"));
  assert.equal(secondSummary.passed, true);
  assert.ok(fs.existsSync(secondSummary.comparisonPath));
  const comparison = JSON.parse(fs.readFileSync(secondSummary.comparisonPath, "utf8"));
  assert.equal(comparison.passed, true);

  const discoveredResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}`,
    "--discover",
    "--baseline", firstSummary.reportPath,
    "--output-dir", tempDir,
    "--summary", discoveredSummaryPath,
  ]);
  assert.equal(discoveredResult.status, 0, discoveredResult.stderr || discoveredResult.stdout);
  assert.match(discoveredResult.stdout, /== discover-live-seed-manifest ==/);

  const discoveredSummary = JSON.parse(fs.readFileSync(discoveredSummaryPath, "utf8"));
  assert.equal(discoveredSummary.passed, true);
  assert.ok(fs.existsSync(discoveredSummary.manifestPath));
  assert.ok(fs.existsSync(discoveredSummary.discoveryReportPath));
  assert.ok(fs.existsSync(discoveredSummary.comparisonPath));
  const discoveredManifest = JSON.parse(fs.readFileSync(discoveredSummary.manifestPath, "utf8"));
  assert.equal(discoveredManifest.document.jobId, "doc-job-1");
  assert.equal(discoveredManifest.code.repositoryId, "repo-1");
  assert.equal(discoveredManifest.code.jobId, "code-job-1");
  assert.equal(discoveredManifest.crawler.documentId, "crawler-doc-1");

  const authDiscoveredResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}/auth`,
    "--discover",
    "--baseline", firstSummary.reportPath,
    "--output-dir", tempDir,
    "--summary", authDiscoveredSummaryPath,
    "--login-id", "admin",
    "--password", "secret",
  ]);
  assert.equal(authDiscoveredResult.status, 0, authDiscoveredResult.stderr || authDiscoveredResult.stdout);
  const authDiscoveredSummary = JSON.parse(fs.readFileSync(authDiscoveredSummaryPath, "utf8"));
  assert.equal(authDiscoveredSummary.passed, true);
  assert.equal(authDiscoveredSummary.authenticated, true);
} finally {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("run-live-indexing-audit tests passed");

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

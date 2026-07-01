import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "discover-live-seed-manifest.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-indexing-live-seed-discovery-"));
const manifestPath = path.join(tempDir, "manifest.json");
const reportPath = path.join(tempDir, "report.json");
const missingManifestPath = path.join(tempDir, "missing-manifest.json");
const missingReportPath = path.join(tempDir, "missing-report.json");
const notReadyManifestPath = path.join(tempDir, "not-ready-manifest.json");
const notReadyReportPath = path.join(tempDir, "not-ready-report.json");
const succeededOnlyManifestPath = path.join(tempDir, "succeeded-only-manifest.json");
const succeededOnlyReportPath = path.join(tempDir, "succeeded-only-report.json");
const authManifestPath = path.join(tempDir, "auth-manifest.json");
const authReportPath = path.join(tempDir, "auth-report.json");

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
      { id: "doc-job-succeeded", status: "SUCCEEDED" },
      { id: "doc-job-partial", status: "PARTIAL" },
    ]));
    return;
  }
  if (requestPath === "/api/code/repositories") {
    response.end(JSON.stringify([
      { id: "repo-1", name: "primary" },
      { id: "repo-2", name: "secondary" },
    ]));
    return;
  }
  if (requestPath === "/api/code/repositories/repo-1/jobs") {
    response.end(JSON.stringify([
      { id: "code-job-success", status: "SUCCESS" },
    ]));
    return;
  }
  if (requestPath === "/api/code/repositories/repo-2/jobs") {
    response.end(JSON.stringify([
      { id: "code-job-failed", status: "FAILED" },
    ]));
    return;
  }
  if (requestPath === "/api/documents") {
    response.end(JSON.stringify([
      { id: "file-doc-1", sourceUri: "file:///tmp/policy.pdf" },
      { id: "crawler-doc-1", sourceUri: "https://example.com/docs", sourceType: "WEB" },
    ]));
    return;
  }
  if (requestPath === "/missing/api/document-indexing/jobs") {
    response.end(JSON.stringify([]));
    return;
  }
  if (requestPath === "/missing/api/code/repositories") {
    response.end(JSON.stringify([]));
    return;
  }
  if (requestPath === "/missing/api/documents") {
    response.end(JSON.stringify([]));
    return;
  }
  if (requestPath === "/not-ready/api/document-indexing/jobs") {
    response.end(JSON.stringify([
      { id: "doc-job-failed-before-search", status: "FAILED" },
    ]));
    return;
  }
  if (requestPath === "/not-ready/api/code/repositories") {
    response.end(JSON.stringify([
      { id: "repo-with-success-only", name: "repository without failed candidate" },
    ]));
    return;
  }
  if (requestPath === "/not-ready/api/code/repositories/repo-with-success-only/jobs") {
    response.end(JSON.stringify([
      { id: "code-job-success-only", status: "SUCCESS" },
    ]));
    return;
  }
  if (requestPath === "/not-ready/api/documents") {
    response.end(JSON.stringify([
      { id: "file-doc-only", sourceUri: "file:///tmp/policy.pdf", sourceType: "FILE" },
    ]));
    return;
  }
  if (requestPath === "/succeeded-only/api/document-indexing/jobs") {
    response.end(JSON.stringify([
      { id: "doc-job-succeeded-only", status: "SUCCEEDED" },
    ]));
    return;
  }
  if (requestPath === "/succeeded-only/api/code/repositories") {
    response.end(JSON.stringify([
      { id: "repo-succeeded-only", name: "repository with failed seed" },
    ]));
    return;
  }
  if (requestPath === "/succeeded-only/api/code/repositories/repo-succeeded-only/jobs") {
    response.end(JSON.stringify([
      { id: "code-job-failed-for-succeeded-only", status: "FAILED" },
    ]));
    return;
  }
  if (requestPath === "/succeeded-only/api/documents") {
    response.end(JSON.stringify([
      { id: "crawler-doc-succeeded-only", sourceUri: "https://example.com/docs", sourceType: "WEB" },
    ]));
    return;
  }
  response.writeHead(404);
  response.end(JSON.stringify({ message: "not found" }));
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
try {
  const { port } = server.address();
  const result = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}`,
    "--output", manifestPath,
    "--report", reportPath,
  ]);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /indexing live seed discovery summary: ready/);

  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  assert.equal(manifest.document.jobId, "doc-job-partial");
  assert.equal(manifest.code.repositoryId, "repo-2");
  assert.equal(manifest.code.jobId, "code-job-failed");
  assert.equal(manifest.crawler.documentId, "crawler-doc-1");

  const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
  assert.equal(report.passed, true);
  assert.equal(report.candidateCounts.codeJobs, 2);
  assert.equal(report.authenticated, false);

  const succeededOnlyResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}/succeeded-only`,
    "--output", succeededOnlyManifestPath,
    "--report", succeededOnlyReportPath,
  ]);
  assert.equal(succeededOnlyResult.status, 0, succeededOnlyResult.stderr || succeededOnlyResult.stdout);
  const succeededOnlyManifest = JSON.parse(fs.readFileSync(succeededOnlyManifestPath, "utf8"));
  assert.equal(succeededOnlyManifest.document.jobId, "doc-job-succeeded-only");

  const authResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}/auth`,
    "--output", authManifestPath,
    "--report", authReportPath,
    "--login-id", "admin",
    "--password", "secret",
  ]);
  assert.equal(authResult.status, 0, authResult.stderr || authResult.stdout);
  const authReport = JSON.parse(fs.readFileSync(authReportPath, "utf8"));
  assert.equal(authReport.passed, true);
  assert.equal(authReport.authenticated, true);

  const notReadyResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}/not-ready`,
    "--output", notReadyManifestPath,
    "--report", notReadyReportPath,
  ]);
  assert.notEqual(notReadyResult.status, 0);
  assert.match(notReadyResult.stdout, /missing document\.jobId, code\.jobId, crawler\.documentId/);
  const notReadyReport = JSON.parse(fs.readFileSync(notReadyReportPath, "utf8"));
  assert.equal(notReadyReport.passed, false);
  assert.deepEqual(notReadyReport.remediation.missingFields, [
    "document.jobId",
    "code.jobId",
    "crawler.documentId",
  ]);
  assert.equal(notReadyReport.selected.codeRepository.id, "repo-with-success-only");

  const missingResult = await runNode([
    scriptPath,
    "--server", `http://127.0.0.1:${port}/missing`,
    "--output", missingManifestPath,
    "--report", missingReportPath,
  ]);
  assert.notEqual(missingResult.status, 0);
  assert.match(missingResult.stdout, /missing document\.jobId, code\.repositoryId, code\.jobId, crawler\.documentId/);
  const missingReport = JSON.parse(fs.readFileSync(missingReportPath, "utf8"));
  assert.equal(missingReport.passed, false);
  assert.deepEqual(missingReport.remediation.missingFields, [
    "document.jobId",
    "code.repositoryId",
    "code.jobId",
    "crawler.documentId",
  ]);
  assert.equal(missingReport.remediation.seedActions.length, 4);
  assert.match(missingReport.remediation.seedActions[0].action, /document ingestion job/);
  assert.match(missingReport.remediation.retryCommand, /run-live-indexing-audit\.mjs --server/);
} finally {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("discover-live-seed-manifest tests passed");

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

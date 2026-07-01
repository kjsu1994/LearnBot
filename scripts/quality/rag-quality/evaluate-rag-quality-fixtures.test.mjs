import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "evaluate-rag-quality-fixtures.mjs");
const fixturesPath = path.join(scriptDir, "fixtures.json");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-rag-quality-"));
const reportPath = path.join(tempDir, "rag-quality-live.json");
const liveFixturesPath = path.join(tempDir, "rag-live-fixtures.json");
const authReportPath = path.join(tempDir, "rag-quality-live-auth.json");
const authLiveFixturesPath = path.join(tempDir, "rag-live-fixtures-auth.json");
const failedReportPath = path.join(tempDir, "rag-quality-failed.json");
const failedFixturesPath = path.join(tempDir, "rag-quality-failed-fixtures.json");
const seededFollowUpFixturesPath = path.join(tempDir, "rag-quality-seeded-follow-up-fixtures.json");
const seededFollowUpReportPath = path.join(tempDir, "rag-quality-seeded-follow-up.json");
const seededFollowUpLiveFixturesPath = path.join(tempDir, "rag-quality-seeded-follow-up-live.json");
let seededFollowUpConversationIdSeen = false;

const server = http.createServer(async (request, response) => {
  if (request.method === "POST" && request.url === "/auth/api/auth/login") {
    response.writeHead(200, {
      "Content-Type": "application/json",
      "Set-Cookie": "learnbot_session=test-session; Path=/; HttpOnly",
    });
    response.end(JSON.stringify({ user: { id: "user-1" } }));
    return;
  }
  if (request.url.startsWith("/auth") && !String(request.headers.cookie ?? "").includes("learnbot_session=test-session")) {
    response.writeHead(401, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ message: "login required" }));
    return;
  }
  const requestPath = request.url.startsWith("/auth") ? request.url.slice("/auth".length) : request.url;
  if (request.method !== "POST" || requestPath !== "/api/rag/ask") {
    response.writeHead(404, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ message: "not found" }));
    return;
  }

  let body = "";
  for await (const chunk of request) {
    body += chunk;
  }
  const payload = body ? JSON.parse(body) : {};
  const isFollowUp = String(payload.question ?? "").includes("recorded");
  if (isFollowUp && payload.conversationId === "conv-seeded") {
    seededFollowUpConversationIdSeen = true;
  }
  const answer = isFollowUp
    ? "All approvals must be recorded in the audit log. [1]"
    : "Security exceptions require manager approval, and all approvals must be recorded. [1]";
  const content = isFollowUp
    ? "All approvals must be recorded in the audit log."
    : "Security exceptions require manager approval. All approvals must be recorded in the audit log.";

  response.writeHead(200, { "Content-Type": "application/json" });
  response.end(JSON.stringify({
    mode: "qa",
    answer,
    effectiveQuestion: isFollowUp ? "What must be recorded from the security approval policy?" : payload.question,
    citations: [
      {
        chunkId: "doc-security-policy#chunk-1",
        content,
      },
    ],
    evidence: [
      {
        chunkId: "doc-security-policy#chunk-1",
        text: content,
      },
    ],
    conversationId: payload.conversationId || "conv-seeded",
    confidence: "high",
    diagnostics: [],
  }));
});

await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
try {
  const { port } = server.address();
  const result = await runNode([
    scriptPath,
    "--live",
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", fixturesPath,
    "--report", reportPath,
    "--live-fixtures-report", liveFixturesPath,
  ]);

  assert.equal(result.status, 0, result.stderr || result.stdout);

  const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
  assert.equal(report.liveMode, true);
  assert.equal(report.summary.totalCases, 3);
  assert.equal(report.summary.scoredCases, 2);
  assert.equal(report.summary.skippedCases, 1);
  assert.equal(report.summary.passedCases, 2);
  assert.equal(report.summary.failedCases, 0);
  assert.equal(report.summary.followUpQuality, 1);
  assert.equal(report.summary.followUpPassedCases, 2);
  assert.equal(report.results[0].status, "passed");
  assert.equal(report.results[1].status, "skipped");
  assert.equal(report.results[1].skipReason, "LIVE_CODE_REPOSITORY_ID_REQUIRED");
  assert.equal(report.results[2].status, "passed");
  assert.equal(report.results[2].followUp.quality, 1);

  const liveFixtures = JSON.parse(fs.readFileSync(liveFixturesPath, "utf8"));
  assert.equal(liveFixtures.schema, "learnbot.quality.rag-live-fixtures.v1");
  assert.equal(liveFixtures.cases[0].observed.citationIds[0], "doc-security-policy#chunk-1");
  assert.equal(liveFixtures.cases[2].observed.effectiveQuestion, "What must be recorded from the security approval policy?");

  const authResult = await runNode([
    scriptPath,
    "--live",
    "--server", `http://127.0.0.1:${port}/auth`,
    "--fixtures", fixturesPath,
    "--report", authReportPath,
    "--live-fixtures-report", authLiveFixturesPath,
    "--login-id", "admin",
    "--password", "secret",
  ]);
  assert.equal(authResult.status, 0, authResult.stderr || authResult.stdout);
  const authLiveFixtures = JSON.parse(fs.readFileSync(authLiveFixturesPath, "utf8"));
  assert.equal(authLiveFixtures.liveCapture.authenticated, true);

  fs.writeFileSync(failedFixturesPath, JSON.stringify({
    cases: [
      {
        id: "latency-budget-failure",
        domain: "document",
        question: "What approval is required?",
        observed: {
          answer: "Manager approval is required.",
          citationIds: [],
          evidence: [],
          latencyMs: 10,
        },
        expectedEvidenceSnippets: ["not present"],
        maxLatencyMs: 1,
      },
    ],
  }, null, 2));
  const failedResult = await runNode([
    scriptPath,
    "--fixtures", failedFixturesPath,
    "--report", failedReportPath,
  ]);
  assert.equal(failedResult.status, 1);
  const failedReport = JSON.parse(fs.readFileSync(failedReportPath, "utf8"));
  assert.equal(failedReport.passed, false);
  assert.equal(failedReport.summary.failedCases, 1);

  fs.writeFileSync(seededFollowUpFixturesPath, JSON.stringify({
    cases: [
      {
        id: "seeded-conversation-follow-up",
        domain: "document",
        priorQuestion: "What is the security policy?",
        question: "What must be recorded?",
        conversational: true,
        expectedCitationIds: ["doc-security-policy#chunk-1"],
        expectedPriorCitationIds: ["doc-security-policy#chunk-1"],
        expectedFollowUpTerms: ["approvals", "audit log"],
        expectedEvidenceSnippets: ["All approvals must be recorded in the audit log"],
        maxLatencyMs: 1500,
      },
    ],
  }, null, 2));
  const seededFollowUpResult = await runNode([
    scriptPath,
    "--live",
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", seededFollowUpFixturesPath,
    "--report", seededFollowUpReportPath,
    "--live-fixtures-report", seededFollowUpLiveFixturesPath,
  ]);
  assert.equal(seededFollowUpResult.status, 0, seededFollowUpResult.stderr || seededFollowUpResult.stdout);
  assert.equal(seededFollowUpConversationIdSeen, true);
  const seededFollowUpLiveFixtures = JSON.parse(fs.readFileSync(seededFollowUpLiveFixturesPath, "utf8"));
  assert.equal(seededFollowUpLiveFixtures.cases[0].liveCapture.prior.conversationId, "conv-seeded");
} finally {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("evaluate-rag-quality-fixtures live capture tests passed");

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

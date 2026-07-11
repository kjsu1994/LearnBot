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
const offlineReportPath = path.join(tempDir, "rag-quality-offline.json");
const reportPath = path.join(tempDir, "rag-quality-live.json");
const liveFixturesPath = path.join(tempDir, "rag-live-fixtures.json");
const authReportPath = path.join(tempDir, "rag-quality-live-auth.json");
const authLiveFixturesPath = path.join(tempDir, "rag-live-fixtures-auth.json");
const failedReportPath = path.join(tempDir, "rag-quality-failed.json");
const failedFixturesPath = path.join(tempDir, "rag-quality-failed-fixtures.json");
const seededFollowUpFixturesPath = path.join(tempDir, "rag-quality-seeded-follow-up-fixtures.json");
const seededFollowUpReportPath = path.join(tempDir, "rag-quality-seeded-follow-up.json");
const seededFollowUpLiveFixturesPath = path.join(tempDir, "rag-quality-seeded-follow-up-live.json");
const evidenceBackedFollowUpFixturesPath = path.join(tempDir, "rag-quality-evidence-backed-follow-up-fixtures.json");
const evidenceBackedFollowUpReportPath = path.join(tempDir, "rag-quality-evidence-backed-follow-up.json");
const environmentFixturesPath = path.join(tempDir, "rag-quality-environment-fixtures.json");
const environmentReportPath = path.join(tempDir, "rag-quality-environment.json");
const environmentLiveFixturesPath = path.join(tempDir, "rag-quality-environment-live.json");
const missingEnvironmentReportPath = path.join(tempDir, "rag-quality-environment-missing.json");
const mismatchedEnvironmentReportPath = path.join(tempDir, "rag-quality-environment-mismatched.json");
const diverseScoringFixturesPath = path.join(tempDir, "rag-quality-diverse-scoring-fixtures.json");
const diverseScoringReportPath = path.join(tempDir, "rag-quality-diverse-scoring.json");
let seededFollowUpConversationIdSeen = false;
let environmentRepositoryIdSeen = null;

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
  if (request.method === "GET" && requestPath === "/api/code/repositories") {
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify([{
      id: "11111111-1111-1111-1111-111111111111",
      sourceType: "LOCAL",
      status: "INDEXED",
      lastIndexedCommit: "0123456789abcdef0123456789abcdef01234567",
      contentFingerprint: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      worktreeState: "CLEAN",
      analyzerVersion: "test-analyzer",
      indexSchemaVersion: "test-schema",
      activeFileCount: 10,
      activeChunkCount: 20,
    }]));
    return;
  }
  if (request.method !== "POST" || !["/api/rag/ask", "/api/code/ask"].includes(requestPath)) {
    response.writeHead(404, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ message: "not found" }));
    return;
  }

  let body = "";
  for await (const chunk of request) {
    body += chunk;
  }
  const payload = body ? JSON.parse(body) : {};
  if (requestPath === "/api/code/ask") {
    environmentRepositoryIdSeen = payload.repositoryId ?? null;
  }
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
  const offlineResult = await runNode([
    scriptPath,
    "--fixtures", fixturesPath,
    "--report", offlineReportPath,
  ]);
  assert.equal(offlineResult.status, 0, offlineResult.stderr || offlineResult.stdout);
  const offlineReport = JSON.parse(fs.readFileSync(offlineReportPath, "utf8"));
  const codeGroundingResult = offlineReport.results.find((item) => item.id === "code-local-agent-tool-api-flow");
  assert.equal(codeGroundingResult.passed, true);
  assert.equal(codeGroundingResult.claims.requiredCoverage, 1);
  assert.equal(codeGroundingResult.claims.forbiddenClaimsFound.length, 0);
  assert.equal(codeGroundingResult.codeGrounding.fileCoverage, 1);
  assert.equal(codeGroundingResult.codeGrounding.symbolCoverage, 1);
  assert.equal(codeGroundingResult.codeGrounding.observedSymbols.includes("undefined"), false);
  assert.deepEqual(codeGroundingResult.failedGates, []);
  assert.equal(offlineReport.summary.strongGatePassedCases, 3);
  assert.equal(offlineReport.summary.gateFailures.requiredClaims, 0);
  assert.equal(offlineReport.summary.gateFailures.expectedFiles, 0);
  assert.equal(offlineReport.summary.gateFailures.expectedSymbols, 0);

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
  assert.equal(report.summary.strongGatePassRate, 1);
  assert.equal(report.summary.answerAccuracy, 1);
  assert.equal(report.summary.answerAccuracyDefinition.includes("strongGatePassedCases / scoredCases"), true);
  assert.equal(report.results[0].citation.mode, "exact");
  assert.equal(report.results[0].status, "passed");
  assert.equal(report.results[1].status, "skipped");
  assert.equal(report.results[1].skipReason, "LIVE_CODE_REPOSITORY_ID_REQUIRED");
  assert.equal(report.results[2].status, "passed");
  assert.equal(report.results[2].followUp.quality, 1);

  const liveFixtures = JSON.parse(fs.readFileSync(liveFixturesPath, "utf8"));
  assert.equal(liveFixtures.schema, "learnbot.quality.rag-live-fixtures.v1");
  assert.equal(liveFixtures.liveCapture.caseDelayMs, 0);
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
        requiredClaims: ["Repository stores the response"],
        forbiddenClaims: ["Manager approval"],
        expectedFiles: ["src/main/java/example/ToolRepository.java"],
        expectedSymbols: ["complete"],
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
  assert.deepEqual(
    failedReport.results[0].failedGates,
    ["citations", "evidence", "requiredClaims", "forbiddenClaims", "expectedFiles", "expectedSymbols", "implementationBodies", "latency"],
  );
  assert.equal(failedReport.summary.gateFailures.requiredClaims, 1);
  assert.equal(failedReport.summary.gateFailures.forbiddenClaims, 1);
  assert.equal(failedReport.summary.gateFailures.expectedFiles, 1);
  assert.equal(failedReport.summary.gateFailures.expectedSymbols, 1);
  assert.equal(failedReport.summary.gateFailures.implementationBodies, 1);

  fs.writeFileSync(diverseScoringFixturesPath, JSON.stringify({
    cases: [
      {
        id: "java-diverse-equivalent-claim",
        language: "java",
        questionLanguage: "ko",
        domain: "code",
        question: "응답은 어디에 저장해?",
        citationRequired: false,
        observed: { answer: "리포지토리의 complete에서 저장합니다.", citationIds: [], evidence: [], latencyMs: 10 },
        requiredClaimGroups: [["repository.complete", "리포지토리의 complete"]],
        maxLatencyMs: 100,
      },
      {
        id: "csharp-diverse-insufficient-evidence",
        language: "csharp",
        domain: "code",
        question: "Explain an invented persistence method.",
        answerMode: "insufficient",
        citationRequired: false,
        acceptedAnswerTerms: ["not found", "insufficient evidence"],
        observed: { answer: "The method was not found in the repository.", citationIds: [], evidence: [], latencyMs: 10 },
        maxLatencyMs: 100,
      },
    ],
  }, null, 2));
  const diverseScoringResult = await runNode([
    scriptPath,
    "--fixtures", diverseScoringFixturesPath,
    "--report", diverseScoringReportPath,
  ]);
  assert.equal(diverseScoringResult.status, 0, diverseScoringResult.stderr || diverseScoringResult.stdout);
  const diverseScoringReport = JSON.parse(fs.readFileSync(diverseScoringReportPath, "utf8"));
  assert.equal(diverseScoringReport.results[0].claims.requiredCoverage, 1);
  assert.equal(diverseScoringReport.results[1].answerMode.passed, true);
  assert.equal(diverseScoringReport.slices.cohort.diverse.answerAccuracy, 1);
  assert.equal(diverseScoringReport.slices.questionLanguage.ko.cases, 1);

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

  fs.writeFileSync(evidenceBackedFollowUpFixturesPath, JSON.stringify({
    cases: [
      {
        id: "evidence-backed-follow-up",
        domain: "document",
        question: "What should it avoid?",
        observed: {
          answer: "It should not be used for production work.",
          citationIds: ["example-domain#chunk-1"],
          evidence: [
            {
              id: "example-domain#chunk-1",
              text: "Example Domain is for documentation examples. Avoid use in operations.",
            },
          ],
          latencyMs: 10,
        },
        expectedCitationIds: ["example-domain#chunk-1"],
        expectedFollowUpTerms: ["avoid", "operations"],
        expectedEvidenceSnippets: ["Avoid use in operations"],
        maxLatencyMs: 1500,
      },
    ],
  }, null, 2));
  const evidenceBackedFollowUpResult = await runNode([
    scriptPath,
    "--fixtures", evidenceBackedFollowUpFixturesPath,
    "--report", evidenceBackedFollowUpReportPath,
  ]);
  assert.equal(evidenceBackedFollowUpResult.status, 0, evidenceBackedFollowUpResult.stderr || evidenceBackedFollowUpResult.stdout);
  const evidenceBackedFollowUpReport = JSON.parse(fs.readFileSync(evidenceBackedFollowUpReportPath, "utf8"));
  assert.equal(evidenceBackedFollowUpReport.results[0].followUp.quality, 1);
  assert.equal(evidenceBackedFollowUpReport.results[0].followUp.matchedAnswerTerms, 0);
  assert.equal(evidenceBackedFollowUpReport.results[0].followUp.matchedEvidenceTerms, 2);

  fs.writeFileSync(environmentFixturesPath, JSON.stringify({
    repositoryVersions: {
      java: {
        repositoryId: "${LEARNBOT_TEST_REPOSITORY_ID}",
        expectedSourceType: "${LEARNBOT_TEST_EXPECTED_SOURCE_TYPE}",
        expectedCommitSha: "${LEARNBOT_TEST_EXPECTED_COMMIT}",
        expectedContentFingerprint: "${LEARNBOT_TEST_EXPECTED_FINGERPRINT}",
      },
    },
    cases: [
      {
        id: "environment-repository-grounded-citation",
        domain: "code",
        repositoryId: "${LEARNBOT_TEST_REPOSITORY_ID}",
        question: "What is the security policy?",
        expectedCitationIds: [],
        expectedEvidenceSnippets: ["Security exceptions require manager approval"],
        maxLatencyMs: 1500,
      },
    ],
  }, null, 2));
  const environmentResult = await runNode([
    scriptPath,
    "--live",
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", environmentFixturesPath,
    "--report", environmentReportPath,
    "--live-fixtures-report", environmentLiveFixturesPath,
    "--case-delay-ms", "0",
  ], { env: {
    LEARNBOT_TEST_REPOSITORY_ID: "11111111-1111-1111-1111-111111111111",
    LEARNBOT_TEST_EXPECTED_COMMIT: "0123456789abcdef0123456789abcdef01234567",
    LEARNBOT_TEST_EXPECTED_FINGERPRINT: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    LEARNBOT_TEST_EXPECTED_SOURCE_TYPE: "LOCAL",
  } });
  assert.equal(environmentResult.status, 0, environmentResult.stderr || environmentResult.stdout);
  assert.equal(environmentRepositoryIdSeen, "11111111-1111-1111-1111-111111111111");
  const environmentReport = JSON.parse(fs.readFileSync(environmentReportPath, "utf8"));
  assert.equal(environmentReport.results[0].citation.mode, "grounded");
  assert.equal(environmentReport.results[0].citation.evidenceMatchedCitationIds.length, 1);
  assert.equal(environmentReport.results[0].gates.citations, true);
  const environmentLiveFixtures = JSON.parse(fs.readFileSync(environmentLiveFixturesPath, "utf8"));
  assert.equal(environmentLiveFixtures.cases[0].repositoryId, "${LEARNBOT_TEST_REPOSITORY_ID}");
  assert.deepEqual(
    environmentLiveFixtures.cases[0].liveCapture.repositoryIdEnvironmentVariables,
    ["LEARNBOT_TEST_REPOSITORY_ID"],
  );
  assert.equal(environmentLiveFixtures.liveCapture.environmentValidation.status, "VALID");

  environmentRepositoryIdSeen = null;
  const mismatchedEnvironmentResult = await runNode([
    scriptPath,
    "--live",
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", environmentFixturesPath,
    "--report", mismatchedEnvironmentReportPath,
    "--live-fixtures-report", environmentLiveFixturesPath,
  ], { env: {
    LEARNBOT_TEST_REPOSITORY_ID: "11111111-1111-1111-1111-111111111111",
    LEARNBOT_TEST_EXPECTED_COMMIT: "ffffffffffffffffffffffffffffffffffffffff",
    LEARNBOT_TEST_EXPECTED_FINGERPRINT: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    LEARNBOT_TEST_EXPECTED_SOURCE_TYPE: "LOCAL",
  } });
  assert.equal(mismatchedEnvironmentResult.status, 1);
  assert.equal(mismatchedEnvironmentResult.stderr.includes("INVALID_ENVIRONMENT"), true);
  assert.equal(mismatchedEnvironmentResult.stderr.includes("INDEXED_COMMIT_MISMATCH")
    || mismatchedEnvironmentResult.stderr.includes("expected ffffffff"), true);
  assert.equal(environmentRepositoryIdSeen, null);
  assert.equal(fs.existsSync(mismatchedEnvironmentReportPath), false);

  const missingEnvironmentResult = await runNode([
    scriptPath,
    "--live",
    "--server", `http://127.0.0.1:${port}`,
    "--fixtures", environmentFixturesPath,
    "--report", missingEnvironmentReportPath,
    "--live-fixtures-report", environmentLiveFixturesPath,
  ], { env: { LEARNBOT_TEST_REPOSITORY_ID: "" } });
  assert.equal(missingEnvironmentResult.status, 0, missingEnvironmentResult.stderr || missingEnvironmentResult.stdout);
  const missingEnvironmentReport = JSON.parse(fs.readFileSync(missingEnvironmentReportPath, "utf8"));
  assert.equal(missingEnvironmentReport.results[0].skipReason, "LIVE_CODE_REPOSITORY_ID_ENV_REQUIRED");
} finally {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("evaluate-rag-quality-fixtures live capture tests passed");

function runNode(args, options = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, args, {
      stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, ...(options.env ?? {}) },
    });
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

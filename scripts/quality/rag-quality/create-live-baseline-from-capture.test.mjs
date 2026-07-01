import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "create-live-baseline-from-capture.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-rag-baseline-"));
const capturePath = path.join(tempDir, "capture.json");
const outputPath = path.join(tempDir, "fixtures.live.json");
const weakCapturePath = path.join(tempDir, "weak-capture.json");
const weakOutputPath = path.join(tempDir, "fixtures.weak.live.json");

fs.writeFileSync(capturePath, JSON.stringify({
  schema: "learnbot.quality.rag-live-fixtures.v1",
  liveCapture: {
    server: "http://127.0.0.1:8083",
    capturedAt: "2026-07-01T00:00:00.000Z",
  },
  cases: [
    {
      id: "live-document-security-policy-citation",
      domain: "document",
      question: "What approvals are required for security exceptions?",
      mode: "qa",
      expectedFollowUpTerms: ["manager approval"],
      expectedPriorCitationIds: ["chunk-security-1"],
      expectedEvidenceSnippets: ["Security exceptions require manager approval"],
      forbiddenAnswerTerms: ["no approval required"],
      forbiddenFollowUpTerms: ["vacation policy"],
      maxLatencyMs: 20000,
      observed: {
        answer: "Security exceptions require manager approval. [1]",
        effectiveQuestion: "What approvals are required for security exceptions?",
        citationIds: ["chunk-security-1"],
        evidence: [{ id: "chunk-security-1", text: "Security exceptions require manager approval." }],
        latencyMs: 900,
      },
    },
  ],
}, null, 2), "utf8");

try {
  const result = spawnSync(process.execPath, [
    scriptPath,
    "--capture", capturePath,
    "--output", outputPath,
  ], { encoding: "utf8" });

  assert.equal(result.status, 0, result.stderr || result.stdout);

  const baseline = JSON.parse(fs.readFileSync(outputPath, "utf8"));
  assert.equal(baseline.schema, "learnbot.quality.rag-live-baseline.v1");
  assert.equal(baseline.cases[0].expectedCitationIds[0], "chunk-security-1");
  assert.equal(baseline.cases[0].expectedPriorCitationIds[0], "chunk-security-1");
  assert.equal(baseline.cases[0].expectedFollowUpTerms[0], "manager approval");
  assert.equal(baseline.cases[0].forbiddenFollowUpTerms[0], "vacation policy");
  assert.equal(baseline.cases[0].observed.latencyMs, 900);
  assert.equal(baseline.cases[0].baselineValidation.status, "accepted");

  fs.writeFileSync(weakCapturePath, JSON.stringify({
    schema: "learnbot.quality.rag-live-fixtures.v1",
    liveCapture: {
      server: "http://127.0.0.1:8083",
      capturedAt: "2026-07-01T00:00:00.000Z",
    },
    cases: [
      {
        id: "weak-document-security-policy-citation",
        domain: "document",
        question: "What approvals are required for security exceptions?",
        expectedEvidenceSnippets: ["Security exceptions require manager approval"],
        expectedFollowUpTerms: ["manager approval"],
        forbiddenAnswerTerms: ["no approval required"],
        forbiddenFollowUpTerms: ["password reset"],
        maxLatencyMs: 100,
        liveCapture: { status: "captured" },
        observed: {
          answer: "There is no approval required. Password reset rules apply.",
          effectiveQuestion: "What password reset rules apply?",
          citationIds: [],
          evidence: [{ id: "chunk-security-1", text: "Temporary access expires after four hours." }],
          latencyMs: 900,
        },
      },
    ],
  }, null, 2), "utf8");

  const weakResult = spawnSync(process.execPath, [
    scriptPath,
    "--capture", weakCapturePath,
    "--output", weakOutputPath,
  ], { encoding: "utf8" });

  assert.notEqual(weakResult.status, 0, weakResult.stderr || weakResult.stdout);
  assert.match(weakResult.stderr, /missing expected evidence snippet/);
  assert.match(weakResult.stderr, /answer contains forbidden term/);
  assert.match(weakResult.stderr, /missing expected follow-up term/);
  assert.match(weakResult.stderr, /follow-up text contains forbidden term/);
  assert.match(weakResult.stderr, /observed citation ids are empty/);
  assert.match(weakResult.stderr, /exceeds 100ms/);
  assert.equal(fs.existsSync(weakOutputPath), false);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("create-live-baseline-from-capture tests passed");

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "compare-rag-quality-reports.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-rag-quality-compare-"));
const baselinePath = path.join(tempDir, "baseline.json");
const currentPath = path.join(tempDir, "current.json");
const regressedPath = path.join(tempDir, "regressed.json");
const comparisonPath = path.join(tempDir, "comparison.json");

const baselineReport = {
  schema: "learnbot.quality.rag-score.v1",
  summary: {
    totalCases: 2,
    scoredCases: 2,
    skippedCases: 0,
    passedCases: 2,
    failedCases: 0,
    citationRecall: 1,
    citationPrecision: 1,
    evidenceCoverage: 1,
    followUpQuality: 1,
    followUpPassedCases: 2,
    latencyPassedCases: 2,
    hallucinationRiskFlags: 0,
  },
  results: [
    {
      id: "document-security-policy-citation",
      status: "passed",
      citation: { recall: 1, precision: 1 },
      evidence: { coverage: 1 },
      followUp: { quality: 1, passed: true },
      latency: { observedMs: 1000, maxMs: 1500, passed: true },
    },
    {
      id: "code-approved-flow-order",
      status: "passed",
      citation: { recall: 1, precision: 1 },
      evidence: { coverage: 1 },
      followUp: { quality: 1, passed: true },
      latency: { observedMs: 900, maxMs: 1500, passed: true },
    },
  ],
  passed: true,
};

try {
  fs.writeFileSync(baselinePath, `\uFEFF${JSON.stringify(baselineReport, null, 2)}\n`, "utf8");
  fs.writeFileSync(currentPath, `${JSON.stringify({
    ...baselineReport,
    results: baselineReport.results.map((result) => ({
      ...result,
      latency: {
        ...result.latency,
        observedMs: Math.round(result.latency.observedMs * 1.1),
      },
    })),
  }, null, 2)}\n`, "utf8");

  const passingResult = spawnSync(process.execPath, [
    scriptPath,
    "--baseline", baselinePath,
    "--current", currentPath,
    "--output", comparisonPath,
  ], { encoding: "utf8" });

  assert.equal(passingResult.status, 0, passingResult.stderr || passingResult.stdout);
  assert.match(passingResult.stdout, /rag quality comparison accepted/);
  const comparison = JSON.parse(fs.readFileSync(comparisonPath, "utf8"));
  assert.equal(comparison.schema, "learnbot.quality.rag-score-comparison.v1");
  assert.equal(comparison.passed, true);
  assert.equal(comparison.regressions.length, 0);

  const regressedReport = structuredClone(baselineReport);
  regressedReport.summary = {
    ...regressedReport.summary,
    failedCases: 1,
    passedCases: 1,
    citationRecall: 0.5,
    evidenceCoverage: 0.5,
    followUpQuality: 0.5,
    followUpPassedCases: 1,
    latencyPassedCases: 1,
    hallucinationRiskFlags: 1,
  };
  regressedReport.results[0] = {
    ...regressedReport.results[0],
    status: "failed",
    citation: { recall: 0.5, precision: 1 },
    evidence: { coverage: 0.5 },
    followUp: { quality: 0.5, passed: false },
    latency: { observedMs: 1400, maxMs: 1500, passed: true },
  };
  regressedReport.passed = false;
  fs.writeFileSync(regressedPath, `${JSON.stringify(regressedReport, null, 2)}\n`, "utf8");

  const regressedResult = spawnSync(process.execPath, [
    scriptPath,
    "--baseline", baselinePath,
    "--current", regressedPath,
  ], { encoding: "utf8" });

  assert.notEqual(regressedResult.status, 0, regressedResult.stderr || regressedResult.stdout);
  assert.match(regressedResult.stderr, /Current RAG quality report did not pass/);
  assert.match(regressedResult.stderr, /citationRecall decreased/);
  assert.match(regressedResult.stderr, /evidenceCoverage decreased/);
  assert.match(regressedResult.stderr, /followUpQuality decreased/);
  assert.match(regressedResult.stderr, /followUpPassedCases decreased/);
  assert.match(regressedResult.stderr, /followUp\.quality decreased/);
  assert.match(regressedResult.stderr, /hallucinationRiskFlags increased/);
  assert.match(regressedResult.stderr, /latency increased beyond 20%/);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("compare-rag-quality-reports tests passed");

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "compare-quality-reports.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-quality-compare-"));
const baselinePath = path.join(tempDir, "baseline.json");
const currentPath = path.join(tempDir, "current.json");
const regressedPath = path.join(tempDir, "regressed.json");
const comparisonPath = path.join(tempDir, "comparison.json");

const baselineReport = {
  schema: "learnbot.quality.regression-harness.v1",
  stepSummary: {
    totalSteps: 2,
    passedSteps: 2,
    failedSteps: 0,
  },
  qualitySignalSummary: {
    totalSignals: 2,
    coveredSignals: 2,
    blockedSignals: 0,
  },
  qualitySignals: [
    { name: "document-rag-grounding", status: "covered", missingCoverage: [], failingCoverage: [] },
    { name: "approved-local-agent-flow", status: "covered", missingCoverage: [], failingCoverage: [] },
  ],
  results: [
    { name: "backend-focused-quality-regressions", status: "passed", durationSeconds: 10 },
    { name: "local-agent-approved-execution-flow-contract", status: "passed", durationSeconds: 2 },
  ],
  durationSeconds: 20,
  passed: true,
};

try {
  fs.writeFileSync(baselinePath, `\uFEFF${JSON.stringify(baselineReport, null, 2)}\n`, "utf8");
  fs.writeFileSync(currentPath, `${JSON.stringify({
    ...baselineReport,
    stepSummary: { totalSteps: 3, passedSteps: 3, failedSteps: 0 },
    durationSeconds: 22,
    results: [
      ...baselineReport.results,
      { name: "frontend-assert-quality-report.test", status: "passed", durationSeconds: 1 },
    ],
  }, null, 2)}\n`, "utf8");

  const passingResult = spawnSync(process.execPath, [
    scriptPath,
    "--baseline", baselinePath,
    "--current", currentPath,
    "--output", comparisonPath,
  ], { encoding: "utf8" });

  assert.equal(passingResult.status, 0, passingResult.stderr || passingResult.stdout);
  assert.match(passingResult.stdout, /quality comparison accepted/);
  const comparison = JSON.parse(fs.readFileSync(comparisonPath, "utf8"));
  assert.equal(comparison.schema, "learnbot.quality.regression-comparison.v1");
  assert.equal(comparison.passed, true);
  assert.equal(comparison.regressions.length, 0);
  assert.equal(comparison.durationThresholds.maxStepDurationIncreaseRatio, null);
  assert.equal(comparison.durationThresholds.maxTotalDurationIncreaseRatio, null);

  fs.writeFileSync(currentPath, `${JSON.stringify({
    ...baselineReport,
    durationSeconds: 27,
    results: [
      { name: "backend-focused-quality-regressions", status: "passed", durationSeconds: 13 },
      { name: "local-agent-approved-execution-flow-contract", status: "passed", durationSeconds: 2 },
    ],
  }, null, 2)}\n`, "utf8");

  const durationRegressionResult = spawnSync(process.execPath, [
    scriptPath,
    "--baseline", baselinePath,
    "--current", currentPath,
    "--max-step-duration-increase-ratio", "0.2",
    "--max-total-duration-increase-ratio", "0.2",
  ], { encoding: "utf8" });

  assert.notEqual(durationRegressionResult.status, 0, durationRegressionResult.stderr || durationRegressionResult.stdout);
  assert.match(durationRegressionResult.stderr, /Step duration grew beyond 20\.0%/);
  assert.match(durationRegressionResult.stderr, /Total harness duration grew beyond 20\.0%/);

  const regressedReport = structuredClone(baselineReport);
  regressedReport.stepSummary = { totalSteps: 2, passedSteps: 1, failedSteps: 1 };
  regressedReport.qualitySignalSummary = { totalSignals: 2, coveredSignals: 1, blockedSignals: 1 };
  regressedReport.qualitySignals[0] = {
    name: "document-rag-grounding",
    status: "failing",
    missingCoverage: [],
    failingCoverage: ["document-rag"],
  };
  regressedReport.results[0] = {
    name: "backend-focused-quality-regressions",
    status: "failed",
  };
  regressedReport.passed = false;
  fs.writeFileSync(regressedPath, `${JSON.stringify(regressedReport, null, 2)}\n`, "utf8");

  const regressedResult = spawnSync(process.execPath, [
    scriptPath,
    "--baseline", baselinePath,
    "--current", regressedPath,
  ], { encoding: "utf8" });

  assert.notEqual(regressedResult.status, 0, regressedResult.stderr || regressedResult.stdout);
  assert.match(regressedResult.stderr, /Current quality report did not pass/);
  assert.match(regressedResult.stderr, /Step regressed from passed to failed/);
  assert.match(regressedResult.stderr, /Quality signal regressed from covered to failing/);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("compare-quality-reports tests passed");

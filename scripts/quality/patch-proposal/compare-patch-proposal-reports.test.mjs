import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "compare-patch-proposal-reports.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-patch-comparison-"));
const baselinePath = path.join(tempDir, "baseline.json");
const currentPath = path.join(tempDir, "current.json");
const comparisonPath = path.join(tempDir, "comparison.json");
const regressedPath = path.join(tempDir, "regressed.json");
const regressedComparisonPath = path.join(tempDir, "comparison.regressed.json");

const baselineReport = {
  schema: "learnbot.quality.patch-proposal-score.v1",
  summary: {
    totalCases: 1,
    passedCases: 1,
    failedCases: 0,
    patchValidity: 1,
    safetyPassedCases: 1,
    rollbackReadyCases: 1,
    evidenceLinkedCases: 1,
    testCommandReadyCases: 1,
  },
  results: [
    {
      id: "local-agent-approved-single-file-patch",
      passed: true,
      patchValidity: { score: 1 },
      safety: { passed: true },
      rollback: { passed: true },
      evidence: { passed: true },
      testCommands: { passed: true },
    },
  ],
  passed: true,
};

const regressedReport = {
  ...baselineReport,
  summary: {
    ...baselineReport.summary,
    passedCases: 0,
    failedCases: 1,
    patchValidity: 0.5,
    safetyPassedCases: 0,
    rollbackReadyCases: 0,
    evidenceLinkedCases: 0,
    testCommandReadyCases: 0,
  },
  results: [
    {
      id: "local-agent-approved-single-file-patch",
      passed: false,
      patchValidity: { score: 0.5 },
      safety: { passed: false },
      rollback: { passed: false },
      evidence: { passed: false },
      testCommands: { passed: false },
    },
  ],
  passed: false,
};

try {
  fs.writeFileSync(baselinePath, `\ufeff${JSON.stringify(baselineReport, null, 2)}\n`, "utf8");
  fs.writeFileSync(currentPath, `${JSON.stringify(baselineReport, null, 2)}\n`, "utf8");
  fs.writeFileSync(regressedPath, `${JSON.stringify(regressedReport, null, 2)}\n`, "utf8");

  const passingResult = spawnSync(process.execPath, [
    scriptPath,
    "--baseline", baselinePath,
    "--current", currentPath,
    "--output", comparisonPath,
  ], { encoding: "utf8" });

  assert.equal(passingResult.status, 0, passingResult.stderr || passingResult.stdout);
  assert.match(passingResult.stdout, /patch proposal comparison passed/);
  const passingComparison = JSON.parse(fs.readFileSync(comparisonPath, "utf8"));
  assert.equal(passingComparison.schema, "learnbot.quality.patch-proposal-comparison.v1");
  assert.equal(passingComparison.passed, true);
  assert.equal(passingComparison.regressionCount, 0);

  const failingResult = spawnSync(process.execPath, [
    scriptPath,
    "--baseline", baselinePath,
    "--current", regressedPath,
    "--output", regressedComparisonPath,
  ], { encoding: "utf8" });

  assert.notEqual(failingResult.status, 0, failingResult.stderr || failingResult.stdout);
  assert.match(failingResult.stderr, /current-report-not-passed/);
  assert.match(failingResult.stderr, /summary-decreased:patchValidity/);
  assert.match(failingResult.stderr, /summary-increased:failedCases/);
  assert.match(failingResult.stderr, /case-no-longer-passing:local-agent-approved-single-file-patch/);
  assert.match(failingResult.stderr, /case-check-regressed:local-agent-approved-single-file-patch:safety\.passed/);
  const failingComparison = JSON.parse(fs.readFileSync(regressedComparisonPath, "utf8"));
  assert.equal(failingComparison.passed, false);
  assert.ok(failingComparison.regressionCount >= 10);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("compare-patch-proposal-reports tests passed");

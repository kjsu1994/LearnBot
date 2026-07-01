import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "assert-quality-report.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-quality-report-"));
const passingReportPath = path.join(tempDir, "passing-report.json");
const missingSignalReportPath = path.join(tempDir, "missing-signal-report.json");

const baseReport = {
  schema: "learnbot.quality.regression-harness.v1",
  stepSummary: {
    totalSteps: 2,
    passedSteps: 2,
    failedSteps: 0,
  },
  qualitySignalSummary: {
    totalSignals: 1,
    coveredSignals: 1,
    blockedSignals: 0,
  },
  qualitySignals: [
    {
      name: "document-rag-grounding",
      status: "covered",
      requiredCoverage: ["document-rag"],
      missingCoverage: [],
      failingCoverage: [],
    },
  ],
  passed: true,
};

try {
  fs.writeFileSync(passingReportPath, `\uFEFF${JSON.stringify(baseReport, null, 2)}\n`, "utf8");

  const passingResult = spawnSync(process.execPath, [
    scriptPath,
    "--report", passingReportPath,
  ], { encoding: "utf8" });

  assert.equal(passingResult.status, 0, passingResult.stderr || passingResult.stdout);
  assert.match(passingResult.stdout, /quality report accepted/);

  const missingSignalReport = structuredClone(baseReport);
  missingSignalReport.qualitySignalSummary = {
    totalSignals: 1,
    coveredSignals: 0,
    blockedSignals: 1,
  };
  missingSignalReport.qualitySignals[0] = {
    ...missingSignalReport.qualitySignals[0],
    status: "missing",
    missingCoverage: ["evidence-relevance"],
  };
  missingSignalReport.passed = false;
  fs.writeFileSync(missingSignalReportPath, `${JSON.stringify(missingSignalReport, null, 2)}\n`, "utf8");

  const missingResult = spawnSync(process.execPath, [
    scriptPath,
    "--report", missingSignalReportPath,
  ], { encoding: "utf8" });

  assert.notEqual(missingResult.status, 0, missingResult.stderr || missingResult.stdout);
  assert.match(missingResult.stderr, /quality signal document-rag-grounding is missing/);
  assert.match(missingResult.stderr, /blockedSignals=1/);
  assert.match(missingResult.stderr, /report passed flag is not true/);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("assert-quality-report tests passed");

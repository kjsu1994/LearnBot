import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, "..", "..", "..");

function readArg(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

const fixturesPath = path.resolve(readArg("--fixtures", path.join(scriptDir, "fixtures.json")));
const reportPath = path.resolve(
  readArg("--report", path.join(root, ".tmp", "quality", `patch-proposal-quality-${timestamp()}.json`)),
);

const startedAt = new Date();
const fixtureDocument = JSON.parse(fs.readFileSync(fixturesPath, "utf8"));
const results = (fixtureDocument.cases ?? []).map(scoreCase);
const failed = results.filter((result) => !result.passed);
const finishedAt = new Date();

const report = {
  schema: "learnbot.quality.patch-proposal-score.v1",
  fixturesPath,
  startedAt: startedAt.toISOString(),
  finishedAt: finishedAt.toISOString(),
  durationSeconds: round((finishedAt.getTime() - startedAt.getTime()) / 1000),
  summary: {
    totalCases: results.length,
    passedCases: results.length - failed.length,
    failedCases: failed.length,
    patchValidity: round(average(results.map((result) => result.patchValidity.score))),
    safetyPassedCases: results.filter((result) => result.safety.passed).length,
    rollbackReadyCases: results.filter((result) => result.rollback.passed).length,
    evidenceLinkedCases: results.filter((result) => result.evidence.passed).length,
    testCommandReadyCases: results.filter((result) => result.testCommands.passed).length,
  },
  results,
  passed: failed.length === 0,
};

fs.mkdirSync(path.dirname(reportPath), { recursive: true });
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
console.log(`patch proposal quality report: ${reportPath}`);
console.log(`patch proposal summary: ${report.summary.passedCases}/${report.summary.totalCases} cases passed, patchValidity=${report.summary.patchValidity}`);

if (!report.passed) {
  process.exit(1);
}

function scoreCase(testCase) {
  const observed = testCase.observed ?? {};
  const files = Array.isArray(observed.files) ? observed.files : [];
  const observedPaths = uniqueStrings(files.map((file) => normalizePath(file.path)));
  const requiredFiles = uniqueStrings(testCase.requiredFiles?.map(normalizePath));
  const forbiddenFiles = uniqueStrings(testCase.forbiddenFiles?.map(normalizePath));
  const evidenceIds = uniqueStrings(observed.evidenceIds);
  const requiredEvidenceIds = uniqueStrings(testCase.requiredEvidenceIds);
  const testCommands = uniqueStrings(observed.testCommands);
  const allowedTestCommands = uniqueStrings(testCase.allowedTestCommands);

  const missingRequiredFiles = requiredFiles.filter((file) => !observedPaths.includes(file));
  const forbiddenTouchedFiles = observedPaths.filter((file) => forbiddenFiles.includes(file));
  const malformedPatchFiles = files
    .filter((file) => !hasUnifiedDiffShape(file.patch))
    .map((file) => normalizePath(file.path));
  const missingEvidenceIds = requiredEvidenceIds.filter((id) => !evidenceIds.includes(id));
  const disallowedTestCommands = testCommands.filter((command) => !allowedTestCommands.includes(command));

  const targetPassed = observed.executionTarget === testCase.expectedExecutionTarget;
  const approvalPassed = observed.approvalRequired === true;
  const rollbackPassed = observed.rollbackSnapshotRequired === true;
  const filePassed = missingRequiredFiles.length === 0 && forbiddenTouchedFiles.length === 0 && malformedPatchFiles.length === 0;
  const evidencePassed = missingEvidenceIds.length === 0;
  const testCommandPassed = testCommands.length > 0 && disallowedTestCommands.length === 0;

  const checks = [
    targetPassed,
    approvalPassed,
    rollbackPassed,
    filePassed,
    evidencePassed,
    testCommandPassed,
  ];
  const score = checks.filter(Boolean).length / checks.length;
  const passed = score === 1;

  return {
    id: testCase.id,
    goal: testCase.goal,
    status: passed ? "passed" : "failed",
    passed,
    patchValidity: {
      score: round(score),
      missingRequiredFiles,
      forbiddenTouchedFiles,
      malformedPatchFiles,
    },
    safety: {
      expectedExecutionTarget: testCase.expectedExecutionTarget,
      observedExecutionTarget: observed.executionTarget ?? null,
      approvalRequired: observed.approvalRequired === true,
      passed: targetPassed && approvalPassed && forbiddenTouchedFiles.length === 0,
    },
    rollback: {
      rollbackSnapshotRequired: observed.rollbackSnapshotRequired === true,
      passed: rollbackPassed,
    },
    evidence: {
      requiredEvidenceIds,
      observedEvidenceIds: evidenceIds,
      missingEvidenceIds,
      passed: evidencePassed,
    },
    testCommands: {
      observed: testCommands,
      disallowedTestCommands,
      passed: testCommandPassed,
    },
  };
}

function hasUnifiedDiffShape(value) {
  const text = String(value ?? "");
  return text.includes("--- ") && text.includes("+++ ") && text.includes("@@");
}

function normalizePath(value) {
  return String(value ?? "").replaceAll("\\", "/").replace(/^\.?\//, "");
}

function uniqueStrings(values) {
  return Array.from(new Set((values ?? []).map((value) => String(value)).filter(Boolean)));
}

function average(values) {
  if (values.length === 0) {
    return 0;
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

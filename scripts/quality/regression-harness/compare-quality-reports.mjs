import fs from "node:fs";
import path from "node:path";

function readArg(name, fallback = "") {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

const baselinePath = readArg("--baseline");
const currentPath = readArg("--current");
const outputPath = readArg("--output");
const maxStepDurationIncreaseRatio = readNumberArg("--max-step-duration-increase-ratio", null);
const maxTotalDurationIncreaseRatio = readNumberArg("--max-total-duration-increase-ratio", null);

if (!baselinePath || !currentPath) {
  console.error("Usage: node scripts\\quality\\regression-harness\\compare-quality-reports.mjs --baseline <old-report.json> --current <new-report.json> [--output <comparison.json>] [--max-step-duration-increase-ratio <ratio>] [--max-total-duration-increase-ratio <ratio>]");
  process.exit(2);
}

const baseline = readReport(baselinePath);
const current = readReport(currentPath);
const regressions = [];
const warnings = [];

if (!isValidRatio(maxStepDurationIncreaseRatio)) {
  regressions.push({
    type: "invalid-step-duration-threshold",
    message: `Invalid --max-step-duration-increase-ratio: ${maxStepDurationIncreaseRatio}`,
  });
}
if (!isValidRatio(maxTotalDurationIncreaseRatio)) {
  regressions.push({
    type: "invalid-total-duration-threshold",
    message: `Invalid --max-total-duration-increase-ratio: ${maxTotalDurationIncreaseRatio}`,
  });
}

validateSchema("baseline", baseline, regressions);
validateSchema("current", current, regressions);

if (current.passed !== true) {
  regressions.push({
    type: "current-report-not-passed",
    message: "Current quality report did not pass.",
  });
}

const baselineSteps = indexByName(baseline.results ?? []);
const currentSteps = indexByName(current.results ?? []);
for (const [name, baselineStep] of baselineSteps) {
  const currentStep = currentSteps.get(name);
  if (!currentStep) {
    warnings.push({
      type: "step-missing-in-current",
      name,
      message: `Step existed in baseline but not in current report: ${name}`,
    });
    continue;
  }
  if (baselineStep.status === "passed" && currentStep.status !== "passed") {
    regressions.push({
      type: "step-status-regression",
      name,
      baselineStatus: baselineStep.status,
      currentStatus: currentStep.status,
      message: `Step regressed from passed to ${currentStep.status}: ${name}`,
    });
  }
  if (isConfiguredRatio(maxStepDurationIncreaseRatio)
      && baselineStep.status === "passed"
      && currentStep.status === "passed"
      && isPositiveNumber(baselineStep.durationSeconds)
      && isPositiveNumber(currentStep.durationSeconds)) {
    const limit = baselineStep.durationSeconds * (1 + maxStepDurationIncreaseRatio);
    if (currentStep.durationSeconds > limit) {
      regressions.push({
        type: "step-duration-regression",
        name,
        baselineDurationSeconds: baselineStep.durationSeconds,
        currentDurationSeconds: currentStep.durationSeconds,
        maxDurationSeconds: round(limit),
        maxIncreaseRatio: maxStepDurationIncreaseRatio,
        message: `Step duration grew beyond ${(maxStepDurationIncreaseRatio * 100).toFixed(1)}%: ${name}`,
      });
    }
  }
}

if (isConfiguredRatio(maxTotalDurationIncreaseRatio)
    && isPositiveNumber(baseline.durationSeconds)
    && isPositiveNumber(current.durationSeconds)) {
  const limit = baseline.durationSeconds * (1 + maxTotalDurationIncreaseRatio);
  if (current.durationSeconds > limit) {
    regressions.push({
      type: "total-duration-regression",
      baselineDurationSeconds: baseline.durationSeconds,
      currentDurationSeconds: current.durationSeconds,
      maxDurationSeconds: round(limit),
      maxIncreaseRatio: maxTotalDurationIncreaseRatio,
      message: `Total harness duration grew beyond ${(maxTotalDurationIncreaseRatio * 100).toFixed(1)}%.`,
    });
  }
}

const baselineSignals = indexByName(baseline.qualitySignals ?? []);
const currentSignals = indexByName(current.qualitySignals ?? []);
for (const [name, baselineSignal] of baselineSignals) {
  const currentSignal = currentSignals.get(name);
  if (!currentSignal) {
    regressions.push({
      type: "quality-signal-missing",
      name,
      message: `Quality signal disappeared from current report: ${name}`,
    });
    continue;
  }
  if (baselineSignal.status === "covered" && currentSignal.status !== "covered") {
    regressions.push({
      type: "quality-signal-regression",
      name,
      baselineStatus: baselineSignal.status,
      currentStatus: currentSignal.status,
      missingCoverage: currentSignal.missingCoverage ?? [],
      failingCoverage: currentSignal.failingCoverage ?? [],
      message: `Quality signal regressed from covered to ${currentSignal.status}: ${name}`,
    });
  }
}

const comparison = {
  schema: "learnbot.quality.regression-comparison.v1",
  comparedAt: new Date().toISOString(),
  baselineReport: path.resolve(baselinePath),
  currentReport: path.resolve(currentPath),
  baselineStepSummary: baseline.stepSummary ?? null,
  currentStepSummary: current.stepSummary ?? null,
  baselineQualitySignalSummary: baseline.qualitySignalSummary ?? null,
  currentQualitySignalSummary: current.qualitySignalSummary ?? null,
  durationThresholds: {
    maxStepDurationIncreaseRatio,
    maxTotalDurationIncreaseRatio,
  },
  regressions,
  warnings,
  passed: regressions.length === 0,
};

if (outputPath) {
  fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(comparison, null, 2)}\n`, "utf8");
}

if (regressions.length > 0) {
  console.error(`quality comparison rejected: ${path.resolve(currentPath)}`);
  for (const regression of regressions) {
    console.error(`- ${regression.message}`);
  }
  process.exit(1);
}

console.log(`quality comparison accepted: ${path.resolve(currentPath)}`);
if (warnings.length > 0) {
  console.log(`quality comparison warnings: ${warnings.length}`);
}

function readReport(reportPath) {
  return JSON.parse(stripBom(fs.readFileSync(reportPath, "utf8")));
}

function readNumberArg(name, fallback) {
  const rawValue = readArg(name, null);
  if (rawValue === null) {
    return fallback;
  }
  return Number(rawValue);
}

function stripBom(value) {
  return value.charCodeAt(0) === 0xfeff ? value.slice(1) : value;
}

function validateSchema(label, report, issues) {
  if (report.schema !== "learnbot.quality.regression-harness.v1") {
    issues.push({
      type: "schema-mismatch",
      label,
      message: `${label} report has unexpected schema: ${report.schema ?? "(missing)"}`,
    });
  }
}

function isValidRatio(value) {
  return value === null || (Number.isFinite(value) && value >= 0);
}

function isConfiguredRatio(value) {
  return value !== null && isValidRatio(value);
}

function isPositiveNumber(value) {
  return Number.isFinite(value) && value > 0;
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}

function indexByName(items) {
  const map = new Map();
  for (const item of items) {
    if (item?.name) {
      map.set(item.name, item);
    }
  }
  return map;
}

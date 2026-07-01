import fs from "node:fs";
import path from "node:path";

function readArg(name, fallback = "") {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

const reportPath = readArg("--report");

if (!reportPath) {
  console.error("Usage: node scripts\\quality\\regression-harness\\assert-quality-report.mjs --report <regression-harness.json>");
  process.exit(2);
}

const report = JSON.parse(stripBom(fs.readFileSync(reportPath, "utf8")));
const issues = [];

if (report.schema !== "learnbot.quality.regression-harness.v1") {
  issues.push(`unexpected schema: ${report.schema ?? "(missing)"}`);
}

const failedSteps = Number(report.stepSummary?.failedSteps ?? 0);
if (!Number.isFinite(failedSteps) || failedSteps > 0) {
  issues.push(`failedSteps=${report.stepSummary?.failedSteps ?? "(missing)"}`);
}

const signals = Array.isArray(report.qualitySignals) ? report.qualitySignals : [];
if (signals.length === 0) {
  issues.push("qualitySignals are missing");
}

for (const signal of signals) {
  if (signal.status !== "covered") {
    const missing = (signal.missingCoverage ?? []).join(",");
    const failing = (signal.failingCoverage ?? []).join(",");
    issues.push(`quality signal ${signal.name ?? "(missing name)"} is ${signal.status ?? "(missing status)"} missing=[${missing}] failing=[${failing}]`);
  }
}

const summaryBlocked = Number(report.qualitySignalSummary?.blockedSignals ?? 0);
if (Number.isFinite(summaryBlocked) && summaryBlocked > 0) {
  issues.push(`blockedSignals=${summaryBlocked}`);
}

if (report.passed !== true) {
  issues.push("report passed flag is not true");
}

if (issues.length > 0) {
  console.error(`quality report rejected: ${path.resolve(reportPath)}`);
  for (const issue of issues) {
    console.error(`- ${issue}`);
  }
  process.exit(1);
}

console.log(`quality report accepted: ${path.resolve(reportPath)}`);

function stripBom(value) {
  return value.charCodeAt(0) === 0xfeff ? value.slice(1) : value;
}

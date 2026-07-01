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
const maxLatencyIncreaseRatio = Number(readArg("--max-latency-increase-ratio", "0.2"));

if (!baselinePath || !currentPath) {
  console.error("Usage: node scripts\\quality\\rag-quality\\compare-rag-quality-reports.mjs --baseline <old-rag-quality.json> --current <new-rag-quality.json> [--output <comparison.json>] [--max-latency-increase-ratio 0.2]");
  process.exit(2);
}

const baseline = readReport(baselinePath);
const current = readReport(currentPath);
const regressions = [];
const warnings = [];

validateSchema("baseline", baseline, regressions);
validateSchema("current", current, regressions);

if (current.passed !== true) {
  regressions.push({
    type: "current-report-not-passed",
    message: "Current RAG quality report did not pass.",
  });
}

compareSummaryNumber("citationRecall", "decreased");
compareSummaryNumber("citationPrecision", "decreased");
compareSummaryNumber("evidenceCoverage", "decreased");
compareSummaryNumber("followUpQuality", "decreased", { optional: true });
compareSummaryNumber("followUpPassedCases", "decreased", { optional: true });
compareSummaryNumber("failedCases", "increased", { higherIsWorse: true });
compareSummaryNumber("hallucinationRiskFlags", "increased", { higherIsWorse: true });
compareSummaryNumber("latencyPassedCases", "decreased");

const baselineCases = indexById(baseline.results ?? []);
const currentCases = indexById(current.results ?? []);
for (const [id, baselineCase] of baselineCases) {
  const currentCase = currentCases.get(id);
  if (!currentCase) {
    warnings.push({
      type: "case-missing-in-current",
      id,
      message: `RAG quality case existed in baseline but not in current report: ${id}`,
    });
    continue;
  }
  if (baselineCase.status === "passed" && currentCase.status !== "passed" && currentCase.status !== "skipped") {
    regressions.push({
      type: "case-status-regression",
      id,
      baselineStatus: baselineCase.status,
      currentStatus: currentCase.status,
      message: `RAG quality case regressed from passed to ${currentCase.status}: ${id}`,
    });
  }
  compareCaseMetric(id, baselineCase, currentCase, "citation", "recall");
  compareCaseMetric(id, baselineCase, currentCase, "citation", "precision");
  compareCaseMetric(id, baselineCase, currentCase, "evidence", "coverage");
  compareCaseMetric(id, baselineCase, currentCase, "followUp", "quality");
  compareCaseLatency(id, baselineCase, currentCase);
}

const comparison = {
  schema: "learnbot.quality.rag-score-comparison.v1",
  comparedAt: new Date().toISOString(),
  baselineReport: path.resolve(baselinePath),
  currentReport: path.resolve(currentPath),
  maxLatencyIncreaseRatio,
  baselineSummary: baseline.summary ?? null,
  currentSummary: current.summary ?? null,
  regressions,
  warnings,
  passed: regressions.length === 0,
};

if (outputPath) {
  fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(comparison, null, 2)}\n`, "utf8");
}

if (regressions.length > 0) {
  console.error(`rag quality comparison rejected: ${path.resolve(currentPath)}`);
  for (const regression of regressions) {
    console.error(`- ${regression.message}`);
  }
  process.exit(1);
}

console.log(`rag quality comparison accepted: ${path.resolve(currentPath)}`);
if (warnings.length > 0) {
  console.log(`rag quality comparison warnings: ${warnings.length}`);
}

function compareSummaryNumber(name, direction, options = {}) {
  const baselineValue = Number(baseline.summary?.[name]);
  const currentValue = Number(current.summary?.[name]);
  if (!Number.isFinite(baselineValue) || !Number.isFinite(currentValue)) {
    if (options.optional) {
      return;
    }
    warnings.push({
      type: "summary-metric-missing",
      name,
      message: `RAG quality summary metric is missing or non-numeric: ${name}`,
    });
    return;
  }
  const regressed = options.higherIsWorse ? currentValue > baselineValue : currentValue < baselineValue;
  if (regressed) {
    regressions.push({
      type: "summary-metric-regression",
      name,
      baselineValue,
      currentValue,
      message: `RAG quality summary ${name} ${direction}: baseline=${baselineValue}, current=${currentValue}`,
    });
  }
}

function compareCaseMetric(id, baselineCase, currentCase, group, metric) {
  const baselineValue = Number(baselineCase?.[group]?.[metric]);
  const currentValue = Number(currentCase?.[group]?.[metric]);
  if (!Number.isFinite(baselineValue) || !Number.isFinite(currentValue)) {
    return;
  }
  if (currentValue < baselineValue) {
    regressions.push({
      type: "case-metric-regression",
      id,
      metric: `${group}.${metric}`,
      baselineValue,
      currentValue,
      message: `RAG quality case ${id} ${group}.${metric} decreased: baseline=${baselineValue}, current=${currentValue}`,
    });
  }
}

function compareCaseLatency(id, baselineCase, currentCase) {
  const baselineLatency = Number(baselineCase?.latency?.observedMs);
  const currentLatency = Number(currentCase?.latency?.observedMs);
  if (!Number.isFinite(baselineLatency) || !Number.isFinite(currentLatency) || baselineLatency <= 0) {
    return;
  }
  const allowed = baselineLatency * (1 + maxLatencyIncreaseRatio);
  if (currentLatency > allowed) {
    regressions.push({
      type: "case-latency-regression",
      id,
      baselineMs: baselineLatency,
      currentMs: currentLatency,
      allowedMs: Math.round(allowed * 1000) / 1000,
      message: `RAG quality case ${id} latency increased beyond ${maxLatencyIncreaseRatio * 100}%: baseline=${baselineLatency}ms, current=${currentLatency}ms`,
    });
  }
}

function readReport(reportPath) {
  return JSON.parse(stripBom(fs.readFileSync(reportPath, "utf8")));
}

function stripBom(value) {
  return value.charCodeAt(0) === 0xfeff ? value.slice(1) : value;
}

function validateSchema(label, report, issues) {
  if (report.schema !== "learnbot.quality.rag-score.v1") {
    issues.push({
      type: "schema-mismatch",
      label,
      message: `${label} report has unexpected schema: ${report.schema ?? "(missing)"}`,
    });
  }
}

function indexById(items) {
  const map = new Map();
  for (const item of items) {
    if (item?.id) {
      map.set(item.id, item);
    }
  }
  return map;
}

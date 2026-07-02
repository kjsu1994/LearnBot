import fs from "node:fs";
import path from "node:path";

function readArg(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

const baselinePath = readArg("--baseline");
const currentPath = readArg("--current");
const outputPath = readArg("--output", "");

if (!baselinePath || !currentPath) {
  console.error("Usage: node compare-indexing-diagnostics-reports.mjs --baseline <baseline.json> --current <current.json> [--output <comparison.json>]");
  process.exit(2);
}

const baseline = readReport(baselinePath);
const current = readReport(currentPath);
const regressions = [];
const warnings = [];

validateReport("baseline", baseline);
validateReport("current", current);

if (current.passed !== true) {
  regressions.push("current-report-not-passed");
}

compareSummaryDecrease("passedCases");
compareSummaryIncrease("failedCases");
compareSummaryDecrease("searchableFallbackPassedCases");
compareSummaryDecrease("retryReadyCases");
compareSummaryDecrease("activeIndexPreservedCases");
compareSummaryDecrease("crawlPolicyPassedCases");
compareSummaryDecrease("citationSourcePassedCases");
compareSummaryDecrease("crawlInsightPassedCases");

const baselineCases = indexResultsById(baseline.results);
const currentCases = indexResultsById(current.results);

for (const [caseId, baselineCase] of baselineCases.entries()) {
  const currentCase = currentCases.get(caseId);
  if (!currentCase) {
    warnings.push(`case-missing-in-current:${caseId}`);
    continue;
  }
  if (baselineCase.passed === true && currentCase.passed !== true) {
    regressions.push(`case-no-longer-passing:${caseId}`);
  }
  compareCaseBoolean(caseId, "searchableFallback.passed", baselineCase.searchableFallback?.passed, currentCase.searchableFallback?.passed);
  compareCaseBoolean(caseId, "retryReadiness.passed", baselineCase.retryReadiness?.passed, currentCase.retryReadiness?.passed);
  compareCaseBoolean(caseId, "activeIndex.passed", baselineCase.activeIndex?.passed, currentCase.activeIndex?.passed);
  compareCaseBoolean(caseId, "crawlPolicy.passed", baselineCase.crawlPolicy?.passed, currentCase.crawlPolicy?.passed);
  compareCaseBoolean(caseId, "citationSource.passed", baselineCase.citationSource?.passed, currentCase.citationSource?.passed);
  compareCaseBoolean(caseId, "crawlInsight.passed", baselineCase.crawlInsight?.passed, currentCase.crawlInsight?.passed);
}

const comparison = {
  schema: "learnbot.quality.indexing-diagnostics-comparison.v1",
  baselinePath: path.resolve(baselinePath),
  currentPath: path.resolve(currentPath),
  comparedAt: new Date().toISOString(),
  regressionCount: regressions.length,
  warningCount: warnings.length,
  regressions,
  warnings,
  passed: regressions.length === 0,
};

if (outputPath) {
  fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(comparison, null, 2)}\n`, "utf8");
}

for (const warning of warnings) {
  console.warn(`warning: ${warning}`);
}
if (regressions.length > 0) {
  for (const regression of regressions) {
    console.error(`regression: ${regression}`);
  }
  process.exit(1);
}

console.log(`indexing diagnostics comparison passed: ${currentPath}`);

function readReport(filePath) {
  return JSON.parse(stripBom(fs.readFileSync(filePath, "utf8")));
}

function validateReport(label, report) {
  if (report?.schema !== "learnbot.quality.indexing-diagnostics.v1") {
    throw new Error(`${label} report has unsupported schema: ${report?.schema}`);
  }
  if (!report.summary || !Array.isArray(report.results)) {
    throw new Error(`${label} report is missing summary or results`);
  }
}

function compareSummaryDecrease(key) {
  const baselineValue = Number(baseline.summary?.[key] ?? 0);
  const currentValue = Number(current.summary?.[key] ?? 0);
  if (currentValue < baselineValue) {
    regressions.push(`summary-decreased:${key}:${baselineValue}->${currentValue}`);
  }
}

function compareSummaryIncrease(key) {
  const baselineValue = Number(baseline.summary?.[key] ?? 0);
  const currentValue = Number(current.summary?.[key] ?? 0);
  if (currentValue > baselineValue) {
    regressions.push(`summary-increased:${key}:${baselineValue}->${currentValue}`);
  }
}

function compareCaseBoolean(caseId, key, baselineValue, currentValue) {
  if (baselineValue === true && currentValue !== true) {
    regressions.push(`case-check-regressed:${caseId}:${key}`);
  }
}

function indexResultsById(results) {
  return new Map((results ?? []).filter((result) => result?.id).map((result) => [result.id, result]));
}

function stripBom(value) {
  return value.charCodeAt(0) === 0xfeff ? value.slice(1) : value;
}

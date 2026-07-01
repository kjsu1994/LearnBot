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
  readArg("--report", path.join(root, ".tmp", "quality", `indexing-diagnostics-${timestamp()}.json`)),
);

const startedAt = new Date();
const fixtureDocument = JSON.parse(fs.readFileSync(fixturesPath, "utf8"));
const results = (fixtureDocument.cases ?? []).map(scoreCase);
const failed = results.filter((result) => !result.passed);
const finishedAt = new Date();

const report = {
  schema: "learnbot.quality.indexing-diagnostics.v1",
  fixturesPath,
  startedAt: startedAt.toISOString(),
  finishedAt: finishedAt.toISOString(),
  durationSeconds: round((finishedAt.getTime() - startedAt.getTime()) / 1000),
  summary: {
    totalCases: results.length,
    passedCases: results.length - failed.length,
    failedCases: failed.length,
    searchableFallbackPassedCases: results.filter((result) => result.searchableFallback.passed).length,
    retryReadyCases: results.filter((result) => result.retryReadiness.passed).length,
    activeIndexPreservedCases: results.filter((result) => result.activeIndex.passed).length,
    crawlPolicyPassedCases: results.filter((result) => result.crawlPolicy.passed).length,
    citationSourcePassedCases: results.filter((result) => result.citationSource.passed).length,
  },
  results,
  passed: failed.length === 0,
};

fs.mkdirSync(path.dirname(reportPath), { recursive: true });
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
console.log(`indexing diagnostics report: ${reportPath}`);
console.log(`indexing diagnostics summary: ${report.summary.passedCases}/${report.summary.totalCases} cases passed`);

if (!report.passed) {
  process.exit(1);
}

function scoreCase(testCase) {
  const observed = testCase.observed ?? {};
  const checks = {
    searchableFallback: scoreSearchableFallback(testCase.kind, observed),
    retryReadiness: scoreRetryReadiness(testCase.kind, observed),
    activeIndex: scoreActiveIndex(testCase.kind, observed),
    crawlPolicy: scoreCrawlPolicy(testCase.kind, observed),
    citationSource: scoreCitationSource(testCase.kind, observed),
  };
  const passed = Object.values(checks).every((check) => check.passed);
  return {
    id: testCase.id,
    kind: testCase.kind,
    goal: testCase.goal,
    status: passed ? "passed" : "failed",
    passed,
    ...checks,
  };
}

function scoreSearchableFallback(kind, observed) {
  if (kind === "document") {
    const statusKeepsSearchable = ["SEARCHABLE", "READY", "PARTIAL", "INDEXED", "SUCCEEDED"].includes(observed.sourceStatus);
    return {
      passed: statusKeepsSearchable && observed.baseChunksSearchable === true && observed.originalChunksPreserved === true,
      sourceStatus: observed.sourceStatus ?? null,
      baseChunksSearchable: observed.baseChunksSearchable === true,
      originalChunksPreserved: observed.originalChunksPreserved === true,
    };
  }
  if (kind === "code") {
    return {
      passed: observed.searchFallbackAvailable === true,
      searchFallbackAvailable: observed.searchFallbackAvailable === true,
    };
  }
  return { passed: true };
}

function scoreRetryReadiness(kind, observed) {
  if (kind !== "document") {
    return { passed: true };
  }
  const failedStages = uniqueStrings(observed.failedStages);
  const retryAvailableStages = uniqueStrings(observed.retryAvailableStages);
  const missingRetryStages = failedStages.filter((stage) => !retryAvailableStages.includes(stage));
  return {
    passed: failedStages.length > 0 && missingRetryStages.length === 0,
    failedStages,
    retryAvailableStages,
    missingRetryStages,
  };
}

function scoreActiveIndex(kind, observed) {
  if (kind !== "code") {
    return { passed: true };
  }
  const candidateFailed = observed.candidateJobStatus === "FAILED";
  const preserved = observed.previousActiveIndexVersion
    && observed.activeIndexVersionAfterFailure === observed.previousActiveIndexVersion
    && observed.activeIndexVersionAfterFailure !== observed.candidateIndexVersion;
  return {
    passed: candidateFailed && Boolean(preserved),
    previousActiveIndexVersion: observed.previousActiveIndexVersion ?? null,
    candidateIndexVersion: observed.candidateIndexVersion ?? null,
    activeIndexVersionAfterFailure: observed.activeIndexVersionAfterFailure ?? null,
    candidateJobStatus: observed.candidateJobStatus ?? null,
  };
}

function scoreCrawlPolicy(kind, observed) {
  if (kind !== "crawler") {
    return { passed: true };
  }
  const pages = Array.isArray(observed.fetchedPages) ? observed.fetchedPages : [];
  const overDepthPages = pages.filter((page) => Number(page.depth ?? 0) > Number(observed.effectiveMaxDepth ?? 0));
  const overPageBudget = pages.length > Number(observed.effectiveMaxPages ?? 0);
  const disallowedFetchedPages = pages.filter((page) => !String(page.url ?? "").startsWith("https://example.com/"));
  return {
    passed: observed.allowedDomain === true
      && observed.robotsAllowed === true
      && Number(observed.effectiveMaxDepth ?? 0) <= 2
      && Number(observed.effectiveMaxPages ?? 0) <= 30
      && overDepthPages.length === 0
      && !overPageBudget
      && disallowedFetchedPages.length === 0,
    effectiveMaxDepth: observed.effectiveMaxDepth ?? null,
    effectiveMaxPages: observed.effectiveMaxPages ?? null,
    overDepthPages: overDepthPages.map((page) => page.url),
    overPageBudget,
    disallowedFetchedPages: disallowedFetchedPages.map((page) => page.url),
  };
}

function scoreCitationSource(kind, observed) {
  if (kind !== "crawler") {
    return { passed: true };
  }
  const pages = Array.isArray(observed.fetchedPages) ? observed.fetchedPages : [];
  const missingSourceUriPages = pages
    .filter((page) => page.storedAsSeparateDocument !== true || !page.sourceUri)
    .map((page) => page.url ?? "(unknown)");
  return {
    passed: pages.length > 0 && missingSourceUriPages.length === 0,
    checkedPages: pages.length,
    missingSourceUriPages,
  };
}

function uniqueStrings(values) {
  return Array.from(new Set((values ?? []).map((value) => String(value)).filter(Boolean)));
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

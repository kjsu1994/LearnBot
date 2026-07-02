import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "evaluate-indexing-diagnostics-fixtures.mjs");
const fixturesPath = path.join(scriptDir, "fixtures.json");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-indexing-quality-"));
const reportPath = path.join(tempDir, "indexing-quality.json");
const failingFixturesPath = path.join(tempDir, "fixtures.failing.json");
const failingReportPath = path.join(tempDir, "indexing-quality-failing.json");
const succeededFixturesPath = path.join(tempDir, "fixtures.succeeded-document.json");
const succeededReportPath = path.join(tempDir, "indexing-quality-succeeded-document.json");

try {
  const passingResult = spawnSync(process.execPath, [
    scriptPath,
    "--fixtures", fixturesPath,
    "--report", reportPath,
  ], { encoding: "utf8" });

  assert.equal(passingResult.status, 0, passingResult.stderr || passingResult.stdout);
  assert.match(passingResult.stdout, /indexing diagnostics summary: 3\/3 cases passed/);
  const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
  assert.equal(report.schema, "learnbot.quality.indexing-diagnostics.v1");
  assert.equal(report.summary.searchableFallbackPassedCases, 3);
  assert.equal(report.summary.retryReadyCases, 3);
  assert.equal(report.summary.activeIndexPreservedCases, 3);
  assert.equal(report.summary.crawlPolicyPassedCases, 3);
  assert.equal(report.summary.citationSourcePassedCases, 3);
  assert.equal(report.summary.crawlInsightPassedCases, 3);

  const succeededFixtures = {
    schema: "learnbot.quality.indexing-fixtures.v1",
    cases: [
      {
        id: "succeeded-document-keeps-searchable",
        kind: "document",
        goal: "Accept LearnBot document job SUCCEEDED as a searchable terminal status.",
        observed: {
          sourceStatus: "SUCCEEDED",
          baseChunksSearchable: true,
          originalChunksPreserved: true,
          failedStages: ["DOCUMENT_GRAPH_REBUILD"],
          retryAvailableStages: ["DOCUMENT_GRAPH_REBUILD"],
        },
      },
    ],
  };
  fs.writeFileSync(succeededFixturesPath, `${JSON.stringify(succeededFixtures, null, 2)}\n`, "utf8");

  const succeededResult = spawnSync(process.execPath, [
    scriptPath,
    "--fixtures", succeededFixturesPath,
    "--report", succeededReportPath,
  ], { encoding: "utf8" });

  assert.equal(succeededResult.status, 0, succeededResult.stderr || succeededResult.stdout);
  const succeededReport = JSON.parse(fs.readFileSync(succeededReportPath, "utf8"));
  assert.equal(succeededReport.results[0].searchableFallback.passed, true);

  const failingFixtures = {
    schema: "learnbot.quality.indexing-fixtures.v1",
    cases: [
      {
        id: "bad-crawler-output",
        kind: "crawler",
        goal: "Reject crawler output that breaks policy and citation source tracking.",
        observed: {
          allowedDomain: true,
          robotsAllowed: true,
          effectiveMaxDepth: 2,
          effectiveMaxPages: 30,
          fetchedPages: [
            { url: "https://example.com/docs", depth: 0, storedAsSeparateDocument: true, sourceUri: "https://example.com/docs" },
            { url: "https://evil.example.net/docs", depth: 3, storedAsSeparateDocument: false }
          ],
          skippedPages: [
            { url: "https://evil.example.net/docs", reason: "DOMAIN_NOT_ALLOWED" }
          ]
        }
      }
    ]
  };
  fs.writeFileSync(failingFixturesPath, `${JSON.stringify(failingFixtures, null, 2)}\n`, "utf8");

  const failingResult = spawnSync(process.execPath, [
    scriptPath,
    "--fixtures", failingFixturesPath,
    "--report", failingReportPath,
  ], { encoding: "utf8" });

  assert.notEqual(failingResult.status, 0, failingResult.stderr || failingResult.stdout);
  const failingReport = JSON.parse(fs.readFileSync(failingReportPath, "utf8"));
  assert.equal(failingReport.passed, false);
  assert.equal(failingReport.results[0].crawlPolicy.passed, false);
  assert.equal(failingReport.results[0].crawlInsight.passed, false);
  assert.deepEqual(failingReport.results[0].crawlPolicy.overDepthPages, ["https://evil.example.net/docs"]);
  assert.deepEqual(failingReport.results[0].citationSource.missingSourceUriPages, ["https://evil.example.net/docs"]);
  assert.deepEqual(failingReport.results[0].crawlInsight.missingInsightPages, ["https://evil.example.net/docs"]);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("evaluate-indexing-diagnostics-fixtures tests passed");

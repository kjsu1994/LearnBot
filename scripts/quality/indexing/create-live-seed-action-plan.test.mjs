import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "create-live-seed-action-plan.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-indexing-live-seed-action-plan-"));
const reportPath = path.join(tempDir, "discovery-report.json");
const missingPlanPath = path.join(tempDir, "missing-plan.json");
const readyPlanPath = path.join(tempDir, "ready-plan.json");

fs.writeFileSync(reportPath, `${JSON.stringify({
  schema: "learnbot.quality.indexing-live-seed-discovery.v1",
  server: "http://127.0.0.1:8083",
  selected: {
    codeRepository: {
      id: "repo-1",
      status: "INDEXED",
      name: "seed repo",
    },
  },
  remediation: {
    missingFields: ["document.jobId", "code.jobId", "crawler.documentId"],
  },
}, null, 2)}\n`, "utf8");

try {
  const missingResult = await runNode([
    scriptPath,
    "--discovery-report", reportPath,
    "--output", missingPlanPath,
  ]);
  assert.notEqual(missingResult.status, 0);
  assert.match(missingResult.stdout, /1\/2 actions ready/);
  const missingPlan = JSON.parse(fs.readFileSync(missingPlanPath, "utf8"));
  assert.equal(missingPlan.readyToRun, false);
  assert.equal(missingPlan.actions.length, 2);
  assert.equal(missingPlan.actions[0].id, "ingest-web-seed");
  assert.equal(missingPlan.actions[0].ready, false);
  assert.equal(missingPlan.actions[1].id, "run-code-reindex-seed");
  assert.equal(missingPlan.actions[1].ready, true);
  assert.match(missingPlan.actions[1].endpoint, /repo-1\/index$/);

  const readyResult = await runNode([
    scriptPath,
    "--discovery-report", reportPath,
    "--output", readyPlanPath,
    "--web-url", "https://example.com/docs",
    "--repository-id", "repo-2",
  ]);
  assert.equal(readyResult.status, 0, readyResult.stderr || readyResult.stdout);
  assert.match(readyResult.stdout, /2\/2 actions ready/);
  const readyPlan = JSON.parse(fs.readFileSync(readyPlanPath, "utf8"));
  assert.equal(readyPlan.readyToRun, true);
  assert.equal(readyPlan.actions[0].payload.url, "https://example.com/docs");
  assert.match(readyPlan.actions[0].powershell, /Invoke-RestMethod/);
  assert.match(readyPlan.actions[1].endpoint, /repo-2\/index$/);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("create-live-seed-action-plan tests passed");

function runNode(args) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("close", (status) => {
      resolve({ status, stdout, stderr });
    });
  });
}

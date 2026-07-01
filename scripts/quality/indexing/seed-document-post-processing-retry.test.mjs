import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "seed-document-post-processing-retry.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-document-retry-seed-"));
const manifestPath = path.join(tempDir, "manifest.json");
const reportPath = path.join(tempDir, "report.json");

try {
  fs.writeFileSync(manifestPath, `${JSON.stringify({
    schema: "learnbot.quality.indexing-live-seed-manifest.v1",
    document: { jobId: "f5bd923d-11da-44d7-9d85-f394e569380f" },
    code: { repositoryId: "repo-1", jobId: "job-1" },
    crawler: { documentId: "doc-1" },
  }, null, 2)}\n`, "utf8");

  const result = await runNode([
    scriptPath,
    "--manifest", manifestPath,
    "--output", reportPath,
    "--message", "seeded 'graph' failure",
  ]);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /execute=false, status=skipped/);

  const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
  assert.equal(report.schema, "learnbot.quality.document-post-processing-retry-seed.v1");
  assert.equal(report.execute, false);
  assert.equal(report.execution.status, "skipped");
  assert.match(report.sql, /DOCUMENT_GRAPH_REBUILD/);
  assert.match(report.sql, /document_graph_jobs/);
  assert.match(report.sql, /document_processing_diagnostics/);
  assert.match(report.sql, /status = 'PARTIAL'/);
  assert.match(report.sql, /seeded ''graph'' failure/);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("seed-document-post-processing-retry tests passed");

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

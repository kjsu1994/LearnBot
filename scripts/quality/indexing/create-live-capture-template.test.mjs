import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "create-live-capture-template.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-indexing-live-template-"));
const manifestPath = path.join(tempDir, "live-seed-manifest.json");
const missingManifestPath = path.join(tempDir, "missing-live-seed-manifest.json");
const outputPath = path.join(tempDir, "indexing-live-capture-template.json");

try {
  fs.writeFileSync(manifestPath, `${JSON.stringify({
    schema: "learnbot.quality.indexing-live-seed-manifest.v1",
    document: { jobId: "doc-job-1" },
    code: { repositoryId: "repo-1", jobId: "code-job-1" },
    crawler: { documentId: "crawler-doc-1" },
  }, null, 2)}\n`, "utf8");

  const result = await runNode([
    scriptPath,
    "--manifest", manifestPath,
    "--output", outputPath,
  ]);
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /indexing live capture template summary: 3 endpoints ready/);

  const output = JSON.parse(fs.readFileSync(outputPath, "utf8"));
  assert.equal(output.schema, "learnbot.quality.indexing-live-capture-template.v1");
  assert.equal(output.cases[0].endpoint, "/api/document-indexing/jobs/doc-job-1");
  assert.equal(output.cases[1].endpoint, "/api/code/repositories/repo-1/jobs/code-job-1/diagnostics");
  assert.equal(output.cases[2].endpoint, "/api/documents/crawler-doc-1");
  assert.equal(output.cases.some((testCase) => /\{[^}]+\}/.test(testCase.endpoint)), false);

  fs.writeFileSync(missingManifestPath, `${JSON.stringify({
    schema: "learnbot.quality.indexing-live-seed-manifest.v1",
    document: { jobId: "doc-job-1" },
    code: { repositoryId: "repo-1" },
    crawler: { documentId: "crawler-doc-1" },
  }, null, 2)}\n`, "utf8");

  const missingResult = await runNode([
    scriptPath,
    "--manifest", missingManifestPath,
    "--output", outputPath,
  ]);
  assert.notEqual(missingResult.status, 0);
  assert.match(missingResult.stderr, /code\.jobId/);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("create-live-capture-template tests passed");

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

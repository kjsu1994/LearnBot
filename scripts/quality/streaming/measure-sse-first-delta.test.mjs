import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "measure-sse-first-delta.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-streaming-latency-"));
const passingReportPath = path.join(tempDir, "passing.json");
const failingReportPath = path.join(tempDir, "failing.json");

const server = http.createServer((request, response) => {
  response.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    "Connection": "keep-alive",
  });
  response.write("event: metadata\ndata: {}\n\n");
  const delay = request.url.includes("slow") ? 70 : 10;
  setTimeout(() => {
    response.write("event: delta\ndata: token\n\n");
    response.end();
  }, delay);
});

try {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  const passingResult = await runNode([
    scriptPath,
    "--url", `${baseUrl}/stream`,
    "--body", "{\"question\":\"hello\"}",
    "--max-first-delta-ms", "1000",
    "--report", passingReportPath,
  ]);

  assert.equal(passingResult.status, 0, passingResult.stderr || passingResult.stdout);
  assert.match(passingResult.stdout, /streaming first-delta report/);
  const passingReport = JSON.parse(fs.readFileSync(passingReportPath, "utf8"));
  assert.equal(passingReport.schema, "learnbot.quality.streaming-first-delta.v1");
  assert.equal(passingReport.passed, true);
  assert.equal(passingReport.eventsSeen.some((event) => event.name === "metadata"), true);
  assert.equal(passingReport.eventsSeen.some((event) => event.name === "delta"), true);
  assert.equal(Number.isFinite(passingReport.firstDeltaMs), true);

  const failingResult = await runNode([
    scriptPath,
    "--url", `${baseUrl}/slow-stream`,
    "--max-first-delta-ms", "5",
    "--report", failingReportPath,
  ]);

  assert.notEqual(failingResult.status, 0, failingResult.stderr || failingResult.stdout);
  const failingReport = JSON.parse(fs.readFileSync(failingReportPath, "utf8"));
  assert.equal(failingReport.passed, false);
  assert.equal(failingReport.firstDeltaMs > failingReport.maxFirstDeltaMs, true);
} finally {
  await new Promise((resolve) => server.close(resolve));
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("measure-sse-first-delta tests passed");

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

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "assert-approved-execution-flow-report.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-approved-flow-report-"));
const goodReportPath = path.join(tempDir, "good.json");
const weakReportPath = path.join(tempDir, "weak.json");

try {
  const baseReport = {
    schema: "learnbot.local-agent.approved-execution-flow-report.v1",
    passed: true,
    targetFile: "src/App.cs",
    fileEvidence: {
      originalSha256: "aaa",
      afterPatchSha256: "bbb",
      afterRollbackSha256: "aaa",
      changedByPatch: true,
      restoredByRollback: true,
    },
    capabilities: {
      patchApply: true,
      commandRunAllowed: true,
      rollbackRestore: true,
    },
    steps: [
      { toolName: "patch.apply", status: "SUCCEEDED", checks: { mutationApplied: true } },
      { toolName: "command.runAllowed", status: "SUCCEEDED", checks: { arbitraryShellAllowed: false } },
      { toolName: "git.status", status: "SUCCEEDED", checks: { clean: false } },
      { toolName: "rollback.restore", status: "SUCCEEDED", checks: { restored: true } },
    ],
    guardrails: {
      executionTarget: "USER_LOCAL_AGENT",
      identitiesPreserved: true,
      arbitraryShellAllowed: false,
      serverLocalMutation: false,
      workspaceWasTemporary: true,
    },
  };

  fs.writeFileSync(goodReportPath, `${JSON.stringify(baseReport, null, 2)}\n`);
  const good = await runNode([scriptPath, "--report", goodReportPath]);
  assert.equal(good.status, 0, good.stderr || good.stdout);

  fs.writeFileSync(weakReportPath, `${JSON.stringify({
    ...baseReport,
    fileEvidence: {
      ...baseReport.fileEvidence,
      afterPatchSha256: "aaa",
      changedByPatch: false,
    },
  }, null, 2)}\n`);
  const weak = await runNode([scriptPath, "--report", weakReportPath]);
  assert.equal(weak.status, 1);
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

console.log("assert-approved-execution-flow-report tests passed");

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

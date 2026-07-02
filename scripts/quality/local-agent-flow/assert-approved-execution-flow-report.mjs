import assert from "node:assert/strict";
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
  console.error("Usage: node scripts\\quality\\local-agent-flow\\assert-approved-execution-flow-report.mjs --report <approved-flow-report.json>");
  process.exit(2);
}

const report = JSON.parse(fs.readFileSync(reportPath, "utf8").replace(/^\uFEFF/, ""));
assert.equal(report.schema, "learnbot.local-agent.approved-execution-flow-report.v1");
assert.equal(report.passed, true);
assert.equal(report.targetFile, "src/App.cs");

assert.equal(report.fileEvidence?.changedByPatch, true);
assert.equal(report.fileEvidence?.restoredByRollback, true);
assert.notEqual(report.fileEvidence?.originalSha256, report.fileEvidence?.afterPatchSha256);
assert.equal(report.fileEvidence?.originalSha256, report.fileEvidence?.afterRollbackSha256);

assert.equal(report.capabilities?.patchApply, true);
assert.equal(report.capabilities?.commandRunAllowed, true);
assert.equal(report.capabilities?.rollbackRestore, true);

const steps = new Map((report.steps ?? []).map((step) => [step.toolName, step]));
assert.equal((report.steps ?? []).length, 4);
assert.equal(steps.get("patch.apply")?.status, "SUCCEEDED");
assert.equal(steps.get("patch.apply")?.checks?.mutationApplied, true);
assert.equal(steps.get("command.runAllowed")?.status, "SUCCEEDED");
assert.equal(steps.get("command.runAllowed")?.checks?.arbitraryShellAllowed, false);
assert.equal(steps.get("git.status")?.status, "SUCCEEDED");
assert.equal(steps.get("git.status")?.checks?.clean, false);
assert.equal(steps.get("rollback.restore")?.status, "SUCCEEDED");
assert.equal(steps.get("rollback.restore")?.checks?.restored, true);
const requestIds = (report.steps ?? []).map((step) => step.requestId).filter(Boolean);
assert.equal(requestIds.length, 4);
assert.equal(new Set(requestIds).size, 4);

assert.equal(report.guardrails?.executionTarget, "USER_LOCAL_AGENT");
assert.equal(report.guardrails?.identitiesPreserved, true);
assert.equal(report.guardrails?.arbitraryShellAllowed, false);
assert.equal(report.guardrails?.serverLocalMutation, false);
assert.equal(report.guardrails?.workspaceWasTemporary, true);

console.log(`approved execution flow report accepted: ${path.resolve(reportPath)}`);

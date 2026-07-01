import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const scriptPath = path.join(scriptDir, "seed-approved-tool-executions.mjs");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "learnbot-approved-flow-seed-"));
const reportPath = path.join(tempDir, "seed-report.json");

try {
  const result = spawnSync(process.execPath, [
    scriptPath,
    "--user-id", "11111111-1111-4111-8111-111111111111",
    "--agent-id", "22222222-2222-4222-8222-222222222222",
    "--workspace-id", "33333333-3333-4333-8333-333333333333",
    "--source-request-id", "44444444-4444-4444-8444-444444444444",
    "--release-attempt-id", "55555555-5555-4555-8555-555555555555",
    "--session-id", "66666666-6666-4666-8666-666666666666",
    "--patch-request-id", "77777777-7777-4777-8777-777777777771",
    "--command-request-id", "77777777-7777-4777-8777-777777777772",
    "--git-status-request-id", "77777777-7777-4777-8777-777777777773",
    "--rollback-request-id", "77777777-7777-4777-8777-777777777774",
    "--manifest-id", "snap-reviewed-flow",
    "--target-file", "src/App.cs",
    "--output", reportPath,
  ], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
  const report = JSON.parse(fs.readFileSync(reportPath, "utf8"));
  assert.equal(report.schema, "learnbot.quality.local-agent-approved-flow-seed.v1");
  assert.equal(report.execute, false);
  assert.equal(report.execution.status, "skipped");
  assert.equal(report.safety.dryRunByDefault, true);
  assert.equal(report.safety.serverLocalMutation, false);
  assert.equal(report.target.requestIds.patchApply, "77777777-7777-4777-8777-777777777771");
  assert.match(report.sql, /INSERT INTO local_agent_tool_executions/);
  assert.match(report.sql, /'patch.apply'/);
  assert.match(report.sql, /'command.runAllowed'/);
  assert.match(report.sql, /'git.status'/);
  assert.match(report.sql, /'rollback.restore'/);
  assert.match(report.sql, /"mutationAllowed":true/);
  assert.match(report.sql, /"dryRunOnly":false/);
  assert.match(report.sql, /"manifestId":"snap-reviewed-flow"/);
  assert.match(report.cleanupSql, /DELETE FROM local_agent_tool_executions/);

  const invalid = spawnSync(process.execPath, [
    scriptPath,
    "--user-id", "not-a-uuid",
    "--agent-id", "22222222-2222-4222-8222-222222222222",
    "--workspace-id", "33333333-3333-4333-8333-333333333333",
    "--manifest-id", "snap-reviewed-flow",
  ], { encoding: "utf8" });
  assert.notEqual(invalid.status, 0);
  assert.match(invalid.stderr, /--user-id must be a UUID/);

  console.log("seed-approved-tool-executions tests passed");
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

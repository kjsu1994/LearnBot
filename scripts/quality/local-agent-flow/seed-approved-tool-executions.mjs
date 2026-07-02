import { spawn } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, "..", "..", "..");

function readArg(name, fallback = "") {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

function hasFlag(name) {
  return process.argv.includes(name);
}

const outputPath = path.resolve(readArg(
  "--output",
  path.join(root, ".tmp", "quality", `local-agent-approved-flow-seed-${timestamp()}.json`),
));
const execute = hasFlag("--execute");
const container = readArg("--container", "learnbot-postgres");
const database = readArg("--database", "learnbot");
const username = readArg("--username", "learnbot");
const psqlCommand = readArg("--psql-command", "docker");

try {
  const userId = requiredUuid("--user-id");
  const agentId = requiredUuid("--agent-id");
  const workspaceId = requiredUuid("--workspace-id");
  const manifestId = requiredString("--manifest-id");
  const sourceRequestId = readUuid("--source-request-id", crypto.randomUUID());
  const releaseAttemptId = readUuid("--release-attempt-id", crypto.randomUUID());
  const sessionId = readUuid("--session-id", crypto.randomUUID());
  const targetFile = normalizeRelativePath(readArg("--target-file", "src/App.cs"));
  const commandId = readArg("--command-id", "dotnet.version");
  const diffFile = readArg("--diff-file", "");
  const diff = diffFile
    ? fs.readFileSync(path.resolve(diffFile), "utf8")
    : readArg("--diff", defaultDiff(targetFile));
  const requestIds = {
    patchApply: readUuid("--patch-request-id", crypto.randomUUID()),
    commandRunAllowed: readUuid("--command-request-id", crypto.randomUUID()),
    gitStatus: readUuid("--git-status-request-id", crypto.randomUUID()),
    rollbackRestore: readUuid("--rollback-request-id", crypto.randomUUID()),
  };
  const rows = buildRows({
    sessionId,
    userId,
    agentId,
    workspaceId,
    sourceRequestId,
    releaseAttemptId,
    manifestId,
    targetFile,
    commandId,
    diff,
    requestIds,
  });
  const sql = buildSql(rows);
  let execution = {
    status: "skipped",
    reason: "dry-run; pass --execute to seed the local PostgreSQL stack",
  };
  if (execute) {
    execution = await runPsql(sql);
  }
  const report = {
    schema: "learnbot.quality.local-agent-approved-flow-seed.v1",
    generatedAt: new Date().toISOString(),
    execute,
    target: {
      container,
      database,
      username,
      sessionId,
      userId,
      agentId,
      workspaceId,
      sourceRequestId,
      releaseAttemptId,
      manifestId,
      targetFile,
      commandId,
      requestIds,
    },
    safety: {
      dryRunByDefault: true,
      executionTarget: "USER_LOCAL_AGENT",
      approvalState: "APPROVED",
      status: "APPROVED",
      mutationAllowedOnlyForPatch: true,
      serverLocalMutation: false,
      cleanupSqlIncluded: true,
    },
    sql,
    cleanupSql: buildCleanupSql(Object.values(requestIds), userId),
    execution,
    passed: execute ? execution.status === "succeeded" : execution.status === "skipped",
  };
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  console.log(`local agent approved-flow seed report: ${outputPath}`);
  console.log(`local agent approved-flow seed summary: execute=${execute}, status=${execution.status}, releaseAttemptId=${releaseAttemptId}`);
  if (!report.passed) {
    process.exitCode = 1;
  }
} catch (error) {
  console.error(`local agent approved-flow seed failed: ${error.message}`);
  process.exitCode = 1;
}

function buildRows({
  sessionId,
  userId,
  agentId,
  workspaceId,
  sourceRequestId,
  releaseAttemptId,
  manifestId,
  targetFile,
  commandId,
  diff,
  requestIds,
}) {
  const common = {
    sessionId,
    userId,
    agentId,
    workspaceId,
    sourceRequestId,
    releaseAttemptId,
  };
  return [
    {
      id: requestIds.patchApply,
      toolName: "patch.apply",
      input: {
        ...common,
        mutationAllowed: true,
        dryRunOnly: false,
        manifestId,
        diff,
        targetFiles: [targetFile],
      },
    },
    {
      id: requestIds.commandRunAllowed,
      toolName: "command.runAllowed",
      input: {
        ...common,
        commandId,
        timeoutSeconds: 30,
        maxOutputBytes: 4096,
      },
    },
    {
      id: requestIds.gitStatus,
      toolName: "git.status",
      input: common,
    },
    {
      id: requestIds.rollbackRestore,
      toolName: "rollback.restore",
      input: {
        ...common,
        manifestId,
      },
    },
  ];
}

function buildSql(rows) {
  const values = rows.map((row, index) => `(
    '${row.id}'::uuid,
    '${row.input.sessionId}'::uuid,
    '${row.input.userId}'::uuid,
    '${row.input.agentId}'::uuid,
    '${row.input.workspaceId}'::uuid,
    'USER_LOCAL_AGENT',
    ${sqlLiteral(row.toolName)},
    'APPROVED',
    'APPROVED',
    ${sqlJson(row.input)},
    '["quality seed: approved local-agent flow; review before execution"]'::jsonb,
    now() + (${index} * INTERVAL '1 millisecond')
  )`).join(",\n");
  return `
BEGIN;

INSERT INTO local_agent_tool_executions (
    id, session_id, user_id, agent_id, workspace_id, execution_target, tool_name,
    approval_state, status, input, request_warnings, created_at
)
VALUES
${values};

COMMIT;
`.trimStart();
}

function buildCleanupSql(requestIds, userId) {
  const ids = requestIds.map((id) => `'${id}'::uuid`).join(", ");
  return `
DELETE FROM local_agent_tool_executions
WHERE user_id = '${userId}'::uuid
  AND id IN (${ids});
`.trimStart();
}

function runPsql(sql) {
  return new Promise((resolve) => {
    const args = psqlCommand === "docker"
      ? ["exec", "-i", container, "psql", "-U", username, "-d", database, "-v", "ON_ERROR_STOP=1"]
      : ["-U", username, "-d", database, "-v", "ON_ERROR_STOP=1"];
    const child = spawn(psqlCommand, args, { stdio: ["pipe", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("close", (code) => {
      resolve({
        status: code === 0 ? "succeeded" : "failed",
        exitCode: code,
        stdout: stdout.slice(-4000),
        stderr: stderr.slice(-4000),
      });
    });
    child.stdin.end(sql);
  });
}

function requiredUuid(name) {
  const value = readArg(name);
  if (!isUuid(value)) {
    throw new Error(`${name} must be a UUID`);
  }
  return value;
}

function readUuid(name, fallback) {
  const value = readArg(name, fallback);
  if (!isUuid(value)) {
    throw new Error(`${name} must be a UUID`);
  }
  return value;
}

function requiredString(name) {
  const value = readArg(name);
  if (!value || !String(value).trim()) {
    throw new Error(`${name} is required`);
  }
  return String(value).trim();
}

function normalizeRelativePath(value) {
  const normalized = String(value ?? "").replace(/\\/g, "/").replace(/^\/+/, "");
  if (!normalized || normalized.includes("..") || /^[a-zA-Z]:/.test(normalized)) {
    throw new Error("--target-file must be a workspace-relative path");
  }
  return normalized;
}

function defaultDiff(targetFile) {
  return [
    `--- a/${targetFile}`,
    `+++ b/${targetFile}`,
    "@@ -1,3 +1,4 @@",
    " class App {",
    "-    string Name = \"old\";",
    "+    string Name = \"new\";",
    "+    string Mode = \"safe\";",
    " }",
    "",
  ].join("\n");
}

function sqlJson(value) {
  return `${sqlLiteral(JSON.stringify(value))}::jsonb`;
}

function sqlLiteral(value) {
  return `'${String(value ?? "").replace(/'/g, "''")}'`;
}

function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(String(value ?? ""));
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

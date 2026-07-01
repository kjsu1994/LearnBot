import { spawn } from "node:child_process";
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

const manifestPath = path.resolve(readArg("--manifest"));
const outputPath = path.resolve(readArg(
  "--output",
  path.join(root, ".tmp", "quality", `document-post-processing-retry-seed-${timestamp()}.json`),
));
const execute = hasFlag("--execute");
const container = readArg("--container", "learnbot-postgres");
const database = readArg("--database", "learnbot");
const username = readArg("--username", "learnbot");
const psqlCommand = readArg("--psql-command", "docker");
const message = readArg("--message", "Milestone 8 seeded document graph retry diagnostic.");

try {
  if (!manifestPath || !fs.existsSync(manifestPath)) {
    throw new Error("--manifest must point to an existing live seed manifest");
  }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const jobId = manifest.document?.jobId;
  if (!isUuid(jobId)) {
    throw new Error("manifest.document.jobId must be a UUID");
  }
  const sql = buildSql(jobId, message);
  let execution = {
    status: "skipped",
    reason: "dry-run; pass --execute to seed the local PostgreSQL stack",
  };
  if (execute) {
    execution = await runPsql(sql);
  }
  const report = {
    schema: "learnbot.quality.document-post-processing-retry-seed.v1",
    generatedAt: new Date().toISOString(),
    manifestPath,
    execute,
    target: {
      container,
      database,
      username,
      jobId,
    },
    sql,
    execution,
    passed: execute ? execution.status === "succeeded" : execution.status === "skipped",
  };
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  console.log(`document post-processing retry seed report: ${outputPath}`);
  console.log(`document post-processing retry seed summary: execute=${execute}, status=${execution.status}, jobId=${jobId}`);
  if (!report.passed) {
    process.exitCode = 1;
  }
} catch (error) {
  console.error(`document post-processing retry seed failed: ${error.message}`);
  process.exitCode = 1;
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

function buildSql(jobId, rawMessage) {
  const cleanMessage = sqlLiteral(rawMessage);
  return `
BEGIN;

WITH target_job AS (
    SELECT id, source_id
    FROM document_indexing_jobs
    WHERE id = '${jobId}'::uuid
      AND searchable_at IS NOT NULL
    FOR UPDATE
),
upsert_graph_job AS (
    INSERT INTO document_graph_jobs (
        id, source_id, job_id, status, attempts, error_message,
        next_attempt_at, started_at, finished_at, updated_at
    )
    SELECT gen_random_uuid(), source_id, id, 'FAILED', 2, ${cleanMessage},
           now(), now(), now(), now()
    FROM target_job
    ON CONFLICT (source_id, job_id) DO UPDATE
    SET status = 'FAILED',
        attempts = GREATEST(document_graph_jobs.attempts, 2),
        error_message = EXCLUDED.error_message,
        lease_owner = NULL,
        lease_until = NULL,
        heartbeat_at = NULL,
        next_attempt_at = now(),
        started_at = COALESCE(document_graph_jobs.started_at, now()),
        finished_at = now(),
        updated_at = now()
    RETURNING source_id, job_id
),
insert_diagnostic AS (
    INSERT INTO document_processing_diagnostics (
        id, source_id, job_id, stage, analyzer, status, mode,
        attempted_items, processed_items, failed_items, node_count, edge_count,
        duration_millis, message, metadata
    )
    SELECT gen_random_uuid(), source_id, job_id,
           'DOCUMENT_GRAPH_REBUILD', 'Document graph builder', 'FAILED', 'ASYNC',
           1, 0, 1, 0, 0, 0,
           ${cleanMessage}, '{"seededBy":"milestone-8-quality","retryable":true}'::jsonb
    FROM upsert_graph_job
    RETURNING source_id
)
UPDATE data_sources
SET status = 'PARTIAL',
    updated_at = now()
WHERE id IN (SELECT source_id FROM insert_diagnostic)
  AND status NOT IN ('INDEXING', 'FAILED');

COMMIT;
`.trimStart();
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

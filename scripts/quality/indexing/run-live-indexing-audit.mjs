import { spawnSync } from "node:child_process";
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

const server = readArg("--server", "http://localhost:8083");
const baselinePath = readArg("--baseline", "");
const outputDir = path.resolve(readArg("--output-dir", path.join(root, ".tmp", "quality")));
const summaryPath = path.resolve(readArg("--summary", path.join(outputDir, `indexing-live-audit-summary-${timestamp()}.json`)));
const loginId = readArg("--login-id", process.env.LEARNBOT_QUALITY_LOGIN_ID ?? "");
const password = readArg("--password", process.env.LEARNBOT_QUALITY_PASSWORD ?? "");
const discoverSeed = hasFlag("--discover");
const runId = timestamp();

const manifestPath = path.resolve(readArg(
  "--manifest",
  discoverSeed
    ? path.join(outputDir, `indexing-live-seed-manifest-${runId}.json`)
    : path.join(scriptDir, "live-seed-manifest.template.json"),
));
const discoveryReportPath = path.join(outputDir, `indexing-live-seed-discovery-${runId}.json`);
const templatePath = path.join(outputDir, `indexing-live-capture-template-${runId}.json`);
const capturePath = path.join(outputDir, `indexing-live-capture-${runId}.json`);
const reportPath = path.join(outputDir, `indexing-diagnostics-live-${runId}.json`);
const comparisonPath = path.join(outputDir, `indexing-diagnostics-live-comparison-${runId}.json`);

fs.mkdirSync(outputDir, { recursive: true });

try {
  if (discoverSeed) {
    runStep("discover-live-seed-manifest", [
      path.join(scriptDir, "discover-live-seed-manifest.mjs"),
      "--server", server,
      "--output", manifestPath,
      "--report", discoveryReportPath,
      ...authArgs(),
    ]);
  }

  runStep("create-live-capture-template", [
    path.join(scriptDir, "create-live-capture-template.mjs"),
    "--manifest", manifestPath,
    "--output", templatePath,
  ]);

  runStep("capture-indexing-audit-fixtures", [
    path.join(scriptDir, "capture-indexing-audit-fixtures.mjs"),
    "--server", server,
    "--fixtures", templatePath,
    "--output", capturePath,
    ...authArgs(),
  ]);

  runStep("evaluate-indexing-diagnostics-fixtures", [
    path.join(scriptDir, "evaluate-indexing-diagnostics-fixtures.mjs"),
    "--fixtures", capturePath,
    "--report", reportPath,
  ]);

  let comparison = null;
  if (baselinePath) {
    runStep("compare-indexing-diagnostics-reports", [
      path.join(scriptDir, "compare-indexing-diagnostics-reports.mjs"),
      "--baseline", path.resolve(baselinePath),
      "--current", reportPath,
      "--output", comparisonPath,
    ]);
    comparison = comparisonPath;
  }

  const summary = {
    schema: "learnbot.quality.indexing-live-audit-run.v1",
    runId,
    server,
    manifestPath,
    discoveryReportPath: discoverSeed ? discoveryReportPath : null,
    templatePath,
    capturePath,
    reportPath,
    baselinePath: baselinePath ? path.resolve(baselinePath) : null,
    comparisonPath: comparison,
    authenticated: Boolean(loginId && password),
    passed: true,
  };
  fs.writeFileSync(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
  console.log(`indexing live audit summary: ${summaryPath}`);
  console.log(`indexing live audit report: ${reportPath}`);
} catch (error) {
  const summary = {
    schema: "learnbot.quality.indexing-live-audit-run.v1",
    runId,
    server,
    manifestPath,
    discoveryReportPath: discoverSeed ? discoveryReportPath : null,
    templatePath,
    capturePath,
    reportPath,
    baselinePath: baselinePath ? path.resolve(baselinePath) : null,
    comparisonPath: baselinePath ? comparisonPath : null,
    authenticated: Boolean(loginId && password),
    passed: false,
    error: error.message,
  };
  fs.writeFileSync(summaryPath, `${JSON.stringify(summary, null, 2)}\n`, "utf8");
  console.error(`indexing live audit failed: ${error.message}`);
  console.error(`indexing live audit summary: ${summaryPath}`);
  process.exit(1);
}

function authArgs() {
  if (!loginId && !password) {
    return [];
  }
  return ["--login-id", loginId, "--password", password];
}

function runStep(name, args) {
  console.log(`== ${name} ==`);
  const result = spawnSync(process.execPath, args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (result.stdout) {
    process.stdout.write(result.stdout);
  }
  if (result.stderr) {
    process.stderr.write(result.stderr);
  }
  if (result.status !== 0) {
    throw new Error(`${name} failed with exit code ${result.status}`);
  }
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

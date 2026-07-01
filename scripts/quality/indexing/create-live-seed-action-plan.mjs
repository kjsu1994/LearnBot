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

const discoveryReportPath = path.resolve(readArg("--discovery-report"));
const outputPath = path.resolve(readArg("--output", path.join(root, ".tmp", "quality", `indexing-live-seed-action-plan-${timestamp()}.json`)));
const server = readArg("--server", "");
const webUrl = readArg("--web-url", "");
const repositoryId = readArg("--repository-id", "");
const gitUrl = readArg("--git-url", "");
const repositoryName = readArg("--repository-name", "milestone-8-seed");
const branch = readArg("--branch", "main");

if (!discoveryReportPath || !fs.existsSync(discoveryReportPath)) {
  console.error("--discovery-report must point to an existing discovery report");
  process.exitCode = 1;
} else {
  const report = JSON.parse(fs.readFileSync(discoveryReportPath, "utf8"));
  const missingFields = report.remediation?.missingFields ?? report.warnings?.map((item) => item.replace(/^missing:/, "")) ?? [];
  const selectedRepositoryId = repositoryId || report.selected?.codeRepository?.id || "";
  const resolvedServer = (server || report.server || "http://localhost:8083").replace(/\/+$/, "");
  const actions = buildActions(missingFields, selectedRepositoryId, resolvedServer);
  const plan = {
    schema: "learnbot.quality.indexing-live-seed-action-plan.v1",
    generatedAt: new Date().toISOString(),
    discoveryReportPath,
    server: resolvedServer,
    missingFields,
    selected: report.selected ?? {},
    inputs: {
      webUrl,
      repositoryId: selectedRepositoryId,
      gitUrl,
      repositoryName,
      branch,
    },
    actions,
    readyToRun: actions.every((action) => action.ready),
  };

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(plan, null, 2)}\n`, "utf8");
  console.log(`indexing live seed action plan: ${outputPath}`);
  console.log(`indexing live seed action plan summary: ${actions.filter((action) => action.ready).length}/${actions.length} actions ready`);
  if (!plan.readyToRun) {
    process.exitCode = 1;
  }
}

function buildActions(missingFields, selectedRepositoryId, resolvedServer) {
  const actions = [];
  if (missingFields.includes("document.jobId") || missingFields.includes("crawler.documentId")) {
    const payload = {
      url: webUrl,
      recursive: true,
      maxDepth: 2,
      maxPages: 30,
      crawlScope: "DESCENDANT_PATH",
      robotsFailurePolicy: "SKIP",
      includeAttachments: false,
      useSitemap: false,
      renderMode: "STATIC",
    };
    actions.push({
      id: "ingest-web-seed",
      fields: missingFields.filter((field) => ["document.jobId", "crawler.documentId"].includes(field)),
      endpoint: "/api/sources/web",
      method: "POST",
      ready: Boolean(webUrl),
      reason: webUrl ? "" : "--web-url is required to create the document/crawler seed",
      payload,
      powershell: invokeJsonCommand(resolvedServer, "/api/sources/web", payload),
    });
  }
  if (missingFields.includes("code.repositoryId")) {
    const payload = {
      gitUrl,
      name: repositoryName,
      branch,
      authType: "NONE",
      storeToken: false,
    };
    actions.push({
      id: "register-code-repository",
      fields: ["code.repositoryId"],
      endpoint: "/api/code/repositories",
      method: "POST",
      ready: Boolean(gitUrl),
      reason: gitUrl ? "" : "--git-url is required to register the code repository seed",
      payload,
      powershell: invokeJsonCommand(resolvedServer, "/api/code/repositories", payload),
    });
  }
  if (missingFields.includes("code.jobId")) {
    const endpoint = selectedRepositoryId ? `/api/code/repositories/${selectedRepositoryId}/index` : "/api/code/repositories/<repository-id>/index";
    const payload = {
      storeToken: false,
    };
    actions.push({
      id: "run-code-reindex-seed",
      fields: ["code.jobId"],
      endpoint,
      method: "POST",
      ready: Boolean(selectedRepositoryId),
      reason: selectedRepositoryId ? "This may finish successfully; Milestone 8 still needs a FAILED or PARTIAL reindex candidate." : "--repository-id or a discovered code repository is required to run the code reindex seed",
      payload,
      powershell: invokeJsonCommand(resolvedServer, endpoint, payload),
    });
  }
  return actions;
}

function invokeJsonCommand(resolvedServer, endpoint, payload) {
  const json = JSON.stringify(payload).replaceAll("'", "''");
  return `$body = '${json}' | ConvertFrom-Json | ConvertTo-Json -Depth 20; Invoke-RestMethod -Method POST -Uri '${resolvedServer}${endpoint}' -ContentType 'application/json' -Body $body -WebSession $session`;
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

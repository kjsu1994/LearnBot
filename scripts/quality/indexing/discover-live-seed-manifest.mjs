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

const server = readArg("--server", "http://localhost:8083").replace(/\/+$/, "");
const outputPath = path.resolve(readArg("--output", path.join(root, ".tmp", "quality", "indexing-live-seed-manifest.json")));
const reportPath = path.resolve(readArg("--report", path.join(root, ".tmp", "quality", `indexing-live-seed-discovery-${timestamp()}.json`)));
const requestTimeoutMs = Number(readArg("--timeout-ms", "30000"));
const loginId = readArg("--login-id", process.env.LEARNBOT_QUALITY_LOGIN_ID ?? "");
const password = readArg("--password", process.env.LEARNBOT_QUALITY_PASSWORD ?? "");
let authCookie = "";

try {
  authCookie = await loginIfConfigured();
  const discovery = await discover();
  const missing = [];
  if (!discovery.selected.documentJob?.id) {
    missing.push("document.jobId");
  }
  if (!discovery.selected.codeRepository?.id) {
    missing.push("code.repositoryId");
  }
  if (!discovery.selected.codeJob?.id) {
    missing.push("code.jobId");
  }
  if (!discovery.selected.crawlerDocument?.id) {
    missing.push("crawler.documentId");
  }

  const manifest = {
    schema: "learnbot.quality.indexing-live-seed-manifest.v1",
    document: {
      jobId: discovery.selected.documentJob?.id ?? "",
    },
    code: {
      repositoryId: discovery.selected.codeRepository?.id ?? "",
      jobId: discovery.selected.codeJob?.id ?? "",
    },
    crawler: {
      documentId: discovery.selected.crawlerDocument?.id ?? "",
    },
  };

  const report = {
    schema: "learnbot.quality.indexing-live-seed-discovery.v1",
    server,
    discoveredAt: new Date().toISOString(),
    requestTimeoutMs,
    authenticated: Boolean(authCookie),
    endpoints: discovery.endpoints,
    selected: discovery.selected,
    candidateCounts: discovery.candidateCounts,
    warnings: missing.map((field) => `missing:${field}`),
    remediation: buildRemediation(missing, discovery.candidateCounts),
    passed: missing.length === 0,
  };

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  console.log(`indexing live seed manifest: ${outputPath}`);
  console.log(`indexing live seed discovery report: ${reportPath}`);
  console.log(`indexing live seed discovery summary: ${missing.length === 0 ? "ready" : `missing ${missing.join(", ")}`}`);
  if (missing.length > 0) {
    console.log(`indexing live seed discovery remediation: ${reportPath}`);
  }

  if (missing.length > 0) {
    process.exitCode = 1;
  }
} catch (error) {
  console.error(`indexing live seed discovery failed: ${error.message}`);
  process.exitCode = 1;
}

async function discover() {
  const documentJobs = normalizeItems(await getJson("/api/document-indexing/jobs"));
  const repositories = normalizeItems(await getJson("/api/code/repositories"));
  const documents = normalizeItems(await getJson("/api/documents"));

  const selectedDocumentJob = selectDocumentJob(documentJobs);
  const codeCandidates = [];
  for (const repository of repositories) {
    const repositoryId = idOf(repository, ["id", "repositoryId"]);
    if (!repositoryId) {
      continue;
    }
    const jobs = normalizeItems(await getJson(`/api/code/repositories/${encodeURIComponent(repositoryId)}/jobs`));
    codeCandidates.push({
      repository,
      jobs,
    });
  }
  const selectedCode = selectCodeSeed(codeCandidates);
  const selectedCodeRepository = selectedCode?.repository ?? repositories[0] ?? null;
  const selectedCrawlerDocument = selectCrawlerDocument(documents);

  return {
    endpoints: {
      documentJobs: "/api/document-indexing/jobs",
      codeRepositories: "/api/code/repositories",
      documents: "/api/documents",
      codeJobs: codeCandidates.map((candidate) => `/api/code/repositories/${idOf(candidate.repository, ["id", "repositoryId"])}/jobs`),
    },
    selected: {
      documentJob: summarizeItem(selectedDocumentJob),
      codeRepository: summarizeItem(selectedCodeRepository),
      codeJob: summarizeItem(selectedCode?.job),
      crawlerDocument: summarizeItem(selectedCrawlerDocument),
    },
    candidateCounts: {
      documentJobs: documentJobs.length,
      codeRepositories: repositories.length,
      codeJobs: codeCandidates.reduce((sum, candidate) => sum + candidate.jobs.length, 0),
      documents: documents.length,
    },
  };
}

function selectDocumentJob(jobs) {
  return jobs
    .filter((job) => documentJobRank(job) < 3)
    .sort((a, b) => documentJobRank(a) - documentJobRank(b))[0] ?? null;
}

function documentJobRank(job) {
  const status = statusOf(job);
  if (status === "PARTIAL") {
    return 0;
  }
  if (["SEARCHABLE", "READY", "INDEXED", "SUCCESS", "SUCCEEDED"].includes(status)) {
    return 1;
  }
  return 3;
}

function selectCodeSeed(candidates) {
  const flattened = [];
  for (const candidate of candidates) {
    for (const job of candidate.jobs) {
      flattened.push({
        repository: candidate.repository,
        job,
      });
    }
  }
  return flattened
    .filter((candidate) => codeJobRank(candidate.job) < 2)
    .sort((a, b) => codeJobRank(a.job) - codeJobRank(b.job))[0] ?? null;
}

function codeJobRank(job) {
  const status = statusOf(job);
  if (status === "FAILED") {
    return 0;
  }
  if (status === "PARTIAL") {
    return 1;
  }
  return 3;
}

function selectCrawlerDocument(documents) {
  return documents.find((document) => {
    const uri = String(document.sourceUri ?? document.url ?? document.sourceUrl ?? "");
    const type = String(document.type ?? document.documentType ?? document.sourceType ?? "").toUpperCase();
    return uri.startsWith("http://") || uri.startsWith("https://") || type.includes("WEB");
  }) ?? null;
}

async function getJson(endpoint) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(`${server}${endpoint}`, {
      signal: controller.signal,
      headers: authCookie ? { Cookie: authCookie } : {},
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${endpoint} returned ${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return text ? JSON.parse(text) : [];
  } finally {
    clearTimeout(timeout);
  }
}

async function loginIfConfigured() {
  if (!loginId && !password) {
    return "";
  }
  if (!loginId || !password) {
    throw new Error("--login-id and --password must be provided together");
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(`${server}/api/auth/login`, {
      method: "POST",
      signal: controller.signal,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ loginId, password, rememberLogin: false }),
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`/api/auth/login returned ${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return cookieHeaderFrom(response);
  } finally {
    clearTimeout(timeout);
  }
}

function cookieHeaderFrom(response) {
  const setCookies = typeof response.headers.getSetCookie === "function"
    ? response.headers.getSetCookie()
    : [response.headers.get("set-cookie")].filter(Boolean);
  return setCookies.map((item) => item.split(";")[0]).filter(Boolean).join("; ");
}

function normalizeItems(payload) {
  if (Array.isArray(payload)) {
    return payload;
  }
  for (const key of ["items", "content", "documents", "jobs", "repositories", "data"]) {
    if (Array.isArray(payload?.[key])) {
      return payload[key];
    }
  }
  return [];
}

function summarizeItem(item) {
  if (!item) {
    return null;
  }
  return {
    id: idOf(item, ["id", "jobId", "documentId", "repositoryId"]),
    status: statusOf(item) || null,
    name: item.name ?? item.title ?? item.sourceLabel ?? item.fileName ?? null,
    sourceUri: item.sourceUri ?? item.url ?? item.sourceUrl ?? null,
  };
}

function idOf(item, keys) {
  for (const key of keys) {
    if (item?.[key]) {
      return String(item[key]);
    }
  }
  return "";
}

function statusOf(item) {
  return String(item?.status ?? item?.sourceStatus ?? item?.jobStatus ?? "").toUpperCase();
}

function buildRemediation(missing, candidateCounts) {
  return {
    missingFields: missing,
    candidateCounts,
    seedActions: missing.map((field) => ({
      field,
      action: actionForMissingField(field),
    })),
    retryCommand: `node scripts\\quality\\indexing\\run-live-indexing-audit.mjs --server ${server} --discover`,
    manualManifestTemplate: "scripts\\quality\\indexing\\live-seed-manifest.template.json",
  };
}

function actionForMissingField(field) {
  switch (field) {
    case "document.jobId":
      return "Create or re-run a document ingestion job that reaches PARTIAL, SEARCHABLE, READY, INDEXED, SUCCESS, or SUCCEEDED after base chunks are searchable and is listed by /api/document-indexing/jobs.";
    case "code.repositoryId":
      return "Register at least one code repository through the Code workspace or /api/code/repositories.";
    case "code.jobId":
      return "Run a code repository reindex attempt that ends as FAILED or PARTIAL while preserving the previous active index, then keep that job visible in the repository jobs endpoint.";
    case "crawler.documentId":
      return "Ingest a web or crawler document so /api/documents includes a document with an http(s) sourceUri/url/sourceUrl or WEB source type.";
    default:
      return "Create the missing seed data, then rerun live indexing audit discovery.";
  }
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

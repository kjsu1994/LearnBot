import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, "..", "..", "..");

function readArg(name, fallback) {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

const fixturesPath = path.resolve(readArg("--fixtures", path.join(scriptDir, "live-capture-fixtures.template.json")));
const outputPath = path.resolve(
  readArg("--output", path.join(root, ".tmp", "quality", `indexing-live-capture-${timestamp()}.json`)),
);
const server = readArg("--server", "http://localhost:8083").replace(/\/+$/, "");
const requestTimeoutMs = Number(readArg("--timeout-ms", "30000"));
const loginId = readArg("--login-id", process.env.LEARNBOT_QUALITY_LOGIN_ID ?? "");
const password = readArg("--password", process.env.LEARNBOT_QUALITY_PASSWORD ?? "");

const fixtureDocument = JSON.parse(fs.readFileSync(fixturesPath, "utf8"));
const capturedCases = [];
const authCookie = await loginIfConfigured();

for (const testCase of fixtureDocument.cases ?? []) {
  capturedCases.push(await captureCase(testCase));
}

const output = {
  ...fixtureDocument,
  schema: "learnbot.quality.indexing-live-capture.v1",
  liveCapture: {
    server,
    capturedAt: new Date().toISOString(),
    timeoutMs: requestTimeoutMs,
    authenticated: Boolean(authCookie),
  },
  cases: capturedCases,
};

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
console.log(`indexing live capture report: ${outputPath}`);
console.log(`indexing live capture summary: ${capturedCases.filter((item) => item.liveCapture?.status === "captured").length}/${capturedCases.length} cases captured`);

async function captureCase(testCase) {
  const endpoint = testCase.endpoint;
  if (!endpoint) {
    return {
      ...testCase,
      observed: {},
      liveCapture: {
        status: "failed",
        error: "endpoint is required",
      },
    };
  }

  try {
    const resolvedEndpoint = endpoint.startsWith("/") ? endpoint : `/${endpoint}`;
    const payload = await getJson(`${server}${resolvedEndpoint}`);
    const context = await captureContext(testCase.kind, resolvedEndpoint);
    return {
      ...testCase,
      observed: normalizeObserved(testCase.kind, payload, context),
      liveCapture: {
        endpoint,
        status: "captured",
      },
    };
  } catch (error) {
    return {
      ...testCase,
      observed: {},
      liveCapture: {
        endpoint,
        status: "failed",
        error: error.message,
      },
    };
  }
}

async function captureContext(kind, endpoint) {
  if (kind === "document") {
    const match = endpoint.match(/^\/api\/document-indexing\/jobs\/([^/]+)$/);
    if (!match) {
      return {};
    }
    const jobId = decodeURIComponent(match[1]);
    const context = { jobId };
    try {
      context.diagnostics = arrayOf(await getJson(`${server}/api/document-indexing/jobs/${encodeURIComponent(jobId)}/diagnostics`));
    } catch {
      context.diagnostics = [];
    }
    return context;
  }
  if (kind !== "code") {
    return {};
  }
  const match = endpoint.match(/^\/api\/code\/repositories\/([^/]+)\/jobs\/([^/]+)\/diagnostics$/);
  if (!match) {
    return {};
  }
  const repositoryId = decodeURIComponent(match[1]);
  const jobId = decodeURIComponent(match[2]);
  const context = { repositoryId, jobId };
  try {
    const jobs = arrayOf(await getJson(`${server}/api/code/repositories/${encodeURIComponent(repositoryId)}/jobs`));
    context.job = jobs.find((job) => String(job.id ?? job.jobId) === jobId) ?? null;
  } catch {
    context.job = null;
  }
  try {
    const repositories = arrayOf(await getJson(`${server}/api/code/repositories`));
    context.repository = repositories.find((repository) => String(repository.id ?? repository.repositoryId) === repositoryId) ?? null;
  } catch {
    context.repository = null;
  }
  return context;
}

async function getJson(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: authCookie ? { Cookie: authCookie } : {},
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return text ? JSON.parse(text) : {};
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

function normalizeObserved(kind, payload, context = {}) {
  if (kind === "document") {
    return normalizeDocument(payload, context);
  }
  if (kind === "code") {
    return normalizeCode(payload, context);
  }
  if (kind === "crawler") {
    return normalizeCrawler(payload);
  }
  return payload ?? {};
}

function normalizeDocument(payload, context = {}) {
  const diagnostics = diagnosticsFrom(payload).concat(diagnosticsFrom({ diagnostics: context.diagnostics ?? [] }));
  const failedStages = uniqueStrings([
    ...arrayOf(payload.failedStages),
    ...diagnostics.filter((item) => statusIsFailure(item.status)).map((item) => item.stage),
  ]);
  const retryAvailableStages = uniqueStrings([
    ...arrayOf(payload.retryAvailableStages),
    ...arrayOf(payload.retryStages),
    ...failedStages.filter(isRetryableDocumentStage),
  ]);
  return {
    sourceStatus: payload.sourceStatus ?? payload.status ?? payload.source?.status ?? null,
    baseChunksSearchable: booleanValue(payload.baseChunksSearchable ?? payload.searchable ?? payload.source?.searchable)
      || Boolean(payload.searchableAt)
      || Number(payload.totalChunks ?? 0) > 0,
    originalChunksPreserved: booleanValue(payload.originalChunksPreserved ?? payload.originalChunksAvailable ?? payload.source?.originalChunksPreserved)
      || Number(payload.totalChunks ?? 0) > 0,
    failedStages,
    retryAvailableStages,
    diagnostics,
  };
}

function normalizeCode(payload, context = {}) {
  const job = context.job ?? {};
  const repository = context.repository ?? {};
  const candidateStatus = payload.candidateJobStatus ?? payload.candidateJob?.status ?? payload.failedJob?.status ?? job.status ?? null;
  const activeVersion = repository.lastIndexedCommit ?? repository.sourceHash ?? payload.activeIndex?.indexVersion ?? payload.activeIndexAfterFailure ?? null;
  const failedReindexPreservedActiveIndex = String(candidateStatus ?? "").toUpperCase() === "FAILED" && Boolean(activeVersion);
  return {
    previousActiveIndexVersion: payload.previousActiveIndexVersion ?? payload.previousActiveIndex?.indexVersion ?? payload.activeIndexBeforeFailure ?? (failedReindexPreservedActiveIndex ? activeVersion : null),
    candidateIndexVersion: payload.candidateIndexVersion ?? payload.candidateJob?.indexVersion ?? payload.failedJob?.indexVersion ?? job.id ?? job.jobId ?? null,
    candidateJobStatus: candidateStatus,
    activeIndexVersionAfterFailure: payload.activeIndexVersionAfterFailure ?? payload.activeIndex?.indexVersion ?? payload.activeIndexAfterFailure ?? (failedReindexPreservedActiveIndex ? activeVersion : null),
    searchFallbackAvailable: booleanValue(payload.searchFallbackAvailable ?? payload.keywordFallbackAvailable ?? payload.search?.fallbackAvailable ?? failedReindexPreservedActiveIndex),
    diagnostics: diagnosticsFrom(payload),
  };
}

function normalizeCrawler(payload) {
  const pages = arrayOf(payload.fetchedPages ?? payload.pages ?? payload.documents ?? payload.chunks);
  const audits = arrayOf(payload.crawlAudits ?? payload.auditEvents ?? payload.audits);
  const skipped = arrayOf(payload.skippedPages ?? payload.skipped);
  const skippedSources = skipped.length > 0 ? skipped : audits.filter((audit) => audit.success === false);
  const fetchedPages = pages.map((page) => ({
    url: page.url ?? page.sourceUri ?? page.location ?? page.metadata?.sourceUrl ?? payload.summary?.sourceUri ?? "",
    depth: Number(page.depth ?? page.crawlDepth ?? audits[0]?.depth ?? 0),
    storedAsSeparateDocument: page.storedAsSeparateDocument === undefined
      ? Boolean(page.documentId || page.sourceUri || payload.summary?.id)
      : booleanValue(page.storedAsSeparateDocument),
    sourceUri: page.sourceUri ?? page.url ?? page.location ?? page.metadata?.sourceUrl ?? payload.summary?.sourceUri ?? "",
  }));
  return {
    allowedDomain: booleanValue(payload.allowedDomain ?? audits[0]?.allowedDomain),
    robotsAllowed: payload.robotsAllowed === undefined && audits[0]?.robotsAllowed == null ? true : booleanValue(payload.robotsAllowed ?? audits[0]?.robotsAllowed),
    requestedMaxDepth: numberOrNull(payload.requestedMaxDepth ?? payload.request?.maxDepth ?? audits[0]?.metadata?.maxDepth),
    effectiveMaxDepth: numberOrNull(payload.effectiveMaxDepth ?? payload.maxDepth ?? audits[0]?.depth ?? 0),
    requestedMaxPages: numberOrNull(payload.requestedMaxPages ?? payload.request?.maxPages ?? audits[0]?.metadata?.maxPages),
    effectiveMaxPages: numberOrNull(payload.effectiveMaxPages ?? payload.maxPages ?? fetchedPages.length),
    fetchedPages,
    skippedPages: skippedSources.map((page) => ({
      url: page.url ?? page.sourceUri ?? "",
      reason: page.reason ?? page.reasonCode ?? "",
      category: page.category ?? null,
      severity: page.severity ?? null,
      indexingBlocked: typeof page.indexingBlocked === "boolean" ? page.indexingBlocked : null,
      userAction: page.userAction ?? "",
    })),
  };
}

function diagnosticsFrom(payload) {
  return arrayOf(payload.diagnostics ?? payload.analysisDiagnostics ?? payload.processingDiagnostics).map((item) => ({
    stage: item.stage ?? item.name ?? "",
    status: item.status ?? "",
  })).filter((item) => item.stage || item.status);
}

function statusIsFailure(status) {
  return ["FAILED", "PARTIAL", "RETRYING"].includes(String(status ?? "").toUpperCase());
}

function isRetryableDocumentStage(stage) {
  return ["DOCUMENT_GRAPH_REBUILD", "DOCUMENT_LLM_ENRICHMENT"].includes(String(stage ?? "").toUpperCase());
}

function booleanValue(value) {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    return ["true", "yes", "1", "ready", "searchable"].includes(value.toLowerCase());
  }
  return Boolean(value);
}

function numberOrNull(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function arrayOf(value) {
  return Array.isArray(value) ? value : [];
}

function uniqueStrings(values) {
  return Array.from(new Set((values ?? []).map((value) => String(value)).filter(Boolean)));
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

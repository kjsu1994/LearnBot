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

const fixturesPath = path.resolve(readArg("--fixtures", path.join(scriptDir, "fixtures.json")));
const reportPath = path.resolve(
  readArg("--report", path.join(root, ".tmp", "quality", `rag-quality-${timestamp()}.json`)),
);
const liveMode = process.argv.includes("--live");
const server = readArg("--server", "http://localhost:8083").replace(/\/+$/, "");
const liveFixturesPath = path.resolve(
  readArg("--live-fixtures-report", path.join(root, ".tmp", "quality", `rag-live-fixtures-${timestamp()}.json`)),
);
const requestTimeoutMs = Number(readArg("--timeout-ms", "30000"));
const loginId = readArg("--login-id", process.env.LEARNBOT_QUALITY_LOGIN_ID ?? "");
const password = readArg("--password", process.env.LEARNBOT_QUALITY_PASSWORD ?? "");
let authCookie = "";

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\..+$/, "").replace("T", "-");
}

function normalize(value) {
  return String(value ?? "").toLowerCase();
}

function includesNormalized(haystack, needle) {
  return normalize(haystack).includes(normalize(needle));
}

function uniqueStrings(values) {
  return Array.from(new Set((values ?? []).map((value) => String(value)).filter(Boolean)));
}

function scoreCase(testCase) {
  if (testCase.skipReason) {
    return {
      id: testCase.id,
      domain: testCase.domain,
      question: testCase.question,
      status: "skipped",
      passed: true,
      skipReason: testCase.skipReason,
    };
  }

  const expectedCitationIds = uniqueStrings(testCase.expectedCitationIds);
  const observedCitationIds = uniqueStrings(testCase.observed?.citationIds);
  const evidenceText = (testCase.observed?.evidence ?? [])
    .map((item) => `${item.id ?? ""}\n${item.text ?? ""}`)
    .join("\n");
  const answer = testCase.observed?.answer ?? "";
  const latencyMs = Number(testCase.observed?.latencyMs ?? Number.NaN);

  const matchedCitationIds = expectedCitationIds.filter((id) => observedCitationIds.includes(id));
  const unexpectedCitationIds = observedCitationIds.filter((id) => !expectedCitationIds.includes(id));
  const citationRecall = expectedCitationIds.length === 0 ? 1 : matchedCitationIds.length / expectedCitationIds.length;
  const citationPrecision = observedCitationIds.length === 0 ? 0 : matchedCitationIds.length / observedCitationIds.length;

  const expectedEvidenceSnippets = uniqueStrings(testCase.expectedEvidenceSnippets);
  const matchedEvidenceSnippets = expectedEvidenceSnippets.filter((snippet) => includesNormalized(evidenceText, snippet));
  const evidenceCoverage =
    expectedEvidenceSnippets.length === 0 ? 1 : matchedEvidenceSnippets.length / expectedEvidenceSnippets.length;
  const evidenceIds = uniqueStrings((testCase.observed?.evidence ?? []).map((item) => item?.id));

  const forbiddenAnswerTerms = uniqueStrings(testCase.forbiddenAnswerTerms);
  const forbiddenTermsFound = forbiddenAnswerTerms.filter((term) => includesNormalized(answer, term));
  const latencyPassed = Number.isFinite(latencyMs) && latencyMs <= Number(testCase.maxLatencyMs);
  const followUp = scoreFollowUpQuality(testCase, answer, observedCitationIds, evidenceIds);

  const passed =
    citationRecall === 1 &&
    citationPrecision === 1 &&
    evidenceCoverage === 1 &&
    followUp.passed &&
    forbiddenTermsFound.length === 0 &&
    latencyPassed;

  return {
    id: testCase.id,
    domain: testCase.domain,
    question: testCase.question,
    status: passed ? "passed" : "failed",
    passed,
    citation: {
      expected: expectedCitationIds.length,
      observed: observedCitationIds.length,
      matched: matchedCitationIds.length,
      recall: round(citationRecall),
      precision: round(citationPrecision),
      unexpectedCitationIds,
    },
    evidence: {
      expectedSnippets: expectedEvidenceSnippets.length,
      matchedSnippets: matchedEvidenceSnippets.length,
      coverage: round(evidenceCoverage),
    },
    hallucinationRisk: {
      forbiddenTermsFound,
      status: forbiddenTermsFound.length === 0 ? "clear" : "flagged",
    },
    followUp,
    latency: {
      observedMs: latencyMs,
      maxMs: Number(testCase.maxLatencyMs),
      passed: latencyPassed,
    },
  };
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}

const startedAt = new Date();
const sourceFixtureDocument = readJson(fixturesPath);
const fixtureDocument = liveMode ? await captureLiveFixtures(sourceFixtureDocument) : sourceFixtureDocument;
const cases = fixtureDocument.cases ?? [];
const results = cases.map(scoreCase);
const scoredResults = results.filter((result) => result.status !== "skipped");
const failed = scoredResults.filter((result) => !result.passed);
const finishedAt = new Date();

const report = {
  schema: "learnbot.quality.rag-score.v1",
  fixturesPath,
  liveMode,
  server: liveMode ? server : null,
  startedAt: startedAt.toISOString(),
  finishedAt: finishedAt.toISOString(),
  durationSeconds: round((finishedAt.getTime() - startedAt.getTime()) / 1000),
  summary: {
    totalCases: results.length,
    scoredCases: scoredResults.length,
    skippedCases: results.length - scoredResults.length,
    passedCases: scoredResults.length - failed.length,
    failedCases: failed.length,
    citationRecall: round(average(scoredResults.map((result) => result.citation.recall))),
    citationPrecision: round(average(scoredResults.map((result) => result.citation.precision))),
    evidenceCoverage: round(average(scoredResults.map((result) => result.evidence.coverage))),
    followUpQuality: round(average(scoredResults.map((result) => result.followUp.quality))),
    followUpPassedCases: scoredResults.filter((result) => result.followUp.passed).length,
    latencyPassedCases: scoredResults.filter((result) => result.latency.passed).length,
    hallucinationRiskFlags: scoredResults.reduce(
      (count, result) => count + result.hallucinationRisk.forbiddenTermsFound.length,
      0,
    ),
  },
  results,
  passed: failed.length === 0,
};

function average(values) {
  if (values.length === 0) {
    return 0;
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, ""));
}

async function captureLiveFixtures(fixtureDocument) {
  authCookie = await loginIfConfigured();
  const capturedCases = [];
  for (const testCase of fixtureDocument.cases ?? []) {
    capturedCases.push(await captureLiveCase(testCase));
  }
  const captured = {
    ...fixtureDocument,
    schema: "learnbot.quality.rag-live-fixtures.v1",
    liveCapture: {
      server,
      capturedAt: new Date().toISOString(),
      timeoutMs: requestTimeoutMs,
      authenticated: Boolean(authCookie),
    },
    cases: capturedCases,
  };

  fs.mkdirSync(path.dirname(liveFixturesPath), { recursive: true });
  fs.writeFileSync(liveFixturesPath, `${JSON.stringify(captured, null, 2)}\n`, "utf8");
  console.log(`rag live fixtures report: ${liveFixturesPath}`);
  return captured;
}

async function captureLiveCase(testCase) {
  if (testCase.domain === "code" && !testCase.repositoryId) {
    return {
      ...testCase,
      observed: null,
      skipReason: "LIVE_CODE_REPOSITORY_ID_REQUIRED",
    };
  }

  const endpoint = testCase.domain === "code" ? "/api/code/ask" : "/api/rag/ask";
  let conversationId = testCase.conversationId ?? null;
  let priorCapture = null;
  if (testCase.domain !== "code" && testCase.conversational && testCase.priorQuestion) {
    const priorResponse = await postJson(`${server}${endpoint}`, {
      question: testCase.priorQuestion,
      mode: testCase.priorMode ?? testCase.mode ?? "qa",
      speedProfile: testCase.speedProfile ?? "balanced",
      spaceId: testCase.spaceId ?? null,
      conversational: true,
      conversationId,
    });
    conversationId = priorResponse?.conversationId ?? conversationId;
    priorCapture = {
      status: "captured",
      question: testCase.priorQuestion,
      conversationId,
    };
  }
  const body = testCase.domain === "code"
    ? {
        repositoryId: testCase.repositoryId,
        spaceId: testCase.spaceId ?? null,
        question: testCase.question,
        mode: testCase.mode ?? "overview",
        limit: testCase.limit ?? 10,
        conversational: false,
        conversationId: testCase.conversationId ?? null,
      }
    : {
        question: testCase.question,
        mode: testCase.mode ?? "qa",
        speedProfile: testCase.speedProfile ?? "balanced",
        spaceId: testCase.spaceId ?? null,
        conversational: Boolean(testCase.conversational),
        conversationId,
      };

  const started = Date.now();
  try {
    const response = await postJson(`${server}${endpoint}`, body);
    const latencyMs = Date.now() - started;
    return {
      ...testCase,
      observed: normalizeLiveObserved(response, latencyMs, testCase.domain),
      liveCapture: {
        endpoint,
        status: "captured",
        prior: priorCapture,
      },
    };
  } catch (error) {
    return {
      ...testCase,
      observed: {
        answer: "",
        citationIds: [],
        evidence: [],
        latencyMs: Date.now() - started,
      },
      liveCapture: {
        endpoint,
        status: "failed",
        error: error.message,
      },
    };
  }
}

async function postJson(url, body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(authCookie ? { Cookie: authCookie } : {}),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
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

function normalizeLiveObserved(response, latencyMs, domain) {
  const evidence = Array.isArray(response?.evidence) ? response.evidence : [];
  const citations = Array.isArray(response?.citations) ? response.citations : [];
  const sourceItems = domain === "code" ? evidence : evidence.length ? evidence : citations;
  const citationItems = citations.length ? citations : evidence;
  return {
    answer: response?.answer ?? "",
    effectiveQuestion: response?.effectiveQuestion ?? response?.diagnostics?.effectiveQuestion ?? "",
    citationIds: uniqueStrings(citationItems.map(citationIdentity)),
    evidence: sourceItems.map((item, index) => ({
      id: citationIdentity(item) || `evidence-${index + 1}`,
      text: evidenceText(item),
    })),
    latencyMs,
  };
}

function scoreFollowUpQuality(testCase, answer, observedCitationIds, evidenceIds) {
  const expectedFollowUpTerms = uniqueStrings(testCase.expectedFollowUpTerms);
  const expectedPriorCitationIds = uniqueStrings(testCase.expectedPriorCitationIds);
  const forbiddenFollowUpTerms = uniqueStrings(testCase.forbiddenFollowUpTerms);
  const effectiveQuestion = testCase.observed?.effectiveQuestion ?? "";
  const searchableText = `${effectiveQuestion}\n${answer}`;

  const matchedTerms = expectedFollowUpTerms.filter((term) => includesNormalized(searchableText, term));
  const matchedPriorCitationIds = expectedPriorCitationIds.filter(
    (id) => observedCitationIds.includes(id) || evidenceIds.includes(id),
  );
  const forbiddenTermsFound = forbiddenFollowUpTerms.filter((term) => includesNormalized(searchableText, term));
  const termCoverage = expectedFollowUpTerms.length === 0 ? 1 : matchedTerms.length / expectedFollowUpTerms.length;
  const priorCitationCoverage =
    expectedPriorCitationIds.length === 0 ? 1 : matchedPriorCitationIds.length / expectedPriorCitationIds.length;
  const quality = Math.min(termCoverage, priorCitationCoverage);

  return {
    expectedTerms: expectedFollowUpTerms.length,
    matchedTerms: matchedTerms.length,
    expectedPriorCitationIds: expectedPriorCitationIds.length,
    matchedPriorCitationIds: matchedPriorCitationIds.length,
    forbiddenTermsFound,
    quality: round(quality),
    passed: quality === 1 && forbiddenTermsFound.length === 0,
  };
}

function citationIdentity(item = {}) {
  return item.chunkId
    || item.citationId
    || item.id
    || item.fileId
    || item.documentId
    || item.sourceUri
    || item.filePath
    || "";
}

function evidenceText(item = {}) {
  return [
    item.content,
    item.text,
    item.preview,
    item.snippet,
    item.filePath,
    item.title,
    item.sourceUri,
  ].filter(Boolean).join("\n");
}

fs.mkdirSync(path.dirname(reportPath), { recursive: true });
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
console.log(`rag quality report: ${reportPath}`);
console.log(
  `rag quality summary: ${report.summary.passedCases}/${report.summary.scoredCases} scored cases passed, skipped=${report.summary.skippedCases}, citationRecall=${report.summary.citationRecall}, evidenceCoverage=${report.summary.evidenceCoverage}`,
);

if (!report.passed) {
  process.exitCode = 1;
}

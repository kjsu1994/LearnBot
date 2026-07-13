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
const parsedCaseDelayMs = Number(readArg("--case-delay-ms", "0"));
const caseDelayMs = Number.isFinite(parsedCaseDelayMs) && parsedCaseDelayMs > 0
  ? Math.floor(parsedCaseDelayMs)
  : 0;
const loginId = readArg("--login-id", process.env.LEARNBOT_QUALITY_LOGIN_ID ?? "");
const password = readArg("--password", process.env.LEARNBOT_QUALITY_PASSWORD ?? "");
const selectedCaseIds = new Set(
  readArg("--case-ids", "").split(",").map((value) => value.trim()).filter(Boolean),
);
const DEFAULT_INSUFFICIENT_ANSWER_TERMS = [
  "답변에 필요한 코드 근거를 충분히 확인하지 못했습니다",
  "코드 근거가 부족",
  "근거가 부족",
  "insufficient evidence",
  "not found",
  "cannot find",
];
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

function normalizedClaimGroups(testCase) {
  const alternatives = (testCase.requiredClaimGroups ?? [])
    .map((group) => uniqueStrings(Array.isArray(group) ? group : [group]))
    .filter((group) => group.length > 0);
  return [
    ...uniqueStrings(testCase.requiredClaims).map((claim) => [claim]),
    ...alternatives,
  ];
}

function inferredDimensions(testCase, answerMode) {
  const question = String(testCase.question ?? "");
  const expectedFiles = uniqueStrings(testCase.expectedFiles);
  const expectedSymbols = uniqueStrings(testCase.expectedSymbols);
  const symbolMentioned = expectedSymbols.some((symbol) => includesNormalized(question, symbol));
  const groundingWidth = Math.max(expectedFiles.length, expectedSymbols.length);
  return {
    cohort: testCase.cohort ?? (String(testCase.id ?? "").includes("-diverse-") ? "diverse" : "baseline"),
    language: testCase.language ?? "unspecified",
    questionLanguage: testCase.questionLanguage ?? (/[가-힣]/.test(question) ? "ko" : "en"),
    explicitness: testCase.explicitness ?? (symbolMentioned ? "exact-symbol" : "functional"),
    reasoningType: testCase.reasoningType ?? testCase.category ?? "unspecified",
    difficulty: testCase.difficulty ?? (groundingWidth >= 3 ? "hard" : groundingWidth >= 2 ? "medium" : "easy"),
    answerability: testCase.answerability ?? (answerMode === "insufficient" ? "insufficient" : "answerable"),
  };
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
  const observedEvidence = Array.isArray(testCase.observed?.evidence) ? testCase.observed.evidence : [];
  const evidenceIds = uniqueStrings(observedEvidence.map((item) => item?.id));
  const evidenceText = observedEvidence
    .map((item) => evidenceSearchableText(item))
    .join("\n");
  const answer = testCase.observed?.answer ?? "";
  const citationRequired = testCase.citationRequired !== false;
  const latencyMs = Number(testCase.observed?.latencyMs ?? Number.NaN);

  const citationMode = resolveCitationMode(testCase, expectedCitationIds);
  const matchedCitationIds = citationMode === "grounded"
    ? observedCitationIds.filter((id) => evidenceIds.includes(id))
    : expectedCitationIds.filter((id) => observedCitationIds.includes(id));
  const unexpectedCitationIds = citationMode === "grounded"
    ? observedCitationIds.filter((id) => !evidenceIds.includes(id))
    : observedCitationIds.filter((id) => !expectedCitationIds.includes(id));
  const citationRecall = !citationRequired ? 1 : citationMode === "grounded"
    ? (observedCitationIds.length > 0 ? 1 : 0)
    : (expectedCitationIds.length === 0 ? 1 : matchedCitationIds.length / expectedCitationIds.length);
  const citationPrecision = !citationRequired ? 1
    : observedCitationIds.length === 0 ? 0 : matchedCitationIds.length / observedCitationIds.length;

  const expectedEvidenceSnippets = uniqueStrings(testCase.expectedEvidenceSnippets);
  const matchedEvidenceSnippets = expectedEvidenceSnippets.filter((snippet) => includesNormalized(evidenceText, snippet));
  const evidenceCoverage =
    expectedEvidenceSnippets.length === 0 ? 1 : matchedEvidenceSnippets.length / expectedEvidenceSnippets.length;

  const requiredClaimGroups = normalizedClaimGroups(testCase);
  const matchedRequiredClaimGroups = requiredClaimGroups.filter((group) =>
    group.some((claim) => includesNormalized(answer, claim))
  );
  const matchedRequiredClaims = matchedRequiredClaimGroups.map((group) =>
    group.find((claim) => includesNormalized(answer, claim))
  );
  const requiredClaimCoverage = requiredClaimGroups.length === 0
    ? 1
    : matchedRequiredClaimGroups.length / requiredClaimGroups.length;
  const forbiddenClaims = uniqueStrings(testCase.forbiddenClaims);
  const forbiddenClaimsFound = forbiddenClaims.filter((claim) => includesNormalized(answer, claim));

  const expectedFiles = uniqueStrings(testCase.expectedFiles);
  const observedFiles = uniqueStrings(observedEvidence.map(evidenceFilePath));
  const matchedFiles = expectedFiles.filter((expected) => observedFiles.some((observed) => filePathMatches(observed, expected)));
  const fileCoverage = expectedFiles.length === 0 ? 1 : matchedFiles.length / expectedFiles.length;

  const expectedSymbols = uniqueStrings(testCase.expectedSymbols);
  const observedSymbols = uniqueStrings(observedEvidence.flatMap(evidenceSymbols));
  const matchedSymbols = expectedSymbols.filter((expected) =>
    observedSymbols.some((observed) => normalize(observed) === normalize(expected))
      || containsIdentifier(evidenceText, expected)
  );
  const symbolCoverage = expectedSymbols.length === 0 ? 1 : matchedSymbols.length / expectedSymbols.length;
  const expectedImplementationSymbols = uniqueStrings(
    testCase.expectedImplementationSymbols ?? expectedSymbols,
  );
  const matchedImplementationSymbols = expectedImplementationSymbols.filter((expected) =>
    observedEvidence.some((item) => evidenceContainsImplementation(item, expected))
  );
  const implementationCoverage = expectedImplementationSymbols.length === 0
    ? 1
    : matchedImplementationSymbols.length / expectedImplementationSymbols.length;

  const forbiddenAnswerTerms = uniqueStrings(testCase.forbiddenAnswerTerms);
  const forbiddenTermsFound = forbiddenAnswerTerms.filter((term) => includesNormalized(answer, term));
  const answerMode = normalize(testCase.answerMode) || "supported";
  const acceptedAnswerTerms = uniqueStrings([
    ...(testCase.acceptedAnswerTerms ?? []),
    ...(answerMode === "insufficient" ? DEFAULT_INSUFFICIENT_ANSWER_TERMS : []),
  ]);
  const matchedAcceptedAnswerTerms = acceptedAnswerTerms.filter((term) => includesNormalized(answer, term));
  const answerModePassed = answerMode !== "insufficient"
    || (acceptedAnswerTerms.length > 0 && matchedAcceptedAnswerTerms.length > 0);
  const latencyPassed = Number.isFinite(latencyMs) && latencyMs <= Number(testCase.maxLatencyMs);
  const followUp = scoreFollowUpQuality(testCase, answer, evidenceText, observedCitationIds, evidenceIds);

  const gates = {
    citations: !citationRequired || (citationRecall === 1 && citationPrecision === 1),
    evidence: evidenceCoverage === 1,
    requiredClaims: requiredClaimCoverage === 1,
    forbiddenClaims: forbiddenClaimsFound.length === 0,
    expectedFiles: fileCoverage === 1,
    expectedSymbols: symbolCoverage === 1,
    implementationBodies: implementationCoverage === 1,
    answerMode: answerModePassed,
    followUp: followUp.passed,
    hallucinationRisk: forbiddenTermsFound.length === 0,
    latency: latencyPassed,
  };
  const failedGates = Object.entries(gates).filter(([, gatePassed]) => !gatePassed).map(([name]) => name);
  const passed = failedGates.length === 0;

  return {
    id: testCase.id,
    domain: testCase.domain,
    question: testCase.question,
    dimensions: inferredDimensions(testCase, answerMode),
    status: passed ? "passed" : "failed",
    passed,
    citation: {
      mode: citationMode,
      expected: expectedCitationIds.length,
      observed: observedCitationIds.length,
      matched: matchedCitationIds.length,
      recall: round(citationRecall),
      precision: round(citationPrecision),
      unexpectedCitationIds,
      evidenceMatchedCitationIds: citationMode === "grounded" ? matchedCitationIds : [],
    },
    evidence: {
      expectedSnippets: expectedEvidenceSnippets.length,
      matchedSnippets: matchedEvidenceSnippets.length,
      coverage: round(evidenceCoverage),
    },
    claims: {
      required: requiredClaimGroups.length,
      matchedRequired: matchedRequiredClaims.length,
      requiredCoverage: round(requiredClaimCoverage),
      matchedRequiredClaims,
      missingRequiredClaims: requiredClaimGroups
        .filter((group) => !group.some((claim) => includesNormalized(answer, claim)))
        .map((group) => group.join(" | ")),
      forbidden: forbiddenClaims.length,
      forbiddenClaimsFound,
    },
    answerMode: {
      expected: answerMode,
      acceptedAnswerTerms,
      matchedAcceptedAnswerTerms,
      passed: answerModePassed,
    },
    codeGrounding: {
      expectedFiles: expectedFiles.length,
      matchedFiles: matchedFiles.length,
      fileCoverage: round(fileCoverage),
      missingFiles: expectedFiles.filter((file) => !matchedFiles.includes(file)),
      observedFiles,
      expectedSymbols: expectedSymbols.length,
      matchedSymbols: matchedSymbols.length,
      symbolCoverage: round(symbolCoverage),
      missingSymbols: expectedSymbols.filter((symbol) => !matchedSymbols.includes(symbol)),
      observedSymbols,
      expectedImplementationSymbols: expectedImplementationSymbols.length,
      matchedImplementationSymbols: matchedImplementationSymbols.length,
      implementationCoverage: round(implementationCoverage),
      missingImplementationSymbols: expectedImplementationSymbols.filter(
        (symbol) => !matchedImplementationSymbols.includes(symbol),
      ),
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
    gates,
    failedGates,
  };
}

function evidenceSearchableText(item = {}) {
  return [
    item.id,
    item.text,
    evidenceFilePath(item),
    ...evidenceSymbols(item),
  ].filter(Boolean).join("\n");
}

function evidenceFilePath(item = {}) {
  return item.filePath ?? item.path ?? "";
}

function evidenceSymbols(item = {}) {
  return uniqueStrings([
    ...(Array.isArray(item.symbols) ? item.symbols : []),
    item.symbolName,
    item.className,
    item.methodName,
    item.controlName,
    item.eventName,
  ].filter((value) => value != null && String(value).trim()));
}

function containsIdentifier(text, identifier) {
  const value = String(identifier ?? "").trim();
  if (!value) {
    return false;
  }
  const escaped = value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(^|[^A-Za-z0-9_])${escaped}([^A-Za-z0-9_]|$)`, "i").test(String(text ?? ""));
}

function evidenceContainsImplementation(item, expectedSymbol) {
  const symbols = evidenceSymbols(item);
  const exactSymbol = symbols.some((symbol) => normalize(symbol) === normalize(expectedSymbol));
  const text = String(item?.text ?? item?.content ?? "");
  if (!exactSymbol || text.trim().length < 12 || !containsIdentifier(text, expectedSymbol)) {
    return false;
  }
  return /\{|=>|\b(?:def|function|func|fn)\s+[A-Za-z_]/.test(text);
}

function filePathMatches(observed, expected) {
  const normalizedObserved = normalizePath(observed);
  const normalizedExpected = normalizePath(expected);
  return normalizedObserved === normalizedExpected || normalizedObserved.endsWith(`/${normalizedExpected}`);
}

function normalizePath(value) {
  return normalize(value).replace(/\\/g, "/").replace(/^\.\//, "").replace(/^\/+|\/+$/g, "");
}

function resolveCitationMode(testCase, expectedCitationIds) {
  const configured = normalize(testCase.citationMode);
  if (configured === "exact" || configured === "grounded") {
    return configured;
  }
  return liveMode && expectedCitationIds.length === 0 ? "grounded" : "exact";
}

function round(value) {
  return Math.round(value * 1000) / 1000;
}

const startedAt = new Date();
const sourceFixtureDocument = readJson(fixturesPath);
const selectedFixtureDocument = selectedCaseIds.size === 0
  ? sourceFixtureDocument
  : {
      ...sourceFixtureDocument,
      cases: (sourceFixtureDocument.cases ?? []).filter((testCase) => selectedCaseIds.has(testCase.id)),
    };
if (selectedCaseIds.size > 0 && (selectedFixtureDocument.cases ?? []).length !== selectedCaseIds.size) {
  const found = new Set((selectedFixtureDocument.cases ?? []).map((testCase) => testCase.id));
  const missing = [...selectedCaseIds].filter((id) => !found.has(id));
  throw new Error(`Unknown --case-ids: ${missing.join(", ")}`);
}
const fixtureDocument = liveMode ? await captureLiveFixtures(selectedFixtureDocument) : selectedFixtureDocument;
const cases = fixtureDocument.cases ?? [];
const results = cases.map(scoreCase);
const scoredResults = results.filter((result) => result.status !== "skipped");
const failed = scoredResults.filter((result) => !result.passed);
const strongGatePassedCases = scoredResults.filter((result) => result.failedGates.length === 0).length;
const strongGatePassRate = scoredResults.length === 0 ? 0 : strongGatePassedCases / scoredResults.length;
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
    requiredClaimCoverage: round(average(scoredResults.map((result) => result.claims.requiredCoverage))),
    expectedFileCoverage: round(average(scoredResults.map((result) => result.codeGrounding.fileCoverage))),
    expectedSymbolCoverage: round(average(scoredResults.map((result) => result.codeGrounding.symbolCoverage))),
    implementationBodyCoverage: round(average(scoredResults.map((result) => result.codeGrounding.implementationCoverage))),
    followUpQuality: round(average(scoredResults.map((result) => result.followUp.quality))),
    followUpPassedCases: scoredResults.filter((result) => result.followUp.passed).length,
    latencyPassedCases: scoredResults.filter((result) => result.latency.passed).length,
    hallucinationRiskFlags: scoredResults.reduce(
      (count, result) => count + result.hallucinationRisk.forbiddenTermsFound.length,
      0,
    ),
    strongGatePassedCases,
    strongGatePassRate: round(strongGatePassRate),
    answerAccuracy: round(strongGatePassRate),
    answerAccuracyDefinition: "strongGatePassedCases / scoredCases; a case passes only when every configured strong gate passes",
    gateFailures: Object.fromEntries(
      [
        "citations",
        "evidence",
        "requiredClaims",
        "forbiddenClaims",
        "expectedFiles",
        "expectedSymbols",
        "implementationBodies",
        "answerMode",
        "followUp",
        "hallucinationRisk",
        "latency",
      ].map((gate) => [gate, scoredResults.filter((result) => result.failedGates.includes(gate)).length]),
    ),
  },
  slices: buildDimensionSlices(scoredResults),
  results,
  passed: failed.length === 0,
};

function average(values) {
  if (values.length === 0) {
    return 0;
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function buildDimensionSlices(results) {
  const dimensions = ["cohort", "language", "questionLanguage", "explicitness", "reasoningType", "difficulty", "answerability"];
  return Object.fromEntries(dimensions.map((dimension) => {
    const groups = new Map();
    for (const result of results) {
      const value = result.dimensions?.[dimension] ?? "unspecified";
      if (!groups.has(value)) groups.set(value, []);
      groups.get(value).push(result);
    }
    return [dimension, Object.fromEntries([...groups.entries()].map(([value, group]) => [value, {
      cases: group.length,
      passedCases: group.filter((result) => result.passed).length,
      answerAccuracy: round(group.filter((result) => result.passed).length / group.length),
      requiredClaimCoverage: round(average(group.map((result) => result.claims.requiredCoverage))),
      expectedFileCoverage: round(average(group.map((result) => result.codeGrounding.fileCoverage))),
      expectedSymbolCoverage: round(average(group.map((result) => result.codeGrounding.symbolCoverage))),
      implementationBodyCoverage: round(average(group.map((result) => result.codeGrounding.implementationCoverage))),
    }]))];
  }));
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, ""));
}

async function captureLiveFixtures(fixtureDocument) {
  authCookie = await loginIfConfigured();
  const environmentValidation = await validateLiveCodeEnvironment(fixtureDocument);
  if (!environmentValidation.valid) {
    const error = new Error(`INVALID_ENVIRONMENT: ${environmentValidation.issues.map((issue) => issue.message).join("; ")}`);
    error.environmentValidation = environmentValidation;
    throw error;
  }
  const capturedCases = [];
  for (const [index, testCase] of (fixtureDocument.cases ?? []).entries()) {
    if (index > 0 && caseDelayMs > 0) {
      await sleep(caseDelayMs);
    }
    capturedCases.push(await captureLiveCase(testCase));
  }
  const captured = {
    ...fixtureDocument,
    schema: "learnbot.quality.rag-live-fixtures.v1",
    liveCapture: {
      server,
      capturedAt: new Date().toISOString(),
      timeoutMs: requestTimeoutMs,
      caseDelayMs,
      authenticated: Boolean(authCookie),
      environmentValidation,
    },
    cases: capturedCases,
  };

  fs.mkdirSync(path.dirname(liveFixturesPath), { recursive: true });
  fs.writeFileSync(liveFixturesPath, `${JSON.stringify(captured, null, 2)}\n`, "utf8");
  console.log(`rag live fixtures report: ${liveFixturesPath}`);
  return captured;
}

async function validateLiveCodeEnvironment(fixtureDocument) {
  const codeCases = (fixtureDocument.cases ?? []).filter((testCase) => {
    if (testCase.domain !== "code" || !String(testCase.repositoryId ?? "").trim()) return false;
    return resolveEnvironmentPlaceholders(testCase.repositoryId).missing.length === 0;
  });
  if (codeCases.length === 0) {
    return { valid: true, status: "NOT_APPLICABLE", repositories: [], issues: [] };
  }
  const contract = fixtureDocument.repositoryVersions;
  if (!contract || typeof contract !== "object") {
    return {
      valid: false,
      status: "INVALID_ENVIRONMENT",
      repositories: [],
      issues: [{ code: "REPOSITORY_VERSION_CONTRACT_REQUIRED", message: "repositoryVersions is required for live code evaluation" }],
    };
  }

  const repositories = await getJson(`${server}/api/code/repositories`);
  const resolvedRepositories = [];
  const issues = [];
  for (const [language, version] of Object.entries(contract)) {
    const idResolution = resolveEnvironmentPlaceholders(version?.repositoryId);
    const commitResolution = resolveEnvironmentPlaceholders(version?.expectedCommitSha);
    const fingerprintResolution = resolveEnvironmentPlaceholders(version?.expectedContentFingerprint);
    const sourceTypeResolution = resolveEnvironmentPlaceholders(version?.expectedSourceType);
    const expectedSourceType = String(sourceTypeResolution.value ?? "").trim().toUpperCase();
    const commitMissing = expectedSourceType === "GIT" ? commitResolution.missing : [];
    if (idResolution.missing.length || commitMissing.length || fingerprintResolution.missing.length || sourceTypeResolution.missing.length) {
      issues.push({
        code: "VERSION_ENV_REQUIRED",
        language,
        message: `${language}: missing ${[...idResolution.missing, ...commitMissing, ...fingerprintResolution.missing, ...sourceTypeResolution.missing].join(", ")}`,
      });
      continue;
    }
    const expectedCommitSha = normalizeCommit(commitResolution.value);
    const expectedContentFingerprint = normalizeCommit(fingerprintResolution.value);
    const commitRequired = expectedSourceType === "GIT";
    if (!idResolution.value || (commitRequired && !expectedCommitSha) || !expectedContentFingerprint || !expectedSourceType) {
      issues.push({ code: "VERSION_VALUE_REQUIRED", language, message: `${language}: repositoryId, expectedContentFingerprint, expectedSourceType, and a GIT expectedCommitSha are required` });
      continue;
    }
    const repository = (Array.isArray(repositories) ? repositories : []).find((item) => String(item?.id) === idResolution.value);
    if (!repository) {
      issues.push({ code: "REPOSITORY_NOT_FOUND", language, message: `${language}: repository ${idResolution.value} was not returned by the server` });
      continue;
    }
    const indexedCommitSha = normalizeCommit(repository.lastIndexedCommit ?? repository.sourceHash);
    const indexedContentFingerprint = normalizeCommit(repository.contentFingerprint);
    const indexedSourceType = String(repository.sourceType ?? "").trim().toUpperCase();
    if (commitRequired && (!indexedCommitSha || !commitsMatch(indexedCommitSha, expectedCommitSha))) {
      issues.push({
        code: "INDEXED_COMMIT_MISMATCH",
        language,
        message: `${language}: expected ${expectedCommitSha}, indexed ${indexedCommitSha || "missing"}`,
      });
    }
    if (indexedContentFingerprint !== expectedContentFingerprint) {
      issues.push({
        code: "CONTENT_FINGERPRINT_MISMATCH",
        language,
        message: `${language}: expected fingerprint ${expectedContentFingerprint}, indexed ${indexedContentFingerprint || "missing"}`,
      });
    }
    if (indexedSourceType !== expectedSourceType) {
      issues.push({
        code: "SOURCE_TYPE_MISMATCH",
        language,
        message: `${language}: expected source type ${expectedSourceType}, indexed ${indexedSourceType || "missing"}`,
      });
    }
    if (repository.status !== "INDEXED" || Number(repository.activeFileCount ?? 0) <= 0 || Number(repository.activeChunkCount ?? 0) <= 0) {
      issues.push({ code: "INDEX_NOT_SEARCHABLE", language, message: `${language}: repository index is not complete and searchable` });
    }
    resolvedRepositories.push({
      language,
      repositoryId: idResolution.value,
      expectedCommitSha,
      indexedCommitSha,
      expectedContentFingerprint,
      indexedContentFingerprint,
      expectedSourceType,
      indexedSourceType,
      worktreeState: repository.worktreeState,
      analyzerVersion: repository.analyzerVersion,
      indexSchemaVersion: repository.indexSchemaVersion,
      status: repository.status,
      activeFileCount: repository.activeFileCount,
      activeChunkCount: repository.activeChunkCount,
    });
  }
  return {
    valid: issues.length === 0,
    status: issues.length === 0 ? "VALID" : "INVALID_ENVIRONMENT",
    repositories: resolvedRepositories,
    issues,
  };
}

function normalizeCommit(value) {
  return String(value ?? "").trim().toLowerCase();
}

function commitsMatch(actual, expected) {
  return actual === expected || (actual.length >= 7 && expected.length >= 7 && (actual.startsWith(expected) || expected.startsWith(actual)));
}

async function captureLiveCase(testCase) {
  const repositoryIdResolution = resolveEnvironmentPlaceholders(testCase.repositoryId);
  if (testCase.domain === "code" && !String(testCase.repositoryId ?? "").trim()) {
    return {
      ...testCase,
      observed: null,
      skipReason: "LIVE_CODE_REPOSITORY_ID_REQUIRED",
    };
  }
  if (testCase.domain === "code" && repositoryIdResolution.missing.length > 0) {
    return {
      ...testCase,
      citationMode: configuredLiveCitationMode(testCase),
      observed: null,
      skipReason: "LIVE_CODE_REPOSITORY_ID_ENV_REQUIRED",
      liveCapture: {
        status: "skipped",
        repositoryIdEnvironmentVariables: repositoryIdResolution.names,
        missingEnvironmentVariables: repositoryIdResolution.missing,
      },
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
        repositoryId: repositoryIdResolution.value,
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
      citationMode: configuredLiveCitationMode(testCase),
      observed: normalizeLiveObserved(response, latencyMs, testCase.domain),
      liveCapture: {
        endpoint,
        status: "captured",
        prior: priorCapture,
        repositoryIdEnvironmentVariables: repositoryIdResolution.names,
      },
    };
  } catch (error) {
    return {
      ...testCase,
      citationMode: configuredLiveCitationMode(testCase),
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
        repositoryIdEnvironmentVariables: repositoryIdResolution.names,
      },
    };
  }
}

function configuredLiveCitationMode(testCase) {
  const configured = normalize(testCase.citationMode);
  if (configured === "exact" || configured === "grounded") {
    return configured;
  }
  return uniqueStrings(testCase.expectedCitationIds).length === 0 ? "grounded" : "exact";
}

function resolveEnvironmentPlaceholders(value) {
  const source = String(value ?? "").trim();
  const names = uniqueStrings(Array.from(source.matchAll(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g), (match) => match[1]));
  const missing = names.filter((name) => !String(process.env[name] ?? "").trim());
  let resolved = source;
  for (const name of names) {
    if (!missing.includes(name)) {
      resolved = resolved.replaceAll(`\${${name}}`, String(process.env[name]).trim());
    }
  }
  return { value: missing.length === 0 ? resolved : "", names, missing };
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
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

async function getJson(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
  try {
    const response = await fetch(url, {
      headers: { ...(authCookie ? { Cookie: authCookie } : {}) },
      signal: controller.signal,
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`${response.status} ${response.statusText}: ${text.slice(0, 500)}`);
    }
    return text ? JSON.parse(text) : null;
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
      filePath: evidenceFilePath(item),
      symbols: evidenceSymbols(item),
    })),
    latencyMs,
  };
}

function scoreFollowUpQuality(testCase, answer, evidenceText, observedCitationIds, evidenceIds) {
  const expectedFollowUpTerms = uniqueStrings(testCase.expectedFollowUpTerms);
  const expectedPriorCitationIds = uniqueStrings(testCase.expectedPriorCitationIds);
  const forbiddenFollowUpTerms = uniqueStrings(testCase.forbiddenFollowUpTerms);
  const effectiveQuestion = testCase.observed?.effectiveQuestion ?? "";
  const answerSearchableText = `${effectiveQuestion}\n${answer}`;
  const evidenceSearchableText = `${evidenceText}`;

  const matchedAnswerTerms = expectedFollowUpTerms.filter((term) => includesNormalized(answerSearchableText, term));
  const matchedEvidenceTerms = expectedFollowUpTerms.filter((term) => includesNormalized(evidenceSearchableText, term));
  const matchedTerms = uniqueStrings([...matchedAnswerTerms, ...matchedEvidenceTerms]);
  const matchedPriorCitationIds = expectedPriorCitationIds.filter(
    (id) => observedCitationIds.includes(id) || evidenceIds.includes(id),
  );
  const forbiddenTermsFound = forbiddenFollowUpTerms.filter((term) => includesNormalized(answerSearchableText, term));
  const termCoverage = expectedFollowUpTerms.length === 0 ? 1 : matchedTerms.length / expectedFollowUpTerms.length;
  const answerTermCoverage =
    expectedFollowUpTerms.length === 0 ? 1 : matchedAnswerTerms.length / expectedFollowUpTerms.length;
  const evidenceTermCoverage =
    expectedFollowUpTerms.length === 0 ? 1 : matchedEvidenceTerms.length / expectedFollowUpTerms.length;
  const priorCitationCoverage =
    expectedPriorCitationIds.length === 0 ? 1 : matchedPriorCitationIds.length / expectedPriorCitationIds.length;
  const quality = Math.min(termCoverage, priorCitationCoverage);

  return {
    expectedTerms: expectedFollowUpTerms.length,
    matchedTerms: matchedTerms.length,
    matchedAnswerTerms: matchedAnswerTerms.length,
    matchedEvidenceTerms: matchedEvidenceTerms.length,
    answerTermCoverage: round(answerTermCoverage),
    evidenceTermCoverage: round(evidenceTermCoverage),
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
  `rag quality summary: ${report.summary.passedCases}/${report.summary.scoredCases} scored cases passed, skipped=${report.summary.skippedCases}, citationRecall=${report.summary.citationRecall}, evidenceCoverage=${report.summary.evidenceCoverage}, requiredClaimCoverage=${report.summary.requiredClaimCoverage}, expectedFileCoverage=${report.summary.expectedFileCoverage}, expectedSymbolCoverage=${report.summary.expectedSymbolCoverage}, gateFailures=${JSON.stringify(report.summary.gateFailures)}`,
);

if (!report.passed) {
  process.exitCode = 1;
}

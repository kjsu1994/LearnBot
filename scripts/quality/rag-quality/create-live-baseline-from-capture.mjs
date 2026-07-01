import fs from "node:fs";
import path from "node:path";

function readArg(name, fallback = "") {
  const index = process.argv.indexOf(name);
  if (index === -1 || index === process.argv.length - 1) {
    return fallback;
  }
  return process.argv[index + 1];
}

const capturePath = readArg("--capture");
const outputPath = readArg("--output");

if (!capturePath || !outputPath) {
  console.error("Usage: node scripts\\quality\\rag-quality\\create-live-baseline-from-capture.mjs --capture <rag-live-fixtures.json> --output <fixtures.live.json>");
  process.exit(2);
}

const capture = JSON.parse(fs.readFileSync(capturePath, "utf8"));
const validationIssues = validateCapture(capture);

if (validationIssues.length > 0) {
  console.error("Live baseline promotion rejected weak capture:");
  for (const issue of validationIssues) {
    console.error(`- ${issue.caseId}: ${issue.reason}`);
  }
  process.exit(1);
}

const cases = (capture.cases ?? []).map((testCase) => {
  const observed = testCase.observed ?? {};
  const expectedCitationIds = uniqueStrings(observed.citationIds);
  return {
    id: testCase.id,
    domain: testCase.domain,
    repositoryId: testCase.repositoryId || undefined,
    spaceId: testCase.spaceId || undefined,
    question: testCase.question,
    mode: testCase.mode,
    speedProfile: testCase.speedProfile,
    limit: testCase.limit,
    expectedCitationIds,
    expectedPriorCitationIds: testCase.expectedPriorCitationIds ?? [],
    expectedFollowUpTerms: testCase.expectedFollowUpTerms ?? [],
    expectedEvidenceSnippets: testCase.expectedEvidenceSnippets ?? [],
    forbiddenAnswerTerms: testCase.forbiddenAnswerTerms ?? [],
    forbiddenFollowUpTerms: testCase.forbiddenFollowUpTerms ?? [],
    maxLatencyMs: testCase.maxLatencyMs,
    observed,
    baselineSource: {
      capturePath: path.resolve(capturePath),
      capturedAt: capture.liveCapture?.capturedAt || null,
      server: capture.liveCapture?.server || null,
    },
    baselineValidation: {
      status: "accepted",
      checks: [
        "captured-status",
        "non-empty-answer",
        "non-empty-citations",
        "non-empty-evidence",
        "expected-evidence-snippets",
        "expected-follow-up-terms",
        "forbidden-answer-terms",
        "forbidden-follow-up-terms",
        "latency-budget",
      ],
    },
  };
}).map(removeUndefinedValues);

const baseline = {
  schema: "learnbot.quality.rag-live-baseline.v1",
  createdAt: new Date().toISOString(),
  sourceCapture: path.resolve(capturePath),
  cases,
};

fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify(baseline, null, 2)}\n`, "utf8");
console.log(`rag live baseline: ${path.resolve(outputPath)}`);
console.log(`rag live baseline cases: ${cases.length}`);

function validateCapture(captureReport) {
  const issues = [];

  for (const testCase of captureReport.cases ?? []) {
    const caseId = testCase.id || "(missing id)";
    const observed = testCase.observed ?? {};
    const answer = String(observed.answer ?? "");
    const citationIds = uniqueStrings(observed.citationIds);
    const evidenceItems = Array.isArray(observed.evidence) ? observed.evidence : [];
    const evidenceText = evidenceItems.map((item) => {
      if (typeof item === "string") {
        return item;
      }
      return [item.id, item.title, item.text, item.content, item.snippet].filter(Boolean).join(" ");
    }).join("\n").toLowerCase();
    const lowerAnswer = answer.toLowerCase();
    const followUpText = `${observed.effectiveQuestion ?? ""}\n${answer}`.toLowerCase();

    if (testCase.skipReason) {
      issues.push({ caseId, reason: `case was skipped (${testCase.skipReason})` });
    }
    if (testCase.liveCapture?.status && testCase.liveCapture.status !== "captured") {
      issues.push({ caseId, reason: `live capture status is ${testCase.liveCapture.status}` });
    }
    if (!answer.trim()) {
      issues.push({ caseId, reason: "observed answer is empty" });
    }
    if (citationIds.length === 0) {
      issues.push({ caseId, reason: "observed citation ids are empty" });
    }
    if (evidenceItems.length === 0 || !evidenceText.trim()) {
      issues.push({ caseId, reason: "observed evidence is empty" });
    }

    for (const snippet of testCase.expectedEvidenceSnippets ?? []) {
      if (!evidenceText.includes(String(snippet).toLowerCase())) {
        issues.push({ caseId, reason: `missing expected evidence snippet: ${snippet}` });
      }
    }

    for (const term of testCase.forbiddenAnswerTerms ?? []) {
      if (lowerAnswer.includes(String(term).toLowerCase())) {
        issues.push({ caseId, reason: `answer contains forbidden term: ${term}` });
      }
    }

    for (const term of testCase.expectedFollowUpTerms ?? []) {
      if (!followUpText.includes(String(term).toLowerCase())) {
        issues.push({ caseId, reason: `missing expected follow-up term: ${term}` });
      }
    }

    for (const term of testCase.forbiddenFollowUpTerms ?? []) {
      if (followUpText.includes(String(term).toLowerCase())) {
        issues.push({ caseId, reason: `follow-up text contains forbidden term: ${term}` });
      }
    }

    const latencyMs = Number(observed.latencyMs);
    if (!Number.isFinite(latencyMs)) {
      issues.push({ caseId, reason: "observed latency is not finite" });
    } else if (Number.isFinite(Number(testCase.maxLatencyMs)) && latencyMs > Number(testCase.maxLatencyMs)) {
      issues.push({ caseId, reason: `observed latency ${latencyMs}ms exceeds ${testCase.maxLatencyMs}ms` });
    }
  }

  return issues;
}

function uniqueStrings(values) {
  return Array.from(new Set((values ?? []).map((value) => String(value)).filter(Boolean)));
}

function removeUndefinedValues(value) {
  return Object.fromEntries(Object.entries(value).filter(([, entryValue]) => entryValue !== undefined));
}

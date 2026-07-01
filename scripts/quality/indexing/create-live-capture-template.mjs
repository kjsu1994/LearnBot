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

const manifestPath = path.resolve(readArg("--manifest", path.join(scriptDir, "live-seed-manifest.template.json")));
const templatePath = path.resolve(readArg("--template", path.join(scriptDir, "live-capture-fixtures.template.json")));
const outputPath = path.resolve(
  readArg("--output", path.join(root, ".tmp", "quality", "indexing-live-capture-template.json")),
);

try {
  const manifest = readJson(manifestPath);
  const template = readJson(templatePath);
  const replacements = buildReplacements(manifest);
  const output = {
    ...template,
    schema: "learnbot.quality.indexing-live-capture-template.v1",
    liveSeedManifest: {
      schema: manifest.schema ?? null,
      generatedAt: new Date().toISOString(),
      manifestPath,
      templatePath,
    },
    cases: (template.cases ?? []).map((testCase) => ({
      ...testCase,
      endpoint: replaceEndpoint(testCase.endpoint, replacements),
    })),
  };

  assertNoUnresolvedPlaceholders(output);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(output, null, 2)}\n`, "utf8");
  console.log(`indexing live capture template: ${outputPath}`);
  console.log(`indexing live capture template summary: ${output.cases.length} endpoints ready`);
} catch (error) {
  console.error(`indexing live capture template failed: ${error.message}`);
  process.exit(1);
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function buildReplacements(manifest) {
  const required = [
    ["document.jobId", manifest.document?.jobId],
    ["code.repositoryId", manifest.code?.repositoryId],
    ["code.jobId", manifest.code?.jobId],
    ["crawler.documentId", manifest.crawler?.documentId],
  ];
  const missing = required
    .filter(([, value]) => !isConcreteId(value))
    .map(([name]) => name);

  if (missing.length > 0) {
    throw new Error(`missing live seed ids: ${missing.join(", ")}`);
  }

  return {
    document: {
      jobId: manifest.document.jobId,
    },
    code: {
      repositoryId: manifest.code.repositoryId,
      jobId: manifest.code.jobId,
    },
    crawler: {
      documentId: manifest.crawler.documentId,
    },
  };
}

function replaceEndpoint(endpoint, replacements) {
  if (typeof endpoint !== "string" || !endpoint.trim()) {
    throw new Error("case endpoint is required");
  }
  if (endpoint.includes("/api/document-indexing/jobs/")) {
    return endpoint.replace("{jobId}", encodeURIComponent(replacements.document.jobId));
  }
  if (endpoint.includes("/api/code/repositories/")) {
    return endpoint
      .replace("{repositoryId}", encodeURIComponent(replacements.code.repositoryId))
      .replace("{jobId}", encodeURIComponent(replacements.code.jobId));
  }
  if (endpoint.includes("/api/documents/")) {
    return endpoint.replace("{documentId}", encodeURIComponent(replacements.crawler.documentId));
  }
  return endpoint;
}

function assertNoUnresolvedPlaceholders(output) {
  const unresolved = [];
  for (const testCase of output.cases ?? []) {
    if (/\{[^}]+\}/.test(testCase.endpoint ?? "")) {
      unresolved.push(`${testCase.id}:${testCase.endpoint}`);
    }
  }
  if (unresolved.length > 0) {
    throw new Error(`unresolved endpoint placeholders: ${unresolved.join(", ")}`);
  }
}

function isConcreteId(value) {
  if (typeof value !== "string" || !value.trim()) {
    return false;
  }
  return !/^replace-with-/i.test(value.trim()) && !/[{}]/.test(value);
}
